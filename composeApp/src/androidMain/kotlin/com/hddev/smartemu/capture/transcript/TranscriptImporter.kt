package com.hddev.smartemu.capture.transcript

import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.CaptureSource
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ProbePacks

/** A transcript can't be imported for a reason that isn't a mismatch with the run: the source or the packs aren't named, or are unknown. */
class TranscriptImportException(message: String) : IllegalArgumentException(message)

/**
 * Ingests a lab capture: the capture runner runs against the [Transcript] of a PC/SC or Proxmark session, sending the
 * same commands a phone would, and builds the usual redacted [com.hddev.smartemu.capture.CaptureRecord] with
 * `source = PCSC` or `PROXMARK`. The record then goes through the same intake as a phone capture (lint, fingerprint, store, merge).
 *
 * A command the transcript doesn't have at that point fails with a [TranscriptMismatchException]; so does a transcript
 * with exchanges the run never used. A Tier C pack needs [acknowledgeSacrificialCard], as it does on the phone.
 */
object TranscriptImporter {
    fun import(
        text: String,
        source: CaptureSource? = null,
        packs: List<ProbePack>? = null,
        acknowledgeSacrificialCard: Boolean = false
    ): CaptureResult = import(Transcript.parse(text), source, packs, acknowledgeSacrificialCard)

    fun import(
        transcript: Transcript,
        source: CaptureSource? = null,
        packs: List<ProbePack>? = null,
        acknowledgeSacrificialCard: Boolean = false
    ): CaptureResult {
        val chosenSource = source ?: transcript.source?.let { name ->
            CaptureSource.entries.firstOrNull { it.name == name } ?: throw TranscriptImportException("The transcript's source '$name' is not PCSC or PROXMARK")
        } ?: throw TranscriptImportException("Name the source: pass PCSC or PROXMARK, or put '#! source: PCSC' in the transcript")
        if (chosenSource == CaptureSource.ANDROID_READER) throw TranscriptImportException("A transcript is a PCSC or PROXMARK capture, not ANDROID_READER")

        val chosenPacks = packs ?: transcript.packs.map { (id, version) ->
            ProbePacks.byId(id, version) ?: throw TranscriptImportException("The transcript names the probe pack $id v$version, which is not published")
        }.ifEmpty { throw TranscriptImportException("Name the probe packs: pass them, or put '#! packs: tier-a/1' in the transcript") }

        val transport = transcript.transport()
        val runner = CaptureRunner(
            pack = chosenPacks.first(), additionalPacks = chosenPacks.drop(1), source = chosenSource,
            acknowledgeSacrificialCard = acknowledgeSacrificialCard
        )
        val result = runner.run(transport)
        if (result is CaptureResult.Complete && transport.remaining != 0) {
            throw TranscriptMismatchException(
                "The transcript has ${transport.remaining} more exchanges than the capture run used: it was recorded with other commands than these probe packs send."
            )
        }
        return result
    }
}
