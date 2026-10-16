package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles

/**
 * Profiles with measured data attached, which [ChipProfiles.byId] finds before the curated ones, so that a document
 * set to such a profile makes the emulator replay what was measured. Empty unless a loader installs something.
 */
object MeasuredProfileRegistry {
    @Volatile
    private var overlay: Map<String, ChipProfile> = emptyMap()

    /** The installed profile with this id, or null. */
    fun profileFor(id: String): ChipProfile? = overlay[id]

    val installed: List<ChipProfile> get() = overlay.values.toList()

    /** Adds [profiles] to the ones installed, replacing any with the same id. */
    fun install(profiles: List<ChipProfile>) {
        overlay = overlay + profiles.associateBy { it.id }
    }

    /**
     * Links [measured] profiles to curated ones: a profile whose [MeasuredProfile.chipProfileId] names a curated profile
     * becomes that profile with the measured data attached (same id). One that names none becomes a profile of its
     * own, [MeasuredChipProfile.from], named "measured-" and its id.
     */
    fun link(measured: List<MeasuredProfile>, curated: List<ChipProfile> = ChipProfiles.all) {
        install(measured.map { m ->
            val base = curated.firstOrNull { it.id == m.chipProfileId }
            base?.withMeasured(m) ?: MeasuredChipProfile.from(m)
        })
    }

    fun clear() {
        overlay = emptyMap()
    }
}

/** The bridge from [MeasuredProfile] to a [ChipProfile] the emulator can be configured with. */
object MeasuredChipProfile {
    /**
     * A profile for the measured chip: [base]'s modelled behaviour (cryptography, files and so on, which no
     * unauthenticated probe measures) with the access control the measurement showed and [measured] attached. The
     * access control follows the capture: PACE if the chip has an EF.CardAccess, BAC if it answered GET CHALLENGE.
     */
    fun from(measured: MeasuredProfile, base: ChipProfile = ChipProfiles.default, id: String = "measured-${measured.id}"): ChipProfile {
        val pace = measured.observations.cardAccessHex != null
        val bac = measured.observations.bacGetChallenge == 0x9000
        val accessControl = when {
            pace && bac -> AccessControl.BAC_AND_PACE
            pace -> AccessControl.PACE_ONLY
            bac -> AccessControl.BAC_ONLY
            else -> base.accessControl
        }
        return base.copyWith(id = id, accessControl = accessControl, measured = measured)
    }
}
