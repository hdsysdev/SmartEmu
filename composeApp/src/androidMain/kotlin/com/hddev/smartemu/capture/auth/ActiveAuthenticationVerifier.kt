package com.hddev.smartemu.capture.auth

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.ISO9796d2Signer
import org.jmrtd.Util
import org.jmrtd.lds.ActiveAuthenticationInfo
import java.math.BigInteger
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/**
 * Checks the answer of INTERNAL AUTHENTICATE against the DG15 key, as ICAO 9303 part 11 describes Active Authentication:
 * ECDSA in the plain (R, S) form, or RSA with ISO/IEC 9796-2 scheme 1. JMRTD's `doAA` sends the command and hands back
 * the response, and leaves the checking to the caller.
 *
 * The chip's digest isn't known to a reader that didn't read it from DG14, and checking is local, so every digest a
 * chip may use is tried against the one response, without a command being sent again. The answer is the name of the
 * scheme that fit, or null if none did.
 */
object ActiveAuthenticationVerifier {

    private class Digests(val name: String, val make: () -> Digest, val jca: String)

    private val digests = listOf(
        Digests("SHA1", ::SHA1Digest, "SHA1"),
        Digests("SHA256", ::SHA256Digest, "SHA256"),
        Digests("SHA384", ::SHA384Digest, "SHA384"),
        Digests("SHA512", ::SHA512Digest, "SHA512"),
        Digests("SHA224", ::SHA224Digest, "SHA224"),
        Digests("RIPEMD160", ::RIPEMD160Digest, "RIPEMD160")
    )

    private val ecdsaOids = mapOf(
        ActiveAuthenticationInfo.ECDSA_PLAIN_SHA1_OID to "SHA1",
        ActiveAuthenticationInfo.ECDSA_PLAIN_SHA224_OID to "SHA224",
        ActiveAuthenticationInfo.ECDSA_PLAIN_SHA256_OID to "SHA256",
        ActiveAuthenticationInfo.ECDSA_PLAIN_SHA384_OID to "SHA384",
        ActiveAuthenticationInfo.ECDSA_PLAIN_SHA512_OID to "SHA512",
        ActiveAuthenticationInfo.ECDSA_PLAIN_RIPEMD160_OID to "RIPEMD160"
    )

    /**
     * The scheme under which [response] is a valid signature of [challenge] by [key], as "ECDSA-SHA256" or
     * "RSA-ISO9796-2-SHA1", or null. [signatureAlgorithmOid] is the one DG14's ActiveAuthenticationInfo names, if any,
     * and goes first.
     */
    fun verify(key: PublicKey, challenge: ByteArray, response: ByteArray, signatureAlgorithmOid: String? = null): String? = when (key) {
        is ECPublicKey -> verifyEcdsa(key, challenge, response, signatureAlgorithmOid)
        is RSAPublicKey -> verifyRsa(key, challenge, response)
        else -> null
    }

    private fun verifyEcdsa(key: ECPublicKey, challenge: ByteArray, response: ByteArray, oid: String?): String? {
        val half = (key.params.curve.field.fieldSize + 7) / 8
        if (response.size != 2 * half) return null
        val der = DERSequence(
            arrayOf(ASN1Integer(BigInteger(1, response.copyOfRange(0, half))), ASN1Integer(BigInteger(1, response.copyOfRange(half, response.size))))
        ).encoded
        val preferred = ecdsaOids[oid]
        return ordered(preferred).firstNotNullOfOrNull { d ->
            val matches = try {
                // Bouncy Castle, since the JDK provider does not know the Brainpool curves
                Signature.getInstance("${d.jca}withECDSA", Util.getBouncyCastleProvider()).run {
                    initVerify(key)
                    update(challenge)
                    verify(der)
                }
            } catch (e: java.security.GeneralSecurityException) {
                false
            }
            if (matches) "ECDSA-${d.name}" else null
        }
    }

    private fun verifyRsa(key: RSAPublicKey, challenge: ByteArray, response: ByteArray): String? {
        val parameters = RSAKeyParameters(false, key.modulus, key.publicExponent)
        for (d in digests) {
            for (implicit in listOf(true, false)) {
                val matches = try {
                    ISO9796d2Signer(RSAEngine(), d.make(), implicit).run {
                        init(false, parameters)
                        updateWithRecoveredMessage(response)
                        update(challenge, 0, challenge.size)
                        verifySignature(response)
                    }
                } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
                    false
                } catch (e: RuntimeException) {
                    false
                }
                if (matches) return "RSA-ISO9796-2-${d.name}"
            }
        }
        return null
    }

    private fun ordered(preferred: String?): List<Digests> =
        if (preferred == null) digests else digests.sortedByDescending { it.name == preferred }
}
