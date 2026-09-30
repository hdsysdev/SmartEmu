package com.hddev.smartemu.ui.guided

import com.hddev.smartemu.data.NfcEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReadProgressTest {

    private val start = Instant.fromEpochSeconds(1_700_000_000)
    private var clock = start

    /** Each event a second after the one before, as the HCE service emits them. */
    private fun next(): Instant = clock.also { clock = Instant.fromEpochSeconds(it.epochSeconds + 1) }

    private fun connected() = NfcEvent.connectionEstablished(next(), "NFC reader connected")
    private fun reading(file: String) = NfcEvent.connectionEstablished(next(), "Reading $file")
    private fun unlocked() = NfcEvent.authenticationSuccess(next(), "PACE-GM")
    private fun failed(reason: String) = NfcEvent.authenticationFailure(next(), "BAC", reason)
    private fun lost() = NfcEvent.connectionLost(next(), "Link loss")

    @Test
    fun waitsUntilAReaderConnects() {
        val progress = ReadProgress.of(emptyList(), since = start)

        assertFalse(progress.connected)
        assertEquals(StepState.PENDING, progress.unlockState())
        assertNull(progress.problem)
    }

    @Test
    fun aConnectedReaderIsUnlocking() {
        val progress = ReadProgress.of(listOf(connected()), since = start)

        assertTrue(progress.connected)
        assertEquals(StepState.ACTIVE, progress.unlockState())
        assertEquals(StepState.PENDING, progress.partState(PassportPart.DETAILS))
    }

    @Test
    fun thePartBeingReadIsActiveAndEarlierOnesDone() {
        val events = listOf(connected(), unlocked(), reading("EF.COM"), reading("EF.DG1"), reading("EF.DG2"))

        val progress = ReadProgress.of(events, since = start)

        assertEquals(StepState.DONE, progress.unlockState())
        assertEquals(StepState.DONE, progress.partState(PassportPart.DETAILS))
        assertEquals(StepState.ACTIVE, progress.partState(PassportPart.PHOTO))
        assertEquals(StepState.PENDING, progress.partState(PassportPart.SECURITY))
        assertFalse(progress.finished)
    }

    @Test
    fun partingAfterTheDetailsFinishes() {
        val events = listOf(connected(), unlocked(), reading("EF.DG1"), reading("EF.SOD"), lost())

        val progress = ReadProgress.of(events, since = start)

        assertTrue(progress.finished)
        assertNull(progress.problem)
        assertEquals(StepState.DONE, progress.partState(PassportPart.SECURITY))
    }

    @Test
    fun goingQuietAfterEveryPartFinishes() {
        val events = listOf(connected(), unlocked(), reading("EF.SOD"), reading("EF.DG1"), reading("EF.DG2"))

        assertFalse(ReadProgress.of(events, since = start, idle = false).finished)
        assertTrue(ReadProgress.of(events, since = start, idle = true).finished)
    }

    @Test
    fun goingQuietWithPartsUnreadDoesNotFinish() {
        val events = listOf(connected(), unlocked(), reading("EF.DG1"))

        assertFalse(ReadProgress.of(events, since = start, idle = true).finished)
    }

    @Test
    fun partingBeforeTheDetailsIsAnInterruption() {
        val events = listOf(connected(), unlocked(), reading("EF.COM"), lost())

        val progress = ReadProgress.of(events, since = start)

        assertFalse(progress.finished)
        assertEquals(ReadProblem.INTERRUPTED, progress.problem)
    }

    @Test
    fun aFailedUnlockMeansTheDetailsDontMatch() {
        val events = listOf(connected(), failed("Authentication token mismatch"))

        assertEquals(ReadProblem.WRONG_DETAILS, ReadProgress.of(events, since = start).problem)
    }

    @Test
    fun aRefusedProtocolMeansTheTypeIsUnsupported() {
        val events = listOf(connected(), failed("BAC disabled; chip requires PACE"))

        assertEquals(ReadProblem.UNSUPPORTED_TYPE, ReadProgress.of(events, since = start).problem)
    }

    @Test
    fun aSuccessAfterAFailureClearsIt() {
        val events = listOf(connected(), failed("Authentication token mismatch"), unlocked())

        val progress = ReadProgress.of(events, since = start)

        assertNull(progress.problem)
        assertTrue(progress.unlocked)
    }

    @Test
    fun onlyTheLastSessionCounts() {
        val events = listOf(connected(), unlocked(), reading("EF.COM"), lost(), connected())

        val progress = ReadProgress.of(events, since = start)

        assertTrue(progress.connected)
        assertFalse(progress.unlocked)
        assertNull(progress.problem)
    }

    @Test
    fun eventsBeforeThisReadStartedAreIgnored() {
        val earlier = listOf(connected(), unlocked(), reading("EF.DG1"), lost())
        val since = next()

        val progress = ReadProgress.of(earlier, since = since)

        assertFalse(progress.connected)
        assertFalse(progress.finished)
    }
}
