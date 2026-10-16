package com.hddev.smartemu.capture

/**
 * Version 1 of the Tier B pack: unauthenticated, but unusual enough that the user confirms first. Nothing here carries
 * a key, a cryptogram or a password, so no counter can be decremented by a correct chip; what Tier B can do is trip a
 * chip's own anomaly handling (a delay, a lock after many odd commands), which is why it isn't Tier A.
 *
 * What it sends: MSE:Set AT and GENERAL AUTHENTICATE step 1 (a PACE start that is never finished), command chaining,
 * the class bytes 00, 0C, 10 and 80, GET DATA, GET RESPONSE and EMV-style commands, SELECT with unusual P1/P2, and
 * READ BINARY past the end of a file or by short file identifier. It never sends INS 20, 24 or 2C (PIN and PUK), a BAC
 * or PACE cryptogram, EXTERNAL AUTHENTICATE, or anything that writes.
 *
 * Published: never change it, add a new version (a test pins its digest).
 */
internal object TierBProbePack {
    private const val AID = Apdu.EMRTD_AID_HEX

    // PACE start: MSE:Set AT (00 22 C1 A4) with a cryptographic mechanism reference (80) and a password reference (83)
    private const val OID_ECDH_GM_AES_128 = "04007F00070202040202" // id-PACE-ECDH-GM-AES-CBC-CMAC-128
    private const val OID_UNSUPPORTED = "04007F00070202046302" // the same arc with an algorithm number no chip assigns
    private const val MSE_UNSUPPORTED_OID = "0022C1A40F800A${OID_UNSUPPORTED}830102"
    private const val MSE_VALID_OID_CAN = "0022C1A40F800A${OID_ECDH_GM_AES_128}830102"
    private const val MSE_VALID_OID_MRZ = "0022C1A40F800A${OID_ECDH_GM_AES_128}830101"
    private const val MSE_NO_OID = "0022C1A403830102"

    /** GENERAL AUTHENTICATE step 1: an empty dynamic authentication data object, which asks for the encrypted nonce. */
    private const val GA_STEP_1 = "00860000027C0000"

    /** The same with CLA 10: command chaining, "more of this command follows". */
    private const val GA_STEP_1_CHAINED = "10860000027C0000"

    private const val SELECT_AID = "00A4040C07$AID"
    private const val SELECT_CARD_ACCESS = "00A4020C02011C"
    private const val SELECT_UNKNOWN_FID = "00A4020C020123"
    private const val GET_CHALLENGE = "0084000008"
    private const val READ_4_NO_FILE = "00B0000004"

    private val classBytes = listOf("00", "0C", "10", "80")

    fun build(): ProbePack = ProbePack(
        id = "tier-b",
        version = 1,
        probes = listOf(
            mseSetAt, abortedPaceThenCommand, generalAuthenticate,
            selectAidClass, selectFidClass, readClass, getChallengeClass,
            unknownInstructions, selectP1P2, readBinaryPastEnd, readBinaryBySfi
        ),
        excluded = excluded
    )

    private fun step(
        id: String,
        setup: ProbeSetup,
        command: String,
        preamble: List<String> = emptyList(),
        repeats: Int = if (preamble.isEmpty()) 3 else 2
    ) = ProbeStep(id, setup, command, repeats, preambleHex = preamble, freshStateEachRepeat = preamble.isNotEmpty())

    private fun probe(id: String, vararg steps: ProbeStep) = Probe(id, ProbeTier.B, steps.toList())

    private val app = ProbeSetup.EMRTD_APPLICATION

    /** MSE:Set AT alone, with the answers a chip gives to a PACE start it can't or can serve. */
    private val mseSetAt = probe(
        "mse-set-at",
        step("unsupported-oid-can", app, MSE_UNSUPPORTED_OID),
        step("valid-oid-can", app, MSE_VALID_OID_CAN),
        step("valid-oid-mrz", app, MSE_VALID_OID_MRZ),
        step("no-oid", app, MSE_NO_OID)
    )

    /**
     * A PACE start that is abandoned, then another command: does the chip keep the half-set state, and does a SELECT
     * clear it? No GENERAL AUTHENTICATE follows the MSE:Set AT, so no password is ever tested.
     */
    private val abortedPaceThenCommand = probe(
        "aborted-pace-start",
        step("unsupported-oid-then-select-aid", app, SELECT_AID, listOf(MSE_UNSUPPORTED_OID)),
        step("unsupported-oid-then-select-card-access", app, SELECT_CARD_ACCESS, listOf(MSE_UNSUPPORTED_OID)),
        step("valid-oid-then-select-aid", app, SELECT_AID, listOf(MSE_VALID_OID_CAN)),
        step("valid-oid-then-select-card-access", app, SELECT_CARD_ACCESS, listOf(MSE_VALID_OID_CAN)),
        step("valid-oid-then-get-challenge", app, GET_CHALLENGE, listOf(MSE_VALID_OID_CAN)),
        step("valid-oid-then-read-no-file", app, READ_4_NO_FILE, listOf(MSE_VALID_OID_CAN)),
        step("valid-oid-then-mse-with-mrz", app, MSE_VALID_OID_MRZ, listOf(MSE_VALID_OID_CAN))
    )

    /**
     * GENERAL AUTHENTICATE step 1 (the nonce request) and then a different command: command-chaining state. Step 1
     * carries no password-derived data, so it can't fail a verification; the chain is never completed.
     */
    private val generalAuthenticate = probe(
        "general-authenticate-chaining",
        step("step-1-without-mse", app, GA_STEP_1),
        step("step-1-after-mse-can", app, GA_STEP_1, listOf(MSE_VALID_OID_CAN)),
        step("step-1-after-mse-mrz", app, GA_STEP_1, listOf(MSE_VALID_OID_MRZ)),
        step("step-1-twice", app, GA_STEP_1, listOf(MSE_VALID_OID_CAN, GA_STEP_1)),
        step("step-1-then-select-aid", app, SELECT_AID, listOf(MSE_VALID_OID_CAN, GA_STEP_1)),
        step("step-1-then-select-card-access", app, SELECT_CARD_ACCESS, listOf(MSE_VALID_OID_CAN, GA_STEP_1)),
        step("step-1-then-get-challenge", app, GET_CHALLENGE, listOf(MSE_VALID_OID_CAN, GA_STEP_1)),
        step("step-1-then-read-no-file", app, READ_4_NO_FILE, listOf(MSE_VALID_OID_CAN, GA_STEP_1)),
        step("chained-cla-10", app, GA_STEP_1_CHAINED, listOf(MSE_VALID_OID_CAN)),
        step("chained-cla-10-then-select-aid", app, SELECT_AID, listOf(MSE_VALID_OID_CAN, GA_STEP_1_CHAINED)),
        step("chained-cla-10-then-last-command", app, GA_STEP_1, listOf(MSE_VALID_OID_CAN, GA_STEP_1_CHAINED))
    )

    /**
     * The same command with each class byte. CLA 0C is the secure-messaging class, but these commands carry no
     * secure-messaging data objects and no session exists, so no MAC is ever wrong.
     */
    private fun classSteps(command: (String) -> String, setup: ProbeSetup) =
        classBytes.map { cla -> step("cla-$cla".lowercase(), setup, command(cla)) }.toTypedArray()

    private val selectAidClass = probe("class-select-aid", *classSteps({ "${it}A4040C07$AID" }, ProbeSetup.MASTER_FILE))
    private val selectFidClass = probe("class-select-fid", *classSteps({ "${it}A4020C02011C" }, ProbeSetup.MASTER_FILE))
    private val readClass = probe("class-read-binary", *classSteps({ "${it}B0000004" }, ProbeSetup.CARD_ACCESS_SELECTED))
    private val getChallengeClass = probe("class-get-challenge", *classSteps({ "${it}84000008" }, app))

    /** Instructions an eMRTD doesn't define: the answer says whether the chip returns 6D00, 6E00, 6985 or something else. */
    private val unknownInstructions = probe(
        "unknown-instructions",
        step("get-data-tag-0000", app, "00CA000000"),
        step("get-data-tag-5f52", app, "00CA5F5200"),
        step("get-data-tag-9f7f", app, "00CA9F7F00"),
        step("get-response-le-08", app, "00C0000008"),
        step("get-response-le-00", app, "00C0000000"),
        step("emv-get-processing-options", app, "80A8000002830000"),
        step("emv-read-record", app, "00B2010C00"),
        step("emv-select-ppse", ProbeSetup.MASTER_FILE, "00A404000E325041592E5359532E444446303100"),
        step("ins-02", app, "0002000000"),
        step("ins-30", app, "0030000000"),
        step("ins-5e", app, "005E000000"),
        step("cla-80-ins-02", app, "8002000000")
    )

    /** SELECT with P1/P2 combinations the ICAO documents don't use; all from the master file. */
    private val selectP1P2 = probe(
        "select-p1-p2",
        step("aid-p1-00-p2-0c", ProbeSetup.MASTER_FILE, "00A4000C07$AID"),
        step("aid-p1-01-p2-0c", ProbeSetup.MASTER_FILE, "00A4010C07$AID"),
        step("aid-p1-02-p2-0c", ProbeSetup.MASTER_FILE, "00A4020C07$AID"),
        step("aid-p1-03-p2-0c", ProbeSetup.MASTER_FILE, "00A4030C07$AID"),
        step("aid-p1-08-p2-0c", ProbeSetup.MASTER_FILE, "00A4080C07$AID"),
        step("aid-p1-09-p2-0c", ProbeSetup.MASTER_FILE, "00A4090C07$AID"),
        step("aid-p2-01", ProbeSetup.MASTER_FILE, "00A4040107$AID"),
        step("aid-p2-02", ProbeSetup.MASTER_FILE, "00A4040207$AID"),
        step("aid-p2-03", ProbeSetup.MASTER_FILE, "00A4040307$AID"),
        step("aid-p2-08-le-00", ProbeSetup.MASTER_FILE, "00A4040807${AID}00"),
        step("aid-p2-10", ProbeSetup.MASTER_FILE, "00A4041007$AID"),
        step("aid-p2-ff", ProbeSetup.MASTER_FILE, "00A404FF07$AID"),
        step("aid-lc-00", ProbeSetup.MASTER_FILE, "00A4040C00"),
        step("aid-short-lc-04", ProbeSetup.MASTER_FILE, "00A4040C04A0000002"),
        step("master-file-no-data-p2-00", ProbeSetup.MASTER_FILE, "00A40000"),
        step("master-file-no-data-p2-0c", ProbeSetup.MASTER_FILE, "00A4000C"),
        step("parent-no-data", ProbeSetup.MASTER_FILE, "00A4030C"),
        step("card-access-by-path-from-mf", ProbeSetup.MASTER_FILE, "00A4080C02011C"),
        step("card-access-by-path-from-current", ProbeSetup.MASTER_FILE, "00A4090C02011C"),
        step("card-access-child-df", ProbeSetup.MASTER_FILE, "00A4010C02011C")
    )

    /** READ BINARY of EF.CardAccess past its end, and after a failed SELECT. */
    private val readBinaryPastEnd = probe(
        "read-binary-past-end",
        step("offset-0100", ProbeSetup.CARD_ACCESS_SELECTED, "00B0010004"),
        step("offset-0200", ProbeSetup.CARD_ACCESS_SELECTED, "00B0020004"),
        step("offset-1000", ProbeSetup.CARD_ACCESS_SELECTED, "00B0100004"),
        step("offset-4000", ProbeSetup.CARD_ACCESS_SELECTED, "00B0400004"),
        step("offset-7fff-le-00", ProbeSetup.CARD_ACCESS_SELECTED, "00B07FFF00"),
        step("offset-08-le-ff", ProbeSetup.CARD_ACCESS_SELECTED, "00B00008FF"),
        step("offset-08-le-00", ProbeSetup.CARD_ACCESS_SELECTED, "00B0000800"),
        step("offset-ff-le-ff", ProbeSetup.CARD_ACCESS_SELECTED, "00B000FFFF"),
        // P1 bit 8 set with SFI 00 means "the file already selected", not a different file
        step("sfi-00-current-file", ProbeSetup.CARD_ACCESS_SELECTED, "00B0800004"),
        step("select-unknown-fid", ProbeSetup.CARD_ACCESS_SELECTED, SELECT_UNKNOWN_FID),
        step("select-unknown-fid-then-read", ProbeSetup.CARD_ACCESS_SELECTED, READ_4_NO_FILE, listOf(SELECT_UNKNOWN_FID))
    )

    /**
     * READ BINARY by short EF identifier (P1 bit 8 set). Every step runs with the MASTER FILE selected and never with
     * the eMRTD application selected: inside the application the same P1 values name the data groups (9E is EF.COM,
     * 9D EF.SOD, 81 is DG1), and a read there could return personal data. In the master file, 9C is EF.CardAccess, 9E
     * EF.DIR and 9F EF.ATR/INFO, all public.
     */
    private val readBinaryBySfi = probe(
        "read-binary-sfi-from-master-file",
        step("ef-dir-sfi-1e", ProbeSetup.MASTER_FILE, "00B09E0004"),
        step("ef-atr-info-sfi-1f", ProbeSetup.MASTER_FILE, "00B09F0004"),
        step("card-access-sfi-1c-le-00", ProbeSetup.MASTER_FILE, "00B09C0000"),
        step("card-access-sfi-1c-offset-01", ProbeSetup.MASTER_FILE, "00B09C0104"),
        step("card-access-sfi-1c-le-ff", ProbeSetup.MASTER_FILE, "00B09C00FF"),
        step("card-security-sfi-1d", ProbeSetup.MASTER_FILE, "00B09D0004"),
        step("p1-c0-reserved-bits", ProbeSetup.MASTER_FILE, "00B0C00004")
    )

    /** What Tier B never sends, so that nobody adds it here by accident; Tier C holds the one thing that may count. */
    private val excluded = listOf(
        "VERIFY, CHANGE REFERENCE DATA and RESET RETRY COUNTER (INS 20, 24, 2C): PIN and PUK attempts decrement a retry counter",
        "GENERAL AUTHENTICATE with a mapping, key agreement or authentication-token step (step 2 onward): it tests the password, and a failed test counts",
        "Any MSE:Set AT followed by a command that carries a password-derived value: an aborted PACE start is probed, a wrong password never is",
        "EXTERNAL AUTHENTICATE of any kind: BAC attempts with a wrong key are in Tier C, sacrificial cards only",
        "Repeated CAN failures, with PACE or any other command, in any tier: some chips lock the CAN after a few",
        "Secure-messaging commands with a MAC (CLA 0C with data objects 87, 8E): a wrong MAC aborts the session; Tier B sends CLA 0C with no data objects only",
        "READ BINARY by short EF identifier with the eMRTD application selected: the identifiers name the data groups and could return personal data",
        "READ BINARY of any data group, EF.SOD or EF.COM: only the files readable before authentication are read, and no step stores response data",
        "WRITE, UPDATE, ERASE, DELETE, PUT DATA, MANAGE CHANNEL, ACTIVATE and DEACTIVATE FILE and anything else that changes the chip",
        "INTERNAL AUTHENTICATE (Active Authentication), PSO and MSE:Set DST (Terminal Authentication): need an authenticated session"
    )
}
