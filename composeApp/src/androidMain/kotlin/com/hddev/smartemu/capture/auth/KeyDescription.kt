package com.hddev.smartemu.capture.auth

import org.jmrtd.Util
import java.security.PublicKey
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.interfaces.DHPublicKey

/** What a public key is, and not the key: its type, its size in bits and, for an elliptic curve, the curve's name. */
data class KeyDescription(val type: String?, val bits: Int?, val curve: String?) {
    companion object {
        fun of(key: PublicKey?): KeyDescription = when (key) {
            null -> KeyDescription(null, null, null)
            is RSAPublicKey -> KeyDescription("RSA", key.modulus.bitLength(), null)
            is ECPublicKey -> KeyDescription("EC", key.params.curve.field.fieldSize, curveName(key))
            is DHPublicKey -> KeyDescription("DH", key.params.p.bitLength(), null)
            is DSAPublicKey -> KeyDescription("DSA", key.params.p.bitLength(), null)
            else -> KeyDescription(key.algorithm.takeIf { it.length <= 16 && it.all { c -> c.isLetterOrDigit() || c == '-' } }, null, null)
        }

        private fun curveName(key: ECPublicKey): String? = try {
            Util.getCurveName(key.params)
        } catch (e: RuntimeException) {
            null
        }
    }
}
