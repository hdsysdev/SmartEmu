package com.hddev.smartemu.utils

import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import kotlinx.datetime.LocalDate
import net.sf.scuba.tlv.TLVUtil
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.PACEInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the chip side of PACE. The complete protocol run is covered against JMRTD's reader in
 * PaceIntegrationTest; these tests cover what a reader must not get away with.
 */
class PaceProtocolTest {

    private val passportData = PassportData(
        passportNumber = "L898902C3",
        dateOfBirth = LocalDate(1974, 8, 12),
        expiryDate = LocalDate(2034, 4, 15),
        firstName = "ANNA",
        lastName = "ERIKSSON",
        gender = "F"
    )

    private fun initializedProtocol(data: PassportData = passportData): PaceProtocol = PaceProtocol().also {
        assertTrue(it.initialize(data).success)
    }

    private fun mseSetAt(
        oid: String = PaceProtocol.oid(PaceMapping.GENERIC),
        passwordReference: Byte = PassportService.MRZ_PACE_KEY_REFERENCE,
        parameterId: Int? = PaceProtocol.PARAMETER_ID
    ): ByteArray {
        val parameter = parameterId?.let { TLVUtil.wrapDO(0x84, byteArrayOf(it.toByte())) } ?: byteArrayOf()
        return Util.toOIDBytes(oid) + TLVUtil.wrapDO(0x83, byteArrayOf(passwordReference)) + parameter
    }

    private fun generalAuthenticate(vararg dataObjects: ByteArray): ByteArray =
        TLVUtil.wrapDO(0x7C, dataObjects.fold(byteArrayOf()) { acc, it -> acc + it })

    @Test
    fun `MSE Set AT with the advertised protocol and the MRZ password is accepted`() {
        val pace = initializedProtocol()

        val result = pace.processMseSetAt(mseSetAt())

        assertTrue(result.success, result.message)
        assertEquals(PaceProtocol.PaceState.KEY_SELECTED, pace.getCurrentState())
    }

    @Test
    fun `MSE Set AT without a domain parameter reference is accepted`() {
        assertTrue(initializedProtocol().processMseSetAt(mseSetAt(parameterId = null)).success)
    }

    @Test
    fun `MSE Set AT with an unsupported protocol is rejected with wrong data`() {
        val result = initializedProtocol().processMseSetAt(mseSetAt(oid = PACEInfo.ID_PACE_ECDH_IM_AES_CBC_CMAC_128))

        assertFalse(result.success)
        assertEquals(PaceProtocol.SW_WRONG_DATA, result.statusWord)
    }

    @Test
    fun `MSE Set AT with the CAN as password is rejected because the chip has no CAN`() {
        val result = initializedProtocol().processMseSetAt(mseSetAt(passwordReference = PassportService.CAN_PACE_KEY_REFERENCE))

        assertFalse(result.success)
        assertEquals(PaceProtocol.SW_REFERENCED_DATA_NOT_FOUND, result.statusWord)
    }

    @Test
    fun `MSE Set AT with the CAN as password is accepted when the document has a CAN`() {
        val pace = initializedProtocol(passportData.copy(can = "123456"))

        val result = pace.processMseSetAt(mseSetAt(passwordReference = PassportService.CAN_PACE_KEY_REFERENCE))

        assertTrue(result.success, result.message)
        assertEquals("PACE-GM selected with the CAN", result.message)
    }

    @Test
    fun `MSE Set AT with a PIN as password is rejected`() {
        val pace = initializedProtocol(passportData.copy(can = "123456"))

        val result = pace.processMseSetAt(mseSetAt(passwordReference = PassportService.PIN_PACE_KEY_REFERENCE))

        assertEquals(PaceProtocol.SW_REFERENCED_DATA_NOT_FOUND, result.statusWord)
    }

    @Test
    fun `chip accepts only the mapping it advertises`() {
        val pace = PaceProtocol().also {
            val camData = passportData.copy(paceMapping = PaceMapping.CHIP_AUTHENTICATION)
            assertTrue(it.initialize(camData, PaceProtocol.generateChipAuthenticationKeyPair()).success)
        }

        assertEquals(PaceProtocol.SW_WRONG_DATA, pace.processMseSetAt(mseSetAt()).statusWord)
        assertTrue(pace.processMseSetAt(mseSetAt(oid = PaceProtocol.oid(PaceMapping.CHIP_AUTHENTICATION))).success)
    }

    @Test
    fun `chip authentication mapping needs the chip key pair`() {
        val camData = passportData.copy(paceMapping = PaceMapping.CHIP_AUTHENTICATION)

        assertFalse(PaceProtocol().initialize(camData).success)
        assertTrue(PaceProtocol().initialize(camData, PaceProtocol.generateChipAuthenticationKeyPair()).success)
    }

    @Test
    fun `MSE Set AT with other domain parameters is rejected`() {
        val result = initializedProtocol().processMseSetAt(mseSetAt(parameterId = PACEInfo.PARAM_ID_ECP_BRAINPOOL_P256_R1))

        assertFalse(result.success)
        assertEquals(PaceProtocol.SW_REFERENCED_DATA_NOT_FOUND, result.statusWord)
    }

    @Test
    fun `GENERAL AUTHENTICATE before MSE Set AT is out of sequence`() {
        val result = initializedProtocol().processGeneralAuthenticate(generalAuthenticate())

        assertFalse(result.success)
        assertEquals(PaceProtocol.SW_CONDITIONS_NOT_SATISFIED, result.statusWord)
    }

    @Test
    fun `first GENERAL AUTHENTICATE returns a nonce encrypted to one AES block`() {
        val pace = initializedProtocol()
        pace.processMseSetAt(mseSetAt())

        val result = pace.processGeneralAuthenticate(generalAuthenticate())

        assertTrue(result.success, result.message)
        val encryptedNonce = TLVUtil.unwrapDO(0x80, TLVUtil.unwrapDO(0x7C, result.data!!))
        assertEquals(16, encryptedNonce.size)
        assertEquals(PaceProtocol.PaceState.NONCE_SENT, pace.getCurrentState())
    }

    @Test
    fun `mapping data that is not a curve point fails the run`() {
        val pace = initializedProtocol()
        pace.processMseSetAt(mseSetAt())
        pace.processGeneralAuthenticate(generalAuthenticate())
        val notOnCurve = byteArrayOf(0x04) + ByteArray(64) { 0x01 }

        val result = pace.processGeneralAuthenticate(generalAuthenticate(TLVUtil.wrapDO(0x81, notOnCurve)))

        assertFalse(result.success)
        assertEquals(PaceProtocol.PaceState.FAILED, pace.getCurrentState())
        assertNull(pace.getSecureMessaging())
    }

    @Test
    fun `MSE Set AT before initialization fails`() {
        assertFalse(PaceProtocol().processMseSetAt(mseSetAt()).success)
    }
}
