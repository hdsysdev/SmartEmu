package com.hddev.smartemu.utils

import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.ReadRecord
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Turns the read history into text to share: one block per reader session, newest first, as the history lists them.
 */
object ReadHistoryFormatter {

    fun text(records: List<ReadRecord>, timeZone: TimeZone = TimeZone.currentSystemDefault()): String = buildString {
        appendLine("SmartEmu read history: ${records.size} ${if (records.size == 1) "read" else "reads"}")
        records.forEach { record ->
            appendLine()
            appendLine("${dateTime(record.startedAt, timeZone)}  ${record.outcome.displayName}")
            appendLine("  Document: ${record.documentType.displayName} ${record.documentNumber}, ${record.holder}")
            appendLine("  Unlocked with: ${record.accessProtocol ?: "nothing"}")
            record.failureReason?.let { appendLine("  Refused because: $it") }
            appendLine("  Files read: ${record.filesRead.joinToString().ifEmpty { "none" }}")
            appendLine("  Active Authentication: ${if (record.activeAuthentication) "asked for" else "not asked for"}")
            if (record.chipFault != ChipFault.NONE) appendLine("  Chip fault: ${record.chipFault.displayName}")
            appendLine("  Took: ${duration(record.durationMillis)}")
        }
    }

    fun fileName(generatedAt: Instant): String =
        "smartemu-reads-${generatedAt.toString().substringBefore('.').replace(":", "").replace("Z", "")}.txt"

    /** The day and time, to the second, as "2026-09-30 14:05:09". */
    fun dateTime(instant: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = instant.toLocalDateTime(timeZone)
        val time = listOf(local.hour, local.minute, local.second).joinToString(":") { it.toString().padStart(2, '0') }
        return "${local.date} $time"
    }

    /** A duration as seconds to one decimal place, such as "2.4 s". */
    fun duration(millis: Long): String = "${millis / 1000}.${(millis % 1000) / 100} s"
}
