package com.hddev.smartemu.utils

import android.util.Log
import com.hddev.smartemu.data.ChipAuthenticationSpec
import net.sf.scuba.tlv.TLVUtil
import org.jmrtd.Util
import org.jmrtd.protocol.PACEProtocol
import java.security.KeyPair
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/**
 * Chip side of EAC Chip Authentication version 1, ICAO 9303 part 11 section 6.2 and BSI TR-03110: under the
 * secure messaging BAC or PACE started, the reader sends an ephemeral ECDH public key, both sides agree a secret
 * with the chip's static key from EF.DG14, and secure messaging restarts with keys derived from it and SSC 0. Only
 * a chip holding the private key can go on answering, so a copied chip is found out.
 *
 * With AES the reader selects the protocol with MSE:Set AT (P1-P2 41 A4) and sends its key in GENERAL AUTHENTICATE;
 * with 3DES it sends its key in MSE:Set KAT (41 A6). Mirrors JMRTD's EACCAProtocol, the reader side.
 *
 * The chip answers the command that completes the protocol under the old keys, and only then switches; see
 * [takeSecureMessaging].
 */
class ChipAuthenticationProtocol(
    private val spec: ChipAuthenticationSpec,
    private val keyPair: KeyPair
) {

    companion object {
        private const val TAG = "ChipAuthentication"

        private const val TAG_CRYPTOGRAPHIC_MECHANISM = 0x80
        private const val TAG_PRIVATE_KEY_REFERENCE = 0x84
        private const val TAG_EPHEMERAL_PUBLIC_KEY = 0x91
        private const val TAG_DYNAMIC_AUTHENTICATION_DATA = 0x7C
        private const val TAG_EPHEMERAL_PUBLIC_KEY_PCD = 0x80

        private const val SW_WRONG_DATA = 0x6A80
        private const val SW_REFERENCED_DATA_NOT_FOUND = 0x6A88
        private const val SW_CONDITIONS_NOT_SATISFIED = 0x6985
    }

    /** The outcome of a Chip Authentication command: the response data, or the status word it failed with. */
    data class Result(val success: Boolean, val message: String, val statusWord: Int = 0x9000, val data: ByteArray = byteArrayOf())

    val oid: String = PassportLdsFiles.chipAuthenticationOid(spec.cipher)

    private var protocolSelected = false
    private var pendingSecureMessaging: ChipSecureMessaging? = null

    /** Whether the reader has completed Chip Authentication in this session. */
    var isAuthenticated: Boolean = false
        private set

    /**
     * MSE:Set AT for Chip Authentication with AES: the protocol's OID, and optionally a key reference the chip's
     * single key doesn't have.
     */
    fun processMseSetAt(data: ByteArray): Result {
        val dataObjects = parseDataObjects(data) ?: return fail("Malformed MSE:Set AT data", SW_WRONG_DATA)
        val mechanism = dataObjects[TAG_CRYPTOGRAPHIC_MECHANISM]
        if (!spec.cipher.isAes || mechanism == null ||
            !TLVUtil.wrapDO(TAG_CRYPTOGRAPHIC_MECHANISM, mechanism).contentEquals(Util.toOIDBytes(oid))) {
            return fail("Chip Authentication protocol not supported", SW_REFERENCED_DATA_NOT_FOUND)
        }
        if (dataObjects.containsKey(TAG_PRIVATE_KEY_REFERENCE)) {
            return fail("Unknown Chip Authentication key reference", SW_REFERENCED_DATA_NOT_FOUND)
        }
        protocolSelected = true
        return Result(true, "Chip Authentication selected: $oid")
    }

    /**
     * GENERAL AUTHENTICATE for Chip Authentication with AES: 7C { 80 PK_PCD }. Answers 7C 00, as version 1 has
     * nothing to return.
     */
    fun processGeneralAuthenticate(data: ByteArray): Result {
        if (!protocolSelected) return fail("GENERAL AUTHENTICATE before MSE:Set AT", SW_CONDITIONS_NOT_SATISFIED)
        protocolSelected = false
        val template = parseDataObjects(data)?.get(TAG_DYNAMIC_AUTHENTICATION_DATA)
            ?: return fail("Missing dynamic authentication data", SW_WRONG_DATA)
        val readerKey = parseDataObjects(template)?.get(TAG_EPHEMERAL_PUBLIC_KEY_PCD)
            ?: return fail("Missing ephemeral public key", SW_WRONG_DATA)
        return agree(readerKey, TLVUtil.wrapDO(TAG_DYNAMIC_AUTHENTICATION_DATA, byteArrayOf()))
    }

    /**
     * MSE:Set KAT for Chip Authentication with 3DES: 91 PK_PCD, and optionally a key reference.
     */
    fun processMseSetKat(data: ByteArray): Result {
        if (spec.cipher.isAes) return fail("Chip Authentication with 3DES not supported", SW_REFERENCED_DATA_NOT_FOUND)
        val dataObjects = parseDataObjects(data) ?: return fail("Malformed MSE:Set KAT data", SW_WRONG_DATA)
        if (dataObjects.containsKey(TAG_PRIVATE_KEY_REFERENCE)) {
            return fail("Unknown Chip Authentication key reference", SW_REFERENCED_DATA_NOT_FOUND)
        }
        val readerKey = dataObjects[TAG_EPHEMERAL_PUBLIC_KEY] ?: return fail("Missing ephemeral public key", SW_WRONG_DATA)
        return agree(readerKey, byteArrayOf())
    }

    /**
     * The secure messaging Chip Authentication agreed, once, for the caller to switch to after sending the response
     * under the old keys.
     */
    fun takeSecureMessaging(): ChipSecureMessaging? = pendingSecureMessaging.also { pendingSecureMessaging = null }

    fun reset() {
        protocolSelected = false
        pendingSecureMessaging = null
        isAuthenticated = false
    }

    private fun agree(encodedReaderKey: ByteArray, response: ByteArray): Result {
        val privateKey = keyPair.private as ECPrivateKey
        val readerKey = decodePublicKey(encodedReaderKey) ?: return fail("Invalid ephemeral public key", SW_WRONG_DATA)

        val keyAgreement = Util.getKeyAgreement("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(readerKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val cipherAlgorithm = if (spec.cipher.isAes) "AES" else "DESede"
        val ksEnc = Util.deriveKey(sharedSecret, cipherAlgorithm, spec.cipher.keyLength, Util.ENC_MODE)
        val ksMac = Util.deriveKey(sharedSecret, cipherAlgorithm, spec.cipher.keyLength, Util.MAC_MODE)
        pendingSecureMessaging = ChipSecureMessaging(ksEnc, ksMac, 0L, ChipSecureMessaging.algorithmFor(spec.cipher))
        isAuthenticated = true
        Log.d(TAG, "Chip Authentication keys agreed")
        return Result(true, "Chip Authentication keys agreed", data = response)
    }

    /** Decodes the reader's uncompressed point on the chip key's curve, rejecting points not on it. */
    private fun decodePublicKey(encoded: ByteArray): PublicKey? = try {
        val parameters = (keyPair.public as ECPublicKey).params
        val publicKey = PACEProtocol.decodePublicKeyFromSmartCard(encoded, parameters) as ECPublicKey
        val point = Util.toBouncyCastleECPoint(publicKey.w, parameters)
        if (point.isValid && !point.isInfinity) publicKey else null
    } catch (e: Exception) {
        Log.w(TAG, "Invalid public key from reader", e)
        null
    }

    private fun fail(message: String, statusWord: Int): Result {
        Log.w(TAG, message)
        protocolSelected = false
        return Result(false, message, statusWord)
    }

    /** Parses a sequence of BER-TLV data objects with one-byte tags, or returns null if malformed. */
    private fun parseDataObjects(data: ByteArray): Map<Int, ByteArray>? {
        val result = mutableMapOf<Int, ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val tag = data[offset++].toInt() and 0xFF
            if (offset >= data.size) return null
            val first = data[offset++].toInt() and 0xFF
            val length = when {
                first < 0x80 -> first
                first == 0x81 && offset < data.size -> data[offset++].toInt() and 0xFF
                first == 0x82 && offset + 1 < data.size ->
                    ((data[offset++].toInt() and 0xFF) shl 8) or (data[offset++].toInt() and 0xFF)
                else -> return null
            }
            if (offset + length > data.size) return null
            result[tag] = data.copyOfRange(offset, offset + length)
            offset += length
        }
        return result
    }
}
