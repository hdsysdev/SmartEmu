package com.hddev.smartemu.capture.ui

import com.hddev.smartemu.capture.AccessAttempt
import com.hddev.smartemu.capture.AccessMethod
import com.hddev.smartemu.capture.AccessStop
import com.hddev.smartemu.capture.AuthenticatedObservation
import com.hddev.smartemu.capture.AuthenticationResult
import com.hddev.smartemu.capture.CredentialKind
import com.hddev.smartemu.capture.PaceMappingType
import com.hddev.smartemu.capture.SecurityInfoKind
import com.hddev.smartemu.capture.SecurityInfoObservation
import com.hddev.smartemu.capture.SecurityInfosObservation
import com.hddev.smartemu.capture.auth.AuthenticatedSteps

/** One line of the authenticated part of a capture: what it is, and what was seen. */
data class SummaryRow(val label: String, val value: String)

data class SummarySection(val title: String, val rows: List<SummaryRow>)

/** How the attempt to open the chip with the key ended, for the plain-language notice. */
enum class AccessOutcome {
    /** The chip opened and was read. */
    OPENED,

    /** The key was refused; nothing more was tried. */
    KEY_REFUSED,

    /** The chip said the access method is locked. */
    BLOCKED,

    /** 6985 after a failed try: the chip may be counting. */
    CONDITIONS_NOT_SATISFIED,

    /** The CAN was tried once and failed. */
    CAN_FAILED,

    /** The person declined to try the CAN. */
    DECLINED,

    /** A CAN was given, and the chip offers no PACE for it. */
    CAN_NEEDS_PACE,

    /** No attempt opened the chip, and none was refused (for instance, every suite was unsupported). */
    NOT_OPENED
}

/** A notice for the person: [title] and [text] in plain words; [problem] where it needs more than a note. */
data class OutcomeNotice(val title: String, val text: String, val problem: Boolean)

/**
 * The authenticated part of a capture as plain rows, built from the record's [AuthenticatedObservation] alone. The
 * screen's "what will be saved" preview and the text report both use it, so what the person agrees to is what is
 * written. Nothing here is read from the document or the key: the observation holds only structure.
 */
object AuthenticatedSummary {

    /** The words the preview and the report both carry: the key and the document's data were used in memory only. */
    const val MEMORY_ONLY_NOTE =
        "The key you typed and the document's personal data were used in memory only, to open the chip and check " +
            "which data groups it has. They are NOT saved, and nothing of them is in this capture."

    fun outcome(observation: AuthenticatedObservation): AccessOutcome {
        if (observation.readUnder != null && observation.access.any { it.succeeded }) return AccessOutcome.OPENED
        return when (observation.accessStopped) {
            AccessStop.KEY_REJECTED -> AccessOutcome.KEY_REFUSED
            AccessStop.BLOCKED -> AccessOutcome.BLOCKED
            AccessStop.CONDITIONS_NOT_SATISFIED -> AccessOutcome.CONDITIONS_NOT_SATISFIED
            AccessStop.CAN_SINGLE_ATTEMPT_FAILED -> AccessOutcome.CAN_FAILED
            AccessStop.DECLINED_BY_USER -> AccessOutcome.DECLINED
            AccessStop.CAN_NEEDS_PACE -> AccessOutcome.CAN_NEEDS_PACE
            null -> AccessOutcome.NOT_OPENED
        }
    }

    fun notice(outcome: AccessOutcome): OutcomeNotice = when (outcome) {
        AccessOutcome.OPENED -> OutcomeNotice(
            "The document accepted the key",
            "The chip was opened and read. What was seen is listed below.",
            problem = false
        )
        AccessOutcome.KEY_REFUSED -> OutcomeNotice(
            "The document did not accept this key",
            "The document did not accept this key. Check the details and try again; stopped after one try to protect the card.",
            problem = true
        )
        AccessOutcome.CAN_FAILED -> OutcomeNotice(
            "The document did not accept the CAN",
            "The CAN is tried once only, and this try did not open the document. It may count against the card. Check the " +
                "number on the card and ask the profile steward before another go. Stopped to protect the card.",
            problem = true
        )
        AccessOutcome.BLOCKED -> OutcomeNotice(
            "The document reports this way in is locked",
            "Stopped. Do not try again. Tell the profile steward which document it was.",
            problem = true
        )
        AccessOutcome.CONDITIONS_NOT_SATISFIED -> OutcomeNotice(
            "The document would not go on",
            "After a failed try the document answered \"conditions not satisfied\", which can mean it is counting failures. " +
                "Stopped to protect it. Do not try again until you have checked the details.",
            problem = true
        )
        AccessOutcome.DECLINED -> OutcomeNotice(
            "Stopped before reading",
            "You chose not to try the CAN, so nothing was sent with it.",
            problem = false
        )
        AccessOutcome.CAN_NEEDS_PACE -> OutcomeNotice(
            "A CAN can't open this document",
            "A CAN works only through PACE, and this chip offers none. Nothing was tried with the CAN. If the document has a " +
                "machine-readable zone, read it with the MRZ instead.",
            problem = false
        )
        AccessOutcome.NOT_OPENED -> OutcomeNotice(
            "No method opened the document",
            "The chip did not support any way in that the key fits. Nothing was refused. Only the public part was read.",
            problem = false
        )
    }

    /** What each stage of the authenticated read is called in progress text, in plain words. */
    fun stepLabel(step: String): String = when (step) {
        AuthenticatedSteps.CARD_ACCESS -> "Checking what the document offers"
        AuthenticatedSteps.ACCESS -> "Opening the document with the key"
        AuthenticatedSteps.EF_COM -> "Listing the data groups"
        AuthenticatedSteps.EF_SOD -> "Reading the document's signature info"
        AuthenticatedSteps.DATA_GROUPS -> "Checking which data groups are there"
        AuthenticatedSteps.DG14 -> "Reading the security protocols"
        AuthenticatedSteps.DG15 -> "Checking for Active Authentication"
        AuthenticatedSteps.CVCA -> "Reading the certificate authority reference"
        AuthenticatedSteps.ACTIVE_AUTHENTICATION -> "Testing Active Authentication"
        AuthenticatedSteps.CHIP_AUTHENTICATION -> "Testing Chip Authentication"
        else -> step
    }

    /** The sections, in the order the preview and the report show them. */
    fun sections(observation: AuthenticatedObservation): List<SummarySection> = buildList {
        add(SummarySection("How the document was opened", accessRows(observation)))
        observation.com?.let { com ->
            add(
                SummarySection(
                    "EF.COM",
                    listOfNotNull(
                        com.ldsVersion?.let { SummaryRow("LDS version", it) },
                        com.unicodeVersion?.let { SummaryRow("Unicode version", it) },
                        SummaryRow("Data groups listed", dataGroupList(com.dataGroups)),
                        com.size?.let { SummaryRow("Size", CaptureReport.sizeText(it)) }
                    )
                )
            )
        }
        if (observation.dataGroups.isNotEmpty()) {
            add(SummarySection("Data groups (presence and rounded size, never contents)", dataGroupRows(observation)))
        }
        observation.sod?.let { sod ->
            add(
                SummarySection(
                    "EF.SOD (the document's signature)",
                    listOfNotNull(
                        sod.size?.let { SummaryRow("Size", CaptureReport.sizeText(it)) },
                        sod.digestAlgorithm?.let { SummaryRow("Digest algorithm", it) },
                        sod.signatureAlgorithm?.let { SummaryRow("Signature algorithm", it) },
                        sod.ldsVersion?.let { SummaryRow("LDS version", it) },
                        sod.unicodeVersion?.let { SummaryRow("Unicode version", it) },
                        SummaryRow("Data groups it signs", dataGroupList(sod.hashedDataGroups) + " (the hash values are not saved)"),
                        SummaryRow("Certificates inside", sod.certificateCount.toString())
                    ) + (sod.signer?.let { signer ->
                        listOfNotNull(
                            signer.signatureAlgorithm?.let { SummaryRow("Signer: signature algorithm", it) },
                            keyText(signer.keyType, signer.keyBits, signer.curve)?.let { SummaryRow("Signer: key", it) },
                            signer.issuerCountry?.let { SummaryRow("Signer: issuing country", it) },
                            signer.validityMonths?.let { SummaryRow("Signer: validity", "$it months") }
                        )
                    } ?: emptyList())
                )
            )
        }
        observation.dg14?.let { add(SummarySection("DG14 (security protocols)", securityInfoRows(it))) }
        observation.dg15?.let { key ->
            add(
                SummarySection(
                    "DG15 (Active Authentication key: its kind and size only)",
                    listOfNotNull(
                        key.size?.let { SummaryRow("Size", CaptureReport.sizeText(it)) },
                        SummaryRow("Key", keyText(key.keyType, key.keyBits, key.curve) ?: "unknown")
                    )
                )
            )
        }
        observation.cardSecurity?.let { add(SummarySection("EF.CardSecurity (structure only)", securityInfoRows(it))) }
        observation.cvca?.let { cvca ->
            add(
                SummarySection(
                    "EF.CVCA",
                    listOfNotNull(
                        cvca.size?.let { SummaryRow("Size", CaptureReport.sizeText(it)) },
                        SummaryRow("Certification authority country", cvca.carCountry ?: "unknown"),
                        SummaryRow("Alternate reference", if (cvca.hasAlternateReference) "yes" else "no")
                    )
                )
            )
        }
        add(
            SummarySection(
                "Authentication tests",
                listOf(
                    SummaryRow("Active Authentication", authenticationText(observation.activeAuthentication, "the chip offers no Active Authentication key")),
                    SummaryRow("Chip Authentication", authenticationText(observation.chipAuthentication, "the chip offers no Chip Authentication"))
                )
            )
        )
    }

    private fun accessRows(observation: AuthenticatedObservation): List<SummaryRow> = buildList {
        add(SummaryRow("Key used", if (observation.credential == CredentialKind.CAN) "CAN (card access number)" else "MRZ (document number, birth and expiry dates)"))
        if (observation.access.isEmpty()) add(SummaryRow("Attempts", "none was made"))
        observation.access.forEachIndexed { index, attempt ->
            add(SummaryRow("Attempt ${index + 1}", attemptText(attempt) + if (observation.readUnder == index) "; the files were read in this one" else ""))
        }
        observation.accessStopped?.let { add(SummaryRow("Stopped because", stopText(it))) }
        add(SummaryRow("DG3 (fingerprints) without Terminal Authentication", refusalText(observation, 3)))
        add(SummaryRow("DG4 (iris) without Terminal Authentication", refusalText(observation, 4)))
    }

    private fun attemptText(attempt: AccessAttempt): String {
        val what = when (attempt.method) {
            AccessMethod.BAC -> "BAC"
            AccessMethod.PACE -> listOfNotNull(
                "PACE",
                attempt.mapping?.let(::mappingText),
                attempt.cipher,
                attempt.parameterId?.let { "parameter ID $it" }
            ).joinToString(", ")
        }
        val result = when {
            attempt.succeeded -> "opened" + if (attempt.camVerified == true) " (chip's key matched the PACE-CAM mapping)" else ""
            attempt.statusWord != null -> "not opened, answered ${CaptureReport.statusWord(attempt.statusWord)} (${attempt.statusClass.name.lowercase().replace('_', ' ')})"
            else -> "not opened, no answer (${attempt.statusClass.name.lowercase().replace('_', ' ')})"
        }
        return "$what: $result"
    }

    private fun mappingText(mapping: PaceMappingType): String = when (mapping) {
        PaceMappingType.GM -> "Generic Mapping"
        PaceMappingType.IM -> "Integrated Mapping"
        PaceMappingType.CAM -> "Chip Authentication Mapping"
    }

    private fun stopText(stop: AccessStop): String = when (stop) {
        AccessStop.KEY_REJECTED -> "the key was refused (the same key is never tried again)"
        AccessStop.BLOCKED -> "the document reported the access method is blocked"
        AccessStop.CONDITIONS_NOT_SATISFIED -> "the document answered \"conditions not satisfied\" after a failure, which can mean it is counting"
        AccessStop.CAN_SINGLE_ATTEMPT_FAILED -> "the CAN is tried once, and that try failed"
        AccessStop.DECLINED_BY_USER -> "you declined to try the CAN"
        AccessStop.CAN_NEEDS_PACE -> "a CAN needs PACE, and the chip offers none"
    }

    private fun refusalText(observation: AuthenticatedObservation, number: Int): String {
        val group = observation.dataGroups.firstOrNull { it.number == number } ?: return "not on this chip"
        val sw = group.readStatusWord ?: group.selectStatusWord ?: return "no answer"
        return if (sw == 0x9000) "readable without it (unusual)" else "refused, ${CaptureReport.statusWord(sw)}"
    }

    private fun dataGroupList(numbers: List<Int>): String =
        if (numbers.isEmpty()) "none" else numbers.sorted().joinToString(", ") { "DG$it" }

    private fun dataGroupRows(observation: AuthenticatedObservation): List<SummaryRow> = observation.dataGroups.map { group ->
        val parts = buildList {
            add("in EF.COM: ${if (group.inCom) "yes" else "no"}")
            add("in EF.SOD: ${if (group.inSod) "yes" else "no"}")
            group.selectStatusWord?.let { add("select ${CaptureReport.statusWord(it)}") }
            group.readStatusWord?.let { add("read ${CaptureReport.statusWord(it)}") }
            group.size?.let { add("size ${CaptureReport.sizeText(it)}") }
        }
        SummaryRow("DG${group.number}", parts.joinToString("; "))
    }

    private fun securityInfoRows(infos: SecurityInfosObservation): List<SummaryRow> = buildList {
        infos.size?.let { add(SummaryRow("Size", CaptureReport.sizeText(it))) }
        add(SummaryRow("Entries", infos.infos.size.toString()))
        add(SummaryRow("PACE with Chip Authentication Mapping", if (infos.paceCamPresent) "present" else "not present"))
        infos.infos.forEachIndexed { index, info -> add(SummaryRow("Entry ${index + 1}", securityInfoText(info))) }
    }

    private fun securityInfoText(info: SecurityInfoObservation): String = buildList {
        add(kindText(info.kind))
        add(info.oid)
        info.version?.let { add("version $it") }
        info.parameterId?.let { add("ID $it") }
        keyText(info.keyType, info.keyBits, info.curve)?.let { add("key $it") }
    }.joinToString(", ")

    private fun kindText(kind: SecurityInfoKind): String = when (kind) {
        SecurityInfoKind.PACE -> "PACE"
        SecurityInfoKind.PACE_DOMAIN_PARAMETERS -> "PACE domain parameters"
        SecurityInfoKind.CHIP_AUTHENTICATION -> "Chip Authentication"
        SecurityInfoKind.CHIP_AUTHENTICATION_PUBLIC_KEY -> "Chip Authentication public key (type and size only)"
        SecurityInfoKind.TERMINAL_AUTHENTICATION -> "Terminal Authentication"
        SecurityInfoKind.ACTIVE_AUTHENTICATION -> "Active Authentication"
        SecurityInfoKind.EF_DIR -> "EF.DIR"
        SecurityInfoKind.OTHER -> "Other"
    }

    private fun keyText(type: String?, bits: Int?, curve: String?): String? {
        val parts = listOfNotNull(type, bits?.let { "$it bits" }, curve)
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    private fun authenticationText(result: AuthenticationResult?, none: String): String {
        if (result == null) return "not tested: $none"
        val head = if (result.succeeded) "succeeded" else "did not succeed"
        return listOfNotNull(
            head,
            result.algorithm,
            result.statusWord?.let { "answered ${CaptureReport.statusWord(it)}" }
        ).joinToString(", ")
    }
}
