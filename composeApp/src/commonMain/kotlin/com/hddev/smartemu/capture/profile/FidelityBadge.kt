package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.data.ChipProfile

/**
 * The per-profile badge: how far a profile's chip has been measured, in words the UI can show as they are. The badge
 * summarises; provenance itself stays per field (see [MeasuredField]).
 */
data class FidelityBadge(
    val level: Level,
    /** As in "Measured, n=3". */
    val label: String,
    /** The fidelity contract with this profile's pack and capture count, or what is still missing. */
    val detail: String,
    val sampleCount: Int,
    /** Comparable fields that earned MEASURED, out of [checkableFields]. */
    val measuredFields: Int,
    val checkableFields: Int
) {
    enum class Level { MEASURED, CAPTURED_NOT_VERIFIED, NOT_MEASURED }

    companion object {
        fun of(profile: ChipProfile): FidelityBadge = of(profile.measured)

        fun of(measured: MeasuredProfile?): FidelityBadge {
            if (measured == null) {
                return FidelityBadge(Level.NOT_MEASURED, "Not measured", "No real chip of this kind has been captured.", 0, 0, 0)
            }
            val checkable = measured.checkableFields.size
            val awarded = measured.measuredFields.count { it.fidelity != FieldFidelity.NOT_COMPARABLE }
            val n = measured.sampleCount
            return when {
                awarded > 0 -> FidelityBadge(
                    Level.MEASURED, "Measured, n=$n",
                    FidelityContract.describe(measured.probePack.version, n) + " $awarded of $checkable comparable fields measured" +
                        measured.measuredFields.count { it.matchesDefault }.let { d -> if (d > 0) " ($d of them match the emulator's own default)." else "." },
                    n, awarded, checkable
                )
                measured.needsMoreCaptures -> FidelityBadge(
                    Level.CAPTURED_NOT_VERIFIED, "Captured, n=$n: needs more captures",
                    "One capture can't be held out. No field is MEASURED until a second capture confirms it.",
                    n, 0, checkable
                )
                else -> FidelityBadge(
                    Level.CAPTURED_NOT_VERIFIED, "Captured, n=$n: not verified",
                    "No field has passed the hold-out check yet.", n, 0, checkable
                )
            }
        }
    }
}
