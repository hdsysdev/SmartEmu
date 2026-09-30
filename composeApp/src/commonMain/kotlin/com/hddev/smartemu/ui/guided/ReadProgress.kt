package com.hddev.smartemu.ui.guided

import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.readerInfo
import kotlin.time.Instant

/**
 * A part of the passport the other phone reads, in the order the guided screens list them, with the chip file that
 * holds it.
 */
enum class PassportPart(val fileName: String) {
    DETAILS("EF.DG1"),
    PHOTO("EF.DG2"),
    SECURITY("EF.SOD")
}

/** Where a step of the read has got to. */
enum class StepState { PENDING, ACTIVE, DONE }

/**
 * Why a read went wrong, in the terms the guided screens explain it.
 */
enum class ReadProblem {
    /** The other app's key didn't open the chip: it scanned or was given details that don't match. */
    WRONG_DETAILS,

    /** The other app tried a way of opening the chip that this passport type turns away. */
    UNSUPPORTED_TYPE,

    /** The phones parted before the other app had read the details. */
    INTERRUPTED
}

/**
 * How far the other phone has got reading the passport, worked out from the emulation's events. Only the last
 * reader session counts, so that holding the phones together again starts afresh.
 */
data class ReadProgress(
    val connected: Boolean = false,
    val unlocked: Boolean = false,
    val partsStarted: Set<PassportPart> = emptySet(),
    val currentPart: PassportPart? = null,
    val finished: Boolean = false,
    val problem: ReadProblem? = null
) {
    fun unlockState(): StepState = when {
        unlocked -> StepState.DONE
        connected && problem == null -> StepState.ACTIVE
        else -> StepState.PENDING
    }

    fun partState(part: PassportPart): StepState = when {
        part !in partsStarted -> StepState.PENDING
        part == currentPart && !finished && problem == null -> StepState.ACTIVE
        else -> StepState.DONE
    }

    companion object {
        /**
         * The progress of the last reader session among [events] since [since]. [idle] says that the reader has
         * sent nothing for a while, which counts as finished once it has read every part.
         */
        fun of(events: List<NfcEvent>, since: Instant, idle: Boolean = false): ReadProgress {
            val recent = events.filter { it.timestamp >= since }
            val start = recent.indexOfLast { it.readerInfo() == NfcEvent.READER_CONNECTED }
            if (start < 0) return ReadProgress()
            val session = recent.subList(start, recent.size)

            val lastSuccess = session.indexOfLast { it.type == NfcEventType.AUTHENTICATION_SUCCESS }
            val lastFailure = session.indexOfLast { it.type == NfcEventType.AUTHENTICATION_FAILURE }
            val unlocked = lastSuccess >= 0
            // The reader may try again after a failure, BAC after PACE say, so a later success clears it
            val failure = session.getOrNull(lastFailure)?.takeIf { lastFailure > lastSuccess }

            var currentPart: PassportPart? = null
            val partsStarted = mutableSetOf<PassportPart>()
            session.forEach { event ->
                val info = event.readerInfo()
                if (info != null && info.startsWith(NfcEvent.READING_PREFIX)) {
                    val fileName = info.removePrefix(NfcEvent.READING_PREFIX)
                    PassportPart.entries.find { it.fileName == fileName }?.let { part ->
                        partsStarted += part
                        currentPart = part
                    }
                }
            }

            val ended = session.last().type == NfcEventType.CONNECTION_LOST
            val readDetails = PassportPart.DETAILS in partsStarted
            val finished = failure == null && unlocked && readDetails &&
                (ended || (idle && partsStarted.size == PassportPart.entries.size))
            val problem = when {
                failure != null -> if (failure.details["reason"]?.toString()?.contains("disabled") == true) {
                    ReadProblem.UNSUPPORTED_TYPE
                } else {
                    ReadProblem.WRONG_DETAILS
                }
                ended && !finished -> ReadProblem.INTERRUPTED
                else -> null
            }

            return ReadProgress(
                connected = !ended,
                unlocked = unlocked,
                partsStarted = partsStarted,
                currentPart = currentPart,
                finished = finished,
                problem = problem
            )
        }
    }
}
