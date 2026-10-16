package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.AccessCredentials
import com.hddev.smartemu.capture.CredentialField
import com.hddev.smartemu.capture.CredentialKind
import com.hddev.smartemu.capture.CredentialProblem
import com.hddev.smartemu.capture.CredentialProblemKind
import com.hddev.smartemu.capture.MrzCheck
import com.hddev.smartemu.capture.use
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessCredentialsTest {
    private val today = LocalDate(2026, 10, 16)

    /** The specimen of ICAO 9303 part 3: L898902C3 / 740812 / 120415 with check digits 6, 2 and 9. */
    private fun specimen(
        number: String = "L898902C3",
        birth: String = "740812",
        expiry: String = "340415",
        numberDigit: Char? = null,
        birthDigit: Char? = null,
        expiryDigit: Char? = null,
        /** Whether the check digits the person would type from the document are given (computed here, as a test's stand-in for the printed ones). */
        digits: Boolean = true
    ) = AccessCredentials.Mrz.of(
        number, birth, expiry,
        numberDigit ?: MrzCheck.checkDigit(number.uppercase().padEnd(9, '<')).takeIf { digits },
        birthDigit ?: MrzCheck.checkDigit(birth).takeIf { digits },
        expiryDigit ?: MrzCheck.checkDigit(expiry).takeIf { digits }
    )

    private fun problems(c: AccessCredentials) = c.validate(today).map { it.credentialField to it.kind }

    @Test
    fun `check digits use weights 7 3 1 on digits, letters as 10 to 35 and the filler as 0`() {
        assertEquals('6', MrzCheck.checkDigit("L898902C3"))
        assertEquals('2', MrzCheck.checkDigit("740812"))
        assertEquals('9', MrzCheck.checkDigit("120415"))
        assertEquals('0', MrzCheck.checkDigit("<<<<<<<<<"))
        assertNull(MrzCheck.checkDigit("l898902c3"), "lower case is not an MRZ character")
        assertTrue(MrzCheck.isValid("L898902C3", '6'))
        assertFalse(MrzCheck.isValid("L898902C3", '7'))
    }

    @Test
    fun `plausible MRZ credentials have no problems when their check digits are given`() {
        assertEquals(emptyList(), problems(specimen()))
        assertEquals(emptyList(), problems(specimen(expiry = "120415", numberDigit = '6', birthDigit = '2', expiryDigit = '9')))
        assertEquals(emptyList(), problems(AccessCredentials.Mrz.fromFieldsWithCheckDigits("L898902C36", "7408122", "1204159")))
    }

    @Test
    fun `check digits are required, and a field given without its digit is a problem before anything is sent`() {
        assertEquals(
            listOf(
                CredentialField.DOCUMENT_NUMBER to CredentialProblemKind.CHECK_DIGIT_MISSING,
                CredentialField.DATE_OF_BIRTH to CredentialProblemKind.CHECK_DIGIT_MISSING,
                CredentialField.DATE_OF_EXPIRY to CredentialProblemKind.CHECK_DIGIT_MISSING
            ),
            problems(specimen(digits = false))
        )
        // One digit missing is one problem, and the other fields are still checked
        assertEquals(
            listOf(CredentialField.DATE_OF_BIRTH to CredentialProblemKind.CHECK_DIGIT_MISSING),
            problems(AccessCredentials.Mrz.of("L898902C3", "740812", "340415", '6', null, MrzCheck.checkDigit("340415")))
        )
        // An empty field is reported as empty, not also as missing its digit
        assertEquals(listOf(CredentialField.DATE_OF_EXPIRY to CredentialProblemKind.EMPTY), problems(specimen(expiry = "")))
        // A field with no digit that is also wrong is reported as wrong
        assertEquals(
            listOf(CredentialField.DATE_OF_BIRTH to CredentialProblemKind.NOT_A_DATE),
            problems(specimen(birth = "741312", digits = false).let { AccessCredentials.Mrz.of("L898902C3", "741312", "340415", '6', null, MrzCheck.checkDigit("340415")) })
        )
        assertEquals(
            "The check digit after the date of birth is missing. Type it as printed on the document.",
            AccessCredentials.Mrz.of("L898902C3", "740812", "340415", '6', null, MrzCheck.checkDigit("340415")).validate(today).single().message
        )
    }

    @Test
    fun `a mistyped field fails its check digit before anything is sent`() {
        assertEquals(
            listOf(CredentialField.DOCUMENT_NUMBER to CredentialProblemKind.CHECK_DIGIT_MISMATCH),
            problems(specimen(expiry = "120415", numberDigit = '5'))
        )
        assertEquals(
            listOf(CredentialField.DATE_OF_BIRTH to CredentialProblemKind.CHECK_DIGIT_MISMATCH),
            problems(AccessCredentials.Mrz.fromFieldsWithCheckDigits("L898902C36", "7408132", "1204159"))
        )
        assertEquals(
            listOf(CredentialField.DATE_OF_EXPIRY to CredentialProblemKind.CHECK_DIGIT_MISMATCH),
            problems(AccessCredentials.Mrz.fromFieldsWithCheckDigits("L898902C36", "7408122", "1204158"))
        )
    }

    @Test
    fun `short document numbers are padded with fillers for their check digit`() {
        val digit = MrzCheck.checkDigit("AB123<<<<")!!
        assertEquals(emptyList(), problems(specimen(number = "AB123", numberDigit = digit)))
    }

    @Test
    fun `document numbers are upper-cased, and only MRZ characters are allowed`() {
        assertEquals(emptyList(), problems(specimen(number = "l898902c3", numberDigit = '6')))
        assertEquals(listOf(CredentialField.DOCUMENT_NUMBER to CredentialProblemKind.INVALID_CHARACTERS), problems(specimen(number = "L89 902C3")))
        assertEquals(listOf(CredentialField.DOCUMENT_NUMBER to CredentialProblemKind.EMPTY), problems(specimen(number = "")))
        assertEquals(listOf(CredentialField.DOCUMENT_NUMBER to CredentialProblemKind.WRONG_LENGTH), problems(specimen(number = "A".repeat(15))))
    }

    @Test
    fun `dates must be six digits that make a date`() {
        assertEquals(listOf(CredentialField.DATE_OF_BIRTH to CredentialProblemKind.WRONG_LENGTH), problems(specimen(birth = "7408")))
        assertEquals(listOf(CredentialField.DATE_OF_BIRTH to CredentialProblemKind.INVALID_CHARACTERS), problems(specimen(birth = "74O812")))
        assertEquals(listOf(CredentialField.DATE_OF_BIRTH to CredentialProblemKind.NOT_A_DATE), problems(specimen(birth = "741312")))
        assertEquals(listOf(CredentialField.DATE_OF_BIRTH to CredentialProblemKind.NOT_A_DATE), problems(specimen(birth = "740231")))
        assertEquals(listOf(CredentialField.DATE_OF_EXPIRY to CredentialProblemKind.EMPTY), problems(specimen(expiry = "")))
        assertEquals(emptyList(), problems(specimen(birth = "000229", expiry = "340415")), "29 February 2000 exists")
    }

    @Test
    fun `dates must be plausible for a travel document`() {
        // Born in 2074 is read as 1974, since a birth date is never in the future
        assertEquals(emptyList(), problems(specimen(birth = "740812")))
        // Expiry is read as a year in the 2000s: 2094 and 2047 are more than 20 years ahead
        assertEquals(listOf(CredentialField.DATE_OF_EXPIRY to CredentialProblemKind.IMPLAUSIBLE_DATE), problems(specimen(expiry = "940415")))
        assertEquals(listOf(CredentialField.DATE_OF_EXPIRY to CredentialProblemKind.IMPLAUSIBLE_DATE), problems(specimen(expiry = "470415")))
        // A chip expired for some years can still be read
        assertEquals(emptyList(), problems(specimen(expiry = "180415")))
    }

    @Test
    fun `birth after expiry is inconsistent`() {
        // Born 2020-05-01 is plausible; expiry 2019 is not later, so the pair is refused
        assertTrue(
            CredentialProblem(CredentialField.DATE_OF_BIRTH, CredentialProblemKind.DATES_INCONSISTENT) in
                specimen(birth = "200501", expiry = "190415").validate(today)
        )
    }

    @Test
    fun `a CAN is six digits`() {
        assertEquals(emptyList(), AccessCredentials.Can.of("123456").validate(today))
        assertEquals(listOf(CredentialField.CAN to CredentialProblemKind.WRONG_LENGTH), problems(AccessCredentials.Can.of("12345")))
        assertEquals(listOf(CredentialField.CAN to CredentialProblemKind.WRONG_LENGTH), problems(AccessCredentials.Can.of("1234567")))
        assertEquals(listOf(CredentialField.CAN to CredentialProblemKind.INVALID_CHARACTERS), problems(AccessCredentials.Can.of("12a456")))
        assertEquals(listOf(CredentialField.CAN to CredentialProblemKind.EMPTY), problems(AccessCredentials.Can.of("")))
    }

    @Test
    fun `a problem names the field and kind and its message never holds a value`() {
        val mrz = specimen(number = "L89%902C3")
        val problem = mrz.validate(today).single()
        assertEquals("The document number holds only capital letters, digits and the filler <.", problem.message)
        assertFalse("L89" in problem.message)
        assertEquals("CredentialProblem(credentialField=DOCUMENT_NUMBER, kind=INVALID_CHARACTERS)", problem.toString())
    }

    @Test
    fun `toString says the kind and nothing of the values`() {
        assertEquals("AccessCredentials(MRZ, redacted)", specimen().toString())
        assertEquals("AccessCredentials(CAN, redacted)", AccessCredentials.Can.of("123456").toString())
        assertEquals(CredentialKind.MRZ, specimen().kind)
    }

    @Test
    fun `closing overwrites the characters the holder was given and refuses further use`() {
        val number = "L898902C3".toCharArray()
        val birth = "740812".toCharArray()
        val expiry = "340415".toCharArray()
        val mrz = AccessCredentials.Mrz.takingOwnershipOf(number, birth, expiry, '6', '2', MrzCheck.checkDigit("340415"))
        val can = "123456".toCharArray()
        val canCredentials = AccessCredentials.Can.takingOwnershipOf(can)
        assertFalse(mrz.isClosed)

        mrz.use { assertEquals(emptyList(), it.validate(today)) }
        canCredentials.use { assertEquals(emptyList(), it.validate(today)) }

        assertTrue(mrz.isClosed)
        assertTrue((number + birth + expiry).all { it == '\u0000' }, "MRZ characters erased")
        assertTrue(can.all { it == '\u0000' }, "CAN erased")
        assertFailsWith<IllegalStateException> { mrz.validate(today) }
        assertFailsWith<IllegalStateException> { canCredentials.validate(today) }
        mrz.close() // twice is fine
    }

    @Test
    fun `use erases the credentials even if the block throws`() {
        val can = "123456".toCharArray()
        assertFailsWith<IllegalArgumentException> {
            AccessCredentials.Can.takingOwnershipOf(can).use { throw IllegalArgumentException("boom") }
        }
        assertTrue(can.all { it == '\u0000' })
    }

    @Test
    fun `no toString in the credential path shows a field`() {
        val mrz = specimen(numberDigit = '6')
        val shown = listOf(
            mrz.toString(), mrz.validate(today).toString(),
            specimen(number = "QQ%%").validate(today).toString(), specimen(number = "QQ%%").validate(today).joinToString { it.message }
        ).joinToString("\n")
        listOf("L898902C3", "740812", "340415").forEach { assertFalse(it in shown, "$it shown") }
    }
}
