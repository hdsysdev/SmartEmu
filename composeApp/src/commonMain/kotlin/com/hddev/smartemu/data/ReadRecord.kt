package com.hddev.smartemu.data

import kotlin.time.Instant

/**
 * How a reader session ended, from the chip's side.
 */
enum class ReadOutcome(val displayName: String) {
    /** The reader unlocked the chip and read the MRZ, the portrait and EF.SOD. */
    COMPLETE("Complete"),

    /** The reader unlocked the chip and read the MRZ, but not everything else. */
    PARTIAL("Partial"),

    /** The reader's key didn't open the chip: it had other details than the document's. */
    WRONG_DETAILS("Wrong details"),

    /** The reader tried an access control protocol the chip has turned off. */
    UNSUPPORTED_PROTOCOL("Protocol refused"),

    /** The reader went away before it had read the MRZ. */
    INTERRUPTED("Interrupted");

    val succeeded: Boolean get() = this == COMPLETE || this == PARTIAL
}

/**
 * One reader session, from its first command until it went away, as the read history keeps it: the document the
 * chip was, what the reader did with it, and how it ended.
 */
data class ReadRecord(
    val startedAt: Instant,
    val endedAt: Instant,
    val documentType: DocumentType,
    /** The holder's name and the document number, as printed. */
    val holder: String,
    val documentNumber: String,
    /** The protocol that unlocked the chip, such as "BAC" or "PACE-GM", or null if none did. */
    val accessProtocol: String?,
    /** The files read, in the order the reader first read them. */
    val filesRead: List<String>,
    /** Whether the reader asked the chip for Active Authentication. */
    val activeAuthentication: Boolean,
    val chipFault: ChipFault,
    val outcome: ReadOutcome,
    /** Why the last attempt to unlock the chip failed, if it did, as the chip saw it. */
    val failureReason: String? = null
) {
    val durationMillis: Long get() = (endedAt - startedAt).inWholeMilliseconds

    companion object {
        /** The files a complete read has, besides whatever else the reader chooses to read. */
        private val COMPLETE_FILES = setOf("EF.DG1", "EF.DG2", "EF.SOD")

        /**
         * The record of the reader session [events] make up, starting with the reader's first command, for the
         * document [passportData] describes. [endedAt] is when the session ended, the last event's time if null.
         */
        fun of(events: List<NfcEvent>, passportData: PassportData, endedAt: Instant? = null): ReadRecord? {
            val first = events.firstOrNull() ?: return null

            val lastSuccess = events.indexOfLast { it.type == NfcEventType.AUTHENTICATION_SUCCESS }
            val lastFailure = events.indexOfLast { it.type == NfcEventType.AUTHENTICATION_FAILURE }
            // The reader may try again after a failure, PACE after BAC say, so a later success clears it
            val failure = events.getOrNull(lastFailure)?.takeIf { lastFailure > lastSuccess }
            val accessProtocol = events.getOrNull(lastSuccess)?.details?.get("protocol")?.toString()

            val filesRead = events.mapNotNull { event ->
                event.readerInfo()?.takeIf { it.startsWith(NfcEvent.READING_PREFIX) }?.removePrefix(NfcEvent.READING_PREFIX)
            }.distinct()
            val activeAuthentication = events.any { it.readerInfo() == NfcEvent.ACTIVE_AUTHENTICATION_ANSWERED }

            val failureReason = failure?.details?.get("reason")?.toString()
            val outcome = when {
                failure != null && failureReason?.contains("disabled") == true -> ReadOutcome.UNSUPPORTED_PROTOCOL
                failure != null -> ReadOutcome.WRONG_DETAILS
                accessProtocol == null || "EF.DG1" !in filesRead -> ReadOutcome.INTERRUPTED
                filesRead.containsAll(COMPLETE_FILES) -> ReadOutcome.COMPLETE
                else -> ReadOutcome.PARTIAL
            }

            return ReadRecord(
                startedAt = first.timestamp,
                endedAt = endedAt ?: events.last().timestamp,
                documentType = passportData.documentType,
                holder = "${passportData.firstName} ${passportData.lastName}".trim(),
                documentNumber = passportData.passportNumber.uppercase(),
                accessProtocol = accessProtocol,
                filesRead = filesRead,
                activeAuthentication = activeAuthentication,
                chipFault = passportData.chipFault,
                outcome = outcome,
                failureReason = failureReason
            )
        }
    }
}

/**
 * Splits the event stream into reader sessions as it arrives, and turns each finished one into a [ReadRecord]. A
 * session starts at the reader's first command and ends when the reader goes away or the emulation stops.
 */
class ReadSessionRecorder {

    private val session = mutableListOf<NfcEvent>()

    /** Whether a reader session is under way. */
    val inSession: Boolean get() = session.isNotEmpty()

    /**
     * Takes the next event, and returns the record of the session it ends, or of the one a new session cut short.
     * [passportData] is the document the chip is emulating.
     */
    fun onEvent(event: NfcEvent, passportData: PassportData): ReadRecord? {
        if (event.readerInfo() == NfcEvent.READER_CONNECTED) {
            val cutShort = finish(passportData)
            session += event
            return cutShort
        }
        if (session.isEmpty()) return null
        if (event.type == NfcEventType.CONNECTION_LOST) {
            session += event
            return finish(passportData)
        }
        // The APDU trace can run to hundreds of events a read; the record needs none of them
        if (event.type != NfcEventType.APDU) session += event
        return null
    }

    /**
     * Ends the session under way, if there is one, as when the emulation stops, and returns its record.
     */
    fun finish(passportData: PassportData): ReadRecord? {
        if (session.isEmpty()) return null
        val record = ReadRecord.of(session.toList(), passportData)
        session.clear()
        return record
    }
}
