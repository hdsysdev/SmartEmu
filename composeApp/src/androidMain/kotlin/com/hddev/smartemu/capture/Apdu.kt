package com.hddev.smartemu.capture

/** The command APDUs capture sends outside the probe pack, whose own commands are hex in [ProbePack]. */
internal object Apdu {
    /** The eMRTD application's AID, ICAO 9303 part 10. */
    const val EMRTD_AID_HEX = "A0000002471001"

    val selectEmrtdApplication: ByteArray = Hex.decode("00A4040C07$EMRTD_AID_HEX")

    fun selectFileId(fileId: Int, p1: Int = 0x02, p2: Int = 0x0C): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), p1.toByte(), p2.toByte(), 0x02, (fileId shr 8).toByte(), fileId.toByte())

    /** READ BINARY with the offset in P1-P2; Le 0 asks for 256 bytes. */
    fun readBinary(offset: Int, le: Int): ByteArray {
        require(offset in 0..0x7FFF) { "Offset out of range" }
        return byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), le.toByte())
    }

    /** READ BINARY with the short EF identifier in P1 and the offset (up to 255) in P2. */
    fun readBinaryBySfi(sfi: Int, offset: Int, le: Int): ByteArray {
        require(sfi in 1..0x1E && offset in 0..0xFF) { "SFI or offset out of range" }
        return byteArrayOf(0x00, 0xB0.toByte(), (0x80 or sfi).toByte(), offset.toByte(), le.toByte())
    }

    /** EXTERNAL AUTHENTICATE with 40 bytes of zeros, which is no valid BAC cryptogram, and Le 28. */
    val externalAuthenticateWithoutChallenge: ByteArray = Hex.decode("00820000" + "28" + "00".repeat(40) + "28")

    val getChallenge: ByteArray = Hex.decode("0084000008")
}
