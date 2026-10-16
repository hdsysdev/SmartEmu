package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.EmulatorTransport
import com.hddev.smartemu.capture.ReaderDevice
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportPresets
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test

/**
 * Writes the SYNTHETIC golden captures in src/androidUnitTest/resources/capture/golden: the JVM emulator of two or three
 * profiles, read twice each by a pretend reader whose device fields vary. They are the emulator's own behaviour, so they
 * establish nothing about any real chip; they exist so the hold-out and diff tests have inputs. Run once with
 * `GENERATE_SYNTHETIC_GOLDEN=1 ./gradlew :composeApp:testDebugUnitTest --tests '*SyntheticGoldenGenerator*'`; without
 * it this test does nothing. Real captures dropped in the same directory are picked up by [GoldenCaptureDiffTest].
 */
class SyntheticGoldenGenerator {
    private val directory = File("src/androidUnitTest/resources/capture/golden")
    private val today = LocalDate(2026, 10, 1)

    private val base = PassportData(
        passportNumber = "SPEC24681",
        dateOfBirth = LocalDate(1986, 3, 14),
        expiryDate = LocalDate(2035, 3, 13),
        issuingCountry = "SWE",
        nationality = "SWE",
        firstName = "Anna Maria",
        lastName = "Eriksson",
        gender = "F"
    )

    private fun reader(model: String, chipset: String, maxTransceive: Int, extended: Boolean = true) = ReaderDevice(
        model = "SYNTHETIC $model (JVM emulator not a phone)",
        androidVersion = "n/a (synthetic)",
        nfcChipset = chipset,
        controllerMaxTransceiveLength = maxTransceive,
        controllerExtendedLengthSupported = extended
    )

    @Test
    fun `write the synthetic golden captures on request`() {
        if (System.getenv("GENERATE_SYNTHETIC_GOLDEN") != "1") return
        directory.mkdirs()
        directory.listFiles { f -> f.name.startsWith("synthetic-") }?.forEach { it.delete() }
        val readers = listOf(
            reader("reader A", "synthetic chipset A", 65279),
            reader("reader B", "synthetic chipset B", 261),
            // Can't send extended-length commands, so the extended steps are missing and the fingerprint differs
            reader("reader C", "synthetic chipset C", 261, extended = false)
        )
        val plan = listOf(
            "de-passport-2017" to listOf(readers[0], readers[1], readers[2]),
            "de-id-card" to listOf(readers[0], readers[1]),
            "us-passport-2021" to listOf(readers[1], readers[0])
        )
        plan.forEach { (profileId, devices) ->
            val profile = ChipProfiles.byId(profileId)
            val data = PassportPresets.all.firstOrNull { it.id == "profile-${profile.id}" }?.build?.invoke(today) ?: base.withChipProfile(profile)
            devices.forEachIndexed { index, device ->
                PassportHceService.setSharedPassportData(data)
                val service = PassportHceService().also { it.onCreate() }
                try {
                    val record = captureOf(EmulatorTransport(service, device))
                    File(directory, "synthetic-$profileId-${index + 1}.json").writeText(CaptureJson.encode(record) + "\n")
                } finally {
                    PassportHceService.setSharedPassportData(null)
                    service.onDestroy()
                }
            }
        }
    }
}
