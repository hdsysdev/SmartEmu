package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.NotEmulatable
import com.hddev.smartemu.data.ChipProfile

/** One line of the "emulation limits" panel and what kind of limit it is. */
data class EmulationLimit(val kind: Kind, val text: String) {
    enum class Kind { NOT_EMULATABLE, NOT_COMPARABLE, NOT_VERIFIED, DISAGREEMENT, NOT_PROBED, PROFILE_NOTE }
}

/**
 * What the emulation of a profile can't copy or hasn't been shown to copy, as text for the "emulation limits"
 * panel: the link-layer properties no Android phone can emulate, whatever was measured, then what the diff
 * can't check, what is not yet verified, and what captures disagreed on.
 */
data class EmulationLimits(val items: List<EmulationLimit>) {
    val lines: List<String> get() = items.map { it.text }

    companion object {
        fun of(profile: ChipProfile): EmulationLimits {
            val items = mutableListOf<EmulationLimit>()
            NotEmulatable.properties.forEach { items += EmulationLimit(EmulationLimit.Kind.NOT_EMULATABLE, "Not emulatable on Android HCE: $it") }
            val measured = profile.measured
            if (measured != null) {
                measured.fields.filter { it.fidelity == FieldFidelity.NOT_COMPARABLE }
                    .groupBy { it.reason.orEmpty() }
                    .forEach { (reason, fields) ->
                        items += EmulationLimit(EmulationLimit.Kind.NOT_COMPARABLE, "Not comparable ($reason): ${summarise(fields.map { it.key })}")
                    }
                val unverified = measured.checkableFields.filter { !it.measured }.map { it.key }
                if (unverified.isNotEmpty()) {
                    val why = if (measured.needsMoreCaptures) "needs more captures" else "not yet confirmed on a held-out capture"
                    items += EmulationLimit(EmulationLimit.Kind.NOT_VERIFIED, "Not verified, $why: ${summarise(unverified)}")
                }
                measured.disagreements.forEach {
                    val across = if (it.acrossDevices) " (split across devices: possibly the phone, not the chip)" else ""
                    items += EmulationLimit(EmulationLimit.Kind.DISAGREEMENT, "Captures disagree on ${it.field}$across: the emulator keeps its own behaviour")
                }
                items += EmulationLimit(
                    EmulationLimit.Kind.NOT_PROBED,
                    "EF.CardAccess is replayed as the chip wrote it; the PACE handshake itself is not probed without authentication, " +
                        "so it follows the profile's modelled suites, which may differ from those advertised"
                )
            }
            profile.notes.forEach { items += EmulationLimit(EmulationLimit.Kind.PROFILE_NOTE, it) }
            return EmulationLimits(items)
        }

        private fun summarise(keys: List<String>): String =
            if (keys.size <= 4) keys.joinToString(", ") else keys.take(3).joinToString(", ") + " and ${keys.size - 3} more"
    }
}
