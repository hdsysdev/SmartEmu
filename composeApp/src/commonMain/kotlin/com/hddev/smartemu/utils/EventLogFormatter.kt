package com.hddev.smartemu.utils

import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportSimulatorUiState
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Turns the event log into reports to copy or export: plain text for people, JSON for tools and agents.
 * Both start with the device, the emulation's state and the chip configuration, and list the events oldest first.
 */
object EventLogFormatter {

    /** Legacy Logcat tag retained for existing reader tools, one JSON object per line; see [eventJson]. */
    const val LOGCAT_TAG = "SmartEmuEvents"

    /**
     * What a report covers besides the events. [filter] names the event types shown, empty for all of them.
     */
    data class ReportContext(
        val uiState: PassportSimulatorUiState,
        val platform: Map<String, String>,
        val filter: Set<NfcEventType> = emptySet(),
        val searchQuery: String = "",
        val generatedAt: Instant = Clock.System.now()
    )

    enum class Format(val extension: String, val mimeType: String) {
        TEXT("txt", "text/plain"),
        JSON("json", "application/json")
    }

    fun format(format: Format, events: List<NfcEvent>, context: ReportContext): String = when (format) {
        Format.TEXT -> text(events, context)
        Format.JSON -> json(events, context)
    }

    fun text(events: List<NfcEvent>, context: ReportContext): String = buildString {
        appendLine("PassportEmu event log")
        appendLine("Generated: ${context.generatedAt}")
        summary(context).forEach { (key, value) -> appendLine("$key: $value") }
        appendLine()
        appendLine("Events (${events.size}, oldest first)")
        events.forEach { appendLine(eventText(it)) }
    }

    fun json(events: List<NfcEvent>, context: ReportContext): String = buildString {
        append("{\n")
        append("  \"generatedAt\": ${quote(context.generatedAt.toString())},\n")
        append("  \"summary\": ${jsonObject(summary(context))},\n")
        append("  \"events\": [")
        events.forEachIndexed { index, event ->
            append(if (index == 0) "\n    " else ",\n    ")
            append(eventJson(event))
        }
        append(if (events.isEmpty()) "]\n" else "\n  ]\n")
        append("}\n")
    }

    /**
     * One event as a single line of text, with its details indented below it.
     */
    fun eventText(event: NfcEvent): String = buildString {
        append("${event.timestamp} [${event.type.name}] ${event.message}")
        event.details.forEach { (key, value) -> append("\n    $key: $value") }
    }

    /**
     * One event as a single-line JSON object.
     */
    fun eventJson(event: NfcEvent): String =
        "{\"timestamp\":${quote(event.timestamp.toString())},\"type\":${quote(event.type.name)}," +
            "\"message\":${quote(event.message)},\"details\":${jsonObject(event.details, compact = true)}}"

    fun fileName(generatedAt: Instant, extension: String): String {
        // Colons aren't allowed in file names on every platform
        val stamp = generatedAt.toString().substringBefore('.').replace(":", "").replace("Z", "")
        return "passportemu-log-$stamp.$extension"
    }

    private fun summary(context: ReportContext): Map<String, String> {
        val state = context.uiState
        val passport = state.passportData
        return buildMap {
            putAll(context.platform)
            put("Emulation", state.simulationStatus.name)
            put("NFC available", state.nfcAvailable.toString())
            put("NFC permission", state.hasNfcPermission.toString())
            put("Document type", "${passport.documentType.displayName} (${passport.documentType.formatName})")
            put("Access control", passport.accessControl.displayName)
            if (passport.accessControl.supportsPace) {
                put("PACE mapping", passport.paceMapping.abbreviation)
                put("CAN", passport.can.ifBlank { "none" })
            }
            put("Active Authentication", passport.activeAuthentication.toString())
            put("Chip fault", passport.chipFault.displayName)
            put("Document number", passport.passportNumber.ifBlank { "none" })
            put("Date of birth", passport.dateOfBirth?.toString() ?: "none")
            put("Expiry date", passport.expiryDate?.toString() ?: "none")
            put("Portrait", passport.portrait?.toString() ?: "placeholder")
            put("Validation errors", passport.getValidationErrors().keys.sorted().joinToString().ifEmpty { "none" })
            put("Filter", context.filter.map { it.name }.sorted().joinToString().ifEmpty { "all events" })
            if (context.searchQuery.isNotBlank()) put("Search", context.searchQuery)
        }
    }

    private fun jsonObject(values: Map<String, Any>, compact: Boolean = false): String {
        if (values.isEmpty()) return "{}"
        val separator = if (compact) "," else ",\n    "
        val prefix = if (compact) "{" else "{\n    "
        val suffix = if (compact) "}" else "\n  }"
        return values.entries.joinToString(separator, prefix, suffix) { (key, value) ->
            "${quote(key)}:${if (compact) "" else " "}${quote(value.toString())}"
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
            }
        }
        append('"')
    }
}
