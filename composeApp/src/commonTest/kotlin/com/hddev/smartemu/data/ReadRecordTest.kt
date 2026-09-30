package com.hddev.smartemu.data

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ReadRecordTest {

    private val passportData = PassportData(
        passportNumber = "spec24681",
        dateOfBirth = LocalDate(1986, 3, 14),
        expiryDate = LocalDate(2035, 3, 13),
        issuingCountry = "SWE",
        nationality = "SWE",
        firstName = "Anna Maria",
        lastName = "Eriksson",
        gender = "F"
    )

    private val start = Instant.fromEpochMilliseconds(1_000_000)
    private fun at(ms: Int) = start + ms.milliseconds

    private fun connected(ms: Int = 0) = NfcEvent.connectionEstablished(at(ms), NfcEvent.READER_CONNECTED)
    private fun reading(file: String, ms: Int) = NfcEvent.connectionEstablished(at(ms), NfcEvent.READING_PREFIX + file)

    @Test
    fun `a read of the MRZ, portrait and SOD is complete`() {
        val events = listOf(
            connected(),
            NfcEvent.authenticationSuccess(at(100), "PACE-GM"),
            reading("EF.COM", 150),
            reading("EF.DG1", 200),
            reading("EF.DG1", 210),
            reading("EF.DG2", 300),
            reading("EF.SOD", 900),
            NfcEvent.connectionEstablished(at(950), NfcEvent.ACTIVE_AUTHENTICATION_ANSWERED)
        )

        val record = ReadRecord.of(events, passportData, endedAt = at(1000))!!

        assertEquals(ReadOutcome.COMPLETE, record.outcome)
        assertEquals("PACE-GM", record.accessProtocol)
        assertEquals(listOf("EF.COM", "EF.DG1", "EF.DG2", "EF.SOD"), record.filesRead)
        assertTrue(record.activeAuthentication)
        assertEquals(1000, record.durationMillis)
        assertEquals("Anna Maria Eriksson", record.holder)
        assertEquals("SPEC24681", record.documentNumber)
    }

    @Test
    fun `wrong details, and a turned off protocol, are told apart`() {
        val wrong = ReadRecord.of(
            listOf(connected(), NfcEvent.authenticationFailure(at(50), "BAC", "MRZ key mismatch")),
            passportData
        )!!
        val refused = ReadRecord.of(
            listOf(connected(), NfcEvent.authenticationFailure(at(50), "BAC", "BAC is disabled on this chip")),
            passportData
        )!!

        assertEquals(ReadOutcome.WRONG_DETAILS, wrong.outcome)
        assertEquals("MRZ key mismatch", wrong.failureReason)
        assertEquals(ReadOutcome.UNSUPPORTED_PROTOCOL, refused.outcome)
        assertFalse(refused.outcome.succeeded)
    }

    @Test
    fun `a success after a failure clears it`() {
        val events = listOf(
            connected(),
            NfcEvent.authenticationFailure(at(50), "PACE", "PACE is disabled on this chip"),
            NfcEvent.authenticationSuccess(at(100), "BAC"),
            reading("EF.DG1", 200)
        )

        val record = ReadRecord.of(events, passportData)!!

        assertEquals(ReadOutcome.PARTIAL, record.outcome)
        assertEquals("BAC", record.accessProtocol)
        assertNull(record.failureReason)
    }

    @Test
    fun `a reader that leaves before reading the MRZ interrupted the read`() {
        val record = ReadRecord.of(listOf(connected(), NfcEvent.authenticationSuccess(at(100), "BAC")), passportData)!!

        assertEquals(ReadOutcome.INTERRUPTED, record.outcome)
    }

    @Test
    fun `the recorder splits the stream into sessions`() {
        val recorder = ReadSessionRecorder()

        assertNull(recorder.onEvent(reading("EF.DG1", 0), passportData), "nothing before a reader connects")
        assertFalse(recorder.inSession)
        assertNull(recorder.onEvent(connected(10), passportData))
        assertNull(recorder.onEvent(NfcEvent.authenticationSuccess(at(20), "BAC"), passportData))
        assertTrue(recorder.inSession)

        // A new reader cuts the first session short
        val first = recorder.onEvent(connected(30), passportData)
        assertNotNull(first)
        assertEquals(at(10), first.startedAt)

        recorder.onEvent(NfcEvent.authenticationSuccess(at(40), "BAC"), passportData)
        recorder.onEvent(reading("EF.DG1", 50), passportData)
        val second = recorder.onEvent(NfcEvent.connectionLost(at(60)), passportData)
        assertNotNull(second)
        assertEquals(listOf("EF.DG1"), second.filesRead)
        assertEquals(at(60), second.endedAt)
        assertFalse(recorder.inSession)
        assertNull(recorder.finish(passportData))
    }
}
