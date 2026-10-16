package com.hddev.smartemu.capture

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** What happened to the draft a run was handed, so that a screen can tell the person. */
enum class ResumeOutcome {
    /** No draft was given: a new capture. */
    NEW_RUN,

    /** The draft was of this same document, and the run carried on from it. */
    RESUMED,

    /**
     * The draft was of another document: what the chip answered for the application and the public files differs
     * from the draft's. The draft was thrown away and the run is a new capture, under a new capture id. A caller
     * holding a key typed for the first document must not use it on this one.
     */
    DIFFERENT_DOCUMENT
}

/**
 * How a capture run ended. [resumeOutcome] says what became of the draft the run was handed, and [draftState] what a
 * resume must remember besides the record (see [DraftState]).
 */
sealed interface CaptureResult {
    val record: CaptureRecord
    val resumeOutcome: ResumeOutcome
    val draftState: DraftState

    /** Every extractor and probe step ran. */
    data class Complete(
        override val record: CaptureRecord,
        override val resumeOutcome: ResumeOutcome = ResumeOutcome.NEW_RUN,
        override val draftState: DraftState = DraftState()
    ) : CaptureResult

    /** The run stopped early; [record] holds what was captured, and says why in [CaptureRecord.aborted]. */
    data class Aborted(
        override val record: CaptureRecord,
        val reason: AbortReason,
        override val resumeOutcome: ResumeOutcome = ResumeOutcome.NEW_RUN,
        override val draftState: DraftState = DraftState()
    ) : CaptureResult {
        /** To pass to [CaptureRunner.run] as `resumeFrom` after a lost tag; a blocked status word is not resumed. */
        val draft: CaptureDraft get() = CaptureDraft(record, draftState)
    }
}

/** Thrown by [CaptureRunner.run] for a Tier C pack when the runner wasn't built with `acknowledgeSacrificialCard = true`. */
class SacrificialCardNotAcknowledgedException(val packs: List<ProbePackRef>) :
    IllegalStateException("Probe pack ${packs.joinToString { "${it.id} v${it.version}" }} may touch a counter and runs only on a sacrificial card: acknowledgeSacrificialCard = true is required")

/**
 * Runs the [extractors] and then the probes of [pack], and of each of [additionalPacks] in turn, against a chip, and
 * builds a [CaptureRecord] from what they saw. Unauthenticated only: no MRZ or CAN is taken, and no command is sent
 * that a pack doesn't hold. The first pack is recorded as [CaptureRecord.probePack] and the others, in order, as
 * [CaptureRecord.additionalProbePacks]; each probe result carries its own pack's version.
 *
 * A pack with [ProbePack.requiresSacrificialCardAcknowledgement] (Tier C) may touch a counter. [run] refuses it with a
 * [SacrificialCardNotAcknowledgedException] before a single command is sent, unless [acknowledgeSacrificialCard] is
 * true, which only a caller that has had the user declare a sacrificial or test card may pass. The default is false.
 *
 * Counters. A run ends at once, and is not resumed, on a status word that means a chip may have counted a failure
 * (see [BlockedStatus]): 6983, 6984, any 63Cx, and 6300 after an EXTERNAL AUTHENTICATE. Tier C, whose design is a
 * bounded run of wrong-key attempts, carries on through 6300 and 63C2 and up, and stops at 63C1 and below.
 *
 * A run is resumable. When the tag is lost the result is [CaptureResult.Aborted] with [AbortReason.TAG_LOST]; hand
 * its [CaptureResult.Aborted.draft] back as `resumeFrom` on the next tap and the run carries on where it stopped, under
 * the same capture id, if the chip is the same document: the application's answer and the public files are read again
 * first and compared with the draft's, and a different document throws the draft away and starts again under a new
 * id ([ResumeOutcome.DIFFERENT_DOCUMENT]). The draft remembers that an EXTERNAL AUTHENTICATE was sent, so a resume
 * never sends one again: a probe of it that a lost tag cut short is left out of the record. A blocked status word ends
 * the run, and a draft that ended that way is returned as it is without a command being sent.
 *
 * [skipProbes] names probes (by id) that are not run; see [forAuthenticatedRead].
 */
class CaptureRunner(
    private val extractors: List<CaptureExtractor> = defaultExtractors(),
    private val pack: ProbePack = ProbePack.tierA,
    private val source: CaptureSource = CaptureSource.ANDROID_READER,
    private val clock: Clock = Clock.System,
    private val additionalPacks: List<ProbePack> = emptyList(),
    private val acknowledgeSacrificialCard: Boolean = false,
    private val skipProbes: Set<String> = emptySet()
) {
    private val packs: List<ProbePack> = listOf(pack) + additionalPacks

    init {
        require(packs.map { it.ref }.toSet().size == packs.size) { "A probe pack is listed twice" }
    }

    /** Thrown inside a run to unwind to the result. */
    private class Stop(val reason: AbortReason) : Exception()

    private fun probesOf(pack: ProbePack): List<Probe> = pack.probes.filter { it.id !in skipProbes }

    /** The steps a run takes, for [run]'s progress: one per extractor and one per probe step. */
    val totalSteps: Int get() = extractors.size + packs.sumOf { pack -> probesOf(pack).sumOf { it.steps.size } }

    /**
     * This runner for the unauthenticated half of an authenticated capture: the same packs, but it sends no EXTERNAL
     * AUTHENTICATE at all (the extractors that would, and every probe that has one in a step, are left out), so that
     * the key the person typed is the first thing the chip's BAC counter sees. The `external-authenticate-out-of-sequence`
     * probe, and the `bac-commands` part of the record it feeds, are therefore not in an authenticated capture.
     */
    fun forAuthenticatedRead(): CaptureRunner = CaptureRunner(
        extractors = extractors.filter { !it.sendsExternalAuthenticate },
        pack = pack, source = source, clock = clock, additionalPacks = additionalPacks,
        acknowledgeSacrificialCard = acknowledgeSacrificialCard,
        skipProbes = skipProbes + packs.flatMap { p -> p.probes.filter(p::probeSendsExternalAuthenticate).map(Probe::id) }
    )

    /**
     * Captures [transport]'s chip. [progress] is called before each step with its name, how many are done and the
     * total, and once more at the end with `done == total`; it runs on the calling thread. A draft from another probe
     * pack, or from a run of other packs, is ignored.
     *
     * @throws SacrificialCardNotAcknowledgedException for a Tier C pack when the runner wasn't acknowledged.
     */
    fun run(
        transport: ApduTransport,
        resumeFrom: CaptureDraft? = null,
        progress: (stepName: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ): CaptureResult {
        val unacknowledged = packs.filter { it.requiresSacrificialCardAcknowledgement }
        if (unacknowledged.isNotEmpty() && !acknowledgeSacrificialCard) {
            throw SacrificialCardNotAcknowledgedException(unacknowledged.map { it.ref })
        }
        val draft = resumeFrom?.takeIf {
            it.record.probePack == pack.ref && it.record.additionalProbePacks == additionalPacks.map(ProbePack::ref)
        }
        if (draft?.record?.aborted == AbortReason.BLOCKED_STATUS_WORD) {
            return CaptureResult.Aborted(draft.record, AbortReason.BLOCKED_STATUS_WORD, ResumeOutcome.RESUMED, draft.state)
        }
        val session = CaptureSession(transport)
        var builder = draft?.let(CaptureBuilder::resume) ?: newBuilder()
        var outcome = if (draft != null) ResumeOutcome.RESUMED else ResumeOutcome.NEW_RUN
        val total = totalSteps
        var done = 0
        return try {
            if (draft != null && !isSameDocument(session, builder)) {
                // Another document: nothing of the draft is kept, not even what it remembers about commands sent
                builder = newBuilder()
                outcome = ResumeOutcome.DIFFERENT_DOCUMENT
            }
            // A resumed run may be on another tap of another phone: the reader and link are the current ones
            builder.setReader(transport.readerDevice()).setLink(transport.linkInfo() ?: UNKNOWN_LINK).setAborted(null)
            // Whether an EXTERNAL AUTHENTICATE was sent before this run: if so, no probe that has one is run again
            val authenticateSentBefore = builder.externalAuthenticateSent()
            for (extractor in extractors) {
                progress(extractor.id, done, total)
                if (!extractor.isComplete(builder) && !(authenticateSentBefore && extractor.sendsExternalAuthenticate)) {
                    extractor.extract(session, builder)
                    stopIfBlocked(session)
                }
                done++
            }
            for (pack in packs) {
                for (probe in probesOf(pack)) {
                    if (builder.hasProbe(probe.id, pack.version) || (authenticateSentBefore && pack.probeSendsExternalAuthenticate(probe))) {
                        done += probe.steps.size
                    } else {
                        done = runProbe(pack, probe, session, builder, transport, progress, done, total)
                    }
                }
            }
            progress("done", total, total)
            CaptureResult.Complete(snapshot(builder), outcome, builder.draftState(session.externalAuthenticateSent))
        } catch (stop: Stop) {
            aborted(builder, session, stop.reason, outcome)
        } catch (e: TagLostException) {
            aborted(builder, session, AbortReason.TAG_LOST, outcome)
        } catch (e: BlockedStatusException) {
            aborted(builder, session, AbortReason.BLOCKED_STATUS_WORD, outcome)
        } finally {
            session.wipe()
        }
    }

    /** [run] without a draft; the progress callback is the last argument, so it can trail the call as a lambda. */
    fun run(transport: ApduTransport, progress: (stepName: String, done: Int, total: Int) -> Unit): CaptureResult =
        run(transport, null, progress)

    private fun newBuilder(): CaptureBuilder {
        val now = clock.now().toLocalDateTime(TimeZone.UTC)
        return CaptureBuilder.create().setSource(source).setProbePack(pack.ref)
            .setAdditionalProbePacks(additionalPacks.map(ProbePack::ref)).setCaptureMonth(now.year, now.month.ordinal + 1)
    }

    private fun aborted(builder: CaptureBuilder, session: CaptureSession, reason: AbortReason, outcome: ResumeOutcome): CaptureResult.Aborted {
        builder.setAborted(reason)
        return CaptureResult.Aborted(snapshot(builder), reason, outcome, builder.draftState(session.externalAuthenticateSent))
    }

    private fun snapshot(builder: CaptureBuilder): CaptureRecord {
        deriveBacCommands(builder)
        return builder.build()
    }

    /**
     * In tier-a v2 no extractor sends the EXTERNAL AUTHENTICATE or the GET CHALLENGE of the `bac-commands` observation
     * (that would put a GET CHALLENGE before the pack's own EXTERNAL AUTHENTICATE probe, or a third EXTERNAL
     * AUTHENTICATE in the run): the observation is the answer of the pack's `lc-40-le-28` step and of its `le-08` GET
     * CHALLENGE step, the same two commands.
     */
    private fun deriveBacCommands(builder: CaptureBuilder) {
        val v2 = packs.firstOrNull { it.id == TIER_A && it.version >= 2 } ?: return
        fun answer(probe: String, step: String) =
            builder.probeResult(probe, v2.version)?.steps?.firstOrNull { it.stepId == step }?.statusWord
        val getChallenge = answer(TierAProbePack.GET_CHALLENGE_PROBE, TierAProbePack.GET_CHALLENGE_STEP)
        val externalAuthenticate = answer(TierAProbePack.EXTERNAL_AUTHENTICATE_PROBE, TierAProbePack.EXTERNAL_AUTHENTICATE_STEP)
        if (getChallenge != null || externalAuthenticate != null) builder.setBacCommands(getChallenge, externalAuthenticate)
    }

    /**
     * Reads the application and the public files again, for each of them the draft already has, and compares what the
     * chip answers with the draft's. No UID is kept or compared, and two chips of one model with the same public
     * files can't be told apart: this catches the person tapping another kind of document, not a twin.
     */
    private fun isSameDocument(session: CaptureSession, draft: CaptureBuilder): Boolean {
        val now = CaptureBuilder.create()
        for (extractor in extractors) {
            if (extractor.readsPublicIdentity && extractor.isComplete(draft)) {
                extractor.extract(session, now)
                stopIfBlocked(session)
            }
        }
        return draft.sameDocumentAs(now)
    }

    private fun stopIfBlocked(session: CaptureSession) {
        if (session.blockedStatusWord != null) throw Stop(AbortReason.BLOCKED_STATUS_WORD)
    }

    /**
     * Runs the steps of [probe] and adds the result. A probe cut short by a lost tag is not added, so a resumed run
     * does it again (unless it sends an EXTERNAL AUTHENTICATE: see [run]); one cut short by a blocked status word is,
     * with the step that got it. Returns the steps done.
     */
    private fun runProbe(
        pack: ProbePack,
        probe: Probe,
        session: CaptureSession,
        builder: CaptureBuilder,
        transport: ApduTransport,
        progress: (String, Int, Int) -> Unit,
        doneBefore: Int,
        total: Int
    ): Int {
        var done = doneBefore
        val results = ArrayList<StepResult>()
        val extendedSupported = transport.readerDevice().controllerExtendedLengthSupported
        session.blockRule = if (probe.tier == ProbeTier.C) BlockedStatus.wrongKeyProbes else BlockedStatus.probes
        try {
            for (step in probe.steps) {
                progress("${probe.id}/${step.id}", done, total)
                done++
                if (step.needsExtendedLength && extendedSupported == false) continue
                results += ProbeStepRunner.run(step, session)
                if (session.blockedStatusWord != null) break
            }
        } finally {
            session.blockRule = BlockedStatus.probes
        }
        builder.addProbe(ProbeResult(probe.id, pack.version, probe.tier, results))
        stopIfBlocked(session)
        return done
    }

    private companion object {
        const val TIER_A = "tier-a"

        /** For a transport that can't tell what its link is: NFC-A, which is what Android HCE and most readers offer. */
        val UNKNOWN_LINK = LinkObservation(NfcTechnology.NFC_A)
    }
}

/**
 * Runs one [ProbeStep]: its setup, its preamble if it has one, then its command [ProbeStep.repeats] times. With
 * [ProbeStep.freshStateEachRepeat] the setup and the preamble are redone before every repeat.
 *
 * If the setup could not put the chip in the state the step starts from, the step is not run: a step that needs the
 * master file, from a chip that refuses SELECT of the master file, would run in whatever state the previous step left,
 * and its answer would mean something else. Its [StepResult] then has no status word (as for a command the reader can't
 * send) and the setup's status word, which says why.
 */
internal object ProbeStepRunner {
    fun run(step: ProbeStep, session: CaptureSession): StepResult {
        val setup = setUp(step.setup, session)
        if (!setup.usable) return StepResult(stepId = step.id, statusWord = null, setupStatusWord = setup.statusWord)
        val command = step.command
        val preamble = step.preamble
        val statusWords = ArrayList<Int?>()
        val nanos = ArrayList<Long>()
        var size: Int? = null
        for (attempt in 0 until step.repeats) {
            if (attempt > 0 && step.freshStateEachRepeat && !setUp(step.setup, session).usable) break
            for (before in preamble) {
                session.transmit(before)
                if (session.blockedStatusWord != null) break
            }
            if (session.blockedStatusWord != null) break
            val response = try {
                session.transmit(command)
            } catch (e: CommandNotSupportedByReader) {
                statusWords += null
                break
            }
            statusWords += response.statusWord
            nanos += response.nanos
            if (attempt == 0) size = response.data.size
            if (session.blockedStatusWord != null) break
        }
        return StepResult(
            stepId = step.id,
            statusWord = statusWords.firstOrNull(),
            responseSize = size?.let(Redaction::probeSizeRange),
            timing = timing(nanos),
            setupStatusWord = setup.statusWord,
            repeatsAgree = statusWords.distinct().size <= 1
        )
    }

    /** What a setup left: the status word of the SELECT that made the state, and whether the state is the one named. */
    private class Setup(val statusWord: Int?, val usable: Boolean)

    /**
     * Puts the chip in the state [setup] names. The master file state is known only if SELECT of the master file was
     * answered 9000. EF.CardAccess selected is known if the SELECT of it was, or if the master file was selected and the
     * SELECT of it failed, which leaves the master file current (the steps then see the failure in their setup status).
     */
    private fun setUp(setup: ProbeSetup, session: CaptureSession): Setup = when (setup) {
        ProbeSetup.NONE -> Setup(null, true)
        ProbeSetup.MASTER_FILE -> session.selectMasterFile().statusWord.let { Setup(it, it == CaptureSession.SW_OK) }
        ProbeSetup.EMRTD_APPLICATION -> {
            session.selectMasterFile()
            Setup(session.selectEmrtdApplication().statusWord, true)
        }
        ProbeSetup.CARD_ACCESS_SELECTED -> {
            val masterFile = session.selectMasterFile().statusWord
            val file = session.selectFileId(PublicFileExtractor.CARD_ACCESS_ID).statusWord
            Setup(file, masterFile == CaptureSession.SW_OK || file == CaptureSession.SW_OK)
        }
    }

    private fun timing(nanos: List<Long>): TimingSummary? {
        if (nanos.isEmpty()) return null
        val sorted = nanos.sorted()
        val toMs = { n: Long -> (n + 500_000) / 1_000_000 }
        return TimingSummary(toMs(sorted.first()), toMs(sorted[sorted.size / 2]), toMs(sorted.last()), sorted.size)
    }
}
