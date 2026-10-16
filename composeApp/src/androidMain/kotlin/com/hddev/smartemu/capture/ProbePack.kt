package com.hddev.smartemu.capture

import java.security.MessageDigest

/**
 * A fixed, versioned set of probes: commands sent to a chip to see how it answers. Results are keyed by probe id and
 * pack [version], so two captures are only compared on the same pack. Change a command or add a step and the version
 * goes up: a published pack is never edited (see docs/probe-packs.md, "Adding a pack version"). [ProbePacks] holds
 * every published pack, so a result can be resolved to the exact definition that produced it.
 */
class ProbePack(
    val id: String,
    val version: Int,
    val probes: List<Probe>,
    /** What the pack leaves out on purpose, and why; a reviewer reads this before approving a new probe. */
    val excluded: List<String> = emptyList()
) {
    val ref: ProbePackRef get() = ProbePackRef(id, version)

    val stepCount: Int get() = probes.sumOf { it.steps.size }

    /** The highest tier of any probe in the pack. */
    val highestTier: ProbeTier get() = probes.maxOf { it.tier }

    /** Whether any command a step sends (its setup is SELECT only) or prepares is an EXTERNAL AUTHENTICATE (INS 82). */
    fun probeSendsExternalAuthenticate(probe: Probe): Boolean = probe.steps.any { step ->
        (step.preambleHex + step.commandHex).any { it.length >= 4 && it.substring(2, 4).equals("82", ignoreCase = true) }
    }

    /**
     * True for a pack that may touch a counter (Tier C): [CaptureRunner] refuses to run it unless the caller
     * acknowledged a sacrificial card.
     */
    val requiresSacrificialCardAcknowledgement: Boolean get() = probes.any { it.tier == ProbeTier.C }

    /**
     * A SHA-256 over the pack's probe ids, tiers and every step (id, setup, commands, repeats), as hex. It changes
     * if and only if the step list does, so a test can pin a published pack and ingest can check which definition a
     * result came from. The [excluded] notes are not part of it.
     */
    val definitionDigest: String by lazy {
        val canonical = buildString {
            append("pack/$id/$version\n")
            probes.forEach { probe ->
                append("probe/${probe.id}/${probe.tier}\n")
                probe.steps.forEach { step ->
                    append("step/${step.id}/${step.setup}/${step.commandHex}/${step.repeats}/${step.needsExtendedLength}")
                    // Fields added after tier-a v1 are written only when set, so that v1's digest stays what it was
                    if (step.preambleHex.isNotEmpty()) append("/preamble=${step.preambleHex.joinToString(",")}")
                    if (step.freshStateEachRepeat) append("/fresh")
                    append('\n')
                }
            }
        }
        Hex.encode(MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()))
    }

    /** The probe with [probeId], or null. */
    fun probe(probeId: String): Probe? = probes.firstOrNull { it.id == probeId }

    companion object {
        /**
         * The always-safe pack, newest version (v2): reads, SELECT variants, wrong lengths and out-of-sequence commands,
         * nothing that counts. Its out-of-sequence EXTERNAL AUTHENTICATE runs first of all, before any GET CHALLENGE. A
         * new capture uses this; [tierAV1] is for reading and replaying what was captured under version 1.
         */
        val tierA: ProbePack by lazy { TierAProbePack.buildV2() }

        /**
         * Tier A version 1, as published: its EXTERNAL AUTHENTICATE probe runs after seven GET CHALLENGE commands, so a
         * chip that keeps a challenge pending may count it as a failed BAC attempt, and the `bac-commands` extractor adds a
         * third one. Kept for the captures made under it and for tests; a new capture doesn't use it.
         */
        val tierAV1: ProbePack by lazy { TierAProbePack.buildV1() }

        /** Unauthenticated but unusual: aborted PACE starts, command chaining, class bytes, SELECT and READ BINARY oddities. */
        val tierB: ProbePack by lazy { TierBProbePack.build() }

        /** Sacrificial cards only: bounded wrong-key BAC attempts. Refused by [CaptureRunner] unless acknowledged. */
        val tierC: ProbePack by lazy { TierCProbePack.build() }
    }
}

class Probe(val id: String, val tier: ProbeTier, val steps: List<ProbeStep>)

/**
 * One command to send, after [setup] has put the chip in the starting state: sent [repeats] times for the timing
 * summary. The command is hex, so the pack can be read and reviewed as data.
 */
class ProbeStep(
    val id: String,
    val setup: ProbeSetup,
    val commandHex: String,
    val repeats: Int = 3,
    /** An extended-length APDU: skipped, not failed, on a phone whose controller can't send one. */
    val needsExtendedLength: Boolean = false,
    /**
     * Commands sent, answers ignored, after [setup] and before [commandHex], to reach the state the command is
     * probed in (for example MSE:Set AT, then GENERAL AUTHENTICATE step 1). Each must also be a step's command in the
     * same pack, so that its own answer is recorded. Empty in tier-a v1.
     */
    val preambleHex: List<String> = emptyList(),
    /**
     * Run [setup] and the [preambleHex] again before every repeat, so that each repeat starts from the same state.
     * A step with a preamble should set it.
     */
    val freshStateEachRepeat: Boolean = false
) {
    val command: ByteArray get() = Hex.decode(commandHex)

    val preamble: List<ByteArray> get() = preambleHex.map(Hex::decode)
}

/** The state a step starts from; each step re-establishes it, so steps don't depend on each other. */
enum class ProbeSetup {
    /** No SELECT first: the step's own command is a SELECT. */
    NONE,

    /** SELECT of the master file: the eMRTD application is left and no EF is selected. */
    MASTER_FILE,

    /** The eMRTD application selected with its AID. */
    EMRTD_APPLICATION,

    /** The master file, then EF.CardAccess selected. */
    CARD_ACCESS_SELECTED
}

/**
 * The Tier A pack. Published versions are never changed (a test pins each digest); a change is a new version.
 *
 * - v1: seven probes, 45 steps. The out-of-sequence EXTERNAL AUTHENTICATE probe is last, after seven GET CHALLENGE
 *   steps, and the `bac-commands` extractor sent another EXTERNAL AUTHENTICATE (and a GET CHALLENGE) before the pack.
 * - v2: the same 45 steps with the same probe and step ids, but the EXTERNAL AUTHENTICATE probe is first, each step
 *   from a fresh SELECT of the application, and the run holds no other EXTERNAL AUTHENTICATE and no GET CHALLENGE
 *   before it: the `bac-commands` observation is taken from this pack's own two steps and `get-challenge-lengths/le-08`
 *   instead of by its extractor (see [com.hddev.smartemu.capture.BacCommandExtractor]). So a whole run sends two
 *   EXTERNAL AUTHENTICATE commands, never with a challenge pending.
 */
internal object TierAProbePack {
    private const val AID = Apdu.EMRTD_AID_HEX
    private val zeros40 = "00".repeat(40)

    /** The two steps whose answers [com.hddev.smartemu.capture.CaptureRunner] reports as the record's `bacCommands` in v2. */
    const val EXTERNAL_AUTHENTICATE_PROBE = "external-authenticate-out-of-sequence"
    const val EXTERNAL_AUTHENTICATE_STEP = "lc-40-le-28"
    const val GET_CHALLENGE_PROBE = "get-challenge-lengths"
    const val GET_CHALLENGE_STEP = "le-08"

    fun buildV1(): ProbePack = ProbePack(
        id = "tier-a",
        version = 1,
        probes = listOf(selectApplication, selectFile, readOrder, readLength, readMaxLength, getChallengeLengths, externalAuthenticate),
        excluded = excluded
    )

    fun buildV2(): ProbePack = ProbePack(
        id = "tier-a",
        version = 2,
        probes = listOf(externalAuthenticate, selectApplication, selectFile, readOrder, readLength, readMaxLength, getChallengeLengths),
        excluded = excluded + excludedV2
    )

    private val selectApplication = Probe(
        "select-aid", ProbeTier.A, listOf(
            ProbeStep("p2-0c-no-le", ProbeSetup.MASTER_FILE, "00A4040C07$AID"),
            ProbeStep("p2-0c-le-00", ProbeSetup.MASTER_FILE, "00A4040C07${AID}00"),
            ProbeStep("p2-00-no-le", ProbeSetup.MASTER_FILE, "00A4040007$AID"),
            ProbeStep("p2-00-le-00", ProbeSetup.MASTER_FILE, "00A4040007${AID}00"),
            ProbeStep("p2-04-le-00", ProbeSetup.MASTER_FILE, "00A4040407${AID}00"),
            ProbeStep("partial-aid", ProbeSetup.MASTER_FILE, "00A4040C05A000000247"),
            ProbeStep("unknown-aid", ProbeSetup.MASTER_FILE, "00A4040C07A0000002471002"),
            ProbeStep("lc-longer-than-data", ProbeSetup.MASTER_FILE, "00A4040C08$AID")
        )
    )

    private val selectFile = Probe(
        "select-fid", ProbeTier.A, listOf(
            ProbeStep("card-access-p1-02-p2-0c", ProbeSetup.MASTER_FILE, "00A4020C02011C"),
            ProbeStep("card-access-p1-00-p2-0c", ProbeSetup.MASTER_FILE, "00A4000C02011C"),
            ProbeStep("card-access-p2-00-le-00", ProbeSetup.MASTER_FILE, "00A4020002011C00"),
            ProbeStep("card-access-p2-0c-le-00", ProbeSetup.MASTER_FILE, "00A4020C02011C00"),
            ProbeStep("card-access-from-application", ProbeSetup.EMRTD_APPLICATION, "00A4020C02011C"),
            ProbeStep("card-security-from-master-file", ProbeSetup.MASTER_FILE, "00A4020C02011D"),
            ProbeStep("ef-com-before-authentication", ProbeSetup.EMRTD_APPLICATION, "00A4020C02011E"),
            ProbeStep("ef-sod-before-authentication", ProbeSetup.EMRTD_APPLICATION, "00A4020C02011D"),
            ProbeStep("ef-dg1-before-authentication", ProbeSetup.EMRTD_APPLICATION, "00A4020C020101"),
            ProbeStep("unknown-fid", ProbeSetup.EMRTD_APPLICATION, "00A4020C020123"),
            ProbeStep("master-file-by-fid", ProbeSetup.EMRTD_APPLICATION, "00A4000C023F00"),
            ProbeStep("lc-one", ProbeSetup.MASTER_FILE, "00A4020C0101")
        )
    )

    /** READ BINARY before a SELECT, by short EF id, and with the odd instruction code B1. */
    private val readOrder = Probe(
        "read-binary-order", ProbeTier.A, listOf(
            ProbeStep("no-file-selected", ProbeSetup.MASTER_FILE, "00B0000004"),
            ProbeStep("card-access-by-sfi", ProbeSetup.MASTER_FILE, "00B09C0004"),
            ProbeStep("ef-com-by-sfi-before-authentication", ProbeSetup.EMRTD_APPLICATION, "00B09E0004"),
            ProbeStep("odd-ins-no-file-selected", ProbeSetup.MASTER_FILE, "00B10000045402000004"),
            ProbeStep("odd-ins-card-access", ProbeSetup.CARD_ACCESS_SELECTED, "00B10000045402000004")
        )
    )

    /** READ BINARY with an Le or an offset that doesn't fit. */
    private val readLength = Probe(
        "read-binary-wrong-length", ProbeTier.A, listOf(
            ProbeStep("le-01", ProbeSetup.CARD_ACCESS_SELECTED, "00B0000001"),
            ProbeStep("le-04", ProbeSetup.CARD_ACCESS_SELECTED, "00B0000004"),
            ProbeStep("le-00-at-offset-1", ProbeSetup.CARD_ACCESS_SELECTED, "00B0000100"),
            ProbeStep("offset-beyond-file", ProbeSetup.CARD_ACCESS_SELECTED, "00B07FFF04"),
            ProbeStep("lc-instead-of-le", ProbeSetup.CARD_ACCESS_SELECTED, "00B00000020000")
        )
    )

    /**
     * The largest block the chip answers in one READ BINARY. Only EF.CardAccess can be read before authentication,
     * so a small file ends the block early; the status word and response size say which it was.
     */
    private val readMaxLength = Probe(
        "read-binary-max-length", ProbeTier.A, listOf(
            ProbeStep("le-df", ProbeSetup.CARD_ACCESS_SELECTED, "00B00000DF"),
            ProbeStep("le-e0", ProbeSetup.CARD_ACCESS_SELECTED, "00B00000E0"),
            ProbeStep("le-ff", ProbeSetup.CARD_ACCESS_SELECTED, "00B00000FF"),
            ProbeStep("le-00", ProbeSetup.CARD_ACCESS_SELECTED, "00B0000000"),
            ProbeStep("extended-le-0100", ProbeSetup.CARD_ACCESS_SELECTED, "00B00000000100", needsExtendedLength = true),
            ProbeStep("extended-le-ffff", ProbeSetup.CARD_ACCESS_SELECTED, "00B0000000FFFF", needsExtendedLength = true)
        )
    )

    private val getChallengeLengths = Probe(
        "get-challenge-lengths", ProbeTier.A, listOf(
            ProbeStep("le-08", ProbeSetup.EMRTD_APPLICATION, "0084000008"),
            ProbeStep("le-00", ProbeSetup.EMRTD_APPLICATION, "0084000000"),
            ProbeStep("le-04", ProbeSetup.EMRTD_APPLICATION, "0084000004"),
            ProbeStep("le-07", ProbeSetup.EMRTD_APPLICATION, "0084000007"),
            ProbeStep("le-09", ProbeSetup.EMRTD_APPLICATION, "0084000009"),
            ProbeStep("le-10", ProbeSetup.EMRTD_APPLICATION, "0084000010"),
            ProbeStep("no-le", ProbeSetup.EMRTD_APPLICATION, "00840000")
        )
    )

    /**
     * EXTERNAL AUTHENTICATE with no GET CHALLENGE before it, so the chip refuses on the sequence, not on the
     * cryptogram. Sent once each: a chip that counts failed BAC attempts must not be sent the same one repeatedly.
     * In v2 this probe is the first of the pack, so that no GET CHALLENGE of this run can leave a challenge pending
     * for it; in v1 it is the last.
     */
    private val externalAuthenticate = Probe(
        "external-authenticate-out-of-sequence", ProbeTier.A, listOf(
            ProbeStep("lc-40-le-28", ProbeSetup.EMRTD_APPLICATION, "0082000028${zeros40}28", repeats = 1),
            ProbeStep("lc-08-wrong-length", ProbeSetup.EMRTD_APPLICATION, "0082000008${"00".repeat(8)}28", repeats = 1)
        )
    )

    /** Added to [excluded] in v2. */
    private val excludedV2 = listOf(
        "GET CHALLENGE before the out-of-sequence EXTERNAL AUTHENTICATE probe, and any EXTERNAL AUTHENTICATE beyond that probe's two steps: a whole run sends at most two, directly after a fresh SELECT"
    )

    /**
     * What Tier A never sends, so that nobody adds it here by accident. Tier B (confirm first) and Tier C
     * (sacrificial cards only) are separate packs.
     */
    private val excluded = listOf(
        "VERIFY, CHANGE REFERENCE DATA and RESET RETRY COUNTER (INS 20, 24, 2C): PIN and PUK attempts decrement a retry counter",
        "A BAC or PACE attempt with a guessed or wrong key, CAN or MRZ, and any repeat of one: failed attempts can count, delay or lock",
        "EXTERNAL AUTHENTICATE with a cryptogram that could pass for a BAC attempt, or more than once per command shape",
        "MSE:Set AT, GENERAL AUTHENTICATE and every other step of PACE, Chip Authentication or Terminal Authentication",
        "PSO:Verify Certificate and MSE:Set DST: Terminal Authentication",
        "INTERNAL AUTHENTICATE (Active Authentication): needs an authenticated session",
        "Commands under secure messaging (CLA with 0C) with a wrong MAC: they abort the session and may count as a failure",
        "WRITE, UPDATE, ERASE, DELETE and anything else that changes the chip",
        "Any command whose answer would hold personal data: no file but EF.CardAccess is read, and SELECT of the other files reads nothing"
    )
}
