package com.hddev.smartemu.capture.probes

import com.hddev.smartemu.capture.CaptureBuilder
import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.GoldenCapture
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ProbePackRef
import com.hddev.smartemu.capture.ProbePacks
import com.hddev.smartemu.capture.ProbeResult
import com.hddev.smartemu.capture.ProbeTier
import com.hddev.smartemu.capture.RedactionLint
import com.hddev.smartemu.capture.StepResult
import com.hddev.smartemu.capture.testReader
import com.hddev.smartemu.capture.LinkObservation
import com.hddev.smartemu.capture.NfcTechnology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A published pack version never changes. These tests pin the probe and step ids and a digest of each step list, so an
 * edit to a published pack fails here and the change has to go into a new version (docs/probe-packs.md).
 */
class PublishedPacksTest {
    private fun ids(pack: ProbePack) = pack.probes.associate { probe -> probe.id to probe.steps.map { it.id } }

    @Test
    fun `tier-a v1 has exactly its published probes and steps`() {
        val pack = ProbePack.tierAV1
        assertEquals(ProbePackRef("tier-a", 1), pack.ref)
        assertEquals(7, pack.probes.size)
        assertEquals(45, pack.stepCount)
        assertEquals(
            mapOf(
                "select-aid" to listOf(
                    "p2-0c-no-le", "p2-0c-le-00", "p2-00-no-le", "p2-00-le-00", "p2-04-le-00", "partial-aid", "unknown-aid", "lc-longer-than-data"
                ),
                "select-fid" to listOf(
                    "card-access-p1-02-p2-0c", "card-access-p1-00-p2-0c", "card-access-p2-00-le-00", "card-access-p2-0c-le-00",
                    "card-access-from-application", "card-security-from-master-file", "ef-com-before-authentication",
                    "ef-sod-before-authentication", "ef-dg1-before-authentication", "unknown-fid", "master-file-by-fid", "lc-one"
                ),
                "read-binary-order" to listOf(
                    "no-file-selected", "card-access-by-sfi", "ef-com-by-sfi-before-authentication", "odd-ins-no-file-selected", "odd-ins-card-access"
                ),
                "read-binary-wrong-length" to listOf("le-01", "le-04", "le-00-at-offset-1", "offset-beyond-file", "lc-instead-of-le"),
                "read-binary-max-length" to listOf("le-df", "le-e0", "le-ff", "le-00", "extended-le-0100", "extended-le-ffff"),
                "get-challenge-lengths" to listOf("le-08", "le-00", "le-04", "le-07", "le-09", "le-10", "no-le"),
                "external-authenticate-out-of-sequence" to listOf("lc-40-le-28", "lc-08-wrong-length")
            ),
            ids(pack)
        )
        assertTrue(pack.probes.all { it.tier == ProbeTier.A })
        assertEquals(TIER_A_V1_DIGEST, pack.definitionDigest, "tier-a v1 is published: a change to its steps needs a new version")
    }

    @Test
    fun `tier-a v2 is v1 with the EXTERNAL AUTHENTICATE probe first, and its digest is pinned`() {
        val v1 = ProbePack.tierAV1
        val v2 = ProbePack.tierA
        assertEquals(ProbePackRef("tier-a", 2), v2.ref)
        assertSame(v2, ProbePacks.latest("tier-a"), "ProbePack.tierA is the newest version, the one a new capture runs")
        assertEquals(45, v2.stepCount)
        assertEquals(7, v2.probes.size)
        assertTrue(v2.probes.all { it.tier == ProbeTier.A })
        // The same probes and steps under the same ids, so that results compare across versions by id; only the order differs
        assertEquals(ids(v1), ids(v2))
        assertEquals("external-authenticate-out-of-sequence", v2.probes.first().id)
        assertEquals(v1.probes.map { it.id }.sorted(), v2.probes.map { it.id }.sorted())
        v1.probes.forEach { probe ->
            assertEquals(
                probe.steps.map { listOf(it.id, it.setup, it.commandHex, it.repeats, it.needsExtendedLength) },
                v2.probe(probe.id)!!.steps.map { listOf(it.id, it.setup, it.commandHex, it.repeats, it.needsExtendedLength) },
                probe.id
            )
        }
        assertNotEquals(v1.definitionDigest, v2.definitionDigest)
        assertEquals(TIER_A_V2_DIGEST, v2.definitionDigest, "tier-a v2 is published: a change to its steps needs a new version")
        assertTrue(v2.excluded.any { it.contains("GET CHALLENGE before the out-of-sequence EXTERNAL AUTHENTICATE") })
    }

    @Test
    fun `tier-b v1 digest is pinned`() {
        assertEquals(ProbePackRef("tier-b", 1), ProbePack.tierB.ref)
        assertEquals(TIER_B_V1_DIGEST, ProbePack.tierB.definitionDigest, "tier-b v1 changed: add tier-b v2 instead")
    }

    @Test
    fun `tier-c v1 digest is pinned`() {
        assertEquals(ProbePackRef("tier-c", 1), ProbePack.tierC.ref)
        assertEquals(TIER_C_V1_DIGEST, ProbePack.tierC.definitionDigest, "tier-c v1 changed: add tier-c v2 instead")
    }

    @Test
    fun `the digest changes with any step`() {
        val a = ProbePack.tierA
        val changed = ProbePack(
            a.id, a.version,
            a.probes.map { probe ->
                if (probe.id != "get-challenge-lengths") probe
                else com.hddev.smartemu.capture.Probe(probe.id, probe.tier, probe.steps.dropLast(1))
            }
        )
        assertNotEquals(a.definitionDigest, changed.definitionDigest)
    }

    @Test
    fun `the schema version 1 fingerprint of the golden record is what it was before the generation key`() {
        // A version 1 record's fingerprint hashed every observation. That recipe is kept (legacyV1Of) so version 1 records
        // can still be checked, and its value for the golden record must not move
        assertEquals(TIER_A_GOLDEN_FINGERPRINT, CaptureFingerprint.legacyV1Of(GoldenCapture.recordV1))
        assertEquals(TIER_A_GOLDEN_FINGERPRINT, GoldenCapture.recordV1.fingerprint)
        assertEquals(emptyList(), GoldenCapture.record.additionalProbePacks)
    }

    @Test
    fun `the generation key and digest of the golden record are pinned`() {
        // Schema version 2: `fingerprint` is the generation key, which hashes only what the chip fixes (EF.CardAccess,
        // the application SELECT, EF.ATR/INFO, EF.DIR). It is a different value from the version 1 pin above on purpose:
        // the recipe changed, so that captures of one kind of chip share it whatever the probes answered. The digest
        // is the old "hash of everything" under a new header, for exact equality. A change to either recipe moves
        // these values and is a schema version bump.
        assertEquals(TIER_A_GOLDEN_GENERATION_KEY, CaptureFingerprint.of(GoldenCapture.record))
        assertEquals(TIER_A_GOLDEN_DIGEST, CaptureFingerprint.digestOf(GoldenCapture.record))
        assertNotEquals(TIER_A_GOLDEN_FINGERPRINT, TIER_A_GOLDEN_GENERATION_KEY)
    }

    @Test
    fun `a second pack changes the digest and not the generation key, and an empty list changes neither`() {
        val record = GoldenCapture.record
        val two = record.copy(additionalProbePacks = listOf(ProbePackRef("tier-b", 1)))
        assertEquals(CaptureFingerprint.digestOf(record), CaptureFingerprint.digestOf(record.copy(additionalProbePacks = emptyList())))
        assertNotEquals(CaptureFingerprint.digestOf(record), CaptureFingerprint.digestOf(two))
        assertEquals(CaptureFingerprint.of(record), CaptureFingerprint.of(two))
    }

    @Test
    fun `a multi-pack record survives the JSON round trip, the builder and the lint`() {
        val builder = CaptureBuilder.create().setProbePack(ProbePack.tierA.ref).setAdditionalProbePacks(listOf(ProbePack.tierB.ref))
            .setCaptureMonth(2026, 10).setReader(testReader).setLink(LinkObservation(NfcTechnology.NFC_A))
            .addProbe(ProbeResult("mse-set-at", 1, ProbeTier.B, listOf(StepResult("valid-oid-can", 0x6A80))))
        val record = builder.build()
        assertEquals(listOf(ProbePackRef("tier-b", 1)), record.additionalProbePacks)
        assertEquals(record, CaptureJson.decode(CaptureJson.encode(record)))
        assertEquals(emptyList(), RedactionLint.check(CaptureJson.encode(record)))
        assertEquals(record.additionalProbePacks, CaptureBuilder.resume(com.hddev.smartemu.capture.CaptureDraft(record)).build().additionalProbePacks)
        // A malformed pack id is refused where a probe id is
        val bad = CaptureJson.encode(record.copy(additionalProbePacks = listOf(ProbePackRef("Anna Eriksson", 1))))
        assertTrue(RedactionLint.check(bad).any { it.rule == RedactionLint.Rule.BAD_FIELD })
    }

    @Test
    fun `the registry resolves packs, probes, steps and records`() {
        assertEquals(listOf("tier-a", "tier-a", "tier-b", "tier-c"), ProbePacks.all.map { it.id })
        assertEquals(listOf(1, 2, 1, 1), ProbePacks.all.map { it.version }, "oldest first, and v1 stays for good")
        assertSame(ProbePack.tierB, ProbePacks.byId("tier-b", 1))
        assertNull(ProbePacks.byId("tier-b", 2))
        assertNull(ProbePacks.byId("tier-d", 1))
        assertSame(ProbePack.tierAV1, ProbePacks.byRef(ProbePackRef("tier-a", 1)))
        assertSame(ProbePack.tierA, ProbePacks.byRef(ProbePackRef("tier-a", 2)))
        assertNull(ProbePacks.byId("tier-a", 3))
        assertSame(ProbePack.tierC, ProbePacks.latest("tier-c"))
        assertNull(ProbePacks.latest("nothing"))

        val result = ProbeResult("mse-set-at", 1, ProbeTier.B, listOf(StepResult("valid-oid-can", 0x6A80)))
        assertSame(ProbePack.tierB, ProbePacks.packOf(result))
        assertEquals("mse-set-at", ProbePacks.probeOf(result)?.id)
        assertNotNull(ProbePacks.stepOf(result, result.steps.single()))
        assertNull(ProbePacks.packOf(result.copy(packVersion = 2)))
        assertNull(ProbePacks.stepOf(result, StepResult("no-such-step", null)))

        val record = GoldenCapture.record.copy(additionalProbePacks = listOf(ProbePackRef("tier-b", 1), ProbePackRef("tier-z", 1)))
        assertEquals(listOf(ProbePack.tierAV1, ProbePack.tierB), ProbePacks.packsOf(record))
        // The same probe id under another version of the pack is another probe
        val v2Result = ProbeResult("select-aid", 2, ProbeTier.A, listOf(StepResult("p2-0c-no-le", 0x9000)))
        assertSame(ProbePack.tierA, ProbePacks.packOf(v2Result))
        assertSame(ProbePack.tierAV1, ProbePacks.packOf(v2Result.copy(packVersion = 1)))
    }

    @Test
    fun `probe ids are unique among the packs of one version, so a result names one probe`() {
        ProbePacks.all.groupBy { it.version }.forEach { (version, packs) ->
            val ids = packs.flatMap { pack -> pack.probes.map { it.id } }
            assertEquals(ids.size, ids.toSet().size, "duplicate probe id in version $version: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
        }
        // A version that changes nothing about a probe keeps its id: the two versions of tier-a share them
        assertEquals(ids(ProbePack.tierAV1).keys, ids(ProbePack.tierA).keys)
        ProbePacks.all.forEach { pack ->
            assertEquals(pack.ref, ProbePacks.byRef(pack.ref)?.ref)
            pack.probes.forEach { probe ->
                val found = ProbePacks.packOf(ProbeResult(probe.id, pack.version, probe.tier, emptyList()))
                assertEquals(pack.id, found?.id)
                assertEquals(pack.version, found?.version)
            }
        }
    }

    @Test
    fun `ids are plain and every probe has the tier of its pack`() {
        val identifier = Regex("[a-z0-9-]{1,48}")
        val tierOfPack = mapOf("tier-a" to ProbeTier.A, "tier-b" to ProbeTier.B, "tier-c" to ProbeTier.C)
        ProbePacks.all.forEach { pack ->
            assertTrue(identifier.matches(pack.id))
            pack.probes.forEach { probe ->
                assertTrue(identifier.matches(probe.id), probe.id)
                assertEquals(tierOfPack.getValue(pack.id), probe.tier, "${pack.id}/${probe.id}")
                assertEquals(probe.steps.size, probe.steps.map { it.id }.toSet().size, "${pack.id}/${probe.id}: step ids")
                probe.steps.forEach { assertTrue(identifier.matches(it.id), "${probe.id}/${it.id}") }
            }
            assertTrue(pack.excluded.isNotEmpty(), "${pack.id} documents what it excludes")
        }
    }

    @Test
    fun `only tier c needs the sacrificial card acknowledgement`() {
        assertEquals(listOf(false, false, false, true), ProbePacks.all.map { it.requiresSacrificialCardAcknowledgement })
        assertEquals(listOf(ProbeTier.A, ProbeTier.A, ProbeTier.B, ProbeTier.C), ProbePacks.all.map { it.highestTier })
    }

    private companion object {
        const val TIER_A_V1_DIGEST = "B8606BD29018C0AC73D9455C6252C5FACE366A09DCAC45BF168DE0824D34727D"
        const val TIER_A_V2_DIGEST = "052C2EDD1BA23C270BF76E122CEE5A2E241B79999EDECB3A2761B81DB24145D3"
        const val TIER_B_V1_DIGEST = "8E73B63CBD838963EB724F4FBE26A59718EE87E0237D1784FD202E25EAB4E9EE"
        const val TIER_C_V1_DIGEST = "2C2F2326D32979CCA53CBF55E560971FC1B621EC87660F3463E5ABB0AAF9BF65"
        const val TIER_A_GOLDEN_FINGERPRINT = "6240B9B93A547090"
        const val TIER_A_GOLDEN_GENERATION_KEY = "01847DB4723079D6"
        const val TIER_A_GOLDEN_DIGEST = "111D38B6C18FE534"
    }
}
