package com.hddev.smartemu.utils

import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.data.isFailedApdu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class EventLogFormatterTest {

    private val timestamp = Instant.fromEpochSeconds(1640995200) // 2022-01-01T00:00:00Z
    private val context = EventLogFormatter.ReportContext(
        uiState = PassportSimulatorUiState.initial(),
        platform = mapOf("Device" to "Test phone"),
        filter = setOf(NfcEventType.APDU),
        generatedAt = timestamp
    )
    private val apdu = NfcEvent.apdu(
        timestamp = timestamp,
        summary = "SELECT → 9000",
        status = "9000",
        command = "00A4040C07A0000002471001",
        response = "9000"
    )

    @Test
    fun eventJsonIsOneLineWithEscapedStrings() {
        val event = NfcEvent(timestamp, NfcEventType.ERROR, "Bad \"quote\"\nand \\ slash", mapOf("code" to 42))

        assertEquals(
            "{\"timestamp\":\"2022-01-01T00:00:00Z\",\"type\":\"ERROR\"," +
                "\"message\":\"Bad \\\"quote\\\"\\nand \\\\ slash\",\"details\":{\"code\":\"42\"}}",
            EventLogFormatter.eventJson(event)
        )
    }

    @Test
    fun jsonReportHasSummaryAndEvents() {
        val json = EventLogFormatter.json(listOf(apdu), context)

        assertTrue(json.startsWith("{\n  \"generatedAt\": \"2022-01-01T00:00:00Z\","))
        assertTrue("\"Device\": \"Test phone\"" in json)
        assertTrue("\"Filter\": \"APDU\"" in json)
        assertTrue(EventLogFormatter.eventJson(apdu) in json)
    }

    @Test
    fun jsonReportWithoutEventsHasEmptyArray() {
        assertTrue("\"events\": []" in EventLogFormatter.json(emptyList(), context))
    }

    @Test
    fun textReportListsEventsWithDetails() {
        val text = EventLogFormatter.text(listOf(apdu), context)

        assertTrue(text.startsWith("SmartEmu event log\nGenerated: 2022-01-01T00:00:00Z\n"))
        assertTrue("Events (1, oldest first)" in text)
        assertTrue("2022-01-01T00:00:00Z [APDU] SELECT → 9000\n    status: 9000\n    command: 00A4040C07A0000002471001" in text)
    }

    @Test
    fun fileNameHasNoColons() {
        assertEquals("smartemu-log-2022-01-01T000000.json", EventLogFormatter.fileName(timestamp, "json"))
    }

    @Test
    fun failedApduIsAnyStatusButSuccess() {
        assertFalse(apdu.isFailedApdu())
        assertTrue(apdu.copy(details = apdu.details + ("status" to "6982")).isFailedApdu())
        assertFalse(NfcEvent.error(timestamp, "Not an APDU").isFailedApdu())
    }
}
