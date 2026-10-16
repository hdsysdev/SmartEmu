package com.hddev.smartemu.capture

import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.Portrait
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The canary: a synthetic passport with known MRZ, name, document number and portrait bytes goes through a capture,
 * and none of them may be found in the saved JSON, as hex, ASCII or base64. [assertNoPii] is what Phase 2's
 * authenticated path runs on its own output.
 */
class CanaryRedactionTest {
    private val portrait = ByteArray(1500).also { Random(42).nextBytes(it) }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[it.size - 2] = 0xFF.toByte(); it[it.size - 1] = 0xD9.toByte()
    }

    private val canary = PassportData(
        passportNumber = "QX7R4K9ZP",
        dateOfBirth = LocalDate(1983, 11, 27),
        expiryDate = LocalDate(2034, 6, 5),
        issuingCountry = "NLD",
        nationality = "NLD",
        firstName = "Quillon Zephyr",
        lastName = "Vandermolen",
        gender = "M",
        portrait = Portrait(portrait, 480, 640)
    )

    private lateinit var hceService: PassportHceService

    @Before
    fun setUp() {
        hceService = PassportHceService()
        hceService.onCreate()
    }

    @After
    fun tearDown() {
        PassportHceService.setSharedPassportData(null)
        hceService.onDestroy()
    }

    private fun piiOf(data: PassportData): CanaryPii {
        val lines = data.toMrzLines()
        return CanaryPii(
            buildMap {
                lines.forEachIndexed { i, line -> put("MRZ line ${i + 1}", line.encodeToByteArray()) }
                put("MRZ", lines.joinToString("").encodeToByteArray())
                put("MRZ key (document number, birth and expiry with check digits)", data.mrzKey().encodeToByteArray())
                put("document number", data.passportNumber.encodeToByteArray())
                put("last name", data.lastName.encodeToByteArray())
                put("last name, upper case", data.lastName.uppercase().encodeToByteArray())
                put("first name", data.firstName.encodeToByteArray())
                put("first name, upper case", data.firstName.uppercase().encodeToByteArray())
                put("DG2 portrait", portrait)
                put("DG2 portrait, start", portrait.copyOfRange(0, 40))
                put("DG2 portrait, middle", portrait.copyOfRange(700, 740))
                put("DG2 portrait, end", portrait.copyOfRange(portrait.size - 40, portrait.size))
            }
        )
    }

    @Test
    fun `no personal data of any profile's synthetic passport is in its capture`() {
        ChipProfiles.all.forEach { profile ->
            val data = canary.withChipProfile(profile)
            PassportHceService.setSharedPassportData(data)
            val record = assertIs<CaptureResult.Complete>(CaptureRunner().run(EmulatorTransport(hceService))).record
            val json = CaptureJson.encode(record)
            try {
                assertNoPii(json, piiOf(data))
            } catch (e: AssertionError) {
                throw AssertionError("${profile.id}: ${e.message}")
            }
            assertEquals(emptyList(), RedactionLint.check(json), profile.id)
        }
    }

    @Test
    fun `the canary finds its pii as hex, ascii and base64, aligned any way`() {
        val data = canary.withChipProfile(ChipProfiles.all.first())
        val pii = piiOf(data)
        val surname = data.lastName.encodeToByteArray()
        val encodings = mapOf(
            "hex" to Hex.encode(portrait.copyOfRange(0, 40)),
            "lower-case hex" to Hex.encode(portrait.copyOfRange(0, 40)).lowercase(),
            "ascii" to "{\"model\": \"${data.lastName}\"}",
            "lower-case ascii" to data.lastName.lowercase(),
            "base64 of the portrait" to Base64.getEncoder().encodeToString(portrait),
            "url-safe base64 of the portrait" to Base64.getUrlEncoder().withoutPadding().encodeToString(portrait),
            "the MRZ" to data.toMrzLines().joinToString("\\n")
        )
        encodings.forEach { (name, text) ->
            assertFailsWith<AssertionError>(name) { assertNoPii("{\"x\": \"$text\"}", pii) }
        }
        // Base64 of a longer text that holds the surname: it lines up three ways, and each is caught
        for (prefix in 0..2) {
            val inside = Base64.getEncoder().encodeToString(ByteArray(prefix) { 'a'.code.toByte() } + surname + "tail".encodeToByteArray())
            assertFailsWith<AssertionError>("prefix $prefix") { assertNoPii("{\"x\": \"$inside\"}", pii) }
        }
    }

    @Test
    fun `the canary does not cry wolf on a capture of nobody`() {
        assertNoPii(CaptureJson.encode(GoldenCapture.record), piiOf(canary.withChipProfile(ChipProfiles.all.first())))
    }

    @Test
    fun `short patterns are refused as canaries`() {
        assertFailsWith<IllegalArgumentException> { CanaryPii(mapOf("short" to byteArrayOf(1, 2, 3))) }
        assertTrue(CanaryPii.MIN_BYTES > 3)
    }
}
