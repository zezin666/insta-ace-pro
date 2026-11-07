#!/usr/bin/env python3
"""Read-only protocol probe for Insta360 Ace Pro.

Standard library only. It never sends destructive commands (no DELETE_FILES).
Run it from a computer connected to the camera's Wi-Fi:

    python tools/probe/probe.py --host 192.168.42.1 --out docs/protocol
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import time
from pathlib import Path
from urllib import error, request

HOST_DEFAULT = "192.168.42.1"
PORT = 6666
SYNC = bytes.fromhex("060000") + b"syNceNdinS"
KEEPALIVE = bytes.fromhex("050000")
MSG_GET_FILE_EXTRA = 11
MSG_GET_FILE_LIST = 13
MSG_CHECK_AUTHORIZATION = 39
RESPONSE_OK = 200


# ---------------------------------------------------------------------------
# Protobuf / framing
# ---------------------------------------------------------------------------

def encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varint must be non-negative")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _varint_field(field_num: int, value: int) -> bytes:
    return encode_varint((field_num << 3) | 0) + encode_varint(value)


def _bytes_field(field_num: int, data: bytes) -> bytes:
    return encode_varint((field_num << 3) | 2) + encode_varint(len(data)) + data


def build_get_file_list(media_type: int, start: int, limit: int) -> bytes:
    return (
        _varint_field(1, media_type)
        + _varint_field(2, start)
        + _varint_field(3, limit)
    )


def build_get_file_extra(uri: str, extra_type: int) -> bytes:
    return _bytes_field(1, uri.encode("utf-8")) + _varint_field(2, extra_type)


def build_check_authorization(client_id: str) -> bytes:
    return _bytes_field(1, client_id.encode("utf-8"))


def frame(payload: bytes) -> bytes:
    return struct.pack("<I", len(payload) + 4) + payload


def header(command: int, seq: int) -> bytes:
    return (
        b"\x04\x00\x00"
        + command.to_bytes(2, "little")
        + b"\x02"
        + seq.to_bytes(3, "little")
        + b"\x80\x00\x00"
    )


def parse_fields(data: bytes):
    """Yield (field_number, wire_type, value) for a protobuf message."""
    i = 0
    while i < len(data):
        key, i = _read_varint(data, i)
        field_num, wire = key >> 3, key & 0x07
        if wire == 0:
            value, i = _read_varint(data, i)
        elif wire == 2:
            length, i = _read_varint(data, i)
            value = data[i:i + length]
            i += length
        elif wire == 1:
            value = data[i:i + 8]
            i += 8
        elif wire == 5:
            value = data[i:i + 4]
            i += 4
        else:
            raise ValueError(f"unsupported wire type {wire}")
        yield field_num, wire, value


def _read_varint(data: bytes, i: int):
    value = 0
    shift = 0
    while True:
        if i >= len(data):
            raise ValueError("truncated varint")
        byte = data[i]
        i += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, i
        shift += 7


def parse_file_list_response(data: bytes):
    uris = []
    total = None
    for field_num, wire, value in parse_fields(data):
        if field_num == 1 and wire == 2:
            uris.append(value.decode("utf-8", errors="replace"))
        elif field_num == 2 and wire == 0:
            total = value
    return uris, total


# ---------------------------------------------------------------------------
# TCP client
# ---------------------------------------------------------------------------

class ProtocolError(RuntimeError):
    def __init__(self, response_code: int, body: bytes):
        super().__init__(f"response code {response_code}")
        self.response_code = response_code
        self.body = body


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("camera closed the connection")
        buf.extend(chunk)
    return bytes(buf)


def recv_frame(sock: socket.socket, timeout: float):
    sock.settimeout(max(0.05, timeout))
    try:
        head = recv_exact(sock, 4)
    except socket.timeout:
        return None
    length = struct.unpack("<I", head)[0]
    if length < 4 or length > 16 * 1024 * 1024:
        raise ValueError(f"bad frame length {length}")
    return recv_exact(sock, length - 4)


class CameraSocket:
    def __init__(self, host: str, port: int = PORT):
        self.host = host
        self.port = port
        self.sock: socket.socket | None = None
        self.seq = 0
        self.raw_frames: list[str] = []
        self.sync_reply: str | None = None

    def open(self) -> None:
        self.sock = socket.create_connection((self.host, self.port), timeout=5)
        self.sock.sendall(frame(SYNC))
        self.sock.sendall(frame(KEEPALIVE))
        reply = recv_frame(self.sock, timeout=1.0)
        if reply is not None:
            self.sync_reply = reply.hex()

    def send_request(self, command: int, payload: bytes, timeout: float = 5.0) -> bytes:
        if self.sock is None:
            raise RuntimeError("socket is not open")
        seq = self.seq
        self.seq += 1
        self.sock.sendall(frame(header(command, seq) + payload))
        deadline = time.time() + timeout
        while time.time() < deadline:
            frame_bytes = recv_frame(self.sock, deadline - time.time())
            if frame_bytes is None:
                break
            if len(self.raw_frames) < 10:
                self.raw_frames.append(frame_bytes[:64].hex())
            if not frame_bytes or frame_bytes[0] != 0x04 or len(frame_bytes) < 12:
                continue
            response_code = int.from_bytes(frame_bytes[3:5], "little")
            response_seq = int.from_bytes(frame_bytes[6:9], "little")
            if response_seq != seq:
                continue
            body = frame_bytes[12:]
            if response_code != RESPONSE_OK:
                raise ProtocolError(response_code, body)
            return body
        raise TimeoutError(f"no response to command {command}")

    def close(self) -> None:
        if self.sock is not None:
            try:
                self.sock.close()
            finally:
                self.sock = None


# ---------------------------------------------------------------------------
# HTTP probes
# ---------------------------------------------------------------------------

def http_probe(host: str, path: str, method: str = "GET", headers=None,
               max_bytes: int = 512) -> dict:
    url = f"http://{host}{path}"
    req = request.Request(url, method=method, headers=headers or {})
    try:
        with request.urlopen(req, timeout=10) as resp:
            body = resp.read(max_bytes)
            return {
                "url": url,
                "status": resp.status,
                "headers": dict(resp.headers),
                "body_len_read": len(body),
                "body_prefix_hex": body[:512].hex(),
            }
    except error.HTTPError as exc:
        body = exc.read(max_bytes)
        return {
            "url": url,
            "status": exc.code,
            "headers": dict(exc.headers),
            "body_len_read": len(body),
            "body_prefix_hex": body[:512].hex(),
        }
    except Exception as exc:  # noqa: BLE001 - probe reports the error verbatim
        return {"url": url, "error": repr(exc)}


def http_range_probe(host: str, path: str, out_dir: Path) -> dict:
    url = f"http://{host}{path}"
    req = request.Request(url, headers={"Range": "bytes=0-1048575"})
    try:
        with request.urlopen(req, timeout=20) as resp:
            body = resp.read(1024 * 1024)
            sample = out_dir / "sample.bin"
            sample.write_bytes(body)
            return {
                "url": url,
                "status": resp.status,
                "headers": dict(resp.headers),
                "bytes_read": len(body),
                "sample_path": str(sample),
            }
    except Exception as exc:  # noqa: BLE001
        return {"url": url, "error": repr(exc)}


# ---------------------------------------------------------------------------
# Camera probes
# ---------------------------------------------------------------------------

def probe_http(host: str, out_dir: Path) -> dict:
    result = {}
    for path in ("/", "/DCIM/", "/osc/info", "/osc/state"):
        result[path] = http_probe(host, path)
    return result


def _try_file_list(cam: CameraSocket, media_type: int, limit: int) -> dict:
    body = cam.send_request(
        MSG_GET_FILE_LIST, build_get_file_list(media_type, 0, limit)
    )
    uris, total = parse_file_list_response(body)
    return {"media_type": media_type, "limit": limit, "count": len(uris),
            "total": total, "uris": uris}


def probe_file_list(cam: CameraSocket) -> dict:
    attempts = []
    last_error = None
    for media_type in (2, 1, 0):
        for limit in (100, 200, 500):
            try:
                attempt = _try_file_list(cam, media_type, limit)
                attempts.append(attempt)
                if attempt["uris"]:
                    return {
                        "attempts": attempts,
                        "media_type": media_type,
                        "limit": limit,
                        "uris": attempt["uris"],
                        "total": attempt["total"],
                    }
            except ProtocolError as exc:
                last_error = {"media_type": media_type, "limit": limit,
                              "response_code": exc.response_code,
                              "body_prefix_hex": exc.body[:64].hex()}
                attempts.append(last_error)
                if exc.response_code == 400:
                    break
    raise RuntimeError(f"file list failed: {last_error or 'no response'}")


def paginate_all(cam: CameraSocket, limit: int = 100) -> dict:
    uris = []
    start = 0
    pages = 0
    total = None
    for _ in range(50):
        body = cam.send_request(
            MSG_GET_FILE_LIST, build_get_file_list(2, start, limit)
        )
        page, total = parse_file_list_response(body)
        pages += 1
        if not page:
            break
        uris.extend(page)
        start += len(page)
        if total is not None and start >= total:
            break
    return {"limit": limit, "pages": pages, "total": total,
            "count": len(uris), "uris": uris}


def describe_files(uris: list[str]) -> list[dict]:
    described = []
    for uri in uris[:10]:
        path = uri.split("://", 1)[-1] if "://" in uri else uri
        name = path.rsplit("/", 1)[-1]
        stem, _, ext = name.rpartition(".")
        prefix = stem.split("_", 1)[0]
        described.append({
            "uri": uri,
            "path": path if path.startswith("/") else "/" + path,
            "name": name,
            "prefix": prefix,
            "ext": ext.lower(),
        })
    return described


def probe_thumbnails(cam: CameraSocket, uris: list[str]) -> list[dict]:
    results = []
    photo = next((u for u in uris if u.lower().endswith((".jpg", ".jpeg", ".dng"))), None)
    video = next((u for u in uris if u.lower().endswith((".mp4", ".mov", ".insv"))), None)
    for label, uri in (("photo", photo), ("video", video)):
        if not uri:
            continue
        for extra_type in (0, 1, 2, 3):
            entry = {"kind": label, "uri": uri, "extra_type": extra_type}
            try:
                body = cam.send_request(
                    MSG_GET_FILE_EXTRA,
                    build_get_file_extra(uri, extra_type),
                    timeout=8.0,
                )
                entry.update({
                    "bytes": len(body),
                    "prefix_hex": body[:64].hex(),
                    "looks_like_jpeg": body[:2] == b"\xff\xd8",
                    "looks_like_png": body[:8] == b"\x89PNG\r\n\x1a\n",
                })
            except ProtocolError as exc:
                entry.update({"response_code": exc.response_code,
                              "prefix_hex": exc.body[:64].hex()})
            except Exception as exc:  # noqa: BLE001
                entry["error"] = repr(exc)
            results.append(entry)
    return results


def probe_authorization(cam: CameraSocket) -> dict:
    try:
        body = cam.send_request(
            MSG_CHECK_AUTHORIZATION, build_check_authorization("ace-gallery-probe")
        )
        return {"checked": True, "response_hex": body[:64].hex()}
    except ProtocolError as exc:
        return {"checked": True, "response_code": exc.response_code,
                "body_prefix_hex": exc.body[:64].hex()}
    except Exception as exc:  # noqa: BLE001
        return {"checked": True, "error": repr(exc)}


def probe_tcp(host: str, http_results: dict, out_dir: Path) -> dict:
    cam = CameraSocket(host)
    result: dict = {}
    try:
        cam.open()
        result["sync_reply_hex"] = cam.sync_reply
        try:
            file_list = probe_file_list(cam)
            result["file_list"] = file_list
            uris = file_list["uris"]
            result["pagination"] = paginate_all(cam, limit=100)
            result["files"] = describe_files(uris)
            result["thumbnails"] = probe_thumbnails(cam, uris)
            first = uris[0] if uris else None
            if first:
                path = first.split("://", 1)[-1] if "://" in first else first
                path = path if path.startswith("/") else "/" + path
                result["head"] = http_probe(host, path, method="HEAD")
                result["range"] = http_range_probe(host, path, out_dir)
        except Exception as exc:  # noqa: BLE001
            result["file_list_error"] = repr(exc)
            result["authorization"] = probe_authorization(cam)
        result["raw_frames_hex"] = cam.raw_frames
    finally:
        cam.close()
    return result


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

def write_report(out_dir: Path, host: str, http_results: dict,
                 tcp_results: dict) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    report = {"host": host, "http": http_results, "tcp": tcp_results}
    (out_dir / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    lines = [
        "# Insta360 Ace Pro 1 protocol probe",
        "",
        f"- Host: `{host}`",
        f"- Generated: {time.strftime('%Y-%m-%d %H:%M:%S')}",
        "",
        "## HTTP",
        "",
        "| Path | Status | Body prefix |",
        "| --- | --- | --- |",
    ]
    for path, res in http_results.items():
        status = res.get("status", res.get("error", "error"))
        prefix = res.get("body_prefix_hex", "")[:120]
        lines.append(f"| `{path}` | {status} | `{prefix}` |")

    lines += ["", "## TCP 6666", ""]
    file_list = tcp_results.get("file_list")
    if file_list:
        lines.append(f"- File list media_type={file_list['media_type']}, "
                     f"limit={file_list['limit']}, count={len(file_list['uris'])}, "
                     f"total={file_list.get('total')}")
        page = tcp_results.get("pagination", {})
        lines.append(f"- Pagination: pages={page.get('pages')}, "
                     f"count={page.get('count')}, total={page.get('total')}")
        lines += ["", "### First files", "", "| uri | prefix | ext |",
                  "| --- | --- | --- |"]
        for item in tcp_results.get("files", []):
            lines.append(f"| `{item['uri'][:100]}` | {item['prefix']} | {item['ext']} |")
    else:
        lines.append(f"- File list failed: {tcp_results.get('file_list_error')}")
        auth = tcp_results.get("authorization")
        if auth:
            lines.append(f"- Authorization probe: {auth}")

    head = tcp_results.get("head")
    rng = tcp_results.get("range")
    if head:
        lines.append(f"- HEAD: status={head.get('status')}, "
                     f"content-length={head.get('headers', {}).get('Content-Length')}")
    if rng:
        lines.append(f"- Range: status={rng.get('status')}, "
                     f"bytes={rng.get('bytes_read')}, "
                     f"content-range={rng.get('headers', {}).get('Content-Range')}")

    lines += ["", "## Thumbnails", "",
              "| kind | extra_type | bytes | jpeg | prefix |",
              "| --- | --- | --- | --- | --- |"]
    for item in tcp_results.get("thumbnails", []):
        lines.append(
            f"| {item.get('kind')} | {item.get('extra_type')} | "
            f"{item.get('bytes', item.get('response_code', 'err'))} | "
            f"{item.get('looks_like_jpeg')} | `{item.get('prefix_hex', '')[:64]}` |"
        )

    lines += ["", "## Raw frames", "", "```",
              "\n".join(tcp_results.get("raw_frames_hex", [])), "```", ""]
    (out_dir / "ace-pro-1-notes.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=HOST_DEFAULT)
    parser.add_argument("--out", default="docs/protocol")
    parser.add_argument("--skip-http", action="store_true")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    http_results = {} if args.skip_http else probe_http(args.host, out_dir)
    tcp_results = probe_tcp(args.host, http_results, out_dir)
    write_report(out_dir, args.host, http_results, tcp_results)

    file_list = tcp_results.get("file_list")
    print(f"HTTP probes: {len(http_results)}")
    if file_list:
        print(f"File list: {len(file_list['uris'])} files, total={file_list.get('total')}")
    else:
        print(f"File list failed: {tcp_results.get('file_list_error')}")
    print(f"Report: {out_dir / 'ace-pro-1-notes.md'}")
    return 0 if file_list else 1


if __name__ == "__main__":
    raise SystemExit(main())
