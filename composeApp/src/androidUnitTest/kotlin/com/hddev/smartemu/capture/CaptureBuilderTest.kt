package com.hddev.smartemu.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureBuilderTest {
    /** A PACEInfo (id-PACE-ECDH-GM-AES-CBC-CMAC-128, version 2, parameter 13) in a SET: the smallest real EF.CardAccess. */
    private val paceInfo = Hex.decode("31143012060A04007F0007020204020202010202010D")
    private fun paceInfoWithParameter(id: Int) = paceInfo.copyOf().also { it[it.size - 1] = id.toByte() }

    private fun builder(): CaptureBuilder = CaptureBuilder.create()
        .setCaptureMonth(2026, 9)
        .setProbePack(ProbePackRef("tier-a", 1))
        .setReader(testReader)
        .setLink(LinkObservation(NfcTechnology.NFC_A))

    @Test
    fun `the capture date is the month only`() {
        val record = builder().setCaptureMonth(2026, 3).build()
        assertEquals("2026-03", record.captureMonth)
        assertFailsWith<IllegalArgumentException> { builder().setCaptureMonth(2026, 13) }
    }

    @Test
    fun `the capture id is a random version 4 UUID`() {
        val ids = (1..50).map { builder().build().captureId }
        assertEquals(50, ids.toSet().size)
        ids.forEach {
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(it), it)
        }
    }

    @Test
    fun `file sizes are rounded to buckets and contents are hex in the chip's order`() {
        val contents = com.hddev.smartemu.capture.profile.FakeChip.defaultCardAccess()
        val record = builder().setCardAccess(0x9000, contents).setAtrInfo(0x6A82, null).build()
        val cardAccess = record.files.cardAccess!!
        assertEquals(Hex.encode(contents), cardAccess.contentsHex)
        assertEquals(SizeRange(256, 511), cardAccess.size)
        assertEquals(FileObservation(0x6A82), record.files.atrInfo)
        assertNull(record.files.dir)
    }

    @Test
    fun `a public file that is not what its allowlist holds keeps only its length`() {
        val notTlv = ByteArray(300) { it.toByte() }
        val record = builder().setCardAccess(0x9000, notTlv).setAtrInfo(0x9000, notTlv).setDir(0x9000, notTlv).build()
        listOf(record.files.cardAccess, record.files.atrInfo, record.files.dir).forEach {
            assertEquals(FileObservation(0x9000, null, SizeRange(256, 511)), it)
        }
        assertEquals(emptyList(), RedactionLint.check(CaptureJson.encode(record)))
    }

    @Test
    fun `EF CardAccess is reduced to the infos that identify no chip`() {
        // A per-chip ChipAuthenticationPublicKeyInfo (0.4.0.127.0.7.2.2.1.2) with explicit key bytes, beside the PACEInfo
        val keyBytes = ByteArray(65) { (it * 31 + 7).toByte() }
        val keyInfoBody = Hex.decode("060904007F000702020102") + Ber.encode(0x30, byteArrayOf(0x04.toByte(), 0x41) + keyBytes)
        val keyInfo = Ber.encode(0x30, keyInfoBody)
        val pace = Hex.decode("3012060A04007F0007020204020202010202010D")
        val record = builder().setCardAccess(0x9000, Ber.encode(0x31, pace + keyInfo)).build()
        assertEquals(Hex.encode(Ber.encode(0x31, pace)), record.files.cardAccess!!.contentsHex)
        assertEquals(SizeRange(0, 255), record.files.cardAccess!!.size)
        assertTrue(record.redactions.any { it.item.contains("public files") })
        assertEquals(emptyList(), RedactionLint.check(CaptureJson.encode(record)))
        // Only key material: nothing parseable is left, so only the length is kept
        val onlyKey = builder().setCardAccess(0x9000, Ber.encode(0x31, keyInfo)).build()
        assertEquals(FileObservation(0x9000, null, SizeRange(0, 255)), onlyKey.files.cardAccess)
    }

    @Test
    fun `EF ATR INFO loses the issuer and pre-issuing data and EF DIR keeps only application identifiers`() {
        val extended = Hex.decode("7F66080202080002020800")
        val issuerData = Hex.decode("45050102030405")
        val preIssuing = Hex.decode("4603AABBCC")
        val atr = builder().setAtrInfo(0x9000, extended + issuerData + preIssuing).build().files.atrInfo!!
        assertEquals(Hex.encode(extended), atr.contentsHex)
        val dir = builder().setDir(0x9000, Hex.decode("610C4F07A00000024710015001FF")).build().files.dir!!
        assertEquals("61094F07A0000002471001", dir.contentsHex)
    }

    @Test
    fun `padding at the end of a public file is dropped`() {
        val padded = Hex.decode("61094F07A0000002471001") + ByteArray(5) { 0xFF.toByte() }
        assertEquals("61094F07A0000002471001", builder().setDir(0x9000, padded).build().files.dir!!.contentsHex)
    }

    @Test
    fun `sizes round out to whole buckets`() {
        assertEquals(SizeRange(0, 255), Redaction.sizeRange(0))
        assertEquals(SizeRange(0, 255), Redaction.sizeRange(255))
        assertEquals(SizeRange(256, 511), Redaction.sizeRange(256))
        assertEquals(SizeRange(208, 223), Redaction.probeSizeRange(223))
        assertEquals(SizeRange(240, 255), Redaction.probeSizeRange(255))
    }

    @Test
    fun `the reader's names are cut to a short plain form`() {
        val record = builder().setReader(
            ReaderDevice(model = "Pixel 8 <script>" + "x".repeat(200), androidVersion = "15\n", nfcChipset = "ST21NFCD")
        ).build()
        assertEquals("Pixel 8 script" + "x".repeat(34), record.reader.model)
        assertEquals("15", record.reader.androidVersion)
    }

    @Test
    fun `a name that could be a CAN or a document number is not kept`() {
        assertEquals("unknown", builder().setReader(testReader.copy(model = "123456")).build().reader.model)
        assertEquals("unknown", builder().setReader(testReader.copy(model = "Pixel L898902C3")).build().reader.model)
        assertEquals("unknown", builder().setReader(testReader.copy(model = "")).build().reader.model)
        assertEquals("unknown", builder().setReader(testReader.copy(model = "\u00e9\u00e8")).build().reader.model)
        // Real models pass: letters, digits, space and . _ + - / ( )
        listOf("Pixel 7 Pro", "SM-S918B", "M2007J3SG", "CPH2451", "XQ-CT54", "SM-G991B/DS", "Nokia 105 (2017)").forEach {
            assertEquals(it, builder().setReader(testReader.copy(model = it)).build().reader.model)
        }
    }

    @Test
    fun `historical bytes that are not short hex are dropped`() {
        assertEquals("8000", builder().setLink(LinkObservation(NfcTechnology.NFC_B, historicalBytes = "8000")).build().link.historicalBytes)
        assertNull(builder().setLink(LinkObservation(NfcTechnology.NFC_B, historicalBytes = "not hex")).build().link.historicalBytes)
        assertNull(builder().setLink(LinkObservation(NfcTechnology.NFC_B, historicalBytes = "AB".repeat(100))).build().link.historicalBytes)
    }

    @Test
    fun `historical bytes lose the issuer data, and keep only their length if they are not in a known form`() {
        // Category 80, then a country indicator (tag 1, 2 bytes), issuer data (tag 4, 3 bytes) and pre-issuing data (tag 5, 2 bytes)
        val whole = builder().setLink(LinkObservation(NfcTechnology.NFC_A, historicalBytes = "80" + "12ABCD" + "43010203" + "520405")).build().link
        assertEquals("8012ABCD", whole.historicalBytes)
        assertEquals(11, whole.historicalBytesLength)
        // A proprietary category: nothing is kept but how long they were
        val proprietary = builder().setLink(LinkObservation(NfcTechnology.NFC_B, historicalBytes = "3B8180018080")).build().link
        assertNull(proprietary.historicalBytes)
        assertEquals(6, proprietary.historicalBytesLength)
        // Untouched bytes carry no length, and resuming a capture doesn't lose one
        assertNull(builder().setLink(LinkObservation(NfcTechnology.NFC_A, historicalBytes = "8000")).build().link.historicalBytesLength)
        val resumed = CaptureBuilder.resume(CaptureDraft(builder().setLink(LinkObservation(NfcTechnology.NFC_B, historicalBytes = "3B8180018080")).build())).build()
        assertEquals(6, resumed.link.historicalBytesLength)
    }

    @Test
    fun `a probe added again replaces the earlier result`() {
        val first = ProbeResult("p", 1, ProbeTier.A, listOf(StepResult("s", 0x9000)))
        val second = ProbeResult("p", 1, ProbeTier.A, listOf(StepResult("s", 0x6A82)))
        val record = builder().addProbe(first).addProbe(second).build()
        assertEquals(listOf(second), record.probes)
    }

    @Test
    fun `the record carries the redaction manifest and a consistent fingerprint and digest`() {
        val record = builder().setCardAccess(0x9000, paceInfo).build()
        assertEquals(RedactionManifest.entries, record.redactions)
        assertEquals(CAPTURE_SCHEMA_VERSION, record.schemaVersion)
        assertTrue(CaptureFingerprint.isConsistent(record))
        assertEquals(16, record.fingerprint.length)
        assertEquals(16, record.observationDigest.length)
    }

    @Test
    fun `building without the month, pack, reader or link fails`() {
        assertFailsWith<IllegalStateException> { CaptureBuilder.create().build() }
    }

    @Test
    fun `resuming keeps the capture id and what was captured`() {
        val first = builder().setCardAccess(0x9000, paceInfo).setAborted(AbortReason.TAG_LOST).build()
        val resumed = CaptureBuilder.resume(CaptureDraft(first))
        assertTrue(resumed.hasCardAccess())
        val record = resumed.setAborted(null).build()
        assertEquals(first.captureId, record.captureId)
        assertEquals(first.files, record.files)
        assertNull(record.aborted)
    }

    @Test
    fun `the generation key ignores timing, device, id, month and every probe answer but not the chip's files`() {
        val step = StepResult("s", 0x9000, Redaction.probeSizeRange(8), TimingSummary(1, 2, 3, 3))
        val a = builder().addProbe(ProbeResult("p", 1, ProbeTier.A, listOf(step))).setCardAccess(0x9000, paceInfo).build()
        val b = builder().setCaptureMonth(2027, 1).setReader(testReader.copy(model = "Other phone"))
            .addProbe(ProbeResult("p", 1, ProbeTier.A, listOf(step.copy(timing = TimingSummary(40, 50, 60, 3)))))
            .setCardAccess(0x9000, paceInfo).build()
        assertNotEquals(a.captureId, b.captureId)
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(a.observationDigest, b.observationDigest, "timing is in neither")

        // A probe answered differently, a probe the phone couldn't send: the same kind of chip, a different observation
        val otherStatus = builder().addProbe(ProbeResult("p", 1, ProbeTier.A, listOf(step.copy(statusWord = 0x6A82))))
            .setCardAccess(0x9000, paceInfo).build()
        val noProbe = builder().setCardAccess(0x9000, paceInfo).build()
        assertEquals(a.fingerprint, otherStatus.fingerprint)
        assertEquals(a.fingerprint, noProbe.fingerprint)
        assertNotEquals(a.observationDigest, otherStatus.observationDigest)
        assertNotEquals(a.observationDigest, noProbe.observationDigest)

        // Another EF.CardAccess, application answer or EF.DIR is another kind of chip
        val otherContents = builder().addProbe(ProbeResult("p", 1, ProbeTier.A, listOf(step))).setCardAccess(0x9000, paceInfoWithParameter(14)).build()
        val otherApplication = builder().setApplication(0x6A82, null).setCardAccess(0x9000, paceInfo).build()
        val otherDir = builder().setCardAccess(0x9000, paceInfo).setDir(0x9000, Hex.decode("61094F07A0000002471001")).build()
        listOf(otherContents, otherApplication, otherDir).forEach { assertNotEquals(a.fingerprint, it.fingerprint) }
    }

    @Test
    fun `the generation key does not depend on the case of the hex`() {
        val a = builder().setCardAccess(0x9000, paceInfo).build()
        val lower = a.copy(files = a.files.copy(cardAccess = a.files.cardAccess!!.copy(contentsHex = a.files.cardAccess!!.contentsHex!!.lowercase())))
        assertEquals(a.fingerprint, CaptureFingerprint.of(lower))
        assertEquals(a.observationDigest, CaptureFingerprint.digestOf(lower))
        // The version 1 recipe hashed the hex as written: kept as it was, so version 1 records still check
        assertNotEquals(CaptureFingerprint.legacyV1Of(a), CaptureFingerprint.legacyV1Of(lower))
        assertTrue(CaptureFingerprint.of(a).matches(Regex("[0-9A-F]{16}")))
    }

    @Test
    fun `the pure Kotlin SHA-256 agrees with the JDK's`() {
        val random = kotlin.random.Random(11)
        (listOf(0, 1, 55, 56, 63, 64, 65, 119, 120, 1000) + List(20) { random.nextInt(0, 400) }).forEach { size ->
            val bytes = random.nextBytes(size)
            assertEquals(Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), Hex.encode(Sha256.digest(bytes)), "size $size")
        }
    }
}
