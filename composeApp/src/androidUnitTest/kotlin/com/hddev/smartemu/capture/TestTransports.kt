package com.hddev.smartemu.capture

import com.hddev.smartemu.PassportHceService

/** The device the JVM tests pretend to read with. */
val testReader = ReaderDevice(
    model = "JVM test reader",
    androidVersion = "n/a",
    controllerMaxTransceiveLength = 65279,
    controllerExtendedLengthSupported = true
)

/** Delivers a reader's APDUs to the emulated chip as Android's NFC stack does, like HceCardService does for JMRTD. */
class EmulatorTransport(
    private val service: PassportHceService,
    private val device: ReaderDevice = testReader
) : ApduTransport {
    override fun transmit(command: ByteArray): ByteArray = service.processCommandApdu(command, null)

    override fun linkInfo(): LinkObservation = LinkObservation(NfcTechnology.NFC_A)

    override fun readerDevice(): ReaderDevice = device
}

/** A chip scripted by a function from command to response; keeps what it was sent. */
class ScriptedTransport(
    private val device: ReaderDevice = testReader,
    private val answer: (ByteArray) -> ByteArray
) : ApduTransport {
    val sent = mutableListOf<ByteArray>()

    override fun transmit(command: ByteArray): ByteArray {
        sent += command
        return answer(command)
    }

    override fun linkInfo(): LinkObservation = LinkObservation(NfcTechnology.NFC_A)

    override fun readerDevice(): ReaderDevice = device
}

/** Passes commands to [inner] but loses the tag at the [loseAt]-th command (counting from 1), once. */
class TagLossTransport(private val inner: ApduTransport, private val loseAt: Int) : ApduTransport {
    var sent = 0
        private set

    override fun transmit(command: ByteArray): ByteArray {
        sent++
        if (sent == loseAt) throw TagLostException()
        return inner.transmit(command)
    }

    override fun linkInfo(): LinkObservation? = inner.linkInfo()

    override fun readerDevice(): ReaderDevice = inner.readerDevice()
}

/** A response APDU of [data] and [statusWord]. */
fun response(statusWord: Int, data: ByteArray = byteArrayOf()): ByteArray =
    data + byteArrayOf((statusWord shr 8).toByte(), statusWord.toByte())

fun ByteArray.hex(): String = Hex.encode(this)

fun hexBytes(text: String): ByteArray = Hex.decode(text)
