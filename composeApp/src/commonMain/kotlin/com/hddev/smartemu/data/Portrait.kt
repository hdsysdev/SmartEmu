package com.hddev.smartemu.data

/**
 * The holder's portrait, a JPEG printed on the data page and served in EF.DG2.
 *
 * Compared by content, so that [PassportData] stays equal to a copy of itself and the chip isn't rebuilt needlessly.
 */
class Portrait(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int
) {

    companion object {
        /**
         * The picked image is cropped and scaled to an ISO/IEC 19794-5 Token Frontal image, the fixed geometry
         * ICAO allows for the chip: 3:4, with the eyes [EYE_DISTANCE] x width apart and centred [EYE_LINE] x width
         * from the top. At this size they're 120 pixels apart, ICAO's best practice for the image in EF.DG2.
         */
        const val WIDTH = 480
        const val HEIGHT = 640

        /** The size used when a portrait at [WIDTH] x [HEIGHT] can't fit; the eyes are 90 pixels apart, ICAO's minimum. */
        const val MIN_WIDTH = 360
        const val MIN_HEIGHT = 480

        const val EYE_DISTANCE = 0.25f
        const val EYE_LINE = 0.6f

        /**
         * Largest JPEG the chip can serve: READ BINARY addresses at most 15 bits of offset, and EF.DG2 wraps the
         * image in a few hundred bytes of biometric headers.
         */
        const val MAX_JPEG_SIZE = 24 * 1024
    }

    override fun equals(other: Any?): Boolean =
        other is Portrait && width == other.width && height == other.height && jpeg.contentEquals(other.jpeg)

    override fun hashCode(): Int = 31 * (31 * width + height) + jpeg.contentHashCode()

    override fun toString(): String = "Portrait(${width}x$height, ${jpeg.size} bytes)"
}
