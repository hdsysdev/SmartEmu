package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.AbortReason
import com.hddev.smartemu.capture.AccessCredentials
import com.hddev.smartemu.capture.ApduTransport
import com.hddev.smartemu.capture.BlockedStatusException
import com.hddev.smartemu.capture.CaptureBuilder
import com.hddev.smartemu.capture.CaptureDraft
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.CaptureSession
import com.hddev.smartemu.capture.BlockedStatus
import com.hddev.smartemu.capture.ResumeOutcome
import com.hddev.smartemu.capture.CredentialProblem
import com.hddev.smartemu.capture.TagLostException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** How an authenticated capture ended. */
sealed interface AuthenticatedCaptureResult {
    /**
     * The credentials were refused before any command was sent: show [problems] (each has a plain-language
     * [CredentialProblem.message]) and let the person correct them.
     */
    data class Rejected(val problems: List<CredentialProblem>) : AuthenticatedCaptureResult

    /**
     * The run happened. [result] is [CaptureResult.Complete], or [CaptureResult.Aborted] with the reason: a lost tag
     * (resume with [CaptureResult.Aborted.draft]), a blocked status word, or [AbortReason.USER_CANCELLED] where the
     * person declined the CAN attempt. A refused key is not an abort: it is a complete record whose
     * [com.hddev.smartemu.capture.AuthenticatedObservation.access] shows the refusal and
     * [com.hddev.smartemu.capture.AuthenticatedObservation.accessStopped] says why no more was tried.
     */
    data class Finished(val result: CaptureResult) : AuthenticatedCaptureResult
}

/**
 * Phase 2: an unauthenticated capture, then an authenticated read of the same chip with MRZ or CAN credentials, in one
 * record. Credentials are validated first (see [AccessCredentials.validate]); if they are not valid, no command is sent.
 *
 * Order. The unauthenticated half is [CaptureRunner.forAuthenticatedRead] of the runner given: it sends no EXTERNAL
 * AUTHENTICATE at all, not the `bac-commands` extractor's and not a probe's (tier-a's out-of-sequence EXTERNAL
 * AUTHENTICATE probe is left out of an authenticated capture), so the key's own attempt is the first the chip's BAC
 * counter sees. Its other probes (SELECT variants, READ BINARY, GET CHALLENGE lengths) run before the authenticated
 * read, in the state they are meant for: unauthenticated. A capture that wants the out-of-sequence probe too is an
 * unauthenticated one of the same document.
 *
 * Safety (see [AuthenticatedReadExtractor]): at most one attempt per PACE suite and one BAC; an MRZ stops at any failure
 * once authentication began, a CAN gets one failed attempt, and is not tried before [run]'s `confirmCanAttempt` says
 * yes (nor again after a lost tag without `confirmInterruptedCanAttempt`); the run ends at a blocked status word.
 *
 * Resume. A draft of another document (the application's answer or a public file differs) is discarded and the run
 * starts again, and then nothing is tried with the credentials, which were typed for the first one: the result is
 * [CaptureResult.Complete] with `resumeOutcome` [ResumeOutcome.DIFFERENT_DOCUMENT] and no authenticated part.
 *
 * The credentials are erased when [run] returns, whatever happened, unless `closeCredentials` is false.
 */
class AuthenticatedCapture(
    unauthenticated: CaptureRunner = CaptureRunner(),
    private val clock: Clock = Clock.System
) {
    private val unauthenticated: CaptureRunner = unauthenticated.forAuthenticatedRead()

    /** The steps [run] reports progress over: the unauthenticated capture's, and one per stage of the authenticated read. */
    val totalSteps: Int get() = unauthenticated.totalSteps + AuthenticatedSteps.all.size

    /**
     * Captures [transport]'s chip with [credentials].
     *
     * @param confirmCanAttempt asked once, on the calling thread, before the CAN is tried, which is the only try it
     * gets; return false to stop before anything is sent with it (the result is aborted with USER_CANCELLED). Block
     * the calling thread on the person's answer. Not asked for an MRZ.
     * @param confirmInterruptedCanAttempt asked instead of [confirmCanAttempt] when [resumeFrom] says a try with the CAN
     * began and was cut short (the chip may have counted it): the CAN is not tried again unless this returns true, and
     * its default is false.
     * @param progress called before each step with its name, steps done and the total, as [CaptureRunner.run] does.
     * @param resumeFrom the draft of an earlier aborted run, to carry on from it.
     * @param closeCredentials whether [run] erases [credentials] when it returns (the default). Pass false to retry
     * with the same ones after a lost tag, and call [AccessCredentials.close] yourself.
     */
    fun run(
        transport: ApduTransport,
        credentials: AccessCredentials,
        confirmCanAttempt: () -> Boolean = { false },
        progress: (stepName: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
        resumeFrom: CaptureDraft? = null,
        closeCredentials: Boolean = true,
        confirmInterruptedCanAttempt: () -> Boolean = { false }
    ): AuthenticatedCaptureResult {
        try {
            val today = clock.now().toLocalDateTime(TimeZone.UTC).date
            val problems = credentials.validate(today)
            if (problems.isNotEmpty()) return AuthenticatedCaptureResult.Rejected(problems)

            val total = totalSteps
            val unauthenticatedSteps = unauthenticated.totalSteps
            val first = unauthenticated.run(transport, resumeFrom) { name, done, _ ->
                // The runner's closing "done" is ours to send, after the authenticated read
                if (name != "done") progress(name, done, total)
            }
            if (first is CaptureResult.Aborted) return AuthenticatedCaptureResult.Finished(first)
            // The key was typed for the document of the draft, not for this one: nothing is tried with it
            if (first.resumeOutcome == ResumeOutcome.DIFFERENT_DOCUMENT) return AuthenticatedCaptureResult.Finished(first)

            val builder = CaptureBuilder.resume(CaptureDraft(first.record, first.draftState)).setAborted(null)
            val session = CaptureSession(transport, blockRule = BlockedStatus.authentication)
            var stepsDone = unauthenticatedSteps
            val extractors = authenticatedExtractors(
                credentials, confirmCanAttempt, { step -> progress(step, stepsDone++, total) },
                canAttemptInterrupted = builder.canAttemptStarted(), confirmInterruptedCanAttempt = confirmInterruptedCanAttempt
            )
            val outcome = first.resumeOutcome
            val result = try {
                JmrtdLogging.silenced {
                    for (extractor in extractors) {
                        if (!extractor.isComplete(builder)) extractor.extract(session, builder)
                    }
                }
                progress("done", total, total)
                CaptureResult.Complete(builder.build(), outcome, builder.draftState(session.externalAuthenticateSent))
            } catch (e: CanAttemptDeclinedException) {
                aborted(builder, session, AbortReason.USER_CANCELLED, outcome)
            } catch (e: TagLostException) {
                aborted(builder, session, AbortReason.TAG_LOST, outcome)
            } catch (e: BlockedStatusException) {
                aborted(builder, session, AbortReason.BLOCKED_STATUS_WORD, outcome)
            } finally {
                session.wipe()
            }
            return AuthenticatedCaptureResult.Finished(result)
        } finally {
            if (closeCredentials) credentials.close()
        }
    }

    private fun aborted(builder: CaptureBuilder, session: CaptureSession, reason: AbortReason, outcome: ResumeOutcome) =
        CaptureResult.Aborted(
            builder.setAborted(reason).build(), reason, outcome, builder.draftState(session.externalAuthenticateSent)
        )
}
