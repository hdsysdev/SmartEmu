package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.HceCardService
import com.hddev.smartemu.capture.EmulatorTransport
import com.hddev.smartemu.capture.Hex
import com.hddev.smartemu.capture.ReaderDevice
import com.hddev.smartemu.capture.hex
import com.hddev.smartemu.capture.hexBytes
import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.chipConfiguration
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.capture.ProbePacks
import com.hddev.smartemu.capture.ProbeSetup
import com.hddev.smartemu.data.withChipProfile
import com.hddev.smartemu.data.ChipProfiles
import kotlinx.datetime.LocalDate
import org.jmrtd.PassportService
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The emulator configured from a measured profile, against a made-up chip ([FakeChip]) that differs from the emulator's
 * own behaviour in every way replay covers. The captures are of the fake, not of a real chip: this proves the replay
 * machinery, and nothing about any real passport.
 */
class ReplayEmulatorTest {
    private val today = LocalDate(2026, 10, 1)
    private val fake = FakeChip()
    private val cardAccess = FakeChip.defaultCardAccess()

    @AfterTest
    fun tearDown() = MeasuredProfileRegistry.clear()

    /** Two captures of the same made-up chip from two phones, merged. */
    private fun profileOf(id: String = "synthetic-fake", chip: (ReaderDevice) -> FakeChip = { FakeChip(device = it) }): MeasuredProfile =
        ProfileMerger.merge(listOf(captureOf(chip(readerNamed("Phone A"))), captureOf(chip(readerNamed("Phone B")))), id = id)

    private fun send(emulator: MeasuredEmulator, vararg commands: String): List<String> =
        commands.map { emulator.transport().transmit(hexBytes(it)).hex() }

    @Test
    fun `the fake chip is captured completely and is nothing like the emulator's defaults`() {
        val record = captureOf(fake)
        assertEquals(null, record.aborted)
        assertEquals(cardAccess.size, record.files.cardAccess!!.contentsHex!!.length / 2)
        assertEquals("7F66080202080002020800", record.files.atrInfo!!.contentsHex)
        assertEquals(0x6982, record.bacCommands!!.externalAuthenticate)
    }

    @Test
    fun `the emulator configured from the profile is indistinguishable on every compared field`() {
        val profile = profileOf()
        val report = MeasuredEmulator.with(profile) { FidelityDiff.run(profile, it.transport(), today = today) }
        assertTrue(report.isClean, report.toTable())
        // Nothing is vacuous: the diff compared real fields, and listed what it can't compare with the reason
        assertTrue(report.matches.size > 100, report.toTable())
        assertTrue(report.entries.any { it.outcome == DiffOutcome.NOT_COMPARABLE && it.note == NotComparableReasons.TIMING })
        assertTrue(report.entries.any { it.outcome == DiffOutcome.NOT_COMPARABLE && it.note == NotComparableReasons.LINK })
        println(report.toTable())
    }

    @Test
    fun `a reader reads EF CardAccess byte for byte as it was captured`() {
        val profile = profileOf()
        assertEquals(Hex.encode(cardAccess), profile.observations.cardAccessHex)
        MeasuredEmulator.with(profile) { emulator ->
            val service = HceCardService.openPassportService(emulator.service)
            val read = service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()
            assertContentEquals(cardAccess, read)
        }
    }

    @Test
    fun `the max read length of the profile is the emulator's limit for a plain read`() {
        val profile = profileOf()
        assertEquals(255, profile.observations.maxReadLength)
        MeasuredEmulator.with(profile) { emulator ->
            val answers = send(emulator, "00A4000C023F00", "00A4020C02011C", "00B00000FF", "00B0000000", "00B00000DF")
            assertEquals(255 * 2 + 4, answers[2].length, "Le FF is answered in full: 255 bytes and 9000")
            assertEquals(255 * 2 + 4, answers[3].length, "Le 00 asks for 256 and is capped at the measured limit")
            assertEquals(223 * 2 + 4, answers[4].length)
        }
    }

    @Test
    fun `without measured data the limit is the emulator's own 223`() {
        PassportHceService.setSharedPassportData(MeasuredEmulator.sampleDocument.withChipProfile(ChipProfiles.byId("de-passport-2017")))
        val service = PassportHceService().also { it.onCreate() }
        try {
            val transport = EmulatorTransport(service)
            transport.transmit(hexBytes("00A4000C023F00"))
            transport.transmit(hexBytes("00A4020C02011C"))
            val answer = transport.transmit(hexBytes("00B00000FF"))
            assertTrue(answer.size <= 223 + 2)
            // B1 is not offered: it is answered as the unknown instruction it is
            assertEquals("6D00", transport.transmit(hexBytes("00B10000045402000004")).hex())
        } finally {
            PassportHceService.setSharedPassportData(null)
            service.onDestroy()
        }
    }

    @Test
    fun `READ BINARY with the odd INS B1 is answered only by a chip measured to support it`() {
        MeasuredEmulator.with(profileOf()) { emulator ->
            val answers = send(emulator, "00A4000C023F00", "00B10000045402000004", "00A4020C02011C", "00B10000045402000004", "00B10000045402001002")
            assertEquals("6986", answers[1], "no file selected")
            val expected = Hex.encode(byteArrayOf(0x53, 0x04) + cardAccess.copyOfRange(0, 4)) + "9000"
            assertEquals(expected, answers[3])
            assertEquals("5302" + Hex.encode(cardAccess.copyOfRange(16, 18)) + "9000", answers[4])
        }
        val without = profileOf { FakeChip(device = it, supportsOddRead = false) }
        assertEquals(0x6D00, without.observations.statusWords["read-binary-order/odd-ins-card-access"])
        MeasuredEmulator.with(without) { emulator ->
            assertEquals(listOf("9000", "9000", "6D00"), send(emulator, "00A4000C023F00", "00A4020C02011C", "00B10000045402000004"))
        }
    }

    @Test
    fun `per-command status words are replayed and outOfSequence stays the default`() {
        val profile = profileOf { FakeChip(device = it, outOfSequenceStatusWord = 0x6986) }
        MeasuredEmulator.with(profile) { emulator ->
            val zeros = "00".repeat(40)
            val config = PassportHceService.getSharedPassportData()!!.chipConfiguration()
            assertEquals(0x6986, config.errorResponses.outOfSequence)
            assertEquals(0x6986, config.errorResponses.byProbe["external-authenticate-out-of-sequence/lc-40-le-28"])
            val answers = send(
                emulator, "00A4000C023F00", "00A4040C07A0000002471001", "0082000028${zeros}28", "00A4040C07A0000002471001", "0082000008${"00".repeat(8)}28",
                "00A4040C07A0000002471001", "0084000004", "0084000009", "00A4040C07A0000002471001", "0084000000"
            )
            assertEquals("6986", answers[2])
            assertEquals("6700", answers[4])
            assertEquals("6C08", answers[6], "GET CHALLENGE Le 4")
            assertEquals("6700", answers[7], "GET CHALLENGE Le 9")
            assertEquals(8 * 2 + 4, answers[9].length, "GET CHALLENGE Le 0 is answered with a challenge, as the fake does")
        }
    }

    @Test
    fun `SELECT variants are answered as measured, including a partial AID the emulator would refuse`() {
        MeasuredEmulator.with(profileOf()) { emulator ->
            val answers = send(
                emulator, "00A4000C023F00", "00A4040C05A000000247", "00A4000C023F00", "00A4040C07A0000002471002", "00A4000C023F00",
                "00A4040C08A0000002471001", "00A4000C023F00", "00A4040007A0000002471001"
            )
            assertEquals("9000", answers[1], "a partial AID is accepted, as measured")
            assertEquals("6A82", answers[3])
            assertEquals("6700", answers[5])
            assertEquals("6F098407A00000024710019000", answers[7])
        }
    }

    @Test
    fun `an extended-length Le is understood by a measured chip`() {
        MeasuredEmulator.with(profileOf()) { emulator ->
            val answers = send(emulator, "00A4000C023F00", "00A4020C02011C", "00B00000000100", "00B00000000010")
            assertEquals(255 * 2 + 4, answers[2].length)
            assertEquals(16 * 2 + 4, answers[3].length, "an extended Le of 16 asks for 16 bytes, not 256")
        }
    }

    @Test
    fun `EF ATR INFO and EF DIR are served from the captured bytes`() {
        MeasuredEmulator.with(profileOf()) { emulator ->
            val answers = send(emulator, "00A4000C023F00", "00A4020C022F01", "00B000000B", "00A4020C022F00", "00B000000B")
            assertEquals(listOf("9000", "9000"), listOf(answers[1], answers[3]))
            assertEquals("7F660802020800020208009000", answers[2])
            assertEquals("61094F07A00000024710019000", answers[4])
        }
    }

    @Test
    fun `without those files measured, SELECT of EF ATR INFO is still 6A82`() {
        val profile = profileOf { FakeChip(device = it, atrInfo = null, dir = null) }
        assertEquals(0x6A82, profile.observations.fileSelectStatusWords["atrInfo"])
        MeasuredEmulator.with(profile) { emulator ->
            assertEquals(listOf("9000", "6A82", "6A82"), send(emulator, "00A4000C023F00", "00A4020C022F01", "00A4020C022F00"))
        }
    }

    @Test
    fun `a profile measured without EF CardAccess drops it and access control follows`() {
        val profile = profileOf("synthetic-bac-only") { FakeChip(device = it, cardAccess = null) }
        assertEquals(null, profile.observations.cardAccessHex)
        assertEquals(0x6A82, profile.observations.fileSelectStatusWords["cardAccess"])
        MeasuredEmulator.with(profile) { emulator ->
            assertEquals(AccessControl.BAC_ONLY, emulator.profile.accessControl)
            assertEquals(listOf("9000", "6A82"), send(emulator, "00A4000C023F00", "00A4020C02011C"))
            val report = FidelityDiff.run(profile, emulator.transport(), today = today)
            assertTrue(report.isClean, report.toTable())
        }
    }

    @Test
    fun `a measured 6282 with data replays nothing and the file is not emptied`() {
        // The chip answered a READ BINARY with "end of file reached, here are the bytes": a bare 6282 would be no file
        val key = "read-binary-max-length/le-ff"
        val base = profileOf()
        val profile = base.copy(observations = base.observations.copy(statusWords = base.observations.statusWords + (key to 0x6282)))
        MeasuredEmulator.with(profile) { emulator ->
            val answers = send(emulator, "00A4000C023F00", "00A4020C02011C", "00B00000FF")
            assertTrue(answers[2].endsWith("9000") && answers[2].length > 4, "the emulator's own answer, with the data: ${answers[2].take(20)}")
        }
        // An error word is still replayed as a bare status
        val refused = base.copy(observations = base.observations.copy(statusWords = base.observations.statusWords + (key to 0x6A86)))
        MeasuredEmulator.with(refused) { emulator ->
            assertEquals("6A86", send(emulator, "00A4000C023F00", "00A4020C02011C", "00B00000FF")[2])
        }
    }

    @Test
    fun `only error status words are replayed as a bare status`() {
        listOf(0x6700, 0x6882, 0x6985, 0x6A82, 0x6B00, 0x6C08, 0x6D00, 0x6E00, 0x6F00).forEach { assertTrue(ProbeReplay.isError(it), it.toString(16)) }
        listOf(0x6100, 0x6282, 0x6300, 0x63C2, 0x9000, 0x9100).forEach { assertTrue(!ProbeReplay.isError(it), it.toString(16)) }
        val key = "read-binary-max-length/le-ff"
        val warning = ProbeReplay(mapOf(key to 0x6282))
        val read = hexBytes("00B00000FF")
        assertEquals(ProbeReplay.Decision.Own, warning.decide(read, ChipState.CARD_ACCESS_SELECTED))
        assertEquals(ProbeReplay.Decision.Status(0x6A86), ProbeReplay(mapOf(key to 0x6A86)).decide(read, ChipState.CARD_ACCESS_SELECTED))
    }

    @Test
    fun `the replay forgets the last session's SELECTs on reset and between RF sessions`() {
        // Two steps that send the same SELECT from different states: the repeats of one find the chip elsewhere than its
        // setup left it, so the replay remembers which step matched last, and must forget it for the next reader
        val steps = ProbePacks.all.flatMap { pack ->
            pack.probes.flatMap { probe ->
                probe.steps.filter { it.preambleHex.isEmpty() && it.commandHex.uppercase().startsWith("00A4") }.map { Triple("${probe.id}/${it.id}", it.commandHex, it.setup) }
            }
        }
        val group = steps.groupBy { it.second.uppercase() }.values.first { g -> g.map { it.third }.distinct().size >= 2 && g.map { it.first }.distinct().size >= 2 }
        val (firstKey, commandHex, firstSetup) = group[0]
        val second = group.first { it.third != firstSetup }
        fun stateOf(setup: ProbeSetup) = when (setup) {
            ProbeSetup.MASTER_FILE -> ChipState.MASTER_FILE
            ProbeSetup.EMRTD_APPLICATION -> ChipState.EMRTD_APPLICATION
            ProbeSetup.CARD_ACCESS_SELECTED -> ChipState.CARD_ACCESS_SELECTED
            ProbeSetup.NONE -> ChipState.OTHER
        }
        val command = hexBytes(commandHex)
        val replay = ProbeReplay(mapOf(firstKey to 0x6A82, second.first to 0x6985))
        val fresh = replay.decide(command, ChipState.OTHER)
        assertEquals(ProbeReplay.Decision.Status(0x6A82), fresh, "no step matched yet: the first step with the command answers")
        // The command matches the second step in its own state, which the replay remembers
        assertEquals(ProbeReplay.Decision.Status(0x6985), replay.decide(command, stateOf(second.third)))
        assertEquals(ProbeReplay.Decision.Status(0x6985), replay.decide(command, ChipState.OTHER), "remembered from the last match")
        replay.reset()
        assertEquals(fresh, replay.decide(command, ChipState.OTHER), "after a reset the replay starts from the first step again")
    }

    @Test
    fun `a second RF session sees the chip as the first did`() {
        // onDeactivated ends a session: the service resets the replay and its own state, so a second reader gets the same answers
        MeasuredEmulator.with(profileOf()) { emulator ->
            val sequence = arrayOf("00A4000C023F00", "00A4040C07A0000002471001", "00A4040C05A000000247", "00A4040C07A0000002471002", "00A4020C02011C", "00B00000FF")
            val one = send(emulator, *sequence)
            emulator.service.onDeactivated(1)
            val two = send(emulator, *sequence)
            assertEquals(one, two)
        }
    }

    @Test
    fun `with no measured data nothing is replayed and nothing is reset`() {
        assertEquals(null, ProbeReplay.of(PassportData(passportNumber = "X").withChipProfile(ChipProfiles.byId("de-passport-2017")).chipConfiguration()))
    }
}
