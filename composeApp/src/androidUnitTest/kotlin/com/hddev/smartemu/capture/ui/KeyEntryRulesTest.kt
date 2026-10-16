package com.hddev.smartemu.capture.ui

import com.hddev.smartemu.capture.CredentialField
import com.hddev.smartemu.capture.CredentialProblemKind
import com.hddev.smartemu.capture.MrzCheck
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyEntryRulesTest {
    private val today = LocalDate(2026, 10, 16)
    private val number = "L898902C3"
    private val birth = "740812"
    private val expiry = "340415"

    private fun withDigit(field: String) = field + MrzCheck.checkDigit(field)

    @Test
    fun `typed MRZ fields that are right, with their check digits, have no problems`() {
        assertEquals(emptyList(), KeyEntryRules.mrzProblems(withDigit(number), withDigit(birth), withDigit(expiry), today))
    }

    @Test
    fun `fields typed without their check digits are not ready because the digits are required`() {
        val problems = KeyEntryRules.mrzProblems(number, birth, expiry, today)
        // The document number's last digit is read as its check digit and doesn't fit; the dates have none
        assertEquals(
            setOf(
                CredentialField.DATE_OF_BIRTH to CredentialProblemKind.CHECK_DIGIT_MISSING,
                CredentialField.DATE_OF_EXPIRY to CredentialProblemKind.CHECK_DIGIT_MISSING
            ),
            problems.filter { it.credentialField != CredentialField.DOCUMENT_NUMBER }.map { it.credentialField to it.kind }.toSet()
        )
        assertFalse(KeyEntryRules.isReady(problems))
        // A number that ends in a letter has no digit to take, so it is reported missing, in plain language
        val letters = KeyEntryRules.mrzProblems("ABCDEFGHI", withDigit(birth), withDigit(expiry), today)
        assertEquals(listOf(CredentialField.DOCUMENT_NUMBER to CredentialProblemKind.CHECK_DIGIT_MISSING), letters.map { it.credentialField to it.kind })
        assertEquals(
            "The check digit after the document number is missing. Type it as printed on the document.",
            KeyEntryRules.messageFor(CredentialField.DOCUMENT_NUMBER, letters)
        )
        assertTrue(KeyEntryRules.shown(letters).isNotEmpty(), "a missing digit is shown, unlike an empty field")
    }

    @Test
    fun `a typo is found by the check digit and named in plain language`() {
        val problems = KeyEntryRules.mrzProblems(withDigit(number), "740813" + MrzCheck.checkDigit(birth), withDigit(expiry), today)
        assertEquals(listOf(CredentialField.DATE_OF_BIRTH to CredentialProblemKind.CHECK_DIGIT_MISMATCH), problems.map { it.credentialField to it.kind })
        assertEquals(
            "The date of birth and its check digit don't match. Check what you typed against the document.",
            KeyEntryRules.messageFor(CredentialField.DATE_OF_BIRTH, problems)
        )
        assertNull(KeyEntryRules.messageFor(CredentialField.DOCUMENT_NUMBER, problems))
    }

    @Test
    fun `an untouched form is not full of errors but is not ready either`() {
        val problems = KeyEntryRules.mrzProblems("", "", "", today)
        assertEquals(3, problems.size)
        assertTrue(problems.all { it.kind == CredentialProblemKind.EMPTY })
        assertEquals(emptyList(), KeyEntryRules.shown(problems))
        assertFalse(KeyEntryRules.isReady(problems))
    }

    @Test
    fun `the date of birth after the expiry is reported once the fields are complete`() {
        val problems = KeyEntryRules.mrzProblems(withDigit(number), withDigit("250101"), withDigit("200101"), today)
        assertTrue(problems.any { it.kind == CredentialProblemKind.DATES_INCONSISTENT })
        assertTrue(KeyEntryRules.shown(problems).isNotEmpty())
    }

    @Test
    fun `a CAN is six digits`() {
        assertTrue(KeyEntryRules.isReady(KeyEntryRules.canProblems("314159", today)))
        assertEquals(CredentialProblemKind.WRONG_LENGTH, KeyEntryRules.canProblems("31415", today).single().kind)
        assertEquals(CredentialProblemKind.INVALID_CHARACTERS, KeyEntryRules.canProblems("31415x", today).single().kind)
        assertEquals("The card access number (CAN) has 6 digits.", KeyEntryRules.canProblems("31415", today).single().message)
    }

    @Test
    fun `no problem message carries the typed value`() {
        val typed = "SECRET123"
        val messages = KeyEntryRules.mrzProblems(typed, "19AA", "00", today).map { it.message } +
            KeyEntryRules.canProblems("99999x", today).map { it.message }
        assertTrue(messages.isNotEmpty())
        messages.forEach { assertFalse(it.contains("SECRET") || it.contains("99999"), it) }
    }

    @Test
    fun `typing is shaped to a length, and the document number to capitals`() {
        assertEquals("L898902C3", KeyEntryRules.shapeDocumentNumber("l898902c3"))
        assertEquals(KeyEntryRules.MAX_DOCUMENT_NUMBER_INPUT, KeyEntryRules.shapeDocumentNumber("A".repeat(30)).length)
        assertEquals(KeyEntryRules.MAX_DATE_INPUT, KeyEntryRules.shapeDate("1234567890").length)
        assertEquals(KeyEntryRules.MAX_CAN_INPUT, KeyEntryRules.shapeCan("1234567890").length)
    }

    @Test
    fun `fields hold text in sensitive state, build credentials and clear`() {
        val fields = KeyEntryFields()
        fields.documentNumber.update("l898902c3" + MrzCheck.checkDigit(number))
        fields.dateOfBirth.update(withDigit(birth))
        fields.dateOfExpiry.update(withDigit(expiry))
        assertEquals("L898902C3" + MrzCheck.checkDigit(number), fields.documentNumber.text)
        assertEquals(emptyList(), fields.mrzProblems(today))

        val credentials = fields.buildMrz()
        assertEquals(emptyList(), credentials.validate(today))
        credentials.close()

        fields.showText = true
        fields.clearAll()
        assertEquals("", fields.documentNumber.text)
        assertEquals("", fields.dateOfBirth.text)
        assertEquals("", fields.dateOfExpiry.text)
        assertEquals("", fields.can.text)
        assertFalse(fields.showText)
    }

    @Test
    fun `building credentials to check them leaves nothing open`() {
        // The checking helpers close what they build; a second call works on fresh ones
        repeat(2) { assertEquals(emptyList(), KeyEntryRules.mrzProblems(withDigit(number), withDigit(birth), withDigit(expiry), today)) }
    }
}
