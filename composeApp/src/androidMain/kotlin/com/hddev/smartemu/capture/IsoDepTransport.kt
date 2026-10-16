package com.hddev.smartemu.capture

import android.nfc.tech.IsoDep
import android.os.Build
import java.io.Closeable
import java.io.IOException

/**
 * Carries a capture run's APDUs over [IsoDep]. It keeps no transcript.
 *
 * No UID is read, kept or compared, and the link's `uidRandom` is always null (unknown): a first byte of 0x08 is only a hint
 * of a random UID, one tap can't confirm it, and on NFC-B the identifier is a PUPI (a pseudo-unique number most
 * chips change on every activation), so a first byte means nothing there. Reporting false would claim a finding no tap
 * made. Construct and use it off the main thread; connecting and transmitting block.
 */
class IsoDepTransport(private val isoDep: IsoDep) : ApduTransport, Closeable {

    private val technology: NfcTechnology
    private val historicalBytes: String?

    init {
        val tag = isoDep.tag
        technology = if (tag.techList.contains(NFC_B_TECH)) NfcTechnology.NFC_B else NfcTechnology.NFC_A
        historicalBytes = when (technology) {
            NfcTechnology.NFC_A -> isoDep.historicalBytes
            NfcTechnology.NFC_B -> isoDep.hiLayerResponse
        }?.toHex()
        try {
            if (!isoDep.isConnected) isoDep.connect()
            isoDep.timeout = TIMEOUT_MS
        } catch (e: IOException) {
            throw TagLostException(e)
        } catch (e: SecurityException) {
            throw TagLostException(e)
        }
    }

    override fun transmit(command: ByteArray): ByteArray = try {
        isoDep.transceive(command)
    } catch (e: IOException) { // android.nfc.TagLostException is one
        throw TagLostException(e)
    } catch (e: SecurityException) { // the tag is out of date, as after the user lifted it and tapped another
        throw TagLostException(e)
    }

    /** Raises the time a command may take, for a slow chip; the default is [TIMEOUT_MS]. */
    fun extendTimeout(timeoutMs: Int) {
        try {
            isoDep.timeout = timeoutMs
        } catch (_: RuntimeException) {
            // The tag is gone; the next transmit says so
        }
    }

    override fun linkInfo(): LinkObservation = LinkObservation(
        technology = technology,
        historicalBytes = historicalBytes,
        // Unknown: a random UID is confirmed only by a second tap with a different UID, which this doesn't do, and no UID
        // is kept for comparing. Null for NFC-A and NFC-B alike (an NFC-B id is a PUPI)
        uidRandom = null,
        maxApduLengthObserved = null
    )

    override fun readerDevice(): ReaderDevice = ReaderDevice(
        model = Build.MODEL,
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        // Android doesn't tell an app which NFC controller the phone has
        nfcChipset = null,
        // These are the phone's controller's limits, not the chip's
        controllerMaxTransceiveLength = runCatching { isoDep.maxTransceiveLength }.getOrNull(),
        controllerExtendedLengthSupported = runCatching { isoDep.isExtendedLengthApduSupported }.getOrNull()
    )

    override fun close() {
        try {
            isoDep.close()
        } catch (_: IOException) {
            // Already gone
        }
    }

    private companion object {
        const val NFC_B_TECH = "android.nfc.tech.NfcB"
        const val TIMEOUT_MS = 5000

        fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    }
}
