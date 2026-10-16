package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.AccessAttempt
import com.hddev.smartemu.capture.AccessMethod
import com.hddev.smartemu.capture.AuthenticatedObservation
import com.hddev.smartemu.capture.CanaryPii
import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.RedactionManifest
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CredentialKind
import com.hddev.smartemu.capture.DataGroupObservation
import com.hddev.smartemu.capture.PaceMappingType
import com.hddev.smartemu.capture.RedactionLint
import com.hddev.smartemu.capture.SecurityInfoKind
import com.hddev.smartemu.capture.SecurityInfoObservation
import com.hddev.smartemu.capture.SecurityInfosObservation
import com.hddev.smartemu.capture.SizeRange
import com.hddev.smartemu.capture.StatusClass
import com.hddev.smartemu.capture.assertNoPii
import com.hddev.smartemu.capture.hex
import com.hddev.smartemu.data.ChipProfiles
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthenticatedLintTest {
    private lateinit var chip: EmulatedChip
    private lateinit var good: CaptureRecord

    @Before
    fun setUp() {
        chip = EmulatedChip()
        good = chip.record(chip.documentFor(ChipProfiles.byId("nl-passport")))
    }

    @After fun tearDown() { chip.close() }

    private val auth get() = good.authenticated!!

    private fun rules(record: CaptureRecord): List<Pair<RedactionLint.Rule, String>> =
        RedactionLint.check(CaptureJson.encode(record)).map { it.rule to it.path }

    /** [good] with the authenticated part changed and the record sealed again, so that the fingerprint rule can't be what fires. */
    private fun withAuth(change: (AuthenticatedObservation) -> AuthenticatedObservation) =
        CaptureFingerprint.seal(good.copy(authenticated = change(auth)))

    private val hashes = listOf(20, 32, 48, 64).associateWith { ByteArray(it) { i -> (i * 37 + 11).toByte() }.hex() }

    @Test
    fun `an authenticated capture passes, and so does one without`() {
        assertEquals(emptyList(), RedactionLint.check(CaptureJson.encode(good)))
        val plain = CaptureFingerprint.seal(good.copy(authenticated = null, redactions = RedactionManifest.entries))
        assertEquals(emptyList(), RedactionLint.check(CaptureJson.encode(plain)))
        // Left as it was, with the authenticated manifest and the old digest, it is refused: the record must be consistent
        assertTrue(rules(good.copy(authenticated = null)).any { it.first == RedactionLint.Rule.REDACTIONS_MISMATCH })
    }

    @Test
    fun `a field outside the schema is refused by the strict decode`() {
        val json = CaptureJson.encode(good).replace("\"credential\": \"MRZ\"", "\"credential\": \"MRZ\",\n \"mrz\": \"x\"")
        assertTrue(RedactionLint.check(json).any { it.rule == RedactionLint.Rule.SCHEMA_MISMATCH })
    }

    @Test
    fun `a hash of 20, 32, 48 or 64 bytes is refused in every string of the authenticated part`() {
        for ((bytes, hex) in hashes) {
            for (upper in listOf(true, false)) {
                val text = if (upper) hex else hex.lowercase()
                val tampered = listOf(
                    withAuth { it.copy(sod = it.sod!!.copy(digestAlgorithm = text)) },
                    withAuth { it.copy(sod = it.sod!!.copy(signer = it.sod!!.signer!!.copy(curve = text))) },
                    withAuth { it.copy(sod = it.sod!!.copy(signer = it.sod!!.signer!!.copy(signatureAlgorithm = "SHA256with$text"))) },
                    withAuth { it.copy(com = it.com!!.copy(ldsVersion = text)) },
                    withAuth { it.copy(dg14 = it.dg14!!.copy(infos = it.dg14!!.infos + SecurityInfoObservation(SecurityInfoKind.OTHER, text))) },
                    withAuth { it.copy(dg15 = it.dg15!!.copy(keyType = text)) },
                    withAuth { it.copy(access = it.access + AccessAttempt(AccessMethod.PACE, cipher = text, succeeded = false, statusClass = StatusClass.OTHER)) }
                )
                tampered.forEachIndexed { i, record ->
                    assertTrue(rules(record).isNotEmpty(), "$bytes-byte hash in variant $i, upper=$upper was accepted")
                }
            }
        }
    }

    @Test
    fun `the canary and the lint both see a data group hash put in a field`() {
        val hash = ByteArray(32) { (it * 7 + 3).toByte() }
        val leaked = withAuth { it.copy(sod = it.sod!!.copy(signatureAlgorithm = hash.hex())) }
        val json = CaptureJson.encode(leaked)
        assertFailsWith<AssertionError> { assertNoPii(json, CanaryPii(mapOf("DG1 hash" to hash))) }
        assertTrue(RedactionLint.check(json).any { it.rule == RedactionLint.Rule.HIGH_ENTROPY_BLOB })
    }

    @Test
    fun `MRZ-like text is refused in the authenticated part`() {
        val line = "P<NLDVANDERMOLEN<<QUILLON<ZEPHYR<<<<<<<<<<<<"
        assertTrue(rules(withAuth { it.copy(sod = it.sod!!.copy(digestAlgorithm = line)) }).any { it.first == RedactionLint.Rule.MRZ_PATTERN })
        assertTrue(rules(withAuth { it.copy(dg15 = it.dg15!!.copy(curve = "QX7R4K9ZP<<9")) }).any { it.first == RedactionLint.Rule.MRZ_PATTERN })
    }

    @Test
    fun `shapes are enforced, field by field`() {
        val cases = mapOf(
            "an algorithm with a space" to withAuth { it.copy(sod = it.sod!!.copy(signatureAlgorithm = "SHA256 with RSA")) },
            "an OID that isn't dotted numbers" to withAuth { it.copy(dg14 = it.dg14!!.copy(infos = it.dg14!!.infos + SecurityInfoObservation(SecurityInfoKind.PACE, "pace"))) },
            "a lower-case issuer country" to withAuth { it.copy(sod = it.sod!!.copy(signer = it.sod!!.signer!!.copy(issuerCountry = "nl"))) },
            "a key of 3 bits" to withAuth { it.copy(dg15 = it.dg15!!.copy(keyBits = 3)) },
            "a size that is not a bucket" to withAuth { it.copy(dg15 = it.dg15!!.copy(size = SizeRange(100, 355))) },
            "an exact size" to withAuth { it.copy(sod = it.sod!!.copy(size = SizeRange(1234, 1234))) },
            "a status word beyond 16 bits" to withAuth { it.copy(dataGroups = it.dataGroups.map { d -> if (d.number == 3) d.copy(readStatusWord = 0x16982) else d }) },
            "DG17" to withAuth { it.copy(dataGroups = it.dataGroups + DataGroupObservation(17, false, false)) },
            "a data group twice" to withAuth { it.copy(dataGroups = it.dataGroups + it.dataGroups.first()) },
            "a data group hashed twice" to withAuth { it.copy(sod = it.sod!!.copy(hashedDataGroups = listOf(1, 1))) },
            "a validity of 100 years" to withAuth { it.copy(sod = it.sod!!.copy(signer = it.sod!!.signer!!.copy(validityMonths = 1500))) },
            "a BAC attempt with a PACE suite" to withAuth { it.copy(access = it.access + AccessAttempt(AccessMethod.BAC, oid = "0.4.0.127.0.7.2.2.4.2.2", succeeded = false, statusClass = StatusClass.OTHER)) },
            "a success that isn't OK" to withAuth { it.copy(access = listOf(AccessAttempt(AccessMethod.BAC, succeeded = true, statusClass = StatusClass.KEY_REJECTED))) },
            "a camVerified on a GM suite" to withAuth { it.copy(access = listOf(AccessAttempt(AccessMethod.PACE, mapping = PaceMappingType.GM, succeeded = true, statusClass = StatusClass.OK, camVerified = true))) },
            "a cipher that isn't one" to withAuth { it.copy(access = listOf(AccessAttempt(AccessMethod.PACE, cipher = "AES-100", succeeded = false, statusClass = StatusClass.OTHER))) },
            "a readUnder that names no attempt" to withAuth { it.copy(readUnder = 99) }
        )
        cases.forEach { (name, record) ->
            val found = rules(record)
            assertTrue(found.isNotEmpty(), "$name was accepted")
            assertTrue(found.none { it.first == RedactionLint.Rule.FINGERPRINT_MISMATCH || it.first == RedactionLint.Rule.REDACTIONS_MISMATCH }, "$name: refused for the wrong reason")
        }
    }

    @Test
    fun `a violation never repeats what it found`() {
        val secret = "QX7R4K9ZP<<"
        val record = withAuth { it.copy(dg15 = it.dg15!!.copy(curve = secret)) }
        RedactionLint.check(CaptureJson.encode(record)).forEach { assertTrue(secret !in it.detail && secret !in it.path) }
    }

    @Test
    fun `the manifest's own words don't trip the lint, and every record carries the manifest`() {
        val manifest = com.hddev.smartemu.capture.RedactionManifest
        // An authenticated record lists what that read leaves out as well; one without is as it always was
        assertEquals(manifest.entries + manifest.authenticatedEntries, good.redactions)
        val plain = assertIs<CaptureResult.Complete>(com.hddev.smartemu.capture.CaptureRunner().run(chip.transport(chip.documentFor(ChipProfiles.byId("nl-passport")))))
        assertEquals(manifest.entries, plain.record.redactions)
        assertIs<CaptureResult.Complete>(chip.capture(chip.documentFor(ChipProfiles.byId("us-passport-2021"))))
        assertEquals(CredentialKind.MRZ, auth.credential)
    }
}
