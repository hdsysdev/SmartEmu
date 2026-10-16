package com.hddev.smartemu.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keeps Tier A safe: a probe added later can't quietly send what the pack promises never to send. */
class ProbePackTest {
    private val pack = ProbePack.tierA
    private val packs = listOf(ProbePack.tierAV1, ProbePack.tierA)
    private val steps = pack.probes.flatMap { probe -> probe.steps.map { probe to it } }

    @Test
    fun `is tier-a version 2, the one a new capture runs`() {
        assertEquals(ProbePackRef("tier-a", 2), pack.ref)
        assertEquals(ProbePackRef("tier-a", 1), ProbePack.tierAV1.ref, "v1 stays, for the captures made under it")
        assertTrue(pack.probes.all { it.tier == ProbeTier.A })
    }

    @Test
    fun `probe and step ids are unique and plain`() {
        assertEquals(pack.probes.size, pack.probes.map { it.id }.toSet().size)
        pack.probes.forEach { probe ->
            assertEquals(probe.steps.size, probe.steps.map { it.id }.toSet().size, probe.id)
        }
        val identifier = Regex("[a-z0-9-]{1,48}")
        pack.probes.forEach { probe ->
            assertTrue(identifier.matches(probe.id), probe.id)
            probe.steps.forEach { assertTrue(identifier.matches(it.id), it.id) }
        }
    }

    @Test
    fun `sends only SELECT, READ BINARY, GET CHALLENGE and EXTERNAL AUTHENTICATE, in the clear`() {
        val allowed = setOf(0xA4, 0xB0, 0xB1, 0x84, 0x82)
        steps.forEach { (probe, step) ->
            val command = step.command
            assertEquals(0x00, command[0].toInt(), "${probe.id}/${step.id} must be CLA 00: no secure messaging, no chaining")
            assertTrue((command[1].toInt() and 0xFF) in allowed, "${probe.id}/${step.id} INS %02X".format(command[1]))
        }
    }

    @Test
    fun `sends EXTERNAL AUTHENTICATE only once per command and never with a cryptogram`() {
        val authenticate = packs.flatMap { p -> p.probes.flatMap { probe -> probe.steps.map { probe to it } } }
            .filter { (_, step) -> step.command[1] == 0x82.toByte() }
        assertTrue(authenticate.isNotEmpty())
        authenticate.forEach { (probe, step) ->
            assertEquals(1, step.repeats, "${probe.id}/${step.id}")
            assertEquals(ProbeSetup.EMRTD_APPLICATION, step.setup, "a fresh SELECT leaves no challenge pending")
            // The data of the command is zeros, which no BAC session derives
            val command = step.command
            val data = command.copyOfRange(5, 5 + (command[4].toInt() and 0xFF))
            assertTrue(data.all { it == 0.toByte() }, "${probe.id}/${step.id}")
        }
        assertEquals(4, authenticate.size, "two in each version")
        packs.forEach { p ->
            assertEquals(2, p.probes.flatMap { it.steps }.count { it.command[1] == 0x82.toByte() }, "each extra EXTERNAL AUTHENTICATE is another chance to count as a failed BAC")
        }
    }

    @Test
    fun `every command is well-formed hex and every step repeats at least once`() {
        steps.forEach { (probe, step) ->
            assertTrue(step.command.size >= 4, "${probe.id}/${step.id}")
            assertTrue(step.repeats >= 1)
        }
    }

    @Test
    fun `lists what it excludes`() {
        assertTrue(pack.excluded.any { it.contains("PIN") })
        assertTrue(pack.excluded.any { it.contains("PACE") })
    }
}
