package com.hddev.smartemu.utils

import com.hddev.smartemu.data.ChipAuthenticationSpec
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.SessionCipher
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.tlv.TLVUtil
import org.jmrtd.Util
import org.jmrtd.protocol.EACCAProtocol
import org.jmrtd.protocol.PACEProtocol
import org.junit.Test
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chip side of Chip Authentication against JMRTD's reader-side key agreement and secure messaging, for the
 * ciphers no profile uses yet as well as those it does.
 */
class ChipAuthenticationProtocolTest {

    private fun keyPair(curve: EcCurve): KeyPair = PassportLdsFiles.generateEcKeyPair(curve)

    private fun readerKeyPair(chip: KeyPair): KeyPair = Util.getKeyPairGenerator("EC").run {
        initialize((chip.public as ECPublicKey).params, SecureRandom())
        generateKeyPair()
    }

    /** Checks that a command the reader wraps with its restarted secure messaging unwraps on the chip. */
    private fun assertSameSessionKeys(oid: String, chip: KeyPair, reader: KeyPair, chipMessaging: ChipSecureMessaging?) {
        assertNotNull(chipMessaging)
        val secret = EACCAProtocol.computeSharedSecret("ECDH", chip.public, reader.private)
        val readerMessaging = EACCAProtocol.restartSecureMessaging(oid, secret, 256, true)
        val command = CommandAPDU(0x00, 0xB0, 0x00, 0x00, 4)
        assertContentEquals(command.bytes, chipMessaging.unwrapCommand(readerMessaging.wrap(command).bytes))
    }

    @Test
    fun `3DES Chip Authentication agrees keys from MSE Set KAT`() {
        for (curve in listOf(EcCurve.NIST_P256, EcCurve.BRAINPOOL_P256R1, EcCurve.NIST_P384)) {
            val chip = keyPair(curve)
            val reader = readerKeyPair(chip)
            val protocol = ChipAuthenticationProtocol(ChipAuthenticationSpec(curve, SessionCipher.TDES), chip)

            val result = protocol.processMseSetKat(TLVUtil.wrapDO(0x91, PACEProtocol.encodePublicKeyForSmartCard(reader.public)))

            assertTrue(result.success, result.message)
            assertTrue(protocol.isAuthenticated)
            assertSameSessionKeys(protocol.oid, chip, reader, protocol.takeSecureMessaging())
            assertNull(protocol.takeSecureMessaging(), "the new keys are handed over once")
        }
    }

    @Test
    fun `AES Chip Authentication agrees keys from MSE Set AT and GENERAL AUTHENTICATE`() {
        for (cipher in listOf(SessionCipher.AES_128, SessionCipher.AES_192, SessionCipher.AES_256)) {
            val chip = keyPair(EcCurve.NIST_P256)
            val reader = readerKeyPair(chip)
            val protocol = ChipAuthenticationProtocol(ChipAuthenticationSpec(EcCurve.NIST_P256, cipher), chip)

            assertTrue(protocol.processMseSetAt(Util.toOIDBytes(protocol.oid)).success)
            val readerKey = TLVUtil.wrapDO(0x80, PACEProtocol.encodePublicKeyForSmartCard(reader.public))
            val result = protocol.processGeneralAuthenticate(TLVUtil.wrapDO(0x7C, readerKey))

            assertTrue(result.success, result.message)
            assertContentEquals(byteArrayOf(0x7C, 0x00), result.data)
            assertSameSessionKeys(protocol.oid, chip, reader, protocol.takeSecureMessaging())
        }
    }

    @Test
    fun `the chip refuses another protocol, an unknown key and a point off the curve`() {
        val chip = keyPair(EcCurve.NIST_P256)
        val protocol = ChipAuthenticationProtocol(ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_128), chip)

        val otherOid = PassportLdsFiles.chipAuthenticationOid(SessionCipher.AES_256)
        assertEquals(0x6A88, protocol.processMseSetAt(Util.toOIDBytes(otherOid)).statusWord)
        assertEquals(
            0x6A88,
            protocol.processMseSetAt(Util.toOIDBytes(protocol.oid) + TLVUtil.wrapDO(0x84, byteArrayOf(1))).statusWord
        )
        assertEquals(0x6985, protocol.processGeneralAuthenticate(byteArrayOf(0x7C, 0x00)).statusWord)

        assertTrue(protocol.processMseSetAt(Util.toOIDBytes(protocol.oid)).success)
        val offCurve = byteArrayOf(0x04) + ByteArray(64) { 1 }
        val result = protocol.processGeneralAuthenticate(TLVUtil.wrapDO(0x7C, TLVUtil.wrapDO(0x80, offCurve)))
        assertEquals(0x6A80, result.statusWord)
        assertFalse(protocol.isAuthenticated)
        assertNull(protocol.takeSecureMessaging())
    }
}
