package com.hddev.smartemu.capture

/**
 * Version 1 of the Tier C pack, for sacrificial and test cards only: it may touch a counter, so [CaptureRunner] runs
 * it only when told `acknowledgeSacrificialCard = true`.
 *
 * What it does: asks for a challenge, answers it with EXTERNAL AUTHENTICATE carrying a cryptogram that no key
 * produced (the bytes 00 to 27, a constant), and does that at most [WRONG_ATTEMPTS] times, each from a fresh SELECT
 * and a fresh challenge, so that the status word of each attempt shows when a chip starts to delay, change its
 * answer or lock. Then it checks the chip still answers GET CHALLENGE and EF.CardAccess. The attempts are separate
 * steps, one command each (`repeats = 1`), so a status word per attempt is kept, not only the first.
 *
 * What it never does, in this or any pack: PIN or PUK commands, any attempt with the right key (the pack has no
 * key), any PACE step with a password, or a second failed attempt beyond [WRONG_ATTEMPTS]. A blocked status word
 * (6983, 6984) or a retry counter at 1 or 0 (63C1, 63C0) ends the run at once; 6300 and 63C2 and up, which wrong keys
 * are expected to get, do not (see [BlockedStatus.wrongKeyProbes]).
 *
 * Published: never change it, add a new version (a test pins its digest).
 */
internal object TierCProbePack {
    /** The most wrong-key EXTERNAL AUTHENTICATE commands the pack sends. */
    const val WRONG_ATTEMPTS = 5

    private const val GET_CHALLENGE = "0084000008"

    /** 40 bytes, the length of a BAC cryptogram plus MAC (32 + 8), that no BAC key derives: 00 01 02 ... 27. */
    private val wrongCryptogram = (0 until 40).joinToString("") { "%02X".format(it) }
    private const val EXTERNAL_AUTHENTICATE_HEADER = "0082000028"
    private val wrongAttempt = "$EXTERNAL_AUTHENTICATE_HEADER${wrongCryptogram}28"

    fun build(): ProbePack = ProbePack(
        id = "tier-c",
        version = 1,
        probes = listOf(wrongKeyBac),
        excluded = excluded
    )

    private val wrongKeyBac = Probe(
        "bac-wrong-key", ProbeTier.C,
        listOf(ProbeStep("get-challenge-before-attempts", ProbeSetup.EMRTD_APPLICATION, GET_CHALLENGE, repeats = 1)) +
            (1..WRONG_ATTEMPTS).map { attempt ->
                ProbeStep(
                    "wrong-cryptogram-attempt-$attempt", ProbeSetup.EMRTD_APPLICATION, wrongAttempt, repeats = 1,
                    preambleHex = listOf(GET_CHALLENGE), freshStateEachRepeat = true
                )
            } +
            listOf(
                ProbeStep("get-challenge-after-attempts", ProbeSetup.EMRTD_APPLICATION, GET_CHALLENGE, repeats = 1),
                ProbeStep("card-access-read-after-attempts", ProbeSetup.CARD_ACCESS_SELECTED, "00B0000004", repeats = 1)
            )
    )

    private val excluded = listOf(
        "VERIFY, CHANGE REFERENCE DATA and RESET RETRY COUNTER (INS 20, 24, 2C): PIN and PUK attempts decrement a retry counter; never in any pack",
        "Repeated CAN failures (PACE with a wrong CAN): some chips lock the CAN or the whole chip after a few; excluded in every tier, with no exception for sacrificial cards, because the retry state is not always reset by reset or power cycle",
        "PACE with any wrong password (CAN, MRZ or PIN) and GENERAL AUTHENTICATE step 2 onward: a failed attempt decrements the PACE retry counter, and the plan's failed-PACE behaviour is covered by Tier B's aborted PACE start without a password test",
        "More than $WRONG_ATTEMPTS wrong-key EXTERNAL AUTHENTICATE commands: the pack stops at a bounded number and never probes for the lock-out point",
        "Any BAC attempt with a key that could be right (a guess, a key from a dictionary or from the document): the cryptogram is a constant no key derives",
        "Secure-messaging commands with a wrong MAC: they abort the session and may count as a failure; not needed to see the BAC counter",
        "Terminal Authentication and Chip Authentication steps with wrong data: not unauthenticated, and not needed to see a counter",
        "WRITE, UPDATE, ERASE, DELETE and anything else that changes the chip"
    )
}
