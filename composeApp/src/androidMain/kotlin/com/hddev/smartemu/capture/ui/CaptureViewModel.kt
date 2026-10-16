package com.hddev.smartemu.capture.ui

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hddev.smartemu.capture.AbortReason
import com.hddev.smartemu.capture.AccessCredentials
import com.hddev.smartemu.capture.ApduTransport
import com.hddev.smartemu.capture.BacCommandExtractor
import com.hddev.smartemu.capture.CaptureDraft
import com.hddev.smartemu.capture.CaptureExtractor
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.CredentialKind
import com.hddev.smartemu.capture.CredentialProblem
import com.hddev.smartemu.capture.IsoDepTransport
import com.hddev.smartemu.capture.ResumeOutcome
import com.hddev.smartemu.capture.TagLostException
import com.hddev.smartemu.capture.auth.AuthenticatedCapture
import com.hddev.smartemu.capture.auth.AuthenticatedCaptureResult
import com.hddev.smartemu.capture.defaultExtractors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import java.util.concurrent.CompletableFuture

/** Why a capture ended without something to save. */
enum class CaptureFailure {
    /** The document kept moving away and the retries ran out. */
    TAG_LOST,

    /** The document reported a blocked status; the run stopped to protect it. */
    BLOCKED,

    /** Not a chip the app can read. */
    UNSUPPORTED,

    /** The person declined to try the CAN, so it was never sent; the public part of the reading may have run. */
    CAN_DECLINED,

    /** Something the app didn't expect; the details are not shown, as they could echo the document. */
    UNEXPECTED
}

/**
 * The question asked before a CAN is tried, which it is once.
 *
 * Two things make it a second try for the card, and the answer then needs an explicit extra confirmation
 * ([requiresExtraConfirmation]; see [CaptureViewModel.answerCanAttempt]): a try that began and was cut short by a lost
 * tag, and a failed CAN earlier in the same session.
 */
data class CanPrompt(
    /** True if an earlier try with this CAN was begun and cut short by a lost tag: it may have counted against the card. */
    val interruptedBefore: Boolean,
    /** True if a CAN try already failed in this session, so this one may be on the same document. */
    val previouslyFailed: Boolean = false
) {
    val requiresExtraConfirmation: Boolean get() = interruptedBefore || previouslyFailed
}

/** Something the screen tells the person alongside whatever it shows, beyond the state itself. */
enum class CaptureNotice {
    /** A lost tag was followed by another document: the first capture was dropped and this one started again. */
    DIFFERENT_DOCUMENT
}

/**
 * What the capture screen shows. Everything here is memory only: it is held by the view model, never by saved
 * instance state, so a capture can't be restored from a bundle after the process dies. The key a person types is not in
 * any of these: it is in [KeyEntryFields] while typed and in the view model, as [AccessCredentials], while used.
 */
sealed interface CaptureUiState {
    /** What will be read, what never is saved, and the consent to go on. */
    data object Intro : CaptureUiState

    /** Typing the document's key; [problems] are what the last start found wrong with it. */
    data class KeyEntry(val kind: CredentialKind, val problems: List<CredentialProblem> = emptyList()) : CaptureUiState

    /**
     * Waiting for the document; [retry] says it was lost mid-read and the run carries on when it's back. [withKey] is
     * the kind of key held for the run, or null for a read without one. [canMayHaveCounted] says the CAN attempt had begun
     * when the tag went: it is not repeated by itself, the person is asked again first and told it may have counted.
     */
    data class Waiting(
        val retry: Boolean = false,
        val withKey: CredentialKind? = null,
        val canMayHaveCounted: Boolean = false
    ) : CaptureUiState

    /** [canPrompt] is set while the run waits for the answer to "try the CAN once?". */
    data class Reading(val step: String, val done: Int, val total: Int, val canPrompt: CanPrompt? = null) : CaptureUiState

    /** The "what will be saved" preview, the consent moment. [saved] is whether a file is on the phone. */
    data class Preview(val record: CaptureRecord, val saved: Boolean = false) : CaptureUiState

    /** [partial] is what was captured before the stop, if anything. */
    data class Failed(val failure: CaptureFailure, val partial: CaptureRecord? = null) : CaptureUiState
}

/**
 * The connection to a document a run reads over. [open] blocks (it connects) and may throw [TagLostException]; [close]
 * ends it, which also ends a blocked command. The phone's is [IsoDep]; a test brings its own.
 */
interface CaptureConnection {
    fun open(): ApduTransport
    fun close()
}

private class IsoDepConnection(private val isoDep: IsoDep) : CaptureConnection {
    private var transport: IsoDepTransport? = null

    override fun open(): ApduTransport = IsoDepTransport(isoDep).also { transport = it }

    override fun close() {
        transport?.close() ?: runCatching { isoDep.close() }
    }
}

/**
 * The extractors of an authenticated read's unauthenticated first half: the usual ones without `bac-commands`. That
 * extractor sends a deliberate wrong EXTERNAL AUTHENTICATE, which a chip with a BAC attempt counter may count, so it
 * does not run right before the real attempt. Nor does tier-a's own out-of-sequence EXTERNAL AUTHENTICATE probe:
 * [AuthenticatedCapture] leaves it out of an authenticated capture ([CaptureRunner.forAuthenticatedRead]).
 */
internal fun authenticatedFlowExtractors(): List<CaptureExtractor> = defaultExtractors().filter { it.id != BacCommandExtractor.id }

/**
 * Runs a capture when the reader mode callback hands over a tag. The callback thread only passes the tag on; connecting
 * and the whole run happen on [ioDispatcher].
 *
 * A read with a key holds the [AccessCredentials] from [startWithKey] until the run ends, the person stops, or the
 * screen is left ([onScreenLeft], [onCleared]), and closes them on every one of those paths. A lost tag is the one case
 * where they are kept past a run, and only while the screen is on "waiting" for the retry.
 */
class CaptureViewModel(
    private val store: CaptureStorage,
    private val matcher: ProfileMatcher = NoProfileMatcher,
    private val newRunner: () -> CaptureRunner = { CaptureRunner() },
    private val newAuthenticatedCapture: () -> AuthenticatedCapture = {
        AuthenticatedCapture(CaptureRunner(extractors = authenticatedFlowExtractors()))
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = KeyEntryRules::today
) : ViewModel() {

    private val mutableState = MutableStateFlow<CaptureUiState>(CaptureUiState.Intro)
    val state: StateFlow<CaptureUiState> = mutableState

    private val mutableNotice = MutableStateFlow<CaptureNotice?>(null)

    /** What to tell the person beside the state, or null; cleared when a new read starts or the screen is reset. */
    val notice: StateFlow<CaptureNotice?> = mutableNotice

    private val lock = Any()
    private var job: Job? = null
    private var connection: CaptureConnection? = null
    private var attempts = 0

    /** Where a run that lost its tag stopped, so the next tap carries on from there. Memory only */
    private var draft: CaptureDraft? = null

    /** The key of a read with one, from the start until the run ends. Closed, never left for the garbage collector. */
    private var credentials: AccessCredentials? = null

    /** The answer to the CAN question, while the run is blocked on it. */
    private var pendingCanAnswer: CompletableFuture<Boolean>? = null

    /**
     * Whether a CAN try has failed in this session (kept until the view model is cleared, in memory only, not the CAN
     * and not the document). No document identity is known when the question is asked, so any CAN after a failed one
     * needs the explicit extra confirmation, whichever document it is for.
     */
    private var canFailedThisSession = false

    constructor(context: Context) : this(CaptureStore(context.applicationContext))

    fun matchSummary(record: CaptureRecord): MatchSummary = matcher.match(record)

    fun savedCount(): Int = store.savedCount()

    fun deleteAllSaved() = store.deleteAll()

    /** The user agreed on the intro screen to read without a key: wait for the document. */
    fun startWaiting() {
        releaseCredentials()
        attempts = 0
        draft = null
        mutableNotice.value = null
        mutableState.value = CaptureUiState.Waiting()
    }

    /** The user chose to read with the document's key: ask for it. */
    fun chooseKey(kind: CredentialKind) {
        if (mutableState.value != CaptureUiState.Intro) return
        mutableState.value = CaptureUiState.KeyEntry(kind)
    }

    /**
     * The key was typed: if it is valid, hold it and wait for the document; if not, stay on the key screen with the
     * problems. Takes ownership of [credentials]: they are closed here or by whoever ends the run, never kept by the caller.
     */
    fun startWithKey(credentials: AccessCredentials) {
        val current = mutableState.value
        if (current !is CaptureUiState.KeyEntry || current.kind != credentials.kind) {
            credentials.close()
            return
        }
        val problems = credentials.validate(today())
        if (problems.isNotEmpty()) {
            credentials.close()
            mutableState.value = current.copy(problems = problems)
            return
        }
        releaseCredentials()
        attempts = 0
        draft = null
        mutableNotice.value = null
        synchronized(lock) { this.credentials = credentials }
        mutableState.value = CaptureUiState.Waiting(withKey = credentials.kind)
    }

    /** Called on the NFC reader's thread: hands the tag to [ioDispatcher] and returns at once. */
    fun onTagDiscovered(tag: Tag) {
        val chosen = IsoDep.get(tag) ?: return
        onConnection(IsoDepConnection(chosen))
    }

    /** As [onTagDiscovered], for a connection that is already made; the seam the tests use. */
    internal fun onConnection(chosen: CaptureConnection) {
        synchronized(lock) {
            if (mutableState.value !is CaptureUiState.Waiting || job?.isActive == true) return
            connection = chosen
            val key = credentials
            mutableState.value = CaptureUiState.Reading("Connecting", 0, 1)
            job = viewModelScope.launch(ioDispatcher) { capture(chosen, key) }
        }
    }

    private fun CoroutineScope.capture(chosen: CaptureConnection, key: AccessCredentials?) {
        // Whether the key stays for another tap: only after a lost tag with retries left
        var keepKey = false
        try {
            val transport = chosen.open()
            if (key == null) {
                val result = newRunner().run(transport, resumeFrom = draft) { step, done, total ->
                    if (isActive) mutableState.value = CaptureUiState.Reading(step, done, total)
                }
                if (isActive) finish(result)
            } else {
                val result = newAuthenticatedCapture().run(
                    transport,
                    key,
                    confirmCanAttempt = { askCanAttempt(interrupted = false) },
                    progress = { step, done, total ->
                        if (isActive) mutableState.value = CaptureUiState.Reading(AuthenticatedSummary.stepLabel(step), done, total)
                    },
                    resumeFrom = draft,
                    // Closed below on every path but a retry, so that a lost tag doesn't ask for the key again
                    closeCredentials = false,
                    confirmInterruptedCanAttempt = { askCanAttempt(interrupted = true) }
                )
                if (isActive) keepKey = finishWithKey(result, key)
            }
        } catch (_: TagLostException) {
            if (isActive) keepKey = tagLost(partial = null, kind = key?.kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class only: a message could carry something the document sent
            Log.w(TAG, "Capture failed: ${e.javaClass.simpleName}")
            if (isActive) mutableState.value = CaptureUiState.Failed(CaptureFailure.UNEXPECTED)
        } finally {
            runCatching { chosen.close() }
            if (key != null && !keepKey) dropCredentials(key)
        }
    }

    private fun finish(result: CaptureResult) {
        noteResume(result)
        mutableState.value = when (result) {
            is CaptureResult.Complete -> CaptureUiState.Preview(result.record)
            is CaptureResult.Aborted -> when (result.reason) {
                // Every step re-selects, so the next tap carries on from the draft where the run stopped
                AbortReason.TAG_LOST -> {
                    draft = result.draft
                    tagLost(result.record, kind = null)
                    return
                }
                AbortReason.BLOCKED_STATUS_WORD -> CaptureUiState.Failed(CaptureFailure.BLOCKED, result.record)
                AbortReason.UNSUPPORTED_CHIP -> CaptureUiState.Failed(CaptureFailure.UNSUPPORTED)
                AbortReason.USER_CANCELLED -> CaptureUiState.Intro
            }
        }
    }

    /** Tells the person if the document changed between taps; a draft that was thrown away is not kept here either. */
    private fun noteResume(result: CaptureResult) {
        mutableNotice.value = if (result.resumeOutcome == ResumeOutcome.DIFFERENT_DOCUMENT) CaptureNotice.DIFFERENT_DOCUMENT else null
        if (result.resumeOutcome == ResumeOutcome.DIFFERENT_DOCUMENT) {
            draft = null
            attempts = 0
        }
    }

    /** Remembers for the session that a CAN try failed, from what the authenticated part of a record shows. */
    private fun rememberCanFailure(record: CaptureRecord) {
        val auth = record.authenticated ?: return
        if (auth.credential == CredentialKind.CAN && auth.access.any { !it.succeeded && it.statusWord != null }) canFailedThisSession = true
    }

    /** Returns whether the key is kept for a retry. */
    private fun finishWithKey(result: AuthenticatedCaptureResult, key: AccessCredentials): Boolean {
        when (result) {
            // Checked before the start, so this is only a chip-time change of the clock; ask for the key again
            is AuthenticatedCaptureResult.Rejected ->
                mutableState.value = CaptureUiState.KeyEntry(key.kind, result.problems)
            is AuthenticatedCaptureResult.Finished -> when (val outcome = result.result) {
                // A refused key is a complete record, shown with what the chip answered. After a lost tag and another
                // document nothing was tried with the key, and the record is the new document's unauthenticated part
                is CaptureResult.Complete -> {
                    noteResume(outcome)
                    rememberCanFailure(outcome.record)
                    mutableState.value = CaptureUiState.Preview(outcome.record)
                }
                is CaptureResult.Aborted -> when (outcome.reason) {
                    AbortReason.TAG_LOST -> {
                        draft = outcome.draft
                        return tagLost(outcome.record, key.kind)
                    }
                    AbortReason.BLOCKED_STATUS_WORD -> {
                        rememberCanFailure(outcome.record)
                        mutableState.value = CaptureUiState.Failed(CaptureFailure.BLOCKED, outcome.record)
                    }
                    AbortReason.UNSUPPORTED_CHIP -> mutableState.value = CaptureUiState.Failed(CaptureFailure.UNSUPPORTED)
                    AbortReason.USER_CANCELLED ->
                        mutableState.value = CaptureUiState.Failed(CaptureFailure.CAN_DECLINED, outcome.record)
                }
            }
        }
        return false
    }

    /** Returns whether the run waits for another tap, in which case the key is kept. */
    private fun tagLost(partial: CaptureRecord?, kind: CredentialKind?): Boolean {
        attempts++
        val retry = attempts < MAX_ATTEMPTS
        mutableState.value = if (retry) {
            CaptureUiState.Waiting(retry = true, withKey = kind, canMayHaveCounted = draft?.state?.canAttemptStarted == true)
        } else {
            CaptureUiState.Failed(CaptureFailure.TAG_LOST, partial)
        }
        return retry
    }

    /**
     * Asked by the run, on its own thread, before the one try a CAN gets: shows the question and blocks until the person
     * answers. A stop, or leaving the screen, answers no. [interrupted] is true when the CAN was begun before and a lost
     * tag cut it short, in which case it may have counted and the question needs the extra confirmation, as it does after
     * a CAN failed earlier in the session.
     */
    private fun askCanAttempt(interrupted: Boolean): Boolean {
        val answer = CompletableFuture<Boolean>()
        synchronized(lock) {
            val reading = mutableState.value as? CaptureUiState.Reading ?: return false
            pendingCanAnswer = answer
            mutableState.value = reading.copy(canPrompt = CanPrompt(interruptedBefore = interrupted, previouslyFailed = canFailedThisSession))
        }
        val accepted = try {
            answer.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        synchronized(lock) {
            if (pendingCanAnswer === answer) pendingCanAnswer = null
        }
        return accepted
    }

    /**
     * The person's answer to "try the CAN once?". Declining ends the run before the CAN is sent. Where the prompt
     * [CanPrompt.requiresExtraConfirmation] a yes counts only with [understandsRisk] true (the person ticked that the
     * attempt may count again); without it the question stays open.
     */
    fun answerCanAttempt(accept: Boolean, understandsRisk: Boolean = false) {
        synchronized(lock) {
            val answer = pendingCanAnswer ?: return
            val reading = mutableState.value as? CaptureUiState.Reading
            if (accept && reading?.canPrompt?.requiresExtraConfirmation == true && !understandsRisk) return
            if (reading?.canPrompt != null) mutableState.value = reading.copy(canPrompt = null)
            answer.complete(accept)
        }
    }

    /** Stops a read in progress, or waiting, and goes back to the intro. */
    fun cancel() {
        stopRun()
        draft = null
        releaseCredentials()
        mutableNotice.value = null
        mutableState.value = CaptureUiState.Intro
    }

    /** Back to the intro from a failure, keeping nothing. */
    fun reset() = cancel()

    /**
     * The screen is no longer in front (the Activity stopped, or is being destroyed): a key being typed or held is
     * dropped and the run ends, back at the intro. A read without a key goes on as it did, and a finished capture is
     * left alone, as the key is already gone.
     */
    fun onScreenLeft() {
        val keyHeld = synchronized(lock) { credentials != null }
        if (keyHeld || mutableState.value is CaptureUiState.KeyEntry) cancel()
    }

    /** A failed run's partial capture, for the user to look at and decide about. */
    fun reviewPartial() {
        val partial = (mutableState.value as? CaptureUiState.Failed)?.partial ?: return
        mutableState.value = CaptureUiState.Preview(partial)
    }

    fun save() {
        val preview = mutableState.value as? CaptureUiState.Preview ?: return
        store.save(preview.record)
        mutableState.value = preview.copy(saved = true)
    }

    /** Offers the capture as JSON through the share sheet, from the cache: sharing doesn't save it on the phone. */
    fun shareJson() {
        val preview = mutableState.value as? CaptureUiState.Preview ?: return
        store.shareJson(preview.record)
    }

    fun shareReport() {
        val preview = mutableState.value as? CaptureUiState.Preview ?: return
        store.shareReport(preview.record, CaptureReport.render(preview.record, matcher.match(preview.record)))
    }

    /** Throws the capture away, deleting its file if one was written. */
    fun discard() {
        (mutableState.value as? CaptureUiState.Preview)?.let { store.delete(it.record) }
        mutableState.value = CaptureUiState.Intro
    }

    private fun stopRun() {
        synchronized(lock) {
            job?.cancel()
            job = null
            // A run blocked on the CAN question is let go with no
            pendingCanAnswer?.complete(false)
            pendingCanAnswer = null
            // Closing the connection is what ends a blocked transceive
            connection?.let { runCatching { it.close() } }
            connection = null
        }
    }

    /** Closes and forgets the key being held, if any. */
    private fun releaseCredentials() {
        val held = synchronized(lock) { credentials.also { credentials = null } }
        held?.close()
    }

    /** Closes [key] at the end of its run, and forgets it if it is the one held. */
    private fun dropCredentials(key: AccessCredentials) {
        synchronized(lock) { if (credentials === key) credentials = null }
        key.close()
    }

    override fun onCleared() = release()

    /** Everything held is dropped: the run stops, the key is closed, and a capture not saved is gone. */
    internal fun release() {
        stopRun()
        releaseCredentials()
        // The capture lives in memory only; leaving the screen drops it unless the user saved it
        mutableState.value = CaptureUiState.Intro
    }

    private companion object {
        const val TAG = "CaptureViewModel"
        const val MAX_ATTEMPTS = 3
    }
}
