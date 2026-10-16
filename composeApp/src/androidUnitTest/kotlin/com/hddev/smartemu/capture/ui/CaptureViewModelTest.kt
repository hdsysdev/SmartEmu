package com.hddev.smartemu.capture.ui

import com.hddev.smartemu.capture.AccessCredentials
import com.hddev.smartemu.capture.AccessStop
import com.hddev.smartemu.capture.ApduTransport
import com.hddev.smartemu.capture.BacCommandExtractor
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.CredentialKind
import com.hddev.smartemu.capture.CredentialProblemKind
import com.hddev.smartemu.capture.TagLossTransport
import com.hddev.smartemu.capture.TagLostException
import com.hddev.smartemu.capture.auth.EmulatedChip
import com.hddev.smartemu.capture.auth.WatchingTransport
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PassportData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A storage that keeps nothing and remembers what it was asked. */
private class FakeStorage : CaptureStorage {
    val saved = mutableListOf<CaptureRecord>()
    val deleted = mutableListOf<CaptureRecord>()
    override fun save(record: CaptureRecord): File = File("unused").also { saved += record }
    override fun delete(record: CaptureRecord) { deleted += record }
    override fun savedCount() = saved.size
    override fun deleteAll() = saved.clear()
    override fun shareJson(record: CaptureRecord) { saved += record }
    override fun shareReport(record: CaptureRecord, text: String) = Unit
}

/** A connection over a transport the test supplies; counts how often it was closed. */
private class FakeConnection(private val transport: ApduTransport) : CaptureConnection {
    val closed = AtomicInteger()
    override fun open(): ApduTransport = transport
    override fun close() { closed.incrementAndGet() }
}

/** A connection whose connecting fails because the tag has already gone. */
private class GoneConnection : CaptureConnection {
    override fun open(): ApduTransport = throw TagLostException()
    override fun close() = Unit
}

/**
 * The capture screen's logic with a fake document and no Android: the state it moves through, and above all where the
 * typed key is closed. The run itself is the emulated chip's, over the same authenticated capture the phone uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    private lateinit var chip: EmulatedChip
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
    private val storage = FakeStorage()
    private val today = LocalDate(2026, 9, 30)

    // CAM then GM, no BAC; and BAC only
    private val pace2 = ChipProfiles.byId("de-passport-2023")
    private val bacOnly = ChipProfiles.byId("us-passport-2021")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        chip = EmulatedChip()
    }

    @After
    fun tearDown() {
        chip.close()
        Dispatchers.resetMain()
        executor.shutdownNow()
    }

    private fun viewModel(newRunner: () -> CaptureRunner = { CaptureRunner() }) = CaptureViewModel(
        store = storage,
        newRunner = newRunner,
        ioDispatcher = executor.asCoroutineDispatcher(),
        today = { today }
    )

    private fun awaitState(model: CaptureViewModel, what: String = "a state", predicate: (CaptureUiState) -> Boolean): CaptureUiState =
        runBlocking {
            try {
                withTimeout(30_000) { model.state.first(predicate) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                error("Timed out waiting for $what; the state is ${model.state.value}")
            }
        }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun CaptureViewModel.enterKey(credentials: AccessCredentials) {
        chooseKey(credentials.kind)
        startWithKey(credentials)
    }

    private fun mrz(data: PassportData) = chip.mrz(data)

    // ---- a read without a key stays what it was ----

    @Test
    fun `a read without a key keeps its flow and its bac-commands probe`() {
        val data = chip.documentFor(bacOnly)
        val model = viewModel()
        model.startWaiting()
        assertEquals(CaptureUiState.Waiting(), model.state.value)

        val connection = FakeConnection(chip.transport(data))
        model.onConnection(connection)
        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })

        assertNull(preview.record.authenticated)
        assertNotNull(preview.record.bacCommands, "the unauthenticated read still records its BAC commands")
        eventually("the connection closes") { connection.closed.get() == 1 }
    }

    // ---- a read with a key ----

    @Test
    fun `an MRZ read ends in a preview with the authenticated part, and the key is closed after the run`() {
        val data = chip.documentFor(pace2)
        val key = mrz(data)
        val model = viewModel()
        model.enterKey(key)
        assertEquals(CaptureUiState.Waiting(withKey = CredentialKind.MRZ), model.state.value)
        assertFalse(key.isClosed, "held while waiting for the document")

        val connection = FakeConnection(chip.transport(data))
        model.onConnection(connection)
        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })

        assertNotNull(preview.record.authenticated?.sod)
        eventually("the key is closed") { key.isClosed }
        eventually("the connection closes") { connection.closed.get() == 1 }
        assertEquals(AccessOutcome.OPENED, AuthenticatedSummary.outcome(preview.record.authenticated!!))
    }

    @Test
    fun `an authenticated read sends no wrong EXTERNAL AUTHENTICATE before the real attempt`() {
        val data = chip.documentFor(bacOnly)
        val watching = WatchingTransport(chip.transport(data))
        val model = viewModel()
        model.enterKey(mrz(data))
        model.onConnection(FakeConnection(watching))
        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })

        // Neither the extractor's nor tier-a's out-of-sequence EXTERNAL AUTHENTICATE is in this capture: the only one the
        // chip saw is the BAC attempt itself, which came with the right key
        assertEquals(1, watching.count(WatchingTransport.INS_EXTERNAL_AUTHENTICATE))
        assertNull(preview.record.bacCommands?.externalAuthenticate, "the deliberate wrong EXTERNAL AUTHENTICATE is not sent")
        assertNotNull(preview.record.bacCommands?.getChallenge, "the GET CHALLENGE probe still ran, before the key was tried")
        assertTrue(preview.record.probes.none { it.probeId == "external-authenticate-out-of-sequence" })
        assertTrue(preview.record.probes.any { it.probeId == "get-challenge-lengths" })
        assertTrue(authenticatedFlowExtractors().none { it.id == BacCommandExtractor.id })
        assertEquals(defaultExtractorsWithout(BacCommandExtractor.id), authenticatedFlowExtractors().map { it.id })
    }

    private fun defaultExtractorsWithout(id: String) = com.hddev.smartemu.capture.defaultExtractors().map { it.id }.filter { it != id }

    @Test
    fun `a refused key is a preview with the refusal, and the key is closed`() {
        val data = chip.documentFor(pace2)
        val wrong = com.hddev.smartemu.capture.auth.mrzWithDigits(data.passportNumber, data.mrzDateOfBirth(), "341231")
        val model = viewModel()
        model.enterKey(wrong)
        model.onConnection(FakeConnection(chip.transport(data)))

        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })
        val auth = assertNotNull(preview.record.authenticated)
        assertEquals(AccessStop.KEY_REJECTED, auth.accessStopped)
        assertEquals(AccessOutcome.KEY_REFUSED, AuthenticatedSummary.outcome(auth))
        eventually("the key is closed") { wrong.isClosed }
    }

    @Test
    fun `an invalid key stays on the key screen with the problems, sends nothing, and is closed`() {
        val bad = AccessCredentials.Mrz.of("L898902C3", "741332", "340415", '6', '0', com.hddev.smartemu.capture.MrzCheck.checkDigit("340415"))
        val model = viewModel()
        model.chooseKey(CredentialKind.MRZ)
        model.startWithKey(bad)

        val state = assertIs<CaptureUiState.KeyEntry>(model.state.value)
        assertTrue(state.problems.any { it.kind == CredentialProblemKind.NOT_A_DATE })
        assertTrue(bad.isClosed)
        // No document is waited for, so a tag is not read
        val connection = FakeConnection(TagLossTransport(chip.transport(chip.documentFor(pace2)), 1))
        model.onConnection(connection)
        assertEquals(0, connection.closed.get())
        assertIs<CaptureUiState.KeyEntry>(model.state.value)
    }

    @Test
    fun `a key of the wrong kind or from the wrong screen is closed and ignored`() {
        val model = viewModel()
        val straySecret = AccessCredentials.Can.of("314159")
        model.startWithKey(straySecret) // still on the intro
        assertTrue(straySecret.isClosed)
        assertEquals(CaptureUiState.Intro, model.state.value)

        model.chooseKey(CredentialKind.MRZ)
        val wrongKind = AccessCredentials.Can.of("314159")
        model.startWithKey(wrongKind)
        assertTrue(wrongKind.isClosed)
        assertEquals(CaptureUiState.KeyEntry(CredentialKind.MRZ), model.state.value)
    }

    // ---- tag lost ----

    @Test
    fun `a lost tag keeps the key for the retry on the waiting screen, and the retry reads it through`() {
        val data = chip.documentFor(pace2)
        val phase1 = WatchingTransport(chip.transport(data))
            .also { CaptureRunner(extractors = authenticatedFlowExtractors()).run(it) }.commands.size
        val key = mrz(data)
        val model = viewModel()
        model.enterKey(key)

        val first = FakeConnection(TagLossTransport(chip.transport(data), loseAt = phase1 + 6))
        model.onConnection(first)
        assertEquals(CaptureUiState.Waiting(retry = true, withKey = CredentialKind.MRZ), awaitState(model) { it is CaptureUiState.Waiting && it.retry })
        eventually("the first connection closes") { first.closed.get() == 1 }
        assertFalse(key.isClosed, "no need to type the key again for a tag lost")

        val second = FakeConnection(chip.transport(data))
        model.onConnection(second)
        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })
        assertNotNull(preview.record.authenticated?.sod)
        eventually("the key is closed") { key.isClosed }
    }

    @Test
    fun `a tag that keeps getting lost ends in a failure and closes the key`() {
        val data = chip.documentFor(pace2)
        val key = mrz(data)
        val model = viewModel()
        model.enterKey(key)
        repeat(3) {
            model.onConnection(FakeConnection(TagLossTransport(chip.transport(data), 1)))
            awaitState(model) { it is CaptureUiState.Waiting && it.retry || it is CaptureUiState.Failed }
            if (it < 2) eventually("waiting again") { model.state.value is CaptureUiState.Waiting }
        }
        val failed = assertIs<CaptureUiState.Failed>(model.state.value)
        assertEquals(CaptureFailure.TAG_LOST, failed.failure)
        eventually("the key is closed") { key.isClosed }
    }

    @Test
    fun `a tag gone before connecting is a lost tag too`() {
        val key = com.hddev.smartemu.capture.auth.mrzWithDigits("L898902C3", "740812", "340415")
        val model = viewModel()
        model.enterKey(key)
        model.onConnection(GoneConnection())
        awaitState(model) { it is CaptureUiState.Waiting && it.retry }
        assertFalse(key.isClosed)
        model.cancel()
        assertTrue(key.isClosed)
    }

    // ---- every other way out closes the key ----

    @Test
    fun `stopping while waiting closes the key and goes back to the intro`() {
        val key = AccessCredentials.Can.of("314159")
        val model = viewModel()
        model.enterKey(key)
        model.cancel()
        assertTrue(key.isClosed)
        assertEquals(CaptureUiState.Intro, model.state.value)
    }

    @Test
    fun `leaving the screen while a key is held closes it and goes back to the intro`() {
        val key = com.hddev.smartemu.capture.auth.mrzWithDigits("L898902C3", "740812", "340415")
        val model = viewModel()
        model.enterKey(key)
        model.onScreenLeft()
        assertTrue(key.isClosed)
        assertEquals(CaptureUiState.Intro, model.state.value)
    }

    @Test
    fun `leaving the screen while typing the key goes back to the intro`() {
        val model = viewModel()
        model.chooseKey(CredentialKind.CAN)
        model.onScreenLeft()
        assertEquals(CaptureUiState.Intro, model.state.value)
    }

    @Test
    fun `leaving the screen on a finished capture leaves it alone`() {
        val data = chip.documentFor(pace2)
        val model = viewModel()
        model.enterKey(mrz(data))
        model.onConnection(FakeConnection(chip.transport(data)))
        awaitState(model) { it is CaptureUiState.Preview }
        model.onScreenLeft()
        assertIs<CaptureUiState.Preview>(model.state.value)
    }

    @Test
    fun `a read without a key is not stopped by leaving the screen`() {
        val model = viewModel()
        model.startWaiting()
        model.onScreenLeft()
        assertEquals(CaptureUiState.Waiting(), model.state.value)
    }

    @Test
    fun `a failure after the key was used closes it`() {
        val key = com.hddev.smartemu.capture.auth.mrzWithDigits("L898902C3", "740812", "340415")
        val model = viewModel()
        model.enterKey(key)
        // An unexpected exception out of the transport, not a lost tag
        val broken = object : ApduTransport {
            override fun transmit(command: ByteArray): ByteArray = throw IllegalStateException("boom")
            override fun readerDevice() = com.hddev.smartemu.capture.testReader
        }
        model.onConnection(FakeConnection(broken))
        val failed = assertIs<CaptureUiState.Failed>(awaitState(model) { it is CaptureUiState.Failed })
        assertEquals(CaptureFailure.UNEXPECTED, failed.failure)
        eventually("the key is closed") { key.isClosed }
    }

    @Test
    fun `clearing the model closes the key`() {
        val key = AccessCredentials.Can.of("314159")
        val model = viewModel()
        model.enterKey(key)
        model.release()
        assertTrue(key.isClosed)
    }

    // ---- CAN ----

    private val canData get() = chip.documentFor(pace2).copy(can = "314159")

    @Test
    fun `a CAN waits for the person's yes before anything is sent with it`() {
        val data = canData
        val watching = WatchingTransport(chip.transport(data))
        val key = AccessCredentials.Can.of("314159")
        val model = viewModel()
        model.enterKey(key)
        model.onConnection(FakeConnection(watching))

        val reading = assertIs<CaptureUiState.Reading>(awaitState(model, "the CAN question") { it is CaptureUiState.Reading && it.canPrompt != null })
        assertEquals(CanPrompt(interruptedBefore = false, previouslyFailed = false), reading.canPrompt)
        assertEquals(0, watching.count(WatchingTransport.INS_MSE), "nothing is sent with the CAN while the question is open")

        model.answerCanAttempt(true)
        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })
        assertNotNull(preview.record.authenticated?.sod)
        assertEquals(CredentialKind.CAN, preview.record.authenticated?.credential)
        eventually("the key is closed") { key.isClosed }
    }

    @Test
    fun `declining the CAN sends nothing with it and says so`() {
        val data = canData
        val watching = WatchingTransport(chip.transport(data))
        val key = AccessCredentials.Can.of("314159")
        val model = viewModel()
        model.enterKey(key)
        model.onConnection(FakeConnection(watching))
        awaitState(model) { it is CaptureUiState.Reading && it.canPrompt != null }

        model.answerCanAttempt(false)
        val failed = assertIs<CaptureUiState.Failed>(awaitState(model) { it is CaptureUiState.Failed })
        assertEquals(CaptureFailure.CAN_DECLINED, failed.failure)
        assertNotNull(failed.partial, "the public part ran before the question")
        assertNull(failed.partial?.authenticated)
        assertEquals(0, watching.count(WatchingTransport.INS_MSE))
        eventually("the key is closed") { key.isClosed }
    }

    @Test
    fun `stopping while the CAN question is open lets the run go with a no, and closes the key`() {
        val data = canData
        val watching = WatchingTransport(chip.transport(data))
        val key = AccessCredentials.Can.of("314159")
        val model = viewModel()
        model.enterKey(key)
        val connection = FakeConnection(watching)
        model.onConnection(connection)
        awaitState(model) { it is CaptureUiState.Reading && it.canPrompt != null }

        model.cancel()
        assertEquals(CaptureUiState.Intro, model.state.value)
        assertTrue(key.isClosed)
        eventually("the run's thread is released and the connection closed") { connection.closed.get() >= 1 }
        Thread.sleep(200)
        assertEquals(0, watching.count(WatchingTransport.INS_MSE), "the CAN was never sent")
        assertEquals(CaptureUiState.Intro, model.state.value, "the stopped run didn't change the screen")
    }

    @Test
    fun `an answer with no question open does nothing`() {
        val model = viewModel()
        model.answerCanAttempt(true)
        assertEquals(CaptureUiState.Intro, model.state.value)
    }

    @Test
    fun `a CAN tried and cut short is not repeated by itself, the person is told it may have counted and must confirm again`() {
        val data = canData
        val phase1 = WatchingTransport(chip.transport(data))
            .also { CaptureRunner(extractors = authenticatedFlowExtractors()).run(it) }.commands.size
        val key = AccessCredentials.Can.of("314159")
        val model = viewModel()
        model.enterKey(key)

        // The tag goes after the CAN was confirmed, mid PACE
        model.onConnection(FakeConnection(TagLossTransport(chip.transport(data), loseAt = phase1 + 6)))
        awaitState(model) { it is CaptureUiState.Reading && it.canPrompt != null }
        model.answerCanAttempt(true)
        val waiting = assertIs<CaptureUiState.Waiting>(awaitState(model) { it is CaptureUiState.Waiting && it.retry })
        assertTrue(waiting.canMayHaveCounted, "the screen says the attempt may have counted")
        assertFalse(key.isClosed)

        val watching = WatchingTransport(chip.transport(data))
        model.onConnection(FakeConnection(watching))
        val again = assertIs<CaptureUiState.Reading>(awaitState(model) { it is CaptureUiState.Reading && it.canPrompt != null })
        assertEquals(CanPrompt(interruptedBefore = true), again.canPrompt)
        assertTrue(again.canPrompt!!.requiresExtraConfirmation)
        assertEquals(0, watching.count(WatchingTransport.INS_MSE), "nothing is sent with the CAN while the question is open")

        // A plain yes is not enough: the explicit confirmation is needed, and the question stays open
        model.answerCanAttempt(true)
        assertEquals(again.canPrompt, (model.state.value as CaptureUiState.Reading).canPrompt)
        assertEquals(0, watching.count(WatchingTransport.INS_MSE))
        model.answerCanAttempt(false)
        awaitState(model) { it is CaptureUiState.Failed }
        assertEquals(0, watching.count(WatchingTransport.INS_MSE))
        eventually("the key is closed") { key.isClosed }
    }

    @Test
    fun `a CAN after a lost tag is tried once the person confirms explicitly`() {
        val data = canData
        val phase1 = WatchingTransport(chip.transport(data))
            .also { CaptureRunner(extractors = authenticatedFlowExtractors()).run(it) }.commands.size
        val key = AccessCredentials.Can.of("314159")
        val model = viewModel()
        model.enterKey(key)
        model.onConnection(FakeConnection(TagLossTransport(chip.transport(data), loseAt = phase1 + 6)))
        awaitState(model) { it is CaptureUiState.Reading && it.canPrompt != null }
        model.answerCanAttempt(true)
        awaitState(model) { it is CaptureUiState.Waiting && it.retry }

        model.onConnection(FakeConnection(chip.transport(data)))
        awaitState(model) { it is CaptureUiState.Reading && it.canPrompt != null }
        model.answerCanAttempt(true, understandsRisk = true)
        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })
        assertNotNull(preview.record.authenticated?.sod)
    }

    @Test
    fun `a failed CAN is remembered for the session, and the next CAN needs the extra confirmation`() {
        val data = canData
        val model = viewModel()
        // A wrong CAN: one failed try
        model.enterKey(AccessCredentials.Can.of("271828"))
        model.onConnection(FakeConnection(chip.transport(data)))
        val first = assertIs<CaptureUiState.Reading>(awaitState(model) { it is CaptureUiState.Reading && it.canPrompt != null })
        assertEquals(CanPrompt(interruptedBefore = false, previouslyFailed = false), first.canPrompt)
        model.answerCanAttempt(true)
        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })
        assertEquals(AccessStop.CAN_SINGLE_ATTEMPT_FAILED, preview.record.authenticated?.accessStopped)
        model.discard()

        // The same document again, with the right CAN: allowed, but not on a plain yes
        val key = AccessCredentials.Can.of("314159")
        model.enterKey(key)
        val watching = WatchingTransport(chip.transport(data))
        model.onConnection(FakeConnection(watching))
        val second = assertIs<CaptureUiState.Reading>(awaitState(model) { it is CaptureUiState.Reading && it.canPrompt != null })
        assertEquals(CanPrompt(interruptedBefore = false, previouslyFailed = true), second.canPrompt)
        model.answerCanAttempt(true)
        assertNotNull((model.state.value as CaptureUiState.Reading).canPrompt, "a plain yes leaves the question open")
        assertEquals(0, watching.count(WatchingTransport.INS_MSE))
        model.answerCanAttempt(true, understandsRisk = true)
        val done = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })
        assertNotNull(done.record.authenticated?.sod)
        eventually("the key is closed") { key.isClosed }
    }

    // ---- a different document after a lost tag ----

    @Test
    fun `a read without a key that meets another document after a lost tag starts again and says so`() {
        val first = chip.documentFor(pace2)
        val other = chip.documentFor(bacOnly)
        val model = viewModel()
        model.startWaiting()
        // Lose the tag well into the probes of the first document
        model.onConnection(FakeConnection(TagLossTransport(chip.transport(first), loseAt = 80)))
        awaitState(model) { it is CaptureUiState.Waiting && it.retry }
        assertNull(model.notice.value)

        model.onConnection(FakeConnection(chip.transport(other)))
        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })
        assertEquals(CaptureNotice.DIFFERENT_DOCUMENT, model.notice.value)
        assertNull(preview.record.aborted)
        // A whole new capture of the second document: every probe is in it, none carried over from the first
        assertEquals(com.hddev.smartemu.capture.ProbePack.tierA.probes.map { it.id }, preview.record.probes.map { it.probeId })

        model.cancel()
        assertNull(model.notice.value, "the notice goes with the capture")
    }

    @Test
    fun `a key held for one document is not tried on another after a lost tag`() {
        val first = chip.documentFor(pace2)
        val other = chip.documentFor(bacOnly)
        val key = mrz(first)
        val model = viewModel()
        model.enterKey(key)
        model.onConnection(FakeConnection(TagLossTransport(chip.transport(first), loseAt = 60)))
        awaitState(model) { it is CaptureUiState.Waiting && it.retry }
        assertFalse(key.isClosed)

        val watching = WatchingTransport(chip.transport(other))
        model.onConnection(FakeConnection(watching))
        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })
        assertEquals(CaptureNotice.DIFFERENT_DOCUMENT, model.notice.value)
        assertNull(preview.record.authenticated, "nothing was tried with the key")
        assertEquals(0, watching.count(WatchingTransport.INS_MSE))
        assertEquals(0, watching.count(WatchingTransport.INS_EXTERNAL_AUTHENTICATE))
        eventually("the key is closed") { key.isClosed }
    }

    // ---- the preview's actions ----

    @Test
    fun `discarding a preview deletes it and goes to the intro`() {
        val data = chip.documentFor(pace2)
        val model = viewModel()
        model.enterKey(mrz(data))
        model.onConnection(FakeConnection(chip.transport(data)))
        val preview = assertIs<CaptureUiState.Preview>(awaitState(model) { it is CaptureUiState.Preview })
        model.discard()
        assertEquals(listOf(preview.record), storage.deleted)
        assertEquals(CaptureUiState.Intro, model.state.value)
    }
}
