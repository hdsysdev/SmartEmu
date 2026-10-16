package com.hddev.smartemu.capture

import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.withChipProfile
import com.hddev.smartemu.capture.RedactionLint.Rule
import kotlinx.datetime.LocalDate
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionLintTest {
    private val td3 = listOf(
        "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
        "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
    )
    private val td2 = listOf("I<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<", "D231458907UTO7408122F1204159<<<<<<<6")
    private val td1 = listOf("I<UTOD231458907<<<<<<<<<<<<<<<", "7408122F1204159UTO<<<<<<<<<<<6", "ERIKSSON<<ANNA<MARIA<<<<<<<<<<")

    private val golden = GoldenCapture.record

    private fun check(record: CaptureRecord) = RedactionLint.check(CaptureJson.encode(record))

    private fun rules(record: CaptureRecord) = check(record).map { it.rule }.toSet()

    @Test
    fun `fixtures have the lengths of MRZ lines`() {
        assertEquals(listOf(44, 44), td3.map { it.length })
        assertEquals(listOf(36, 36), td2.map { it.length })
        assertEquals(listOf(30, 30, 30), td1.map { it.length })
    }

    @Test
    fun `a clean record passes`() {
        assertEquals(emptyList(), check(golden))
    }

    @Test
    fun `a capture of the emulator passes`() {
        val service = PassportHceService().also { it.onCreate() }
        try {
            ChipProfiles.all.forEach { profile ->
                PassportHceService.setSharedPassportData(
                    PassportData(passportNumber = "SPEC24681", dateOfBirth = LocalDate(1986, 3, 14), expiryDate = LocalDate(2035, 3, 13), firstName = "Anna", lastName = "Eriksson")
                        .withChipProfile(profile)
                )
                val record = (CaptureRunner().run(EmulatorTransport(service)) as CaptureResult.Complete).record
                assertEquals(emptyList(), check(record), profile.id)
            }
        } finally {
            PassportHceService.setSharedPassportData(null)
            service.onDestroy()
        }
    }

    @Test
    fun `refuses a wrong schema version and says nothing else`() {
        for (version in listOf(0, 3, 99)) {
            val json = CaptureJson.encode(golden).replace("\"schemaVersion\": 2", "\"schemaVersion\": $version")
            assertEquals(listOf(Rule.SCHEMA_VERSION), RedactionLint.check(json).map { it.rule })
        }
        assertEquals(listOf(Rule.SCHEMA_VERSION), RedactionLint.check("{}").map { it.rule })
    }

    @Test
    fun `refuses text that is not JSON`() {
        assertEquals(listOf(Rule.MALFORMED_JSON), RedactionLint.check("P<UTOERIKSSON").map { it.rule })
    }

    @Test
    fun `refuses unknown fields`() {
        val json = CaptureJson.encode(golden).replaceFirst("{", "{\n    \"holder\": \"Anna\",")
        assertTrue(Rule.SCHEMA_MISMATCH in RedactionLint.check(json).map { it.rule })
    }

    @Test
    fun `refuses MRZ lines of every format in any text field`() {
        for (lines in listOf(td3, td2, td1)) {
            val joined = lines.joinToString("\n")
            val record = golden.copy(reader = golden.reader.copy(model = joined))
            assertTrue(Rule.MRZ_PATTERN in rules(record), "${lines.first().length}-character lines")
        }
    }

    @Test
    fun `refuses MRZ lines run together or hidden among other text`() {
        assertTrue(Rule.MRZ_PATTERN in rules(golden.copy(reader = golden.reader.copy(model = td3.joinToString("")))))
        assertTrue(Rule.MRZ_PATTERN in rules(golden.copy(reader = golden.reader.copy(model = "Pixel ${td3[0]} 9"))))
        assertTrue(Rule.MRZ_PATTERN in rules(golden.copy(reader = golden.reader.copy(model = "ERIKSSON<<ANNA"))))
        // Lines with no filler at all are caught by their shape
        val noFiller = List(3) { "A1B2C3D4E5".repeat(3) }
        assertTrue(Rule.MRZ_PATTERN in rules(golden.copy(reader = golden.reader.copy(model = noFiller.joinToString("\n")))))
    }

    @Test
    fun `refuses an MRZ hidden in the hex of a file`() {
        val hidden = Hex.encode(td3.joinToString("").encodeToByteArray())
        val record = golden.copy(files = golden.files.copy(dir = FileObservation(0x9000, hidden)))
        assertTrue(Rule.MRZ_PATTERN in rules(record))
    }

    @Test
    fun `refuses hex and base64 blobs outside the file fields`() {
        val blob = Random(7).nextBytes(64)
        val hex = Hex.encode(blob)
        val b64 = Base64.getEncoder().encodeToString(blob)
        val b64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(blob)
        for (text in listOf(hex, hex.lowercase(), b64, b64Url, "Pixel $b64")) {
            assertTrue(Rule.HIGH_ENTROPY_BLOB in rules(golden.copy(reader = golden.reader.copy(nfcChipset = text))), text.take(12))
        }
        val redaction = golden.copy(redactions = listOf(RedactionEntry("Portrait", hex)))
        assertTrue(Rule.HIGH_ENTROPY_BLOB in rules(redaction))
    }

    @Test
    fun `allows the three files' hex and ordinary text`() {
        assertEquals(emptyList(), check(golden.copy(reader = golden.reader.copy(model = "Samsung SM-S928B", nfcChipset = "NXP PN7160"))))
        assertFalse(RedactionLint.looksLikeMrz("Chip Authentication Mapping"))
        assertFalse(RedactionLint.looksLikeMrz("A0000002471001"))
    }

    @Test
    fun `refuses file contents that are not what the field holds`() {
        val tooLong = Hex.encode(ByteArray(600) { 0x01 })
        assertTrue(Rule.BAD_FIELD in rules(golden.copy(files = golden.files.copy(atrInfo = FileObservation(0x9000, tooLong)))))
        assertTrue(Rule.BAD_FIELD in rules(golden.copy(files = golden.files.copy(dir = FileObservation(0x9000, "xyz")))))
        // EF.CardAccess is BER-TLV; a portrait is not
        val notTlv = Hex.encode(Random(3).nextBytes(100).also { it[0] = 0xFF.toByte() })
        assertTrue(Rule.BAD_FIELD in rules(golden.copy(files = golden.files.copy(cardAccess = FileObservation(0x9000, notTlv)))))
        val longCardAccess = Hex.encode(byteArrayOf(0x31, 0x82.toByte(), 0x0A, 0x00) + ByteArray(2560))
        assertTrue(Rule.BAD_FIELD in rules(golden.copy(files = golden.files.copy(cardAccess = FileObservation(0x9000, longCardAccess)))))
    }

    @Test
    fun `refuses an id that is not random, a month with a day and odd identifiers`() {
        assertTrue(Rule.BAD_FIELD in rules(golden.copy(captureId = "device-1234")))
        assertTrue(Rule.BAD_FIELD in rules(golden.copy(captureId = "3b0d6c2e-7a41-1d8e-9c55-0f7e1a2b3c4d")))
        assertTrue(Rule.BAD_FIELD in rules(golden.copy(captureMonth = "2026-09-14")))
        assertTrue(Rule.BAD_FIELD in rules(golden.copy(probePack = ProbePackRef("Anna Eriksson", 1))))
        assertTrue(Rule.BAD_FIELD in rules(golden.copy(link = golden.link.copy(historicalBytes = "AB".repeat(100)))))
        assertTrue(Rule.BAD_FIELD in rules(golden.copy(fingerprint = "A".repeat(64))))
    }

    @Test
    fun `a violation never repeats the text it found`() {
        val secret = td3.joinToString("\n")
        val violations = check(golden.copy(reader = golden.reader.copy(model = secret)))
        assertTrue(violations.isNotEmpty())
        violations.forEach { v ->
            assertFalse(v.detail.contains("ERIKSSON") || v.path.contains("ERIKSSON"), v.toString())
        }
    }

    @Test
    fun `finds the pii of the canary passport if it leaked`() {
        val leaky = golden.copy(redactions = listOf(RedactionEntry("Name", "Quillon Zephyr Vandermolen"), RedactionEntry("MRZ", td3.joinToString("\n"))))
        assertTrue(Rule.MRZ_PATTERN in rules(leaky))
    }

    // ---- the rules the docs state, enforced ----

    private fun sealed(record: CaptureRecord) = CaptureFingerprint.seal(record)

    @Test
    fun `an empty fingerprint, a wrong one and a lower-case one are refused`() {
        assertTrue(Rule.FINGERPRINT_MISMATCH in rules(golden.copy(fingerprint = "")))
        assertTrue(Rule.FINGERPRINT_MISMATCH in rules(golden.copy(fingerprint = "0123456789ABCDEF")))
        assertTrue(Rule.FINGERPRINT_MISMATCH in rules(golden.copy(fingerprint = golden.fingerprint.lowercase())))
        assertTrue(Rule.FINGERPRINT_MISMATCH in rules(golden.copy(observationDigest = "")), "a version 2 record carries its digest")
        assertTrue(Rule.FINGERPRINT_MISMATCH in rules(golden.copy(observationDigest = "0123456789ABCDEF")))
        // The fingerprint is recomputed: a record edited after it was sealed no longer matches
        assertTrue(Rule.FINGERPRINT_MISMATCH in rules(golden.copy(files = golden.files.copy(dir = null))))
        assertTrue(Rule.FINGERPRINT_MISMATCH in rules(golden.copy(bacCommands = BacCommandObservation(0x6A82, null))))
        assertEquals(emptyList(), check(sealed(golden.copy(bacCommands = BacCommandObservation(0x6A82, null)))))
    }

    @Test
    fun `lower-case hex in a file is accepted and hashes as upper case`() {
        val lower = golden.copy(files = golden.files.copy(dir = golden.files.dir!!.copy(contentsHex = golden.files.dir!!.contentsHex!!.lowercase())))
        assertEquals(golden.fingerprint, CaptureFingerprint.of(lower))
        assertEquals(emptyList(), check(lower))
    }

    @Test
    fun `a version 1 record is checked as version 1`() {
        val v1 = GoldenCapture.recordV1
        assertEquals(emptyList(), check(v1))
        assertTrue(Rule.FINGERPRINT_MISMATCH in rules(v1.copy(fingerprint = golden.fingerprint)), "the version 2 key is not a version 1 fingerprint")
        assertTrue(Rule.REDACTIONS_MISMATCH in rules(v1.copy(redactions = RedactionManifest.entries)), "nor the version 2 manifest a version 1 record's")
        assertTrue(Rule.BAD_FIELD in rules(v1.copy(observationDigest = "0123456789ABCDEF")))
    }

    @Test
    fun `the redactions must be the manifest, with the authenticated entries only where there is an authenticated part`() {
        assertTrue(Rule.REDACTIONS_MISMATCH in rules(golden.copy(redactions = emptyList())))
        assertTrue(Rule.REDACTIONS_MISMATCH in rules(golden.copy(redactions = golden.redactions.drop(1))))
        assertTrue(Rule.REDACTIONS_MISMATCH in rules(golden.copy(redactions = golden.redactions + RedactionEntry("Something", "else"))))
        assertTrue(Rule.REDACTIONS_MISMATCH in rules(golden.copy(redactions = golden.redactions.reversed())))
        assertTrue(Rule.REDACTIONS_MISMATCH in rules(golden.copy(redactions = golden.redactions + RedactionManifest.authenticatedEntries)), "authenticated entries without an authenticated part")
        val withAuth = sealed(golden.copy(authenticated = AuthenticatedObservation(CredentialKind.MRZ), redactions = RedactionManifest.expected(2, true)))
        assertEquals(emptyList(), check(withAuth))
        assertTrue(Rule.REDACTIONS_MISMATCH in rules(withAuth.copy(redactions = RedactionManifest.entries)))
    }

    @Test
    fun `sizes must be buckets, 256 bytes for files and 16 for probe and application responses`() {
        fun bad(record: CaptureRecord, path: String) =
            assertTrue(check(sealed(record)).any { it.rule == Rule.BAD_FIELD && it.path == path }, path)
        bad(golden.copy(files = golden.files.copy(dir = golden.files.dir!!.copy(size = SizeRange(0, 15)))), "files.dir.size")
        bad(golden.copy(files = golden.files.copy(dir = golden.files.dir!!.copy(size = SizeRange(100, 355)))), "files.dir.size")
        bad(golden.copy(files = golden.files.copy(dir = golden.files.dir!!.copy(size = SizeRange(11, 11)))), "files.dir.size")
        bad(golden.copy(application = ApplicationObservation(0x9000, SizeRange(0, 255))), "application.responseSize")
        val step = golden.probes[0].steps[0]
        fun withStep(change: (StepResult) -> StepResult) =
            golden.copy(probes = listOf(golden.probes[0].copy(steps = listOf(change(step)) + golden.probes[0].steps.drop(1))) + golden.probes.drop(1))
        bad(withStep { it.copy(responseSize = SizeRange(0, 255)) }, "probes[].steps[].responseSize")
        bad(withStep { it.copy(responseSize = SizeRange(5, 20)) }, "probes[].steps[].responseSize")
        assertEquals(emptyList(), check(sealed(withStep { it.copy(responseSize = SizeRange(208, 223)) })))
    }

    @Test
    fun `numbers are bounded`() {
        fun bad(record: CaptureRecord, path: String) =
            assertTrue(check(sealed(record)).any { it.rule == Rule.BAD_FIELD && it.path == path }, path)
        val step = golden.probes[0].steps[0]
        fun withStep(change: (StepResult) -> StepResult) =
            golden.copy(probes = listOf(golden.probes[0].copy(steps = listOf(change(step)) + golden.probes[0].steps.drop(1))) + golden.probes.drop(1))
        bad(withStep { it.copy(statusWord = 0x10000) }, "probes[].steps[].statusWord")
        bad(withStep { it.copy(statusWord = -1) }, "probes[].steps[].statusWord")
        bad(withStep { it.copy(setupStatusWord = 70000) }, "probes[].steps[].setupStatusWord")
        bad(withStep { it.copy(timing = TimingSummary(1, 2, 600_001, 3)) }, "probes[].steps[].timing.maxMs")
        bad(withStep { it.copy(timing = TimingSummary(-1, 2, 3, 3)) }, "probes[].steps[].timing.minMs")
        bad(withStep { it.copy(timing = TimingSummary(1, 2, 3, 0)) }, "probes[].steps[].timing.samples")
        bad(withStep { it.copy(timing = TimingSummary(1, 2, 3, 51)) }, "probes[].steps[].timing.samples")
        bad(withStep { it.copy(timing = TimingSummary(5, 2, 3, 3)) }, "probes[].steps[].timing")
        bad(golden.copy(files = golden.files.copy(dir = golden.files.dir!!.copy(selectStatusWord = 0x1FFFF))), "files.dir.selectStatusWord")
        bad(golden.copy(application = ApplicationObservation(-5)), "application.selectStatusWord")
        bad(golden.copy(bacCommands = BacCommandObservation(0x12345, null)), "bacCommands.getChallenge")
        bad(golden.copy(reader = golden.reader.copy(controllerMaxTransceiveLength = 65536)), "reader.controllerMaxTransceiveLength")
        bad(golden.copy(reader = golden.reader.copy(controllerMaxTransceiveLength = -1)), "reader.controllerMaxTransceiveLength")
        bad(golden.copy(link = golden.link.copy(maxApduLengthObserved = 100_000)), "link.maxApduLengthObserved")
        // The limits themselves are fine
        assertEquals(emptyList(), check(sealed(withStep { it.copy(statusWord = 0xFFFF, timing = TimingSummary(0, 0, 600_000, 50)) })))
        assertEquals(emptyList(), check(sealed(golden.copy(reader = golden.reader.copy(controllerMaxTransceiveLength = 65535)))))
    }

    @Test
    fun `the reader's names are short plain names that are not a CAN or a document number`() {
        fun bad(model: String) = assertTrue(check(sealed(golden.copy(reader = golden.reader.copy(model = model)))).any { it.path == "reader.model" }, model)
        listOf("123456", "Pixel 123456", "L898902C3", "Phone L898902C3", "Pixel, rooted", "Pixel <x>", "x".repeat(65), "", "Pixel\u00e9").forEach(::bad)
        assertTrue(check(sealed(golden.copy(reader = golden.reader.copy(androidVersion = "654321")))).any { it.path == "reader.androidVersion" })
        assertTrue(check(sealed(golden.copy(reader = golden.reader.copy(nfcChipset = "ST54K, secret")))).any { it.path == "reader.nfcChipset" })
        // Real model strings are not refused
        listOf(
            "Pixel 7 Pro", "Pixel 9", "SM-S918B", "SM-G991B/DS", "M2007J3SG", "CPH2451", "XQ-CT54", "Nokia 105 (2017)", "Galaxy S24 Ultra",
            "SYNTHETIC reader A (JVM emulator not a phone)", "ACR122U", "OnePlus 12", "2201123G", "moto g84 5G", "Redmi Note 12 Pro+"
        ).forEach { model ->
            assertEquals(emptyList(), check(sealed(golden.copy(reader = golden.reader.copy(model = model, androidVersion = "14", nfcChipset = "NXP PN7160")))), model)
        }
        assertEquals(emptyList(), check(sealed(golden.copy(reader = golden.reader.copy(androidVersion = "n/a (synthetic)")))))
    }

    @Test
    fun `identifiers have the shape the registry has, and are resolved where the registry is`() {
        assertTrue(check(sealed(golden.copy(probePack = ProbePackRef("Tier A", 1)))).any { it.path == "probePack.id" })
        assertTrue(check(sealed(golden.copy(probes = listOf(golden.probes[0].copy(probeId = "Anna")) + golden.probes.drop(1)))).any { it.path == "probes[].probeId" })
        // Whether the pack, probe and step exist is not the lint's to say (it is shape only): capture intake resolves them
        assertEquals(emptyList(), check(sealed(golden.copy(probePack = ProbePackRef("unheard-of", 9)))))
    }

    @Test
    fun `a record from the builder for a real-looking device passes the whole lint`() {
        val record = CaptureBuilder.create().setCaptureMonth(2026, 9).setProbePack(ProbePackRef("tier-a", 1))
            .setReader(ReaderDevice("Pixel 7 Pro", "14", "ST54K", 65279, true)).setLink(LinkObservation(NfcTechnology.NFC_A, historicalBytes = "8000"))
            .setCardAccess(0x9000, hexBytes("31143012060A04007F0007020204020202010202010C")).build()
        assertEquals(emptyList(), check(record))
    }
}
