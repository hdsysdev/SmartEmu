package com.hddev.smartemu.data

/**
 * Access control protocols the emulated chip accepts before it releases the data groups.
 * With PACE, the chip publishes EF.CardAccess so readers try PACE first; without it, readers fall back to BAC.
 */
enum class AccessControl(
    val displayName: String,
    val description: String,
    val supportsBac: Boolean,
    val supportsPace: Boolean
) {
    BAC_AND_PACE(
        "BAC and PACE",
        "Advertises PACE in EF.CardAccess and still accepts BAC from older readers",
        supportsBac = true,
        supportsPace = true
    ),
    PACE_ONLY("PACE only", "Rejects BAC, so readers must use PACE", supportsBac = false, supportsPace = true),
    BAC_ONLY("BAC only", "No EF.CardAccess, so readers use BAC with the MRZ", supportsBac = true, supportsPace = false)
}
