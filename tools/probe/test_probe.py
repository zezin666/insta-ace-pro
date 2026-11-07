import unittest

from probe import build_get_file_list, frame, header


class EncodingTest(unittest.TestCase):
    def test_get_file_list_bytes(self):
        self.assertEqual(build_get_file_list(2, 0, 100), bytes.fromhex("080210001864"))

    def test_frame_prefixes_length(self):
        self.assertEqual(frame(bytes.fromhex("050000")), bytes.fromhex("07000000050000"))

    def test_header_layout(self):
        self.assertEqual(header(13, 1), bytes.fromhex("0400000d0002010000800000"))


if __name__ == "__main__":
    unittest.main()
