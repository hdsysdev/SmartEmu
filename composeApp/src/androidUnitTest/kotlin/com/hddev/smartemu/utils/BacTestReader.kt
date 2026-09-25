package com.hddev.smartemu.utils

import com.hddev.smartemu.data.PassportData
import org.jmrtd.BACKey
import org.jmrtd.Util
import org.jmrtd.protocol.BACProtocol
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Reader (inspection system) side of BAC mutual authentication, following JMRTD's BACAPDUSender,
 * for driving the chip-side [BacProtocol] in tests.
 */
object BacTestReader {
    
    private val zeroIv = IvParameterSpec(ByteArray(8))
    private val random = SecureRandom()
    
    class MutualAuthentication(val data: ByteArray, val rndIfd: ByteArray, val kIfd: ByteArray)
    
    fun documentKeys(passportData: PassportData): Pair<SecretKey, SecretKey> {
        val bacKey = BACKey(passportData.passportNumber, passportData.mrzDateOfBirth(), passportData.mrzExpiryDate())
        val keySeed = BACProtocol.computeKeySeedForBAC(bacKey)
        return Util.deriveKey(keySeed, Util.ENC_MODE) to Util.deriveKey(keySeed, Util.MAC_MODE)
    }
    
    /**
     * Builds EXTERNAL AUTHENTICATE data E_IFD || M_IFD for the chip challenge [rndIc].
     */
    fun createMutualAuthentication(passportData: PassportData, rndIc: ByteArray): MutualAuthentication {
        val (kEnc, kMac) = documentKeys(passportData)
        val rndIfd = ByteArray(8).also { random.nextBytes(it) }
        val kIfd = ByteArray(16).also { random.nextBytes(it) }
        
        val cipher = Util.getCipher("DESede/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kEnc, zeroIv)
        val eIfd = cipher.doFinal(rndIfd + rndIc + kIfd)
        return MutualAuthentication(eIfd + mac(kMac, eIfd), rndIfd, kIfd)
    }
    
    /**
     * Verifies the chip's E_IC || M_IC and returns the decrypted RND.IC || RND.IFD || K.IC.
     */
    fun decryptChipResponse(passportData: PassportData, response: ByteArray): ByteArray {
        val (kEnc, kMac) = documentKeys(passportData)
        val eIc = response.copyOfRange(0, 32)
        check(mac(kMac, eIc).contentEquals(response.copyOfRange(32, 40))) { "Chip response MAC mismatch" }
        val cipher = Util.getCipher("DESede/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, kEnc, zeroIv)
        return cipher.doFinal(eIc)
    }
    
    private fun mac(key: SecretKey, data: ByteArray): ByteArray {
        val mac = Util.getMac("ISO9797Alg3Mac")
        mac.init(key)
        return mac.doFinal(Util.pad(data, 8))
    }
}
