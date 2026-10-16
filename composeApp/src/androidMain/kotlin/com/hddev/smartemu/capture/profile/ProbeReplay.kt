package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.Hex
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ProbePacks
import com.hddev.smartemu.capture.ProbeSetup
import com.hddev.smartemu.data.ChipConfiguration

/** Where the emulated chip is, in the terms a probe step's setup uses. */
enum class ChipState {
    /** The master file is current and no EF is selected. */
    MASTER_FILE,

    /** The eMRTD application is selected and no EF is. */
    EMRTD_APPLICATION,

    /** EF.CardAccess is selected. */
    CARD_ACCESS_SELECTED,

    /** Anything else: no probe step starts here. */
    OTHER
}

/** The ids of the probe steps the emulator names in its own code, as they are in [com.hddev.smartemu.data.ErrorResponses.byProbe]. */
object ProbeKeys {
    const val EXTERNAL_AUTHENTICATE_OUT_OF_SEQUENCE = "external-authenticate-out-of-sequence/lc-40-le-28"

    /** READ BINARY with odd INS B1 on EF.CardAccess: measured 9000 means the chip supports it. */
    const val ODD_READ_CARD_ACCESS = "read-binary-order/odd-ins-card-access"
}

/**
 * Recognises a command as the probe step that sent it: the same bytes, in the state the step starts from. This is
 * how the emulator finds which measured status word is its answer. Steps with a preamble start from a state the
 * emulator doesn't track and are not recognised.
 */
class ProbeSituations(packs: List<ProbePack>) {
    private class Entry(val key: String, val setup: ProbeSetup, val isSelect: Boolean)

    private val byCommand: Map<String, List<Entry>> = buildMap<String, MutableList<Entry>> {
        packs.forEach { pack ->
            pack.probes.forEach { probe ->
                probe.steps.filter { it.preambleHex.isEmpty() }.forEach { step ->
                    getOrPut(step.commandHex.uppercase()) { mutableListOf() }
                        .add(Entry("${probe.id}/${step.id}", step.setup, step.commandHex.uppercase().startsWith("00A4")))
                }
            }
        }
    }

    /**
     * The key of the step [command] is in [state], or null. A SELECT changes the state, so the repeats of a SELECT
     * step find the chip somewhere else than the setup left it: for those, [lastSelect] remembers which step the
     * command last matched exactly, and that one answers (the first step with the command if none yet).
     */
    fun match(command: ByteArray, state: ChipState, lastSelect: MutableMap<String, String> = mutableMapOf()): String? {
        val hex = Hex.encode(command)
        val candidates = byCommand[hex] ?: return null
        candidates.firstOrNull { it.setup.matches(state) }?.let {
            if (it.isSelect) lastSelect[hex] = it.key
            return it.key
        }
        return candidates.firstOrNull { it.isSelect }?.let { lastSelect[hex] ?: it.key }
    }

    private fun ProbeSetup.matches(state: ChipState): Boolean = when (this) {
        ProbeSetup.NONE -> true
        ProbeSetup.MASTER_FILE -> state == ChipState.MASTER_FILE
        ProbeSetup.EMRTD_APPLICATION -> state == ChipState.EMRTD_APPLICATION
        ProbeSetup.CARD_ACCESS_SELECTED -> state == ChipState.CARD_ACCESS_SELECTED
    }

    companion object {
        /** Every published pack. */
        val published: ProbeSituations by lazy { ProbeSituations(ProbePacks.all) }
    }
}

/**
 * What the emulator does with a command that is a probe step the chip was measured on: answer with the measured status
 * word, or, where the measured word is 9000 and the emulator's own handling would refuse, say how to do it anyway.
 * Built from [com.hddev.smartemu.data.ErrorResponses.byProbe], so a hand-written entry replays the same way as a
 * measured one. With no entries there is no replay, and the emulator behaves as it always has.
 *
 * Only an error status word is replayed as a bare status. A warning or a success that carries data (62xx and 63xx, 61xx,
 * 90xx) is not: answering it with no data would turn an EF.CardAccess read the chip answered "6282 plus the bytes" into
 * an empty file. For those the emulator's own handling answers, as it does for a step nobody measured; 9000 is replayed
 * only as "carry on" (see [Decision.Succeed]).
 */
class ProbeReplay(private val byProbe: Map<String, Int>, private val situations: ProbeSituations = ProbeSituations.published) {

    /** What to do with a command. */
    sealed interface Decision {
        /** Answer with this status word and no data. */
        data class Status(val statusWord: Int) : Decision

        /** The chip answered 9000, so the emulator carries on, doing [kind] even where it would normally refuse. */
        data class Succeed(val kind: Kind) : Decision

        /** Not a measured step: the emulator's own handling. */
        data object Own : Decision
    }

    enum class Kind { SELECT_APPLICATION, GET_CHALLENGE, OTHER }

    /** Whether the chip was measured answering READ BINARY with INS B1 on EF.CardAccess, so the emulator offers it. */
    val supportsOddRead: Boolean = byProbe[ProbeKeys.ODD_READ_CARD_ACCESS] == 0x9000

    private val lastSelect = mutableMapOf<String, String>()

    /** Forgets what the session did, so the next reader starts from the first step of every command (see [ProbeSituations.match]). */
    fun reset() = lastSelect.clear()

    fun decide(command: ByteArray, state: ChipState): Decision {
        val key = situations.match(command, state, lastSelect) ?: return Decision.Own
        val statusWord = byProbe[key] ?: return Decision.Own
        if (statusWord != 0x9000) return if (isError(statusWord)) Decision.Status(statusWord) else Decision.Own
        val ins = if (command.size > 1) command[1].toInt() and 0xFF else -1
        val p1 = if (command.size > 2) command[2].toInt() and 0xFF else -1
        return Decision.Succeed(
            when {
                ins == 0xA4 && p1 == 0x04 -> Kind.SELECT_APPLICATION
                ins == 0x84 -> Kind.GET_CHALLENGE
                else -> Kind.OTHER
            }
        )
    }

    companion object {
        /**
         * Whether [statusWord] is an error with no data: SW1 67, 68, 69, 6A, 6B, 6C (wrong Le: it asks for the command
         * again), 6D, 6E or 6F. Not 61, 62, 63 or 90, which may come with data or with a counter.
         */
        fun isError(statusWord: Int): Boolean = (statusWord shr 8) in listOf(0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F)

        /** The replay for a chip, or null for one with nothing measured, which keeps the emulator's own behaviour. */
        fun of(configuration: ChipConfiguration): ProbeReplay? =
            configuration.errorResponses.byProbe.takeIf { it.isNotEmpty() }?.let { ProbeReplay(it) }
    }
}
