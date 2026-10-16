package com.hddev.smartemu.capture.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hddev.smartemu.capture.AccessCredentials
import com.hddev.smartemu.capture.CredentialField
import com.hddev.smartemu.capture.CredentialProblem
import com.hddev.smartemu.capture.CredentialProblemKind
import com.hddev.smartemu.capture.use
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The rules of the key entry screen, with no Compose in them: how typed text is shaped, how it becomes
 * [AccessCredentials], and which of the credentials' problems are worth showing while the person is still typing.
 * Validation is [AccessCredentials.validate], so what the screen accepts is exactly what the capture accepts.
 */
object KeyEntryRules {
    /** A document number is up to 14 characters, and one more where its check digit is typed after it. */
    const val MAX_DOCUMENT_NUMBER_INPUT = 15

    /** A date is YYMMDD, and one more digit where its check digit is typed after it. */
    const val MAX_DATE_INPUT = 7

    /** The CAN has six digits; the field takes a little more so that a typo shows as a length problem. */
    const val MAX_CAN_INPUT = 8

    fun shapeDocumentNumber(text: String): String = text.uppercase().take(MAX_DOCUMENT_NUMBER_INPUT)
    fun shapeDate(text: String): String = text.take(MAX_DATE_INPUT)
    fun shapeCan(text: String): String = text.take(MAX_CAN_INPUT)

    fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.UTC).date

    /**
     * The MRZ credentials for the three fields, each ending in its check digit as the MRZ line prints it
     * ("L898902C36", "7408122", "9406236"). The digits are required: a field without one is reported by validation
     * as [CredentialProblemKind.CHECK_DIGIT_MISSING], and one that doesn't fit its field as a mismatch.
     */
    fun mrz(documentNumber: String, dateOfBirth: String, dateOfExpiry: String): AccessCredentials.Mrz =
        AccessCredentials.Mrz.fromFieldsWithCheckDigits(documentNumber, dateOfBirth, dateOfExpiry)

    fun can(can: String): AccessCredentials.Can = AccessCredentials.Can.of(can)

    /** Every problem with typed MRZ fields as of [today]. The credentials built to check them are erased at once. */
    fun mrzProblems(
        documentNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String,
        today: LocalDate
    ): List<CredentialProblem> = mrz(documentNumber, dateOfBirth, dateOfExpiry).use { it.validate(today) }

    fun canProblems(can: String, today: LocalDate): List<CredentialProblem> = can(can).use { it.validate(today) }

    /**
     * The problems to show while typing: a missing field is not one yet (the Continue button stays off until nothing is
     * missing, and a field that was never touched is not an error).
     */
    fun shown(problems: List<CredentialProblem>): List<CredentialProblem> = problems.filter { it.kind != CredentialProblemKind.EMPTY }

    /** The plain-language message for [field], or null if it has no problem. */
    fun messageFor(field: CredentialField, problems: List<CredentialProblem>): String? =
        problems.firstOrNull { it.credentialField == field }?.message

    /** Whether the credentials may be tried: nothing is missing and nothing is wrong. */
    fun isReady(problems: List<CredentialProblem>): Boolean = problems.isEmpty()
}

/**
 * What the key entry screen has been typed. Every value is in a [SensitiveInputState], which is not saved with the
 * instance state and is cleared by [clearAll] and when the screen leaves the composition.
 */
@Stable
class KeyEntryFields {
    val documentNumber = SensitiveInputState(KeyEntryRules::shapeDocumentNumber)
    val dateOfBirth = SensitiveInputState(KeyEntryRules::shapeDate)
    val dateOfExpiry = SensitiveInputState(KeyEntryRules::shapeDate)
    val can = SensitiveInputState(KeyEntryRules::shapeCan)

    /** Whether the typed text is shown rather than dots. */
    var showText by mutableStateOf(false)

    fun mrzProblems(today: LocalDate): List<CredentialProblem> =
        KeyEntryRules.mrzProblems(documentNumber.text, dateOfBirth.text, dateOfExpiry.text, today)

    fun canProblems(today: LocalDate): List<CredentialProblem> = KeyEntryRules.canProblems(can.text, today)

    /** The credentials to start with: built from the typed text, which stays until [clearAll]. */
    fun buildMrz(): AccessCredentials.Mrz =
        KeyEntryRules.mrz(documentNumber.text, dateOfBirth.text, dateOfExpiry.text)

    fun buildCan(): AccessCredentials.Can = KeyEntryRules.can(can.text)

    fun clearAll() {
        documentNumber.clear()
        dateOfBirth.clear()
        dateOfExpiry.clear()
        can.clear()
        showText = false
    }
}

@Composable
fun rememberKeyEntryFields(): KeyEntryFields {
    val fields = remember { KeyEntryFields() }
    DisposableEffect(fields) { onDispose { fields.clearAll() } }
    return fields
}
