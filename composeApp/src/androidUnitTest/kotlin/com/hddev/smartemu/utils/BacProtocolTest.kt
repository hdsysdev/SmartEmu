package com.hddev.smartemu.utils

import com.hddev.smartemu.data.PassportData
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the chip side of BAC (Basic Access Control), driven by a reader-side
 * implementation that follows JMRTD.
 */
class BacProtocolTest {

    private val validPassportData = PassportData(
        passportNumber = "L898902C3",
        dateOfBirth = LocalDate(1974, 8, 12),
        expiryDate = LocalDate(2034, 4, 15),
        issuingCountry = "NLD",
        nationality = "NLD",
        firstName = "ANNA",
        lastName = "ERIKSSON",
        gender = "F"
    )

    private val invalidPassportData = PassportData(
        passportNumber = "", // Invalid empty passport number
        dateOfBirth = null,
        expiryDate = null
    )

    private fun initializedProtocol(): BacProtocol = BacProtocol().also {
        assertTrue(it.initialize(validPassportData).success)
    }

    @Test
    fun `initialize with valid passport data should succeed`() {
        val bacProtocol = BacProtocol()

        val result = bacProtocol.initialize(validPassportData)

        assertTrue(result.success)
        assertEquals("BAC initialized", result.message)
        assertEquals(BacProtocol.BacState.INITIAL, result.newState)
        assertEquals(BacProtocol.BacState.INITIAL, bacProtocol.getCurrentState())
    }

    @Test
    fun `initialize with invalid passport data should fail`() {
        val bacProtocol = BacProtocol()

        val result = bacProtocol.initialize(invalidPassportData)

        assertFalse(result.success)
        assertEquals("Invalid passport data", result.message)
        assertEquals(BacProtocol.BacState.FAILED, result.newState)
        assertEquals(BacProtocol.BacState.FAILED, bacProtocol.getCurrentState())
    }

    @Test
    fun `generate challenge returns an 8 byte RND IC`() {
        val bacProtocol = initializedProtocol()

        val result = bacProtocol.generateChallenge()

        assertTrue(result.success)
        assertEquals(8, result.data?.size)
        assertEquals(BacProtocol.BacState.CHALLENGE_GENERATED, bacProtocol.getCurrentState())
    }

    @Test
    fun `generate challenge before initialization should fail`() {
        val result = BacProtocol().generateChallenge()

        assertFalse(result.success)
        assertEquals("BAC not initialized", result.message)
    }

    @Test
    fun `mutual authentication with the document keys succeeds and returns the chip cryptogram`() {
        val bacProtocol = initializedProtocol()
        val rndIc = bacProtocol.generateChallenge().data!!
        val auth = BacTestReader.createMutualAuthentication(validPassportData, rndIc)

        val result = bacProtocol.processExternalAuthenticate(auth.data)

        assertTrue(result.success, result.message)
        assertEquals(BacProtocol.BacState.AUTHENTICATED, bacProtocol.getCurrentState())
        assertEquals(40, result.data?.size)

        // R = RND.IC || RND.IFD || K.IC, encrypted and MACed with the document keys
        val r = BacTestReader.decryptChipResponse(validPassportData, result.data!!)
        assertContentEquals(rndIc, r.copyOfRange(0, 8))
        assertContentEquals(auth.rndIfd, r.copyOfRange(8, 16))
        assertNotNull(bacProtocol.getSecureMessaging())
    }

    @Test
    fun `mutual authentication with keys from a different MRZ fails`() {
        val bacProtocol = initializedProtocol()
        val rndIc = bacProtocol.generateChallenge().data!!
        val otherPassport = validPassportData.copy(passportNumber = "X12345678")
        val auth = BacTestReader.createMutualAuthentication(otherPassport, rndIc)

        val result = bacProtocol.processExternalAuthenticate(auth.data)

        assertFalse(result.success)
        assertEquals(BacProtocol.BacState.FAILED, bacProtocol.getCurrentState())
        assertNull(bacProtocol.getSecureMessaging())
    }

    @Test
    fun `mutual authentication over a stale challenge fails`() {
        val bacProtocol = initializedProtocol()
        val staleChallenge = bacProtocol.generateChallenge().data!!
        bacProtocol.generateChallenge()
        val auth = BacTestReader.createMutualAuthentication(validPassportData, staleChallenge)

        val result = bacProtocol.processExternalAuthenticate(auth.data)

        assertFalse(result.success)
        assertEquals("Authentication verification failed", result.message)
    }

    @Test
    fun `tampered cryptogram is rejected`() {
        val bacProtocol = initializedProtocol()
        val rndIc = bacProtocol.generateChallenge().data!!
        val data = BacTestReader.createMutualAuthentication(validPassportData, rndIc).data
        data[0] = (data[0].toInt() xor 0x01).toByte()

        val result = bacProtocol.processExternalAuthenticate(data)

        assertFalse(result.success)
    }

    @Test
    fun `external authenticate without a challenge fails`() {
        val bacProtocol = initializedProtocol()

        val result = bacProtocol.processExternalAuthenticate(ByteArray(40))

        assertFalse(result.success)
        assertEquals("Invalid state for authentication", result.message)
    }

    @Test
    fun `external authenticate with wrong length fails`() {
        val bacProtocol = initializedProtocol()
        bacProtocol.generateChallenge()

        val result = bacProtocol.processExternalAuthenticate(ByteArray(32))

        assertFalse(result.success)
        assertEquals("Invalid authentication data length", result.message)
    }

    @Test
    fun `challenge is single use`() {
        val bacProtocol = initializedProtocol()
        val rndIc = bacProtocol.generateChallenge().data!!
        val auth = BacTestReader.createMutualAuthentication(validPassportData, rndIc)
        assertTrue(bacProtocol.processExternalAuthenticate(auth.data).success)

        val replay = bacProtocol.processExternalAuthenticate(auth.data)

        assertFalse(replay.success)
    }

    @Test
    fun `reset returns to initial state and drops the session`() {
        val bacProtocol = initializedProtocol()
        val rndIc = bacProtocol.generateChallenge().data!!
        bacProtocol.processExternalAuthenticate(BacTestReader.createMutualAuthentication(validPassportData, rndIc).data)

        bacProtocol.reset()

        assertEquals(BacProtocol.BacState.INITIAL, bacProtocol.getCurrentState())
        assertNull(bacProtocol.getSecureMessaging())
        assertTrue(bacProtocol.generateChallenge().success)
    }

    @Test
    fun `document basic access keys match the ICAO 9303 part 11 worked example`() {
        // ICAO 9303-11 Appendix D.2: document number L898902C<, born 1969-08-06, expires 1994-06-23
        val icaoExample = PassportData(
            passportNumber = "L898902C",
            dateOfBirth = LocalDate(1969, 8, 6),
            expiryDate = LocalDate(1994, 6, 23)
        )
        
        val (kEnc, kMac) = BacTestReader.documentKeys(icaoExample)
        
        assertEquals("690806", icaoExample.mrzDateOfBirth())
        assertEquals("940623", icaoExample.mrzExpiryDate())
        // DES ignores the parity bits, which ICAO shows adjusted
        assertEquals(withoutParity("AB94FDECF2674FDFB9B391F85D7F76F2"), withoutParity(kEnc.encoded))
        assertEquals(withoutParity("7862D9ECE03C1BCD4D77089DCF131442"), withoutParity(kMac.encoded))
    }
    
    private fun withoutParity(hex: String): List<Int> = withoutParity(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    
    private fun withoutParity(key: ByteArray): List<Int> = key.take(16).map { it.toInt() and 0xFE }
}
