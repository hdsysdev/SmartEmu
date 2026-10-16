package com.hddev.smartemu.capture

import com.hddev.smartemu.capture.RedactionLint.Rule
import com.hddev.smartemu.capture.profile.FakeChip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The structural allowlist of the three public files and the historical bytes: what a builder keeps of them and what the
 * lint accepts. The aim is that no per-chip key, issuer data or free-text label rides along in a field called public.
 */
class PublicFileRulesTest {
    private fun h(text: String) = Hex.decode(text.replace(" ", ""))

    private val paceInfo = h("3012 060A04007F0007020204020202 010202 01 0D")
    private fun set(vararg infos: ByteArray) = Ber.encode(0x31, infos.fold(ByteArray(0)) { a, b -> a + b })
    private fun info(oid: String, vararg parts: ByteArray) = Ber.encode(0x30, h(oid) + parts.fold(ByteArray(0)) { a, b -> a + b })
    private val paceOid = "060A04007F00070202040202"
    private val paceOid2 = "060A04007F00070202040204"

    private fun lintOf(cardAccess: ByteArray, golden: CaptureRecord = GoldenCapture.record): List<RedactionLint.Violation> =
        RedactionLint.check(CaptureJson.encode(CaptureFingerprint.seal(golden.copy(files = golden.files.copy(cardAccess = FileObservation(0x9000, Hex.encode(cardAccess), Redaction.sizeRange(cardAccess.size)))))))

    private fun accepts(cardAccess: ByteArray) {
        assertContentEquals(cardAccess, PublicFileRules.reduceCardAccess(cardAccess), "kept whole")
        assertEquals(emptyList(), lintOf(cardAccess))
    }

    @Test
    fun `real EF CardAccess shapes are accepted`() {
        accepts(set(paceInfo))
        accepts(FakeChip.defaultCardAccess())
        accepts(h("31143012060A04007F0007020204020202010202010C"))
        // PACEInfo without a parameter id, several suites
        accepts(set(info(paceOid, h("020102")), info(paceOid2, h("020102"), h("02010D"))))
        // PACEDomainParameterInfo naming a standard domain by number, ChipAuthenticationInfo, TerminalAuthenticationInfo, ActiveAuthenticationInfo
        val domain = info(paceOid, Ber.encode(0x30, h("0607 04007F00070102 02010D")), h("02010D"))
        val ca = info("060A04007F00070202030204", h("020101"), h("02010D"))
        val ta = info("0608 04007F0007020202", h("020101"))
        val aa = info("0606 678108010105", h("020101"), h("0608 2A8648CE3D040302"))
        accepts(set(paceInfo, domain, ca, ta, aa))
    }

    @Test
    fun `a ChipAuthenticationPublicKeyInfo and explicit key bytes are refused, and dropped by the builder's reduction`() {
        val key = ByteArray(65) { (it * 29 + 3).toByte() }
        val keyInfo = info("0609 04007F000702020102", Ber.encode(0x30, h("0607 04007F00070102") + Ber.encode(0x03, byteArrayOf(0) + key)), h("02010D"))
        val withKey = set(paceInfo, keyInfo)
        assertContentEquals(set(paceInfo), PublicFileRules.reduceCardAccess(withKey))
        assertTrue(lintOf(withKey).any { it.rule == Rule.BAD_FIELD && it.path == "files.cardAccess.contentsHex" })
        // The same key bytes under an unknown protocol are refused too: more than 32 bytes of payload
        val unknown = info("0605 2A03040506", Ber.encode(0x04, key))
        assertContentEquals(set(paceInfo), PublicFileRules.reduceCardAccess(set(paceInfo, unknown)))
        assertTrue(lintOf(set(paceInfo, unknown)).any { it.rule == Rule.BAD_FIELD })
        // A key info on its own leaves nothing to keep
        assertNull(PublicFileRules.reduceCardAccess(set(keyInfo)))
    }

    @Test
    fun `explicit domain parameters and extra data in a PACEInfo are refused`() {
        val explicit = info(paceOid, Ber.encode(0x30, h("0607 04007F00070102") + Ber.encode(0x30, ByteArray(40) { 0x02 })), h("02010D"))
        assertTrue(lintOf(set(paceInfo, explicit)).any { it.rule == Rule.BAD_FIELD })
        assertContentEquals(set(paceInfo), PublicFileRules.reduceCardAccess(set(paceInfo, explicit)))
        val extra = info(paceOid, h("020102"), h("02010D"), h("040401020304"))
        assertContentEquals(set(paceInfo), PublicFileRules.reduceCardAccess(set(paceInfo, extra)))
    }

    @Test
    fun `an unknown protocol is kept with a payload of 32 bytes or less and not above`() {
        val ok = info("0605 2A03040506", Ber.encode(0x04, ByteArray(30) { 0x00 }))
        val tooBig = info("0605 2A03040506", Ber.encode(0x04, ByteArray(31) { 0x00 }))
        accepts(set(paceInfo, ok))
        assertContentEquals(set(paceInfo), PublicFileRules.reduceCardAccess(set(paceInfo, tooBig)))
        assertTrue(lintOf(set(paceInfo, tooBig)).any { it.rule == Rule.BAD_FIELD })
    }

    @Test
    fun `random-looking bytes are refused even inside what the structure allows`() {
        val random = Random(5).nextBytes(30)
        val sneaky = set(paceInfo, info("0605 2A03040506", Ber.encode(0x04, random)))
        assertTrue(PublicFileRules.highEntropy(sneaky.copyOfRange(sneaky.size - 34, sneaky.size)))
        assertNull(PublicFileRules.reduceCardAccess(sneaky), "the reduction doesn't keep it")
        assertTrue(lintOf(sneaky).any { it.rule == Rule.BAD_FIELD })
        // What real files look like is nowhere near it
        assertFalse(PublicFileRules.highEntropy(FakeChip.defaultCardAccess()))
        assertFalse(PublicFileRules.highEntropy(h("31143012060A04007F0007020204020202010202010C")))
        assertTrue(PublicFileRules.highEntropy(Random(9).nextBytes(64)))
    }

    @Test
    fun `fill after the last object is dropped by the reduction and refused by the lint`() {
        val padded = set(paceInfo) + ByteArray(6) { 0xFF.toByte() }
        assertContentEquals(set(paceInfo), PublicFileRules.reduceCardAccess(padded))
        assertTrue(lintOf(padded).any { it.rule == Rule.BAD_FIELD })
        assertNull(PublicFileRules.reduceCardAccess(byteArrayOf(0x31, 0x05, 0x01)), "a cut-off object")
        assertNull(PublicFileRules.reduceCardAccess(ByteArray(0)))
    }

    private fun atrLint(bytes: ByteArray): List<RedactionLint.Violation> {
        val g = GoldenCapture.record
        return RedactionLint.check(CaptureJson.encode(CaptureFingerprint.seal(g.copy(files = g.files.copy(atrInfo = FileObservation(0x9000, Hex.encode(bytes), Redaction.sizeRange(bytes.size)))))))
    }

    @Test
    fun `EF ATR INFO keeps the extended length information and drops issuer and pre-issuing data`() {
        val extended = h("7F66080202080002020800")
        assertContentEquals(extended, PublicFileRules.reduceAtrInfo(extended))
        assertEquals(emptyList(), atrLint(extended))
        val withServices = extended + h("4301 80") + h("4703 0001 80")
        assertContentEquals(withServices, PublicFileRules.reduceAtrInfo(withServices))
        // DO 45 (card issuer's data) and DO 46 (pre-issuing data) name the issuance: dropped, and refused if stored
        val issuer = h("4505 0102030405")
        val preIssuing = h("4604 AABBCCDD")
        assertContentEquals(extended, PublicFileRules.reduceAtrInfo(extended + issuer + preIssuing))
        assertTrue(atrLint(extended + issuer).any { it.rule == Rule.BAD_FIELD })
        assertTrue(atrLint(preIssuing).any { it.rule == Rule.BAD_FIELD })
        // Any other tag is not on the allowlist
        assertContentEquals(extended, PublicFileRules.reduceAtrInfo(extended + h("5F50 04 74657374")))
        // Historical bytes inside it are reduced too: issuer data (compact tag 4) cut out
        assertContentEquals(h("5F52 04 80 12ABCD"), PublicFileRules.reduceAtrInfo(h("5F52 08 80 12ABCD 43010203")))
        // A raw ATR (not BER-TLV) is not a file of data objects
        assertNull(PublicFileRules.reduceAtrInfo(h("3B8180018080")))
    }

    private fun dirLint(bytes: ByteArray): List<RedactionLint.Violation> {
        val g = GoldenCapture.record
        return RedactionLint.check(CaptureJson.encode(CaptureFingerprint.seal(g.copy(files = g.files.copy(dir = FileObservation(0x9000, Hex.encode(bytes), Redaction.sizeRange(bytes.size)))))))
    }

    @Test
    fun `EF DIR keeps only the application identifiers`() {
        val dir = h("61094F07A0000002471001")
        assertContentEquals(dir, PublicFileRules.reduceDir(dir))
        assertEquals(emptyList(), dirLint(dir))
        val labelled = h("610F4F07A00000024710015004 65504153")
        assertContentEquals(dir, PublicFileRules.reduceDir(labelled))
        assertTrue(dirLint(labelled).any { it.rule == Rule.BAD_FIELD })
        // Two applications, each kept; an AID longer than 16 bytes is not one
        assertContentEquals(h("61094F07A0000002471001 61054F03010203"), PublicFileRules.reduceDir(h("61094F07A0000002471001 61054F03010203")))
        assertNull(PublicFileRules.reduceDir(h("61124F10") + ByteArray(15)), "cut off")
        assertNull(PublicFileRules.reduceDir(h("61144F12") + ByteArray(18)), "an identifier longer than 16 bytes")
        // Text in the file: not data objects
        assertNull(PublicFileRules.reduceDir("Anna Maria Eriksson".encodeToByteArray()))
    }

    @Test
    fun `historical bytes are bounded and lose the issuer's and the pre-issuing data`() {
        assertContentEquals(h("8000"), PublicFileRules.reduceHistorical(h("8000")))
        assertContentEquals(h("80 12ABCD"), PublicFileRules.reduceHistorical(h("80 12ABCD 43010203 520405")))
        // Category 00: compact-TLV then the three status bytes
        assertContentEquals(h("00 12ABCD 009000"), PublicFileRules.reduceHistorical(h("00 12ABCD 43010203 009000")))
        // Not in a known category, cut off, or too long: nothing is kept
        assertNull(PublicFileRules.reduceHistorical(h("3B8180018080")))
        assertNull(PublicFileRules.reduceHistorical(h("80 1AAB")))
        assertNull(PublicFileRules.reduceHistorical(h("80") + ByteArray(70)))
        assertNull(PublicFileRules.reduceHistorical(ByteArray(0)))
    }

    @Test
    fun `the lint refuses historical bytes that were not reduced`() {
        val g = GoldenCapture.record
        fun lint(hex: String) = RedactionLint.check(CaptureJson.encode(CaptureFingerprint.seal(g.copy(link = g.link.copy(historicalBytes = hex)))))
        assertEquals(emptyList(), lint("8000"))
        assertTrue(lint("80" + "12ABCD" + "43010203").any { it.path == "link.historicalBytes" })
        assertTrue(lint("3B8180018080").any { it.path == "link.historicalBytes" })
        assertTrue(lint("80" + "00".repeat(70)).any { it.path == "link.historicalBytes" })
    }
}
