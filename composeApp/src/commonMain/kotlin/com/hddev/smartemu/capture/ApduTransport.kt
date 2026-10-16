package com.hddev.smartemu.capture

/**
 * The seam between a capture run and whatever carries APDUs to a chip: `IsoDep` on the phone, the JVM's emulated
 * chip in tests, or a PC/SC or Proxmark importer. Implementations hold no transcript; logging is the
 * [com.hddev.smartemu.capture.CaptureSession]'s job and stays in memory.
 */
interface ApduTransport {
    /** Sends one command APDU and returns the response APDU, data plus status word. Throws [TagLostException]. */
    fun transmit(command: ByteArray): ByteArray

    /** What the link layer showed, or null where the transport can't tell (a test double, a PC/SC reader). */
    fun linkInfo(): LinkObservation? = null

    /** The phone's NFC controller limits; reader-side, not the chip's. */
    fun readerDevice(): ReaderDevice
}

class TagLostException(cause: Throwable? = null) : Exception("The tag was lost", cause)

/** The reader can't send this command, for instance because it is longer than the NFC controller transmits. */
class CommandNotSupportedByReader(message: String) : Exception(message)
