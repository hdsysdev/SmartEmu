package com.hddev.smartemu.utils

import com.hddev.smartemu.data.DhGroup
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PaceSpec
import com.hddev.smartemu.data.PaceSuite
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SessionCipher
import kotlinx.datetime.LocalDate
import net.sf.scuba.tlv.TLVUtil
import org.bouncycastle.util.encoders.Hex
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.PACEInfo
import org.jmrtd.protocol.PACEProtocol
import org.junit.Test
import java.math.BigInteger
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
        val parameter = parameterId?.let { TLVUtil.wrapDO(0x84, Util.i2os(it.toBigInteger())) } ?: byteArrayOf()
        return Util.toOIDBytes(oid) + TLVUtil.wrapDO(0x83, byteArrayOf(passwordReference)) + parameter
    }

    private fun camKeyPairs() = mapOf(PaceProtocol.PARAMETER_ID to PaceProtocol.generateChipAuthenticationKeyPair())

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
        assertEquals("PACE-GM NIST P-256 AES-128 selected with the CAN", result.message)
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
            assertTrue(it.initialize(camData, camKeyPairs()).success)
        }

        assertEquals(PaceProtocol.SW_WRONG_DATA, pace.processMseSetAt(mseSetAt()).statusWord)
        assertTrue(pace.processMseSetAt(mseSetAt(oid = PaceProtocol.oid(PaceMapping.CHIP_AUTHENTICATION))).success)
    }

    @Test
    fun `chip authentication mapping needs the chip key pair`() {
        val camData = passportData.copy(paceMapping = PaceMapping.CHIP_AUTHENTICATION)

        assertFalse(PaceProtocol().initialize(camData).success)
        assertTrue(PaceProtocol().initialize(camData, camKeyPairs()).success)
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
    fun `a curve point with a trailing byte fails the run`() {
        val pace = initializedProtocol()
        pace.processMseSetAt(mseSetAt())
        pace.processGeneralAuthenticate(generalAuthenticate())
        val point = PACEProtocol.encodePublicKeyForSmartCard(PaceProtocol.generateChipAuthenticationKeyPair().public)

        val result = pace.processGeneralAuthenticate(generalAuthenticate(TLVUtil.wrapDO(0x81, point + 0x00)))

        assertFalse(result.success)
        assertEquals(PaceProtocol.PaceState.FAILED, pace.getCurrentState())
    }

    @Test
    fun `MSE Set AT before initialization fails`() {
        assertFalse(PaceProtocol().processMseSetAt(mseSetAt()).success)
    }

    /** A chip that offers ECDH GM on NIST P-256 (its own), GM on brainpoolP256r1 and DH IM in the 2048/256 group. */
    private val multiSuiteData = passportData.copy(
        extraPaceSuites = listOf(
            PaceSuite(PaceMapping.GENERIC, PaceSpec(curve = EcCurve.BRAINPOOL_P256R1)),
            PaceSuite(PaceMapping.INTEGRATED, PaceSpec(cipher = SessionCipher.AES_256, dhGroup = DhGroup.MODP_2048_256))
        )
    )
    private val dhImOid = PaceProtocol.oid(PaceMapping.INTEGRATED, SessionCipher.AES_256, dh = true)

    @Test
    fun `a chip offering several sets of domain parameters selects the suite DO'84 names`() {
        val pace = initializedProtocol(multiSuiteData)

        assertTrue(pace.processMseSetAt(mseSetAt(parameterId = EcCurve.BRAINPOOL_P256R1.paceParameterId)).success)
        assertEquals(EcCurve.BRAINPOOL_P256R1, pace.curve)
        assertTrue(pace.processMseSetAt(mseSetAt(parameterId = EcCurve.NIST_P256.paceParameterId)).success)
        assertEquals(EcCurve.NIST_P256, pace.curve)
        assertTrue(pace.processMseSetAt(mseSetAt(oid = dhImOid, parameterId = 2)).success)
        assertEquals(PaceSuite(PaceMapping.INTEGRATED, PaceSpec(cipher = SessionCipher.AES_256, dhGroup = DhGroup.MODP_2048_256)), pace.suite)
    }

    @Test
    fun `a chip offering several sets of domain parameters needs DO'84`() {
        val result = initializedProtocol(multiSuiteData).processMseSetAt(mseSetAt(parameterId = null))

        assertFalse(result.success)
        assertEquals(PaceProtocol.SW_WRONG_DATA, result.statusWord)
    }

    @Test
    fun `a protocol is accepted only with domain parameters it is offered on`() {
        val pace = initializedProtocol(multiSuiteData)

        assertEquals(PaceProtocol.SW_REFERENCED_DATA_NOT_FOUND, pace.processMseSetAt(mseSetAt(oid = dhImOid, parameterId = 12)).statusWord)
        assertEquals(PaceProtocol.SW_REFERENCED_DATA_NOT_FOUND, pace.processMseSetAt(mseSetAt(parameterId = 2)).statusWord)
    }

    private fun encryptedNonceLength(mapping: PaceMapping, cipher: SessionCipher, dhGroup: DhGroup? = null): Int {
        val pace = initializedProtocol(passportData.copy(paceMapping = mapping, paceCipher = cipher, paceDhGroup = dhGroup))
        assertTrue(pace.processMseSetAt(mseSetAt(oid = pace.oid, parameterId = null)).success)
        val result = pace.processGeneralAuthenticate(generalAuthenticate())
        assertTrue(result.success, result.message)
        return TLVUtil.unwrapDO(0x80, TLVUtil.unwrapDO(0x7C, result.data!!)).size
    }

    @Test
    fun `the nonce is one block, or for integrated mapping the key size rounded up to whole blocks`() {
        assertEquals(16, encryptedNonceLength(PaceMapping.GENERIC, SessionCipher.AES_256))
        assertEquals(8, encryptedNonceLength(PaceMapping.GENERIC, SessionCipher.TDES))
        assertEquals(16, encryptedNonceLength(PaceMapping.INTEGRATED, SessionCipher.TDES))
        assertEquals(16, encryptedNonceLength(PaceMapping.INTEGRATED, SessionCipher.AES_128))
        assertEquals(32, encryptedNonceLength(PaceMapping.INTEGRATED, SessionCipher.AES_192))
        assertEquals(32, encryptedNonceLength(PaceMapping.INTEGRATED, SessionCipher.AES_256, DhGroup.MODP_2048_224))
    }

    @Test
    fun `integrated mapping needs a nonce t of exactly the key size and answers with empty mapping data`() {
        val data = passportData.copy(paceMapping = PaceMapping.INTEGRATED, paceCipher = SessionCipher.AES_192)
        for ((size, accepted) in listOf(16 to false, 24 to true, 32 to false)) {
            val pace = initializedProtocol(data)
            pace.processMseSetAt(mseSetAt(oid = pace.oid))
            pace.processGeneralAuthenticate(generalAuthenticate())

            val result = pace.processGeneralAuthenticate(generalAuthenticate(TLVUtil.wrapDO(0x81, ByteArray(size) { 0x5A })))

            assertEquals(accepted, result.success, "t of $size bytes")
            if (accepted) assertEquals("7c028200", Hex.toHexString(result.data!!))
        }
    }

    @Test
    fun `DH mapping data outside the subgroup or badly encoded fails the run`() {
        val data = passportData.copy(paceDhGroup = DhGroup.MODP_1024_160)
        val group = PACEInfo.toParameterSpec(DhGroup.MODP_1024_160.paceParameterId) as PACEInfo.DHCParameterSpec
        val valid = group.g.modPow(BigInteger.valueOf(12345), group.p)
        val badValues = mapOf(
            "1" to byteArrayOf(1),
            "p - 1" to Util.i2os(group.p - BigInteger.ONE),
            "outside the subgroup" to Util.i2os(BigInteger.valueOf(2)),
            "a leading zero byte" to byteArrayOf(0) + Util.i2os(valid)
        )
        for ((name, value) in badValues) {
            val pace = initializedProtocol(data)
            pace.processMseSetAt(mseSetAt(oid = pace.oid, parameterId = null))
            pace.processGeneralAuthenticate(generalAuthenticate())

            val result = pace.processGeneralAuthenticate(generalAuthenticate(TLVUtil.wrapDO(0x81, value)))

            assertFalse(result.success, name)
            assertEquals(PaceProtocol.PaceState.FAILED, pace.getCurrentState(), name)
        }
    }
}
