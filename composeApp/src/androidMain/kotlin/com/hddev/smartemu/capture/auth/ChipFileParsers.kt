package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.ActiveAuthenticationKeyObservation
import com.hddev.smartemu.capture.ComObservation
import com.hddev.smartemu.capture.CvcaObservation
import com.hddev.smartemu.capture.Redaction
import com.hddev.smartemu.capture.SignerCertificateObservation
import com.hddev.smartemu.capture.SizeRange
import com.hddev.smartemu.capture.SodObservation
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.jmrtd.lds.CVCAFile
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG15File
import java.io.ByteArrayInputStream
import java.security.cert.X509Certificate

/**
 * Reduces the files of the eMRTD application to what a capture keeps. Each function takes the file's bytes and returns
 * a structure with no byte string in it: the data group hashes of EF.SOD are counted by data group and dropped, a key is
 * a type and a size, and a certificate is its algorithms and the length of its validity.
 */
internal object ChipFileParsers {
    private val name = Regex("[A-Za-z0-9][A-Za-z0-9._/+-]{0,47}")
    private val curveName = Regex("[A-Za-z0-9-]{1,32}")
    private val millisPerMonth = 30.4375 * 24 * 3600 * 1000

    class ParsedCom(val observation: ComObservation, val dataGroups: Set<Int>)

    fun com(bytes: ByteArray): ParsedCom {
        val file = COMFile(ByteArrayInputStream(bytes))
        val dataGroups = file.tagList.toList().mapNotNull(DataGroups::numberOfTag).toSortedSet()
        return ParsedCom(
            ComObservation(
                ldsVersion = file.ldsVersion?.takeIf { it.matches(Regex("[0-9.]{1,12}")) },
                unicodeVersion = file.unicodeVersion?.takeIf { it.matches(Regex("[0-9.]{1,12}")) },
                dataGroups = dataGroups.toList(),
                size = Redaction.sizeRange(bytes.size)
            ),
            dataGroups
        )
    }

    class ParsedSod(val observation: SodObservation, val hashedDataGroups: Set<Int>)

    /** EF.SOD without its hash values: the digest and signature algorithms, the versions, which data groups it hashes, and the signer. */
    fun sod(bytes: ByteArray): ParsedSod {
        val file = SODFile(ByteArrayInputStream(bytes))
        val hashed = file.dataGroupHashes.keys.toSortedSet()
        val certificates = file.docSigningCertificates ?: emptyList()
        return ParsedSod(
            SodObservation(
                size = Redaction.sizeRange(bytes.size),
                digestAlgorithm = file.digestAlgorithm?.let(::plain),
                signatureAlgorithm = file.digestEncryptionAlgorithm?.let(::plain),
                ldsVersion = file.ldsVersion?.takeIf { it.matches(Regex("[0-9.]{1,12}")) },
                unicodeVersion = file.unicodeVersion?.takeIf { it.matches(Regex("[0-9.]{1,12}")) },
                hashedDataGroups = hashed.toList(),
                certificateCount = certificates.size.coerceAtMost(8),
                signer = certificates.firstOrNull()?.let(::signer)
            ),
            hashed
        )
    }

    /**
     * The document signer's certificate as structure: its signature algorithm, key type, size and curve, the issuer's
     * country, and the validity period's length in months. Not its serial number, subject key identifier, names or bytes:
     * those would single out one signer, and the certificate is anyway the issuer's to publish.
     */
    fun signer(certificate: X509Certificate): SignerCertificateObservation {
        val key = KeyDescription.of(certificate.publicKey)
        val months = ((certificate.notAfter.time - certificate.notBefore.time) / millisPerMonth).toInt().takeIf { it in 0..1200 }
        return SignerCertificateObservation(
            signatureAlgorithm = certificate.sigAlgName?.let(::plain),
            keyType = key.type,
            keyBits = key.bits,
            curve = key.curve?.takeIf { it.matches(curveName) },
            issuerCountry = issuerCountry(certificate),
            validityMonths = months
        )
    }

    private fun issuerCountry(certificate: X509Certificate): String? = try {
        X500Name.getInstance(certificate.issuerX500Principal.encoded).getRDNs(BCStyle.C).firstOrNull()
            ?.first?.value?.toString()?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2,3}")) }
    } catch (e: RuntimeException) {
        null
    }

    /** DG15's key as a kind and a size. */
    fun activeAuthenticationKey(bytes: ByteArray): Pair<ActiveAuthenticationKeyObservation, java.security.PublicKey?> {
        val key = DG15File(ByteArrayInputStream(bytes)).publicKey
        val description = KeyDescription.of(key)
        return ActiveAuthenticationKeyObservation(
            size = Redaction.sizeRange(bytes.size),
            keyType = description.type,
            keyBits = description.bits,
            curve = description.curve?.takeIf { it.matches(curveName) }
        ) to key
    }

    /** EF.CVCA: its size, and the country of the CAR (the first two characters of the reference to the certification authority). */
    fun cvca(bytes: ByteArray): CvcaObservation {
        val file = CVCAFile(ByteArrayInputStream(bytes))
        val country = file.caReference?.name?.take(2)?.takeIf { it.matches(Regex("[A-Z]{2}")) }
        return CvcaObservation(Redaction.sizeRange(bytes.size), country, hasAlternateReference = file.altCAReference != null)
    }

    /** A bucket for a length, or null. */
    fun size(length: Int?): SizeRange? = length?.let(Redaction::sizeRange)

    /** [value] if it is the shape of an algorithm name, else null: a name from a certificate or SOD is never passed on unchecked. */
    fun plain(value: String): String? = value.takeIf { it.matches(name) }
}
