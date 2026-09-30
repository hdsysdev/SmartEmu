package com.hddev.smartemu.utils

import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.tlv.TLVUtil
import org.jmrtd.Util
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Chip side of ICAO 9303 part 11 secure messaging, with 3DES session keys (BAC, and PACE or Chip Authentication
 * with 3DES) or AES session keys (PACE or Chip Authentication with AES).
 * Mirrors JMRTD's DESedeSecureMessagingWrapper and AESSecureMessagingWrapper: where the reader wraps commands
 * and unwraps responses, the chip unwraps commands and wraps responses. The send sequence counter is incremented
 * before every command MAC check and every response MAC computation.
 */
class ChipSecureMessaging(
    private val ksEnc: SecretKey,
    private val ksMac: SecretKey,
    private var ssc: Long,
    private val algorithm: Algorithm = Algorithm.DESEDE
) {

    /**
     * Session cipher suites: 3DES with retail MAC and a zero IV, or AES with CMAC and IV = E(KS_enc, SSC).
     */
    enum class Algorithm(val cipherName: String, val macName: String, val blockSize: Int) {
        DESEDE("DESede/CBC/NoPadding", "ISO9797Alg3Mac", 8),
        AES("AES/CBC/NoPadding", "AESCMAC", 16)
    }

    companion object {
        private const val MAC_LENGTH = 8

        /** The suite secure messaging runs with after PACE or Chip Authentication with [cipher]. */
        fun algorithmFor(cipher: com.hddev.smartemu.data.SessionCipher): Algorithm =
            if (cipher.isAes) Algorithm.AES else Algorithm.DESEDE

        private const val TAG_ENCRYPTED_DATA = 0x87
        private const val TAG_ENCRYPTED_DATA_ODD_INS = 0x85
        private const val TAG_EXPECTED_LENGTH = 0x97
        private const val TAG_STATUS_WORD = 0x99
        private const val TAG_MAC = 0x8E
        private const val PADDING_INDICATOR = 0x01
    }

    /**
     * Thrown when a command cannot be verified; the chip must answer with SW 6988 and drop the session.
     */
    class SecureMessagingException(message: String) : Exception(message)

    private val cipher: Cipher = Util.getCipher(algorithm.cipherName)
    private val mac = Util.getMac(algorithm.macName)
    private val sscIvCipher: Cipher? = if (algorithm == Algorithm.AES) {
        Util.getCipher("AES/ECB/NoPadding", Cipher.ENCRYPT_MODE, ksEnc)
    } else {
        null
    }

    /**
     * Verifies and decrypts a protected command APDU, returning the equivalent plain APDU (CLA 00).
     */
    fun unwrapCommand(apdu: ByteArray): ByteArray {
        val command = try {
            CommandAPDU(apdu)
        } catch (e: IllegalArgumentException) {
            throw SecureMessagingException("Malformed protected APDU: ${e.message}")
        }
        if ((command.cla and 0x0C) != 0x0C) {
            throw SecureMessagingException("APDU is not protected by secure messaging")
        }

        var dataObject8587 = byteArrayOf()
        var dataObject97 = byteArrayOf()
        var encryptedData: ByteArray? = null
        var expectedLength = 0
        var cryptographicChecksum: ByteArray? = null

        val body = command.data
        var offset = 0
        while (offset < body.size) {
            val start = offset
            val tag = body[offset++].toInt() and 0xFF
            val (length, lengthSize) = readLength(body, offset)
            offset += lengthSize
            if (offset + length > body.size) {
                throw SecureMessagingException("Data object ${Integer.toHexString(tag)} exceeds command data")
            }
            val value = body.copyOfRange(offset, offset + length)
            offset += length
            val dataObject = body.copyOfRange(start, offset)
            when (tag) {
                TAG_ENCRYPTED_DATA -> {
                    if (length < 1 || value[0].toInt() != PADDING_INDICATOR) {
                        throw SecureMessagingException("DO'87 without padding indicator")
                    }
                    dataObject8587 = dataObject
                    encryptedData = value.copyOfRange(1, value.size)
                }
                TAG_ENCRYPTED_DATA_ODD_INS -> {
                    dataObject8587 = dataObject
                    encryptedData = value
                }
                TAG_EXPECTED_LENGTH -> {
                    dataObject97 = dataObject
                    expectedLength = decodeLe(value)
                }
                TAG_MAC -> cryptographicChecksum = value
                else -> throw SecureMessagingException("Unexpected data object ${Integer.toHexString(tag)}")
            }
        }
        
        val checksum = cryptographicChecksum ?: throw SecureMessagingException("Missing DO'8E")

        ssc++
        val maskedHeader = byteArrayOf(command.cla.toByte(), command.ins.toByte(), command.p1.toByte(), command.p2.toByte())
        val macInput = Util.pad(maskedHeader, algorithm.blockSize) + dataObject8587 + dataObject97
        if (!computeMac(macInput).contentEquals(checksum)) {
            throw SecureMessagingException("Invalid command MAC")
        }

        val plainData = encryptedData?.let { decrypt(it) } ?: byteArrayOf()
        val plainCla = command.cla and 0x0C.inv()
        return CommandAPDU(plainCla, command.ins, command.p1, command.p2, plainData, expectedLength).bytes
    }

    /**
     * Protects a plain response (data followed by SW1 SW2) with DO'87, DO'99 and DO'8E.
     */
    fun wrapResponse(plainResponse: ByteArray): ByteArray {
        require(plainResponse.size >= 2) { "Response APDU must contain a status word" }
        val data = plainResponse.copyOfRange(0, plainResponse.size - 2)
        val statusWord = plainResponse.copyOfRange(plainResponse.size - 2, plainResponse.size)

        // The response SSC also determines the AES IV, so it is incremented before encrypting
        ssc++
        val dataObject87 = if (data.isNotEmpty()) {
            TLVUtil.wrapDO(TAG_ENCRYPTED_DATA, byteArrayOf(PADDING_INDICATOR.toByte()) + encrypt(data))
        } else {
            byteArrayOf()
        }
        val dataObject99 = TLVUtil.wrapDO(TAG_STATUS_WORD, statusWord)
        val dataObject8E = TLVUtil.wrapDO(TAG_MAC, computeMac(dataObject87 + dataObject99))

        return dataObject87 + dataObject99 + dataObject8E + statusWord
    }

    private fun computeMac(data: ByteArray): ByteArray {
        mac.init(ksMac)
        mac.update(encodedSsc())
        // AES-CMAC yields 16 bytes; secure messaging uses the first 8
        return mac.doFinal(Util.pad(data, algorithm.blockSize)).copyOf(MAC_LENGTH)
    }

    private fun encrypt(data: ByteArray): ByteArray {
        cipher.init(Cipher.ENCRYPT_MODE, ksEnc, iv())
        return cipher.doFinal(Util.pad(data, algorithm.blockSize))
    }

    private fun decrypt(data: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % algorithm.blockSize != 0) {
            throw SecureMessagingException("Encrypted data is not a multiple of the block size")
        }
        cipher.init(Cipher.DECRYPT_MODE, ksEnc, iv())
        return try {
            Util.unpad(cipher.doFinal(data))
        } catch (e: javax.crypto.BadPaddingException) {
            throw SecureMessagingException("Invalid padding in encrypted data")
        }
    }

    private fun iv(): IvParameterSpec {
        val ivCipher = sscIvCipher ?: return IvParameterSpec(ByteArray(algorithm.blockSize))
        return IvParameterSpec(ivCipher.doFinal(encodedSsc()))
    }

    /**
     * The SSC as a big-endian block: 8 bytes for 3DES, 16 bytes (zero-extended) for AES.
     */
    private fun encodedSsc(): ByteArray {
        val size = algorithm.blockSize
        return ByteArray(size) { i -> if (i < size - 8) 0 else (ssc ushr (8 * (size - 1 - i))).toByte() }
    }

    /**
     * Reads a BER-TLV length at [offset], returning the length and the number of bytes it occupied.
     */
    private fun readLength(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        if (offset >= bytes.size) throw SecureMessagingException("Truncated data object length")
        val first = bytes[offset].toInt() and 0xFF
        if (first < 0x80) return first to 1
        val lengthBytes = first and 0x7F
        if (lengthBytes !in 1..2 || offset + lengthBytes >= bytes.size) {
            throw SecureMessagingException("Unsupported data object length encoding")
        }
        var length = 0
        for (i in 1..lengthBytes) {
            length = (length shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return length to lengthBytes + 1
    }
    
    /**
     * Decodes the Le carried in DO'97; zero encodes the maximum for the given width.
     */
    private fun decodeLe(value: ByteArray): Int {
        return when (value.size) {
            1 -> (value[0].toInt() and 0xFF).takeIf { it != 0 } ?: 256
            2 -> (((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)).takeIf { it != 0 } ?: 65536
            else -> throw SecureMessagingException("DO'97 has invalid length ${value.size}")
        }
    }
}
