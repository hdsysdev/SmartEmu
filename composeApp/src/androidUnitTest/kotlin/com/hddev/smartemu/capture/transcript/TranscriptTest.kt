package com.hddev.smartemu.capture.transcript

import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.capture.ApduTransport
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.CaptureSource
import com.hddev.smartemu.capture.EmulatorTransport
import com.hddev.smartemu.capture.Hex
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.RedactionLint
import com.hddev.smartemu.capture.SacrificialCardNotAcknowledgedException
import com.hddev.smartemu.capture.profile.MeasuredEmulator
import com.hddev.smartemu.capture.testReader
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.withChipProfile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TranscriptTest {
    private lateinit var service: PassportHceService

    @BeforeTest
    fun setUp() {
        service = PassportHceService().also { it.onCreate() }
        PassportHceService.setSharedPassportData(MeasuredEmulator.sampleDocument.withChipProfile(ChipProfiles.default))
    }

    @AfterTest
    fun tearDown() {
        PassportHceService.setSharedPassportData(null)
        service.onDestroy()
    }

    private fun emulator(): ApduTransport = EmulatorTransport(service, testReader)

    /** What an operator's tool does with a command sheet: send each command to the chip and write the answer after it. */
    private fun operatorTranscript(sheet: String, chip: ApduTransport = emulator()): String = buildString {
        sheet.lines().forEach { line ->
            appendLine(line)
            if (line.startsWith(">")) appendLine("< ${Hex.encode(chip.transmit(Hex.decode(line.removePrefix(">").trim())))}")
        }
    }

    /** A record without what differs between two readers: id, source, reader and timing. */
    private fun comparable(record: CaptureRecord) = record.copy(
        captureId = "-", source = CaptureSource.ANDROID_READER, reader = testReader,
        probes = record.probes.map { p -> p.copy(steps = p.steps.map { it.copy(timing = null) }) }
    )

    // --- format ---------------------------------------------------------------------------------------------------

    @Test
    fun `the format parses commands, responses, comments and directives, and renders back`() {
        val text = "# a comment\n#! source: PCSC\n#! reader: ACS ACR1252U (lab)\n#! extended-length: false\n#! packs: tier-a/1\n\n" +
            "> 00 A4 04 0C 07 A0000002471001\n< 90 00\n> 0084000008\n< 1122334455667788 9000\n"
        val transcript = Transcript.parse(text)

        assertEquals(2, transcript.exchanges.size)
        assertEquals("00A4040C07A0000002471001", Hex.encode(transcript.exchanges[0].command))
        assertEquals("11223344556677889000", Hex.encode(transcript.exchanges[1].response))
        assertEquals("PCSC", transcript.source)
        assertEquals(listOf("tier-a" to 1), transcript.packs)
        assertEquals("ACS ACR1252U (lab)", transcript.directives["reader"])
        assertEquals(7, transcript.exchanges[0].line)
        assertEquals(transcript.render(), Transcript.parse(transcript.render()).render())
    }

    @Test
    fun `a malformed transcript is refused with its line and never quotes a response`() {
        fun failure(text: String) = assertFailsWith<TranscriptFormatException> { Transcript.parse(text) }
        assertTrue(failure("> 00A4040C\n> 00A4040C\n< 9000").message!!.contains("line 1"))
        assertTrue(failure("< 9000").message!!.contains("Response with no command"))
        assertTrue(failure("> 00A4040C").message!!.contains("no response at the end"))
        assertTrue(failure("> 00A4040C\n< 9").message!!.contains("even number of hex digits"))
        assertTrue(failure("> 00A4040C\n< 90").message!!.contains("shorter than 2 bytes"))
        assertTrue(failure("> 00A4\n< 9000").message!!.contains("shorter than 4 bytes"))
        assertTrue(failure("hello").message!!.contains("starts with"))
        assertTrue(failure("#! colour: red").message!!.contains("Unknown directive"))
        assertTrue(failure("#! source: PCSC\n#! source: PCSC").message!!.contains("appears twice"))
        assertTrue(failure("#! reader: P<D<<MUSTERMANN<<ERIKA").message!!.contains("reader name"))
        assertTrue(failure("#! extended-length: maybe").message!!.contains("true or false"))
        val secret = failure("> 00A4040C\n< DEADBEEFCAFE9000\n> 00A4040C\n< ZZ9000")
        assertFalse(secret.message!!.contains("DEADBEEF"))
        assertFailsWith<TranscriptFormatException> { Transcript.parse("#! packs: tier-a").packs }
    }

    // --- replay ---------------------------------------------------------------------------------------------------

    @Test
    fun `the transport answers in order and reports what is left`() {
        val transport = Transcript.parse("> 00A4040C\n< 9000\n> 0084000008\n< 6985\n").transport()

        assertEquals("9000", Hex.encode(transport.transmit(Hex.decode("00A4040C"))))
        assertEquals(1, transport.remaining)
        assertEquals("6985", Hex.encode(transport.transmit(Hex.decode("0084000008"))))
        assertEquals(0, transport.remaining)
        val tooMany = assertFailsWith<TranscriptMismatchException> { transport.transmit(Hex.decode("0084000008")) }
        assertTrue(tooMany.message!!.contains("command #3") && tooMany.message!!.contains("ends after 2 exchanges"), tooMany.message)
    }

    @Test
    fun `a command out of order fails clearly, with both commands, the line, and no response`() {
        val transport = Transcript.parse("> 00A4040C\n< AABBCCDD9000\n> 0084000008\n< 9000\n").transport()

        val failure = assertFailsWith<TranscriptMismatchException> { transport.transmit(Hex.decode("0084000008")) }
        assertTrue(failure.message!!.contains("exchange #1 (line 1)"), failure.message)
        assertTrue(failure.message!!.contains("it sent 0084000008") && failure.message!!.contains("transcript has 00A4040C"), failure.message)
        assertFalse(failure.message!!.contains("AABBCCDD"))
    }

    @Test
    fun `the lab reader has no phone controller limits, and extended-length follows the directive`() {
        val plain = Transcript.parse("> 00A4040C\n< 9000\n").transport().readerDevice()
        assertEquals("PC/SC lab reader", plain.model)
        assertEquals(null, plain.controllerMaxTransceiveLength)
        assertEquals(true, plain.controllerExtendedLengthSupported)
        val proxmark = Transcript.parse("#! source: PROXMARK\n#! extended-length: false\n> 00A4040C\n< 9000\n").transport().readerDevice()
        assertEquals("Proxmark lab reader", proxmark.model)
        assertEquals(false, proxmark.controllerExtendedLengthSupported)
    }

    // --- the exporter ---------------------------------------------------------------------------------------------

    @Test
    fun `the command sheet lists every command of the pack in the order the runner sends it`() {
        val pack = ProbePack.tierA
        val sheet = PackTranscriptExporter.render(listOf(pack))
        val commands = PackTranscriptExporter.commands(listOf(pack))

        assertEquals(commands.map { "> ${Hex.encode(it)}" }, sheet.lines().filter { it.startsWith(">") })
        assertTrue(sheet.contains("#! source: PCSC") && sheet.contains("#! packs: ${pack.id}/${pack.version}") && sheet.contains("#! extended-length: true"))
        // Every probe step's own command is in the sheet as often as the pack repeats it
        pack.probes.forEach { probe ->
            probe.steps.forEach { step ->
                val count = commands.count { it.contentEquals(step.command) }
                assertTrue(count >= step.repeats, "${probe.id}/${step.id}: ${step.commandHex} appears $count times, the step repeats ${step.repeats}")
            }
        }
        assertTrue(sheet.lines().any { it == "# select-aid/p2-0c-no-le" })
        assertEquals(sheet, PackTranscriptExporter.render(listOf(pack)), "deterministic")
        // A sheet is commands only: it is not yet a transcript
        assertFailsWith<TranscriptFormatException> { Transcript.parse(sheet) }
    }

    @Test
    fun `without extended length the extended steps are left out, and a Tier C sheet says to use a sacrificial card`() {
        val withExtended = PackTranscriptExporter.commands(listOf(ProbePack.tierA), extendedLength = true)
        val without = PackTranscriptExporter.commands(listOf(ProbePack.tierA), extendedLength = false)
        assertTrue(without.size < withExtended.size)
        assertTrue(PackTranscriptExporter.render(listOf(ProbePack.tierA), extendedLength = false).contains("#! extended-length: false"))
        val tierC = PackTranscriptExporter.render(listOf(ProbePack.tierA, ProbePack.tierC))
        assertTrue(tierC.contains("TIER C: use a sacrificial or test card only"))
        assertTrue(tierC.contains("#! packs: ${ProbePack.tierA.id}/${ProbePack.tierA.version}, ${ProbePack.tierC.id}/${ProbePack.tierC.version}"))
    }

    // --- the round trip -------------------------------------------------------------------------------------------

    @Test
    fun `a transcript recorded from the emulator imports as the same record apart from the source`() {
        for (packs in listOf(listOf(ProbePack.tierA), listOf(ProbePack.tierA, ProbePack.tierB))) {
            for (source in listOf(CaptureSource.PCSC, CaptureSource.PROXMARK)) {
                val recorder = RecordingTransport(emulator())
                val direct = assertIs<CaptureResult.Complete>(
                    CaptureRunner(pack = packs.first(), additionalPacks = packs.drop(1), source = CaptureSource.ANDROID_READER).run(recorder)
                ).record
                val text = recorder.transcript(mapOf("source" to source.name, "packs" to packs.joinToString(", ") { "${it.id}/${it.version}" })).render()

                val imported = assertIs<CaptureResult.Complete>(TranscriptImporter.import(text)).record

                assertEquals(source, imported.source)
                assertNotEquals(direct.source, imported.source)
                assertEquals(direct.fingerprint, imported.fingerprint, "the same generation")
                assertEquals(comparable(direct), comparable(imported), "${packs.map { it.id }} as $source")
                assertEquals(if (source == CaptureSource.PCSC) "PC/SC lab reader" else "Proxmark lab reader", imported.reader.model)
                // And what comes out of the importer is what intake accepts
                assertEquals(emptyList(), RedactionLint.check(CaptureJson.encode(imported)))
            }
        }
    }

    @Test
    fun `an operator following the command sheet produces a transcript that imports`() {
        val sheet = PackTranscriptExporter.render(listOf(ProbePack.tierA), reader = "Lab reader 1")
        val transcript = operatorTranscript(sheet)

        val imported = assertIs<CaptureResult.Complete>(TranscriptImporter.import(transcript)).record
        val direct = assertIs<CaptureResult.Complete>(CaptureRunner().run(emulator())).record

        assertEquals(CaptureSource.PCSC, imported.source)
        assertEquals("Lab reader 1", imported.reader.model)
        assertEquals(direct.fingerprint, imported.fingerprint)
    }

    @Test
    fun `an operator whose reader can't send extended length is imported without those steps`() {
        val sheet = PackTranscriptExporter.render(listOf(ProbePack.tierA), extendedLength = false)
        val imported = assertIs<CaptureResult.Complete>(TranscriptImporter.import(operatorTranscript(sheet))).record
        assertEquals(false, imported.reader.controllerExtendedLengthSupported)
        assertTrue(imported.probes.flatMap { it.steps }.none { it.stepId.startsWith("extended-") })
    }

    @Test
    fun `a transcript with the commands in another order is refused naming the first command that differs`() {
        val sheet = PackTranscriptExporter.render(listOf(ProbePack.tierA))
        val lines = operatorTranscript(sheet).lines().toMutableList()
        val commandLines = lines.withIndex().filter { it.value.startsWith(">") }.map { it.index }
        // Swap the commands of the first and the third exchange, which differ
        val a = commandLines[0]
        val b = commandLines[2]
        val tmp = lines[a]; lines[a] = lines[b]; lines[b] = tmp

        val failure = assertFailsWith<TranscriptMismatchException> { TranscriptImporter.import(lines.joinToString("\n")) }
        assertTrue(failure.message!!.contains("exchange #1"), failure.message)
        assertTrue(failure.message!!.contains("it sent") && failure.message!!.contains("transcript has"), failure.message)
    }

    @Test
    fun `a transcript with a missing exchange or with extra ones is refused`() {
        val full = Transcript.parse(operatorTranscript(PackTranscriptExporter.render(listOf(ProbePack.tierA))))

        val short = Transcript(full.exchanges.dropLast(3), full.directives)
        assertTrue(assertFailsWith<TranscriptMismatchException> { TranscriptImporter.import(short) }.message!!.contains("transcript ends after"))

        val extra = Transcript(full.exchanges + full.exchanges.take(2), full.directives)
        assertTrue(assertFailsWith<TranscriptMismatchException> { TranscriptImporter.import(extra) }.message!!.contains("2 more exchanges"))
    }

    @Test
    fun `the importer needs the source and the packs, from the arguments or the transcript, and not the phone source`() {
        val bare = Transcript(listOf(TranscriptExchange(Hex.decode("00A4040C"), Hex.decode("9000"))))
        assertTrue(assertFailsWith<TranscriptImportException> { TranscriptImporter.import(bare) }.message!!.contains("Name the source"))
        assertTrue(assertFailsWith<TranscriptImportException> { TranscriptImporter.import(bare, CaptureSource.PCSC) }.message!!.contains("Name the probe packs"))
        assertTrue(assertFailsWith<TranscriptImportException> { TranscriptImporter.import(bare, CaptureSource.ANDROID_READER, listOf(ProbePack.tierA)) }.message!!.contains("not ANDROID_READER"))
        val unknown = Transcript(bare.exchanges, mapOf("source" to "PCSC", "packs" to "tier-z/9"))
        assertTrue(assertFailsWith<TranscriptImportException> { TranscriptImporter.import(unknown) }.message!!.contains("not published"))
    }

    @Test
    fun `a Tier C transcript is refused unless the card is declared sacrificial`() {
        val transcript = operatorTranscript(PackTranscriptExporter.render(listOf(ProbePack.tierC)))
        assertFailsWith<SacrificialCardNotAcknowledgedException> { TranscriptImporter.import(transcript) }
        assertIs<CaptureResult.Complete>(TranscriptImporter.import(transcript, acknowledgeSacrificialCard = true))
    }
}
