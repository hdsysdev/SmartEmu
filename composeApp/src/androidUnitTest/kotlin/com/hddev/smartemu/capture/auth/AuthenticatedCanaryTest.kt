package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.HceCardService
import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.capture.AccessCredentials
import com.hddev.smartemu.capture.CanaryPii
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.Hex
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.RedactionLint
import com.hddev.smartemu.capture.assertNoPii
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.Portrait
import com.hddev.smartemu.data.chipConfiguration
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The canary on the authenticated path: a synthetic passport with a known MRZ, names, DG11/DG12 text and portrait, and
 * a chip of every kind the profiles have, goes through an authenticated capture. None of it may be in the JSON, nor may
 * what the manifest says is never kept: the SOD's hash values, the chip's keys, the signer's serial number.
 */
class AuthenticatedCanaryTest {
    private lateinit var chip: EmulatedChip

    @Before fun setUp() { chip = EmulatedChip() }
    @After fun tearDown() { chip.close() }

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
        portrait = Portrait(portrait, 480, 640),
        can = "731946",
        personalNumber = "ZK4417H92",
        placeOfBirth = "Ooststellingwerf",
        issuingAuthority = "Burgemeester Wolkenkrabber",
        dateOfIssue = LocalDate(2024, 6, 6)
    )

    private fun piiOf(data: PassportData, chipItems: Map<String, ByteArray>): CanaryPii {
        val lines = data.toMrzLines()
        return CanaryPii(
            buildMap {
                lines.forEachIndexed { i, line -> put("MRZ line ${i + 1}", line.encodeToByteArray()) }
                put("MRZ", lines.joinToString("").encodeToByteArray())
                put("MRZ key", data.mrzKey().encodeToByteArray())
                put("document number", data.passportNumber.encodeToByteArray())
                put("date of birth", data.mrzDateOfBirth().encodeToByteArray() + "<".encodeToByteArray())
                put("last name", data.lastName.encodeToByteArray())
                put("last name, upper case", data.lastName.uppercase().encodeToByteArray())
                put("first name", data.firstName.encodeToByteArray())
                put("first name, upper case", data.firstName.uppercase().encodeToByteArray())
                put("CAN", data.can.encodeToByteArray())
                put("personal number", data.personalNumber.encodeToByteArray())
                put("place of birth", data.placeOfBirth.encodeToByteArray())
                put("issuing authority", data.issuingAuthority.encodeToByteArray())
                put("DG2 portrait, start", portrait.copyOfRange(0, 40))
                put("DG2 portrait, middle", portrait.copyOfRange(700, 740))
                put("DG2 portrait, end", portrait.copyOfRange(portrait.size - 40, portrait.size))
                // Values too short for a byte search (see assertNoShortValues) are checked as whole tokens instead
                putAll(chipItems.filterKeys { !it.startsWith(SHORT) })
            }
        )
    }

    /**
     * What the manifest says is never kept, read from the same chip by an independent JMRTD reader: every data group hash,
     * the SOD signature, the signer certificate's serial number, key identifier and key, and the per-chip AA and CA keys.
     */
    private fun chipSecrets(data: PassportData): Map<String, ByteArray> {
        PassportHceService.setSharedPassportData(data)
        val hce = PassportHceService().also { it.onCreate() }
        try {
            val service = HceCardService.openPassportService(hce)
            val key = BACKey(data.passportNumber, data.mrzDateOfBirth(), data.mrzExpiryDate())
            if (data.chipConfiguration().accessControl.supportsPace) {
                val suite = SecurityInfoReader.paceSuites(
                    SecurityInfoReader.fromCardAccess(service.getInputStream(PassportService.EF_CARD_ACCESS, 223).readBytes())
                ).first()
                service.doPACE(key, suite.info.objectIdentifier, PACEInfo.toParameterSpec(suite.parameterId), suite.parameterId)
                service.sendSelectApplet(true)
            } else {
                service.sendSelectApplet(false)
                service.doBAC(key)
            }
            return buildMap {
                val sod = SODFile(ByteArrayInputStream(service.getInputStream(PassportService.EF_SOD, 223).readBytes()))
                sod.dataGroupHashes.forEach { (n, hash) -> put("SOD hash of DG$n", hash) }
                put("SOD signature", sod.encryptedDigest)
                sod.docSigningCertificate?.let { cert ->
                    put("signer certificate", cert.encoded)
                    put("signer public key", cert.publicKey.encoded)
                    // A short serial can't be searched for as bytes (it would turn up in a JSON by chance), and is not replaced
                    // by a stand-in that proves nothing: it is checked as a whole token, in hex and in decimal
                    cert.serialNumber.toByteArray().let { serial ->
                        put(if (serial.size >= CanaryPii.MIN_BYTES) "signer serial number" else "${SHORT}signer serial number", serial)
                    }
                }
                sod.subjectKeyIdentifier?.takeIf { it.size >= CanaryPii.MIN_BYTES }?.let { put("signer key identifier", it) }
                if (14 in data.chipConfiguration().dataGroups) {
                    val dg14 = DG14File(ByteArrayInputStream(service.getInputStream(PassportService.EF_DG14, 223).readBytes()))
                    dg14.securityInfos.filterIsInstance<org.jmrtd.lds.ChipAuthenticationPublicKeyInfo>().forEachIndexed { i, k ->
                        put("CA public key $i", k.subjectPublicKey.encoded)
                    }
                }
                if (15 in data.chipConfiguration().dataGroups) {
                    put("AA public key", DG15File(ByteArrayInputStream(service.getInputStream(PassportService.EF_DG15, 223).readBytes())).publicKey.encoded)
                }
            }
        } finally {
            hce.onDestroy()
        }
    }

    /**
     * Fails if any string in [json] is, as a whole token, one of the [secrets] kept under [SHORT] in hex (either case, with
     * or without a leading zero byte) or in decimal. Numbers are not compared: a serial of 1 is also a count.
     */
    private fun assertNoShortValues(json: String, secrets: Map<String, ByteArray>) {
        val tokens = mutableSetOf<String>()
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is kotlinx.serialization.json.JsonObject -> e.values.forEach(::walk)
                is kotlinx.serialization.json.JsonArray -> e.forEach(::walk)
                is kotlinx.serialization.json.JsonPrimitive -> if (e.isString) e.content.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.forEach { tokens += it.lowercase() }
            }
        }
        walk(kotlinx.serialization.json.Json.parseToJsonElement(json))
        secrets.filterKeys { it.startsWith(SHORT) }.forEach { (name, bytes) ->
            val number = java.math.BigInteger(1, bytes)
            val forms = setOfNotNull(
                Hex.encode(bytes).lowercase(), number.toString(16).padStart(2, '0'), number.toString(16).takeIf { it.length % 2 == 0 },
                number.toString().takeIf { it.length >= 4 }
            )
            val found = forms.filter { it in tokens }
            assertTrue(found.isEmpty(), "The capture holds ${name.removePrefix(SHORT)} as a token")
        }
    }

    private fun capture(data: PassportData, credentials: AccessCredentials, confirm: () -> Boolean = { true }): CaptureRecord =
        assertIs<CaptureResult.Complete>(
            assertIs<AuthenticatedCaptureResult.Finished>(AuthenticatedCapture().run(chip.transport(data), credentials, confirm)).result
        ).record

    @Test
    fun `no personal data and none of the redacted chip data of any profile is in its authenticated capture`() {
        ChipProfiles.all.forEach { profile ->
            val data = canary.withChipProfile(profile)
            val secrets = chipSecrets(data)
            val record = capture(data, chip.mrz(data))
            val json = CaptureJson.encode(record)
            try {
                assertNoPii(json, piiOf(data, secrets))
                assertNoShortValues(json, secrets)
            } catch (e: AssertionError) {
                throw AssertionError("${profile.id}: ${e.message}")
            }
            assertEquals(emptyList(), RedactionLint.check(json), profile.id)
            assertTrue(record.authenticated?.sod != null || profile.id.isEmpty(), "${profile.id} read nothing, so the canary proved nothing")
            assertTrue(secrets.size >= 3, "${profile.id}: too few things to look for")
        }
    }

    @Test
    fun `the same holds with a CAN, which is not in the capture either`() {
        val profile = ChipProfiles.byId("de-id-card")
        val data = canary.withChipProfile(profile)
        val record = capture(data, AccessCredentials.Can.of(data.can))
        val json = CaptureJson.encode(record)
        assertTrue(record.authenticated?.sod != null, "the CAN opened the chip")
        chipSecrets(data).let { secrets ->
            assertNoPii(json, piiOf(data, secrets))
            assertNoShortValues(json, secrets)
        }
        assertEquals(emptyList(), RedactionLint.check(json))
    }

    @Test
    fun `the kinds of chip the canary covers include each the plan names`() {
        val configs = ChipProfiles.all.associateWith { canary.withChipProfile(it).chipConfiguration() }
        fun any(what: String, test: (com.hddev.smartemu.data.ChipConfiguration) -> Boolean) =
            assertTrue(configs.values.any(test), "no profile with $what")
        any("PACE-GM") { c -> c.paceSuites.any { it.mapping == com.hddev.smartemu.data.PaceMapping.GENERIC } }
        any("PACE-CAM") { c -> c.paceSuites.any { it.mapping == com.hddev.smartemu.data.PaceMapping.CHIP_AUTHENTICATION } }
        any("PACE-IM") { c -> c.paceSuites.any { it.mapping == com.hddev.smartemu.data.PaceMapping.INTEGRATED } }
        any("BAC only") { it.accessControl.supportsBac && !it.accessControl.supportsPace }
        any("DG14 with Chip Authentication") { it.chipAuthentication != null }
        any("Active Authentication") { it.activeAuthentication != null }
        any("Terminal Authentication and DG3") { it.terminalAuthentication }
    }

    @Test
    fun `credentials are erased after the run, and nothing in the capture path prints them`() {
        val data = canary.withChipProfile(ChipProfiles.byId("eu-passport"))
        val number = data.passportNumber.toCharArray()
        val birth = data.mrzDateOfBirth().toCharArray()
        val expiry = data.mrzExpiryDate().toCharArray()
        val mrz = AccessCredentials.Mrz.takingOwnershipOf(
            number, birth, expiry,
            com.hddev.smartemu.capture.MrzCheck.checkDigit(String(number).padEnd(9, '<')),
            com.hddev.smartemu.capture.MrzCheck.checkDigit(String(birth)), com.hddev.smartemu.capture.MrzCheck.checkDigit(String(expiry))
        )
        val shown = mutableListOf(mrz.toString())

        val result = AuthenticatedCapture().run(chip.transport(data), mrz)
        shown += result.toString()
        val record = assertIs<CaptureResult.Complete>(assertIs<AuthenticatedCaptureResult.Finished>(result).result).record
        shown += record.toString()
        shown += record.authenticated.toString()
        shown += record.authenticated!!.access.toString()
        shown += CaptureJson.encode(record)

        assertTrue(mrz.isClosed)
        assertTrue((number + birth + expiry).all { it == '\u0000' }, "the characters were overwritten")
        val text = shown.joinToString("\n")
        listOf(data.passportNumber, data.mrzDateOfBirth(), data.mrzExpiryDate(), data.mrzKey(), data.lastName, data.firstName).forEach {
            assertFalse(it in text, "'$it' appears in what the capture path prints")
        }

        // The same for a CAN, and for credentials that are refused
        val can = "731946".toCharArray()
        val canCredentials = AccessCredentials.Can.takingOwnershipOf(can)
        val canResult = AuthenticatedCapture().run(chip.transport(data.copy(can = "731946")), canCredentials, { true })
        assertFalse("731946" in canResult.toString() + canCredentials.toString() + canResult.let { (it as AuthenticatedCaptureResult.Finished).result.toString() })
        assertTrue(can.all { it == '\u0000' })
        val refused = AuthenticatedCapture().run(chip.transport(data), AccessCredentials.Can.of("73194"))
        assertFalse("73194" in refused.toString())
    }

    @Test
    fun `JMRTD's logging is off while the capture runs and back afterwards`() {
        val captured = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured += record }
            override fun flush() {}
            override fun close() {}
        }
        val loggers = listOf(Logger.getLogger("org.jmrtd"), Logger.getLogger("net.sf.scuba"))
        val before = loggers.map { it.level }
        loggers.forEach { it.level = Level.ALL; it.addHandler(handler) }
        try {
            val data = canary.withChipProfile(ChipProfiles.byId("nl-passport"))
            capture(data, chip.mrz(data))
            assertTrue(captured.isEmpty(), "logged: ${captured.take(3).map { it.message }}")
            assertEquals(listOf(Level.ALL, Level.ALL), loggers.map { it.level })
        } finally {
            loggers.forEachIndexed { i, l -> l.level = before[i]; l.removeHandler(handler) }
        }
    }

    @Test
    fun `the check for short chip values finds a serial as a whole token and ignores numbers`() {
        val secrets = mapOf("${SHORT}signer serial number" to byteArrayOf(0x01, 0x2C, 0x4F))
        assertNoShortValues("{\"certificateCount\": 1, \"x\": \"abcdef\"}", secrets)
        assertFailsWith<AssertionError>("hex") { assertNoShortValues("{\"curve\": \"012c4f\"}", secrets) }
        assertFailsWith<AssertionError>("no leading zero") { assertNoShortValues("{\"curve\": \"x 12C4F y\"}", secrets) }
        assertFailsWith<AssertionError>("decimal") { assertNoShortValues("{\"curve\": \"76879\"}", secrets) }
    }

    private companion object {
        /** Prefix of a chip secret too short to be a byte-search canary. */
        const val SHORT = "short: "
    }
}
