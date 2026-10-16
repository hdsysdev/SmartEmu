package com.hddev.smartemu.capture.probes

import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ScriptedTransport
import com.hddev.smartemu.capture.response
import com.hddev.smartemu.capture.hexBytes
import com.hddev.smartemu.capture.defaultExtractors
import com.hddev.smartemu.capture.ProbePacks
import com.hddev.smartemu.capture.ProbeSetup
import com.hddev.smartemu.capture.ProbeTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Static checks on what each tier's step lists may send, over setups, preambles and commands. */
class PackSafetyTest {
    /**
     * Instructions no Tier A or B step may carry: PIN and PUK (20, 24, 2C), everything that writes, creates, erases or
     * switches files on or off, and the ones that need an authenticated session (PSO, INTERNAL AUTHENTICATE,
     * key generation, MANAGE CHANNEL).
     */
    private val banned = setOf(
        0x20, 0x24, 0x2C, // VERIFY, CHANGE REFERENCE DATA, RESET RETRY COUNTER
        0x0E, 0x0F, 0xD0, 0xD1, 0xD2, 0xD6, 0xD7, 0xDA, 0xDB, 0xDC, 0xDD, 0xE0, 0xE2, 0xE4, 0xE6, 0xEE, // erase, write, update, put, create, append, delete
        0x04, 0x44, 0x06, 0x08, 0x0A, 0x0C, 0x10, 0x12, 0x14, // activate, deactivate, terminate and related
        0x2A, 0x88, 0x46, 0x70 // PSO, INTERNAL AUTHENTICATE, GENERATE KEY PAIR, MANAGE CHANNEL
    )

    private val tierAB = listOf(ProbePack.tierAV1, ProbePack.tierA, ProbePack.tierB)

    private fun commandsOf(pack: ProbePack) = pack.allSteps().flatMap { (probe, step) -> step.allCommands().map { Triple(probe.id, step.id, it) } }

    @Test
    fun `no step of tier a or b has a banned instruction, in a setup, a preamble or a command`() {
        tierAB.forEach { pack ->
            commandsOf(pack).forEach { (probe, step, command) ->
                assertTrue(command.ins() !in banned, "${pack.id}/$probe/$step sends INS %02X".format(command.ins()))
            }
        }
    }

    @Test
    fun `no pack sends PIN or PUK commands`() {
        ProbePacks.all.forEach { pack ->
            commandsOf(pack).forEach { (probe, step, command) ->
                assertTrue(command.ins() !in setOf(0x20, 0x24, 0x2C), "${pack.id}/$probe/$step")
            }
        }
    }

    @Test
    fun `tier b sends only the documented instructions`() {
        // SELECT, READ BINARY (and the odd B1), READ RECORD, GET CHALLENGE, MSE, GENERAL AUTHENTICATE, GET DATA, GET RESPONSE,
        // the EMV GET PROCESSING OPTIONS (A8), and the three unassigned instructions 02, 30 and 5E
        val allowed = setOf(0xA4, 0xB0, 0xB2, 0x84, 0x22, 0x86, 0xCA, 0xC0, 0xA8, 0x02, 0x30, 0x5E)
        commandsOf(ProbePack.tierB).forEach { (probe, step, command) ->
            assertTrue(command.ins() in allowed, "tier-b/$probe/$step sends INS %02X".format(command.ins()))
        }
    }

    @Test
    fun `tier b class bytes are 00, 0C, 10 and 80, and 0C or 10 only where documented`() {
        commandsOf(ProbePack.tierB).forEach { (probe, step, command) ->
            assertTrue(command.cla() in setOf(0x00, 0x0C, 0x10, 0x80), "$probe/$step CLA %02X".format(command.cla()))
            if (command.cla() == 0x0C) assertTrue(probe.startsWith("class-"), "$probe/$step: CLA 0C outside the class probes")
        }
        // A secure-messaging class command is a SELECT, READ BINARY or GET CHALLENGE as in the other classes, with
        // the same plain data: no data object 87 or 8E, so no MAC that could be wrong
        commandsOf(ProbePack.tierB).filter { it.third.cla() == 0x0C }.forEach { (probe, step, command) ->
            assertTrue(command.ins() in setOf(0xA4, 0xB0, 0x84), "$probe/$step")
            assertTrue(command.size <= 5 + 7, "$probe/$step")
        }
    }

    @Test
    fun `tier b sends no EXTERNAL AUTHENTICATE and tier a at most two`() {
        val count = { pack: ProbePack -> pack.allSteps().sumOf { (_, step) -> step.allCommands().count { it.ins() == 0x82 } * (if (step.freshStateEachRepeat) step.repeats else 1) } }
        assertEquals(0, count(ProbePack.tierB))
        assertTrue(count(ProbePack.tierA) <= 2)
    }

    /** Every command a whole run sends, extractors and all, against a chip that answers each with 6A82 (and 9000 to the master file). */
    private fun wholeRun(runner: CaptureRunner): List<ByteArray> {
        val transport = ScriptedTransport { command ->
            if (command.contentEquals(hexBytes("00A4000C023F00"))) response(0x9000) else response(0x6A82)
        }
        assertTrue(runner.run(transport) is CaptureResult.Complete)
        return transport.sent
    }

    @Test
    fun `a whole tier-a run counts every EXTERNAL AUTHENTICATE, extractors included, and has at most two`() {
        // The extractors' commands are part of the run: tier-a v2 sends none of its own, so the pack's two are all
        val v2 = wholeRun(CaptureRunner(extractors = defaultExtractors(), pack = ProbePack.tierA)).map { it.ins() }
        assertEquals(2, v2.count { it == 0x82 })
        assertTrue(v2.indexOf(0x82) < v2.indexOf(0x84), "the first EXTERNAL AUTHENTICATE comes before any GET CHALLENGE")
        // With tier B after it: tier B adds none
        val withB = wholeRun(CaptureRunner(extractors = defaultExtractors(), pack = ProbePack.tierA, additionalPacks = listOf(ProbePack.tierB)))
        assertEquals(2, withB.count { it.ins() == 0x82 })
        // The extractors alone, with tier a v2 named, send none: the observation is taken from the pack's steps
        val extractorsOnly = wholeRun(CaptureRunner(extractors = defaultExtractors(), pack = ProbePack(ProbePack.tierA.id, ProbePack.tierA.version, emptyList())))
        assertEquals(0, extractorsOnly.count { it.ins() == 0x82 })
        assertEquals(0, extractorsOnly.count { it.ins() == 0x84 })

        // v1, published as it was, sent three: the extractor's, and the pack's two, after seven GET CHALLENGE commands
        val v1 = wholeRun(CaptureRunner(extractors = defaultExtractors(), pack = ProbePack.tierAV1)).map { it.ins() }
        assertEquals(3, v1.count { it == 0x82 })
    }

    @Test
    fun `no EXTERNAL AUTHENTICATE command of tier a v2 comes after a GET CHALLENGE of the pack`() {
        // In the pack, in order: the EXTERNAL AUTHENTICATE steps are before every step that sends a GET CHALLENGE
        val steps = ProbePack.tierA.allSteps()
        val firstChallenge = steps.indexOfFirst { (_, step) -> step.allCommands().any { it.ins() == 0x84 } }
        val lastAuthenticate = steps.indexOfLast { (_, step) -> step.allCommands().any { it.ins() == 0x82 } }
        assertTrue(lastAuthenticate in 0 until firstChallenge, "EA at $lastAuthenticate, GET CHALLENGE at $firstChallenge")
        // And each is sent from a fresh SELECT of the application, with nothing in a preamble
        steps.filter { (_, step) -> step.command.ins() == 0x82 }.forEach { (probe, step) ->
            assertEquals(ProbeSetup.EMRTD_APPLICATION, step.setup, "${probe.id}/${step.id}")
            assertEquals(emptyList(), step.preambleHex)
            assertEquals(1, step.repeats)
        }
    }

    @Test
    fun `tier a reads by short file identifier only a file that is public, and none of the data groups`() {
        // In the application, the short ids are the data groups' (01 DG1, 02 DG2, ... 1D EF.SOD, 1E EF.COM). Tier a may
        // read EF.COM (1E) there and nothing else; from the master file 1C to 1F are EF.CardAccess, EF.CardSecurity, EF.DIR
        // and EF.ATR/INFO, all public
        listOf(ProbePack.tierAV1, ProbePack.tierA).forEach { pack ->
            pack.allSteps().forEach { (probe, step) ->
                (step.preamble + step.command).filter { it.ins() in setOf(0xB0, 0xB1) && it[2].toInt() and 0x80 != 0 }.forEach { command ->
                    val sfi = command[2].toInt() and 0x1F
                    val where = "${pack.id} v${pack.version}/${probe.id}/${step.id}: SFI %02X".format(sfi)
                    when (step.setup) {
                        ProbeSetup.EMRTD_APPLICATION -> assertEquals(0x1E, sfi, "$where inside the application: only EF.COM")
                        else -> assertTrue(sfi in 0x1C..0x1F || sfi == 0, "$where from the master file")
                    }
                }
            }
        }
    }

    @Test
    fun `tier b never sends GENERAL AUTHENTICATE beyond step 1, and its steps carry no password-derived data`() {
        commandsOf(ProbePack.tierB).filter { it.third.ins() == 0x86 }.forEach { (probe, step, command) ->
            // CLA 00 or 10, P1P2 0000, Lc 02, data 7C 00, Le 00: the empty dynamic authentication data object
            assertEquals("86000002" + "7C0000", com.hddev.smartemu.capture.Hex.encode(command.copyOfRange(1, command.size)).uppercase(), "$probe/$step")
        }
        commandsOf(ProbePack.tierB).filter { it.third.ins() == 0x22 }.forEach { (probe, step, command) ->
            // MSE:Set AT, with at most the mechanism OID (80) and the password reference (83): no data object of a password
            assertEquals(0xC1, command[2].toInt() and 0xFF, "$probe/$step")
            assertEquals(0xA4, command[3].toInt() and 0xFF, "$probe/$step")
            assertTrue(command.size <= 5 + 15, "$probe/$step")
        }
    }

    @Test
    fun `tier b reads by short file identifier only while the master file is selected`() {
        ProbePack.tierB.allSteps().forEach { (probe, step) ->
            val command = step.command
            val sfiForm = command.ins() in setOf(0xB0, 0xB1) && command[2].toInt() and 0x80 != 0 && command[2].toInt() and 0x1F != 0
            if (sfiForm) {
                assertEquals(ProbeSetup.MASTER_FILE, step.setup, "${probe.id}/${step.id}: an SFI read names a data group inside the application")
                assertTrue(step.preamble.isEmpty(), "${probe.id}/${step.id}")
            }
            // And no preamble or later command selects the application first in an SFI probe
            if (probe.id.contains("sfi")) assertEquals(ProbeSetup.MASTER_FILE, step.setup)
        }
    }

    @Test
    fun `every preamble command is also recorded as a step of its own in the same pack`() {
        ProbePacks.all.forEach { pack ->
            val commands = pack.allSteps().map { (_, step) -> step.commandHex.uppercase() }.toSet()
            pack.allSteps().forEach { (probe, step) ->
                step.preambleHex.forEach { assertTrue(it.uppercase() in commands, "${pack.id}/${probe.id}/${step.id}: preamble $it has no step recording its answer") }
            }
        }
    }

    @Test
    fun `a step with a preamble starts from fresh state on every repeat`() {
        ProbePacks.all.forEach { pack ->
            pack.allSteps().filter { (_, step) -> step.preambleHex.isNotEmpty() }.forEach { (probe, step) ->
                assertTrue(step.freshStateEachRepeat, "${pack.id}/${probe.id}/${step.id}")
            }
        }
    }

    @Test
    fun `every command is well-formed hex of a plausible APDU`() {
        ProbePacks.all.forEach { pack ->
            pack.allSteps().forEach { (probe, step) ->
                (step.preambleHex + step.commandHex).forEach {
                    assertTrue(com.hddev.smartemu.capture.Hex.isHex(it) && it.length >= 8, "${probe.id}/${step.id}: $it")
                }
                assertTrue(step.repeats in 1..3, "${probe.id}/${step.id}")
                // Case 2/3/4 length fields agree with the data, for a short APDU
                val c = step.command
                if (!step.needsExtendedLength && c.size > 5) {
                    val lc = c[4].toInt() and 0xFF
                    val ok = c.size == 5 + lc || c.size == 6 + lc
                    // The deliberately malformed ones are named as such
                    if (!ok) assertTrue(listOf("longer", "wrong", "lc-instead", "short").any { step.id.contains(it) } || step.id.startsWith("aid-"), "${probe.id}/${step.id}")
                }
            }
        }
    }

    @Test
    fun `tier c holds only wrong-key BAC probes with a bounded number of attempts`() {
        val pack = ProbePack.tierC
        assertTrue(pack.probes.all { it.tier == ProbeTier.C })
        assertEquals(listOf("bac-wrong-key"), pack.probes.map { it.id })
        val commands = commandsOf(pack)
        // Only SELECT, READ BINARY, GET CHALLENGE and EXTERNAL AUTHENTICATE, all CLA 00
        commands.forEach { (_, step, command) ->
            assertEquals(0x00, command.cla(), step)
            assertTrue(command.ins() in setOf(0xA4, 0xB0, 0x84, 0x82), "$step INS %02X".format(command.ins()))
        }
        val wrong = pack.allSteps().filter { (_, step) -> step.command.ins() == 0x82 }
        assertEquals(5, wrong.size)
        assertTrue(wrong.all { (_, step) -> step.repeats == 1 && step.preambleHex == listOf("0084000008") })
        // One and the same constant cryptogram, which is never a BAC key's output: the bytes 00 to 27
        assertEquals(1, wrong.map { (_, step) -> step.commandHex }.toSet().size)
        assertEquals("0082000028" + (0 until 40).joinToString("") { "%02X".format(it) } + "28", wrong.first().second.commandHex)
        // The challenge is asked for before each attempt, and nothing authenticates correctly
        assertTrue(pack.excluded.any { it.contains("20, 24, 2C") })
        assertTrue(pack.excluded.any { it.contains("CAN") })
    }

    @Test
    fun `tier c excluded list states the CAN failure probe is excluded entirely`() {
        assertTrue(ProbePack.tierC.excluded.any { it.contains("Repeated CAN failures") && it.contains("every tier") })
        assertTrue(ProbePack.tierB.excluded.any { it.contains("Repeated CAN failures") })
        assertTrue(ProbePack.tierA.excluded.any { it.contains("PIN") })
    }
}
