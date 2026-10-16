package com.hddev.smartemu.utils

import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.DhGroup
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import net.sf.scuba.tlv.TLVUtil
import org.bouncycastle.util.encoders.Hex
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.PACEInfo
import org.jmrtd.protocol.PACEProtocol
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.DHPublicKeySpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The chip side of PACE against the worked examples of ICAO Doc 9303 Part 11 (8th ed., 2021), all with AES-128 and
 * the MRZ of Appendix G: G.1 and G.2, generic mapping with ECDH on brainpoolP256r1 and with DH in the 1024-bit RFC
 * 5114 group, and H.1 and H.2, integrated mapping on the same. The chip draws the examples' nonces and private keys,
 * so each of its responses must match the example byte for byte. PaceIntegrationTest's JMRTD reader shares the
 * chip's helpers and would agree with a mistake they both make; these values come from outside the code under test.
 *
 * Appendix H says it uses Appendix G's MRZ, but its K_pi, 591468CD..., isn't the one that MRZ gives, so with the
 * MRZ the chip's encrypted nonce differs from H's. Steps 2 to 4 don't depend on K_pi and still match.
 */
class PaceIcaoWorkedExampleTest {

    private companion object {
        const val OID = "0.4.0.127.0.7.2.2.4.2.2"
        const val PARAMETER_ID: Byte = 13

        const val NONCE_S = "3F00C4D39D153F2B2A214A078D899B22"
        const val ENCRYPTED_NONCE_Z = "95A3A016522EE98D01E76CB6B98B42C3"

        const val TERMINAL_MAP_PUBLIC = "04" +
            "7ACF3EFC982EC45565A4B155129EFBC74650DCBFA6362D896FC70262E0C2CC5E" +
            "544552DCB6725218799115B55C9BAA6D9F6BC3A9618E70C25AF71777A9C4922D"
        const val CHIP_MAP_PRIVATE = "498FF49756F2DC1587840041839A85982BE7761D14715FB091EFA7BCE9058560"
        const val CHIP_MAP_PUBLIC = "04" +
            "824FBA91C9CBE26BEF53A0EBE7342A3BF178CEA9F45DE0B70AA601651FBA3F57" +
            "30D8C879AAA9C9F73991E61B58F4D52EB87A0A0C709A49DC63719363CCD13C54"

        const val TERMINAL_EPHEMERAL_PUBLIC = "04" +
            "2DB7A64C0355044EC9DF190514C625CBA2CEA48754887122F3A5EF0D5EDD301C" +
            "3556F3B3B186DF10B857B58F6A7EB80F20BA5DC7BE1D43D9BF850149FBB36462"
        const val CHIP_EPHEMERAL_PRIVATE = "107CF58696EF6155053340FD633392BA81909DF7B9706F226F32086C7AFF974A"
        const val CHIP_EPHEMERAL_PUBLIC = "04" +
            "9E880F842905B8B3181F7AF7CAA9F0EFB743847F44A306D2D28C1D9EC65DF6DB" +
            "7764B22277A2EDDC3C265A9F018F9CB852E111B768B326904B59A0193776F094"

        const val T_IFD = "C2B0BD78D94BA866"
        const val T_IC = "3ABB9674BCE93C08"

        /** K_pi from the MRZ, Appendix G. */
        const val MRZ_PASSWORD_KEY = "89DED1B26624EC1E634C1989302849DD"

        const val G2_OID = "0.4.0.127.0.7.2.2.4.1.2"
        const val G2_NONCE_S = "FA5B7E3E49753A0DB9178B7B9BD898C8"
        const val G2_ENCRYPTED_NONCE_Z = "854D8DF5827FA6852D1A4FA701CDDDCA"
        const val G2_CHIP_MAP_PRIVATE = "66DDAFEAC1609CB5B963BB0CB3FF8B3E047F336C"
        const val G2_CHIP_EPHEMERAL_PRIVATE = "A5B780126B7C980E9FCEA1D4539DA1D27C342DFA"
        const val G2_T_IFD = "B46DD9BD4D98381F"
        const val G2_T_IC = "917F37B5C0E6D8D1"

        const val H1_OID = "0.4.0.127.0.7.2.2.4.4.2"
        const val H1_NONCE_S = "2923BE84E16CD6AE529049F1F1BBE9EB"
        const val H1_NONCE_T = "5DD4CBFC96F5453B130D890A1CDBAE32"
        const val H1_T_IFD = "450F02B86F6A0909"
        const val H1_T_IC = "75D4D96E8D5B0308"

        const val H2_OID = "0.4.0.127.0.7.2.2.4.3.2"
        const val H2_NONCE_S = "FA5B7E3E49753A0DB9178B7B9BD898C8"
        const val H2_NONCE_T = "B3A6DB3C870C3E99245E0D1C06B747DE"

        const val G2_TERMINAL_MAP_PUBLIC =
            "23FB3749EA030D2A25B278D2A562047ADE3F01B74F17A15402CB7352CA7D2B3E" +
            "B71C343DB13D1DEBCE9A3666DBCFC920B49174A602CB47965CAA73DC702489A4" +
            "4D41DB914DE9613DC5E98C94160551C0DF86274B9359BC0490D01B03AD54022D" +
            "CB4F57FAD6322497D7A1E28D46710F461AFE710FBBBC5F8BA166F4311975EC6C"
        const val G2_CHIP_MAP_PUBLIC =
            "78879F57225AA8080D52ED0FC890A4B25336F699AA89A2D3A189654AF70729E6" +
            "23EA5738B26381E4DA19E004706FACE7B235C2DBF2F38748312F3C98C2DD4882" +
            "A41947B324AA1259AC22579DB93F7085655AF30889DBB845D9E6783FE42C9F24" +
            "49400306254C8AE8EE9DD812A804C0B66E8CAFC14F84D8258950A91B44126EE6"
        const val G2_TERMINAL_EPHEMERAL_PUBLIC =
            "907D89E2D425A178AA81AF4A7774EC8E388C115CAE67031E85EECE520BD91155" +
            "1B9AE4D04369F29A02626C86FBC6747CC7BC352645B6161A2A42D44EDA80A08F" +
            "A8D61B76D3A154AD8A5A51786B0BC07147057871A922212C5F67F43173172236" +
            "B7747D1671E6D692A3C7D40A0C3C5CE397545D015C175EB5130551EDBC2EE5D4"
        const val G2_CHIP_EPHEMERAL_PUBLIC =
            "075693D9AE941877573E634B6E644F8E60AF17A0076B8B123D9201074D36152B" +
            "D8B3A213F53820C42ADC79AB5D0AEEC3AEFB91394DA476BD97B9B14D0A65C1FC" +
            "71A0E019CB08AF55E1F729005FBA7E3FA5DC41899238A250767A6D46DB974064" +
            "386CD456743585F8E5D90CC8B4004B1F6D866C79CE0584E49687FF61BC29AEA1"
        const val H1_TERMINAL_PUBLIC =
            "04" +
            "89CBA23FFE96AA18D824627C3E934E54A9FD0B87A95D1471DC1C0ABFDCD640D4" +
            "6755DE9B7B778280B6BEBD57439ADFEB0E21FD4ED6DF42578C13418A59B34C37"
        const val H1_CHIP_PUBLIC =
            "04" +
            "67F78E5F7F7686082B293E8D087E056916D0F74BC01A5F8957D0DE45691E51E8" +
            "932B69A962B52A0985AD2C0A271EE6A13A8ADDDCD1A3A994B9DED257F4D22753"
        const val H2_GENERATOR =
            "1D7D767F11E333BCD6DBAEF40E799E7A926B96973550656FF3C830726D118D61" +
            "C276CDCC61D475CF03A98E0C0E79CAEBA5BE25578BD4551D0B10903236F0B0F9" +
            "76852FA78EEA14EA0ACA87D1E91F688FE0DFF897BBE35A472621D343564B262F" +
            "34223AE8FC59B664BFEDFA2BFE7516CA5510A6BBB633D517EC25D4E0BBAA16C2"
        const val H2_TERMINAL_PRIVATE =
            "4BD0E54740F9A028E6A515BFDAF967848C4F5F5FFF65AA0915947FFD1A0DF2FA" +
            "6981271BC905F3551457B7E03AC3B8066DE4AA406C1171FB43DD939C4BA16175" +
            "103BA3DEE16419AA248118F90CC36A3D6F4C373652E0C3CCE7F0F1D0C5425B36" +
            "00F0F0D6A67F004C8BBA33F2B4733C7252445C1DFC4F1107203F71D2EFB28161"
        const val H2_TERMINAL_PUBLIC =
            "0F0CC62945A8029251FB7EF3C094E12EC68E4EF07F27CB9D9CD04C5C4250FAE0" +
            "E4F8A951557E929AEB48E5C6DD47F2F5CD7C351A9BD2CD722C07EDE166770F08" +
            "FFCB370262CF308DD7B07F2E0DA9CAAA1492344C852906919538C98A4BA4187E" +
            "76CE9D87832386D319CE2E043C3343AEAE6EDBA1A9894DC5094D22F7FE1351D5"
    }

    /** The example's MRZ: T22000129, born 12 August 1964, expiring 31 October 2010, on a Brainpool P-256 chip. */
    private val passportData = PassportData(
        passportNumber = "T22000129",
        dateOfBirth = LocalDate(1964, 8, 12),
        expiryDate = LocalDate(2010, 10, 31),
        firstName = "ERIKA",
        lastName = "MUSTERMANN",
        gender = "F"
    ).withChipProfile(ChipProfiles.byId("de-passport-2017"))

    /** Hands out the given values, in the order PACE draws them: the nonce s, then the chip's two private keys. */
    private class ReplayRandom(vararg values: String) : SecureRandom() {
        private val pending = ArrayDeque(values.map { Hex.decode(it) })

        override fun nextBytes(bytes: ByteArray) {
            val next = pending.removeFirst()
            assertEquals(next.size, bytes.size, "Random bytes drawn")
            next.copyInto(bytes)
        }
    }

    private fun step(pace: PaceProtocol, command: String): ByteArray {
        val result = pace.processGeneralAuthenticate(TLVUtil.wrapDO(0x7C, Hex.decode(command)))
        assertTrue(result.success, result.message)
        return TLVUtil.unwrapDO(0x7C, result.data!!)
    }

    private fun assertStep(pace: PaceProtocol, command: String, expectedResponse: String) {
        assertEquals(expectedResponse.lowercase(), Hex.toHexString(step(pace, command)))
    }

    private fun selectWithMrz(pace: PaceProtocol, oid: String) {
        val mseSetAt = Util.toOIDBytes(oid) + TLVUtil.wrapDO(0x83, byteArrayOf(PassportService.MRZ_PACE_KEY_REFERENCE))
        val result = pace.processMseSetAt(mseSetAt)
        assertTrue(result.success, result.message)
    }

    /** Step 1 with Appendix H, whose K_pi the MRZ doesn't give: the chip encrypts the example's nonce with the MRZ's. */
    private fun assertNonceEncryptedWithMrzKey(pace: PaceProtocol, nonce: String) {
        val z = TLVUtil.unwrapDO(0x80, step(pace, ""))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Hex.decode(MRZ_PASSWORD_KEY), "AES"), IvParameterSpec(ByteArray(16)))
        assertEquals(nonce.lowercase(), Hex.toHexString(cipher.doFinal(z)))
    }

    @Test
    fun `the chip answers every PACE step with the values of the ICAO worked example`() {
        val pace = PaceProtocol(ReplayRandom(NONCE_S, CHIP_MAP_PRIVATE, CHIP_EPHEMERAL_PRIVATE))
        assertTrue(pace.initialize(passportData).success)
        assertEquals(EcCurve.BRAINPOOL_P256R1, pace.curve)
        assertEquals(OID, pace.oid)

        val mseSetAt = Util.toOIDBytes(OID) +
            TLVUtil.wrapDO(0x83, byteArrayOf(PassportService.MRZ_PACE_KEY_REFERENCE)) +
            TLVUtil.wrapDO(0x84, byteArrayOf(PARAMETER_ID))
        assertTrue(pace.processMseSetAt(mseSetAt).success)

        // z = E(K_pi, s) checks the password key derived from the MRZ
        assertStep(pace, "", "8010$ENCRYPTED_NONCE_Z")
        assertStep(pace, "8141$TERMINAL_MAP_PUBLIC", "8241$CHIP_MAP_PUBLIC")
        // The chip's ephemeral public key is on the mapped generator G~ = s * G + H
        assertStep(pace, "8341$TERMINAL_EPHEMERAL_PUBLIC", "8441$CHIP_EPHEMERAL_PUBLIC")
        // Both tokens check the shared secret and KS_mac
        assertStep(pace, "8508$T_IFD", "8608$T_IC")

        assertEquals(PaceProtocol.PaceState.AUTHENTICATED, pace.getCurrentState())
        assertNotNull(pace.getSecureMessaging())
    }

    @Test
    fun `the chip answers every PACE step with the values of the ICAO DH worked example`() {
        val pace = PaceProtocol(ReplayRandom(G2_NONCE_S, G2_CHIP_MAP_PRIVATE, G2_CHIP_EPHEMERAL_PRIVATE))
        assertTrue(pace.initialize(passportData.copy(paceDhGroup = DhGroup.MODP_1024_160)).success)
        assertEquals(G2_OID, pace.oid)

        // As in the example, MSE:Set AT names no domain parameters: the chip offers only the one group
        selectWithMrz(pace, G2_OID)
        assertStep(pace, "", "8010$G2_ENCRYPTED_NONCE_Z")
        assertStep(pace, "818180$G2_TERMINAL_MAP_PUBLIC", "828180$G2_CHIP_MAP_PUBLIC")
        // The chip's ephemeral public key is g~^SK with the mapped generator g~ = g^s * h
        assertStep(pace, "838180$G2_TERMINAL_EPHEMERAL_PUBLIC", "848180$G2_CHIP_EPHEMERAL_PUBLIC")
        // The example's step 4 response also has the CAR of a CVCA (DO'87), which only EAC chips send
        assertStep(pace, "8508$G2_T_IFD", "8608$G2_T_IC")

        assertEquals(PaceProtocol.PaceState.AUTHENTICATED, pace.getCurrentState())
    }

    @Test
    fun `the chip answers every PACE step with the values of the ICAO ECDH integrated mapping example`() {
        val pace = PaceProtocol(ReplayRandom(H1_NONCE_S, CHIP_EPHEMERAL_PRIVATE))
        assertTrue(pace.initialize(passportData.copy(paceMapping = PaceMapping.INTEGRATED)).success)
        assertEquals(H1_OID, pace.oid)

        selectWithMrz(pace, H1_OID)
        assertNonceEncryptedWithMrzKey(pace, H1_NONCE_S)
        // Integrated mapping: the reader sends t and the chip's mapping data is empty
        assertStep(pace, "8110$H1_NONCE_T", "8200")
        // The chip's ephemeral public key is SK * G^ with G^ = f_G(R_p(s, t))
        assertStep(pace, "8341$H1_TERMINAL_PUBLIC", "8441$H1_CHIP_PUBLIC")
        assertStep(pace, "8508$H1_T_IFD", "8608$H1_T_IC")

        assertEquals(PaceProtocol.PaceState.AUTHENTICATED, pace.getCurrentState())
    }

    /**
     * Appendix H.2's chip private key has the size of p, where the chip draws one the size of the subgroup order q,
     * so the chip draws another key x. Its public key must still be g^^x with the example's mapped generator g^, and
     * the tokens are computed from the example's terminal key.
     */
    @Test
    fun `the chip maps the nonces to the generator of the ICAO DH integrated mapping example`() {
        val pace = PaceProtocol(ReplayRandom(H2_NONCE_S, G2_CHIP_EPHEMERAL_PRIVATE))
        val data = passportData.copy(paceMapping = PaceMapping.INTEGRATED, paceDhGroup = DhGroup.MODP_1024_160)
        assertTrue(pace.initialize(data).success)
        assertEquals(H2_OID, pace.oid)

        selectWithMrz(pace, H2_OID)
        assertNonceEncryptedWithMrzKey(pace, H2_NONCE_S)
        assertStep(pace, "8110$H2_NONCE_T", "8200")

        val group = PACEInfo.toParameterSpec(DhGroup.MODP_1024_160.paceParameterId) as PACEInfo.DHCParameterSpec
        val p = group.p
        val chipPublic = BigInteger(1, TLVUtil.unwrapDO(0x84, step(pace, "838180$H2_TERMINAL_PUBLIC")))
        val x = BigInteger(G2_CHIP_EPHEMERAL_PRIVATE, 16)
        assertEquals(BigInteger(H2_GENERATOR, 16).modPow(x, p), chipPublic)

        // The terminal's side: K = PK_IC^SK_IFD, padded to the length of p
        val sharedSecret = Util.i2os(chipPublic.modPow(BigInteger(H2_TERMINAL_PRIVATE, 16), p), 128)
        val macKey = Util.deriveKey(sharedSecret, "AES", 128, Util.MAC_MODE)
        val keyFactory = KeyFactory.getInstance("DH")
        val generator = BigInteger(H2_GENERATOR, 16)
        val chipKey = keyFactory.generatePublic(DHPublicKeySpec(chipPublic, p, generator))
        val terminalKey = keyFactory.generatePublic(DHPublicKeySpec(BigInteger(H2_TERMINAL_PUBLIC, 16), p, generator))
        val terminalToken = Hex.toHexString(PACEProtocol.generateAuthenticationToken(H2_OID, macKey, chipKey))
        val chipToken = Hex.toHexString(PACEProtocol.generateAuthenticationToken(H2_OID, macKey, terminalKey))
        assertStep(pace, "8508$terminalToken", "8608$chipToken")

        assertEquals(PaceProtocol.PaceState.AUTHENTICATED, pace.getCurrentState())
    }
}
