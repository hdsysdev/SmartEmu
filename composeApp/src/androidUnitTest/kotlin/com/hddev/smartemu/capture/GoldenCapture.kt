package com.hddev.smartemu.capture

/**
 * The sample record behind golden-capture-v1.json (as a version 1 record wrote it, with the fingerprint of every
 * observation) and golden-capture-v2.json (the same chip, upgraded: [record]); see [CaptureGoldenFileTest].
 */
object GoldenCapture {
    /** The record as schema version 1 wrote it. */
    val recordV1: CaptureRecord = unsealedV1().let { it.copy(fingerprint = CaptureFingerprint.legacyV1Of(it)) }

    /** The record as the current schema writes it: [recordV1], upgraded. */
    val record: CaptureRecord = CaptureMigration.toCurrent(recordV1)

    private fun unsealedV1() = CaptureRecord(
        schemaVersion = 1,
        redactions = RedactionManifest.entriesV1,
        captureId = "3b0d6c2e-7a41-4d8e-9c55-0f7e1a2b3c4d",
        captureMonth = "2026-09",
        source = CaptureSource.ANDROID_READER,
        probePack = ProbePackRef("tier-a", 1),
        reader = ReaderDevice(
            model = "Pixel 9",
            androidVersion = "15",
            nfcChipset = "ST54K",
            controllerMaxTransceiveLength = 65279,
            controllerExtendedLengthSupported = true
        ),
        link = LinkObservation(NfcTechnology.NFC_A, historicalBytes = "8000", uidRandom = false, maxApduLengthObserved = 223),
        files = UnauthenticatedFiles(
            cardAccess = FileObservation(0x9000, "31143012060A04007F0007020204020202010202010C", Redaction.sizeRange(22)),
            atrInfo = FileObservation(0x6A82),
            dir = FileObservation(0x9000, "61094F07A0000002471001", Redaction.sizeRange(11))
        ),
        application = ApplicationObservation(0x9000, Redaction.probeSizeRange(0)),
        bacCommands = BacCommandObservation(getChallenge = 0x9000, externalAuthenticate = 0x6985),
        probes = listOf(
            ProbeResult(
                "read-binary-max-length", 1, ProbeTier.A, listOf(
                    StepResult("le-df", 0x9000, Redaction.probeSizeRange(22), TimingSummary(12, 13, 15, 3), setupStatusWord = 0x9000),
                    StepResult("extended-le-ffff", 0x6700, Redaction.probeSizeRange(0), TimingSummary(10, 10, 11, 3), setupStatusWord = 0x9000)
                )
            ),
            ProbeResult(
                "external-authenticate-out-of-sequence", 1, ProbeTier.A, listOf(
                    StepResult("lc-40-le-28", 0x6985, Redaction.probeSizeRange(0), TimingSummary(9, 9, 9, 1), setupStatusWord = 0x9000)
                )
            )
        ),
        aborted = null
    )
}
