package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.SecurityInfoKind
import com.hddev.smartemu.capture.SecurityInfoObservation
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.cms.CMSSignedData
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.PACEDomainParameterInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.TerminalAuthenticationInfo

/** One entry of a set of SecurityInfos in the order the chip wrote it, as JMRTD's typed object if it knows the kind. */
class OrderedSecurityInfo(val oid: String, val info: SecurityInfo?)

/**
 * Reads the SecurityInfos of EF.CardAccess, DG14 and EF.CardSecurity in the chip's own order. JMRTD's `CardAccessFile`,
 * `DG14File` and `CardSecurityFile` hold them in a hash set and so lose it, and the order matters here: a reader
 * that tries PACE suites in turn must start with the one the chip lists first.
 */
object SecurityInfoReader {

    /** The SecurityInfos of EF.CardAccess: a bare SET OF SecurityInfo. */
    fun fromCardAccess(bytes: ByteArray): List<OrderedSecurityInfo> = fromSet(ASN1Set.getInstance(ASN1InputStream(bytes).use { it.readObject() }))

    /** The SecurityInfos of DG14: the SET OF SecurityInfo inside the data group's tag 6E. */
    fun fromDg14(bytes: ByteArray): List<OrderedSecurityInfo> {
        val (valueStart, valueLength) = BerHeader.valueRange(bytes) ?: return emptyList()
        val value = bytes.copyOfRange(valueStart, minOf(bytes.size, valueStart + valueLength))
        return fromSet(ASN1Set.getInstance(ASN1InputStream(value).use { it.readObject() }))
    }

    /** The SecurityInfos in the signed content of EF.CardSecurity, a CMS SignedData; the signature itself is not read. */
    fun fromCardSecurity(bytes: ByteArray): List<OrderedSecurityInfo> {
        val signed = CMSSignedData(bytes)
        val content = signed.signedContent.content as ByteArray
        return fromSet(ASN1Set.getInstance(ASN1InputStream(content).use { it.readObject() }))
    }

    private fun fromSet(set: ASN1Set): List<OrderedSecurityInfo> = set.map { element ->
        val oid = ASN1ObjectIdentifier.getInstance(ASN1Sequence.getInstance(element).getObjectAt(0)).id
        OrderedSecurityInfo(oid, parse(element))
    }

    private fun parse(element: ASN1Encodable): SecurityInfo? = try {
        SecurityInfo.getInstance(element)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The PACE suites in [infos], in order, with the domain parameters EF.CardAccess carries for identifiers the standard doesn't name. */
    fun paceSuites(infos: List<OrderedSecurityInfo>): List<PaceSuiteInfo> {
        val domains = infos.mapNotNull { it.info as? PACEDomainParameterInfo }
        return infos.mapNotNull { it.info as? PACEInfo }.map { info ->
            val id = info.parameterId?.takeIf { it.signum() >= 0 }
            PaceSuiteInfo(info, id, domains.firstOrNull { d -> id != null && d.parameterId == id })
        }
    }

    /** A SecurityInfo as the structure the record keeps: its protocol, versions and identifiers, and a key's type and size but never its bytes. */
    fun describe(entry: OrderedSecurityInfo): SecurityInfoObservation {
        val info = entry.info
        return when (info) {
            is PACEInfo -> SecurityInfoObservation(
                SecurityInfoKind.PACE, entry.oid, version = info.version, parameterId = info.parameterId?.takeIf { it.signum() >= 0 }?.toIntOrNull()
            )
            is PACEDomainParameterInfo -> SecurityInfoObservation(
                SecurityInfoKind.PACE_DOMAIN_PARAMETERS, entry.oid, parameterId = info.parameterId?.toIntOrNull()
            )
            is ChipAuthenticationInfo -> SecurityInfoObservation(
                SecurityInfoKind.CHIP_AUTHENTICATION, entry.oid, version = info.version, parameterId = info.keyId?.toIntOrNull()
            )
            is ChipAuthenticationPublicKeyInfo -> {
                val key = KeyDescription.of(info.subjectPublicKey)
                SecurityInfoObservation(
                    SecurityInfoKind.CHIP_AUTHENTICATION_PUBLIC_KEY, entry.oid, parameterId = info.keyId?.toIntOrNull(),
                    keyType = key.type, keyBits = key.bits, curve = key.curve
                )
            }
            is TerminalAuthenticationInfo -> SecurityInfoObservation(SecurityInfoKind.TERMINAL_AUTHENTICATION, entry.oid, version = info.version)
            is ActiveAuthenticationInfo -> SecurityInfoObservation(SecurityInfoKind.ACTIVE_AUTHENTICATION, entry.oid, version = info.version)
            else -> SecurityInfoObservation(SecurityInfoKind.OTHER, entry.oid)
        }
    }

    private fun java.math.BigInteger.toIntOrNull(): Int? = takeIf { it.bitLength() < 16 }?.toInt()

    fun paceCamPresent(infos: List<OrderedSecurityInfo>): Boolean =
        paceSuites(infos).any { PACEInfo.toMappingType(it.info.objectIdentifier) == PACEInfo.MappingType.CAM }
}

/** A PACE suite from EF.CardAccess: its [info], the parameter identifier (null if absent), and explicit domain parameters if it names some. */
class PaceSuiteInfo(val info: PACEInfo, val parameterId: java.math.BigInteger?, val domainParameters: PACEDomainParameterInfo?)

/** The BER header of a data group or file: where its value starts and how long it is. */
object BerHeader {
    /** The offset and length of the value of the TLV at the start of [bytes], or null if it has no readable header. */
    fun valueRange(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 2) return null
        var i = 0
        val first = bytes[i++].toInt() and 0xFF
        if (first and 0x1F == 0x1F) {
            do {
                if (i >= bytes.size) return null
                val more = bytes[i++].toInt() and 0x80 != 0
            } while (more)
        }
        if (i >= bytes.size) return null
        var length = bytes[i++].toInt() and 0xFF
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            if (count == 0 || count > 3 || i + count > bytes.size) return null
            length = 0
            repeat(count) { length = (length shl 8) or (bytes[i++].toInt() and 0xFF) }
        }
        return i to length
    }

    /** The length of the whole TLV whose header is [header] (its tag, length bytes, and value), or null if the header is incomplete. */
    fun totalLength(header: ByteArray): Int? = valueRange(header)?.let { (start, length) -> start + length }
}
