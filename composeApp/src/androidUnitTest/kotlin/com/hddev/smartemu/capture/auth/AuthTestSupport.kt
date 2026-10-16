package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.capture.AccessCredentials
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.EmulatorTransport
import com.hddev.smartemu.capture.MrzCheck
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportPresets
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import kotlin.test.assertIs

/** An emulated chip, and the authenticated capture of it, for the Phase 2 tests. */
class EmulatedChip {
    private val today = LocalDate(2026, 9, 30)
    private val service = PassportHceService().also { it.onCreate() }

    val base = PassportData(
        passportNumber = "SPEC24681",
        dateOfBirth = LocalDate(1986, 3, 14),
        expiryDate = LocalDate(2035, 3, 13),
        issuingCountry = "SWE",
        nationality = "SWE",
        firstName = "Anna Maria",
        lastName = "Eriksson",
        gender = "F"
    )

    fun close() {
        PassportHceService.setSharedPassportData(null)
        service.onDestroy()
    }

    /** The profile's ready-made document where there is one, else the base holder on a document of its kind. */
    fun documentFor(profile: ChipProfile): PassportData =
        PassportPresets.all.firstOrNull { it.id == "profile-${profile.id}" }?.build?.invoke(today) ?: base.withChipProfile(profile)

    fun transport(data: PassportData): EmulatorTransport {
        PassportHceService.setSharedPassportData(data)
        return EmulatorTransport(service)
    }

    fun mrz(data: PassportData): AccessCredentials.Mrz = mrzWithDigits(data.passportNumber, data.mrzDateOfBirth(), data.mrzExpiryDate())

    fun capture(data: PassportData, credentials: AccessCredentials = mrz(data), confirm: () -> Boolean = { true }): CaptureResult =
        assertIs<AuthenticatedCaptureResult.Finished>(
            AuthenticatedCapture().run(transport(data), credentials, confirm)
        ).result

    fun record(data: PassportData, credentials: AccessCredentials = mrz(data), confirm: () -> Boolean = { true }): CaptureRecord =
        assertIs<CaptureResult.Complete>(capture(data, credentials, confirm)).record
}

/** Passes commands to [inner], keeps every one, and lets [rewrite] replace a response (to play a chip that answers differently). */
class WatchingTransport(
    private val inner: com.hddev.smartemu.capture.ApduTransport,
    private val rewrite: (index: Int, command: ByteArray, response: ByteArray) -> ByteArray = { _, _, response -> response }
) : com.hddev.smartemu.capture.ApduTransport {
    val commands = mutableListOf<ByteArray>()

    override fun transmit(command: ByteArray): ByteArray {
        commands += command.copyOf()
        return rewrite(commands.size - 1, command, inner.transmit(command))
    }

    override fun linkInfo() = inner.linkInfo()

    override fun readerDevice() = inner.readerDevice()

    /** How many commands had instruction [ins]. */
    fun count(ins: Int): Int = commands.count { it.size > 1 && (it[1].toInt() and 0xFF) == ins }

    companion object {
        const val INS_MSE = 0x22
        const val INS_GENERAL_AUTHENTICATE = 0x86
        const val INS_EXTERNAL_AUTHENTICATE = 0x82
        const val INS_GET_CHALLENGE = 0x84
        const val INS_INTERNAL_AUTHENTICATE = 0x88
    }
}

/**
 * MRZ credentials with the check digits a person would type from the document's MRZ line (computed here, as the test's
 * stand-in for the printed ones: the app itself never computes them for a key, see [AccessCredentials.Mrz]).
 */
fun mrzWithDigits(number: String, birth: String, expiry: String): AccessCredentials.Mrz = AccessCredentials.Mrz.of(
    number, birth, expiry,
    MrzCheck.checkDigit(number.uppercase().padEnd(9, '<')), MrzCheck.checkDigit(birth), MrzCheck.checkDigit(expiry)
)

/** A response APDU that is only the status word [sw]. */
fun statusOnly(sw: Int): ByteArray = byteArrayOf((sw shr 8).toByte(), sw.toByte())
