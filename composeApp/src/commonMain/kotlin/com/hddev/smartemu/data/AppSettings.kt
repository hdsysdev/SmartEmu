package com.hddev.smartemu.data

/**
 * How the app looks and behaves, as opposed to the document it emulates.
 *
 * [developerMode] swaps the guided screens for the developer ones: every chip setting and chip profile, and the
 * reader's commands as they arrive. The chip always uses its profile's cryptography as it is. Only in developer mode,
 * [paceCurve] or [paceDhGroup] and [paceCipher] put every
 * document's PACE on those domain parameters or that cipher instead of its profile's, and [extraPaceSuites] makes the
 * chip offer more PACE suites than the document's own, to test a reader against each suite ICAO 9303 defines and its
 * choice among several.
 */
data class AppSettings(
    val developerMode: Boolean = false,
    /** The curve PACE runs on whatever the profile, or null for the profile's (or [paceDhGroup]). */
    val paceCurve: EcCurve? = null,
    /** The Diffie-Hellman group PACE runs in whatever the profile, or null for ECDH; it takes over from [paceCurve]. */
    val paceDhGroup: DhGroup? = null,
    /** The cipher PACE runs with whatever the profile, or null for the profile's. */
    val paceCipher: SessionCipher? = null,
    /** PACE suites EF.CardAccess offers besides the document's own. */
    val extraPaceSuites: List<PaceSuite> = emptyList(),
    /** Whether [extraPaceSuites] come before the document's own suite in EF.CardAccess, rather than after it. */
    val extraPaceSuitesFirst: Boolean = false,
    val theme: AppTheme = AppTheme.SYSTEM,
    /** Whether the screen stays on while the chip is switched on, so a long read isn't cut short by the lock screen. */
    val keepScreenOn: Boolean = true,
    /** Whether reader sessions are recorded in the read history. */
    val keepReadHistory: Boolean = true,
    /** Whether the guided introduction has been seen, so it only opens the app the first time. */
    val introSeen: Boolean = false
) {
    /** The document as the chip emulates it with these settings, which are fixed for the run. */
    fun emulating(passportData: PassportData): PassportData = passportData.copy(
        paceCurve = paceCurve?.takeIf { developerMode },
        paceDhGroup = paceDhGroup?.takeIf { developerMode },
        paceCipher = paceCipher?.takeIf { developerMode },
        extraPaceSuites = if (developerMode) extraPaceSuites else emptyList(),
        extraPaceSuitesFirst = developerMode && extraPaceSuitesFirst
    )
}

enum class AppTheme(val displayName: String) {
    SYSTEM("Same as the phone"),
    LIGHT("Light"),
    DARK("Dark")
}
