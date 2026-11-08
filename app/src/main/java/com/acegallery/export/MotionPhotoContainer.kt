package com.acegallery.export

/**
 * Byte-level assembly of an Android Motion Photo JPEG:
 * `SOI -> APP1 Exif -> APP1 XMP -> JPEG data -> EOI -> MP4`.
 *
 * The XMP carries only Google `GCamera` + OPPO `OpCamera` + `Container` fields —
 * the legacy Xiaomi `MiCamera`/`MicroVideo` fields are deliberately absent, as on
 * native ColorOS captures. The Exif UserComment is type 7 with the `ASCII\0\0\0`
 * prefix and `oplus_10485792`; ColorOS 16 rejects the older ASCII-only marker.
 */
object MotionPhotoContainer {

    /** Builds the complete APP1 XMP segment (marker, length, identifier, XML). */
    fun buildXmp(videoLength: Long, presentationUs: Long): ByteArray {
        val xml = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                xmlns:OpCamera="http://ns.oplus.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                GCamera:MotionPhoto="1"
                GCamera:MotionPhotoVersion="1"
                GCamera:MotionPhotoPresentationTimestampUs="$presentationUs"
                OpCamera:MotionPhotoPrimaryPresentationTimestampUs="$presentationUs"
                OpCamera:MotionPhotoOwner="oplus"
                OpCamera:OLivePhotoVersion="2"
                OpCamera:VideoLength="$videoLength">
               <Container:Directory>
                <rdf:Seq>
                 <rdf:li rdf:parseType="Resource">
                  <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
                 </rdf:li>
                 <rdf:li rdf:parseType="Resource">
                  <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength"/>
                 </rdf:li>
                </rdf:Seq>
               </Container:Directory>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val payload = XMP_IDENTIFIER + xml.toByteArray(Charsets.UTF_8)
        val segmentLength = payload.size + 2
        require(segmentLength <= 0xFFFF) { "XMP segment too large" }
        return byteArrayOf(
            0xFF.toByte(),
            0xE1.toByte(),
            (segmentLength shr 8).toByte(),
            segmentLength.toByte(),
        ) + payload
    }

    /** Builds the 124-byte APP1 Exif segment with the OPPO live-photo marker. */
    fun oppoExif(width: Int, height: Int): ByteArray {
        val bytes = OPPO_EXIF_HEX.hexToBytes()
        if (width > 0) {
            bytes[28] = (width ushr 24).toByte()
            bytes[29] = (width ushr 16).toByte()
            bytes[30] = (width ushr 8).toByte()
            bytes[31] = width.toByte()
        }
        if (height > 0) {
            bytes[40] = (height ushr 24).toByte()
            bytes[41] = (height ushr 16).toByte()
            bytes[42] = (height ushr 8).toByte()
            bytes[43] = height.toByte()
        }
        return bytes
    }

    /** Inserts the Exif and XMP segments right after APP0, keeping the JPEG intact. */
    fun insertSegments(jpeg: ByteArray, exif: ByteArray, xmp: ByteArray): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        var offset = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            val app0Length = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
            offset = 4 + app0Length
        }
        require(offset in 2 until jpeg.size) { "invalid JPEG" }
        return jpeg.copyOfRange(0, offset) + exif + xmp + jpeg.copyOfRange(offset, jpeg.size)
    }

    /** Appends the motion video after the JPEG's EOI. */
    fun assemble(jpegWithSegments: ByteArray, video: ByteArray): ByteArray {
        require(video.isNotEmpty()) { "视频片段为空" }
        return jpegWithSegments + video
    }

    private val XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)

    // 124-byte Exif with UserComment (type 7) = "ASCII\0\0\0" + "oplus_10485792".
    // Matches native OnePlus Ace 3 Pro / ColorOS 16 captures; the older
    // "oplus_8388608" ASCII-only marker is not recognized there.
    // Width at [28..31], height at [40..43] (big-endian TIFF).
    private const val OPPO_EXIF_HEX =
        "FFE1007A4578696600004D4D002A0000000800040100000400000001000005A001010004000000010000043C" +
            "87690004000000010000003E01120003000000010000000000000000000292860007000000160000005C" +
            "9208000400000001000000000000000041534349490000006F706C75735F3130343835373932"

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
