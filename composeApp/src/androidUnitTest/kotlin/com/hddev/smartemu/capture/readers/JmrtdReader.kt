package com.hddev.smartemu.capture.readers

import android.nfc.cardemulation.HostApduService
import com.hddev.smartemu.HceCardService
import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.DhGroup
import com.hddev.smartemu.data.KeySpec
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.chipConfiguration
import net.sf.scuba.smartcards.CardServiceException
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.protocol.PACECAMResult
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/** One step a reader takes on a profile, and what happened. */
enum class StepKind(val word: String) {
    PASS("pass"),

    /** The step ran and broke, and the reader claims to support what it needed. Triage: profile, emulator or reader. */
    FAIL("fail"),

    /** The reader can't do something the profile uses, and its code or documentation says so (see [KnownReaderLimits]). */
    UNSUPPORTED("reader unsupported"),

    /** The profile has nothing for the step (no BAC, no Chip Authentication). */
    NOT_APPLICABLE("n/a")
}

data class StepOutcome(
    val kind: StepKind,
    val detail: String? = null,
    /** For an unsupported step, the exception the reader threw: the evidence behind the label. Not pinned in the baseline. */
    val evidence: String? = null
) {
    /** As written in the matrix and the baseline: `pass`, `fail: <reason>`, `reader unsupported: <reason>`, `n/a`. */
    val text: String get() = if (detail == null) kind.word else "${kind.word}: $detail"

    companion object {
        val pass = StepOutcome(StepKind.PASS)
        val notApplicable = StepOutcome(StepKind.NOT_APPLICABLE)
        fun parse(text: String): StepOutcome {
            val kind = StepKind.entries.first { text == it.word || text.startsWith(it.word + ": ") }
            return StepOutcome(kind, text.removePrefix(kind.word).removePrefix(": ").ifEmpty { null })
        }
    }
}

/** Every step of one profile, in the order run. A key is a [Steps] id, or `pace:<suite>`. */
class ProfileRun(val profileId: String, val steps: LinkedHashMap<String, StepOutcome>)

object Steps {
    const val CARD_ACCESS = "ef-card-access"
    const val BAC = "bac"
    const val PACE_PREFIX = "pace:"
    const val EF_COM = "ef-com"
    const val EF_SOD = "ef-sod"
    const val DG1 = "dg1"
    const val PASSIVE_AUTHENTICATION = "passive-authentication"
    const val CHIP_AUTHENTICATION = "chip-authentication"
    const val ACTIVE_AUTHENTICATION = "active-authentication"

    val columns = listOf(BAC, EF_COM, EF_SOD, DG1, PASSIVE_AUTHENTICATION, CHIP_AUTHENTICATION, ACTIVE_AUTHENTICATION)
}

/**
 * The reader side of JMRTD with SCUBA and Bouncy Castle (the versions in gradle/libs.versions.toml), driving the emulated
 * chip as the app's integration tests do: through [HceCardService], which delivers APDUs to [PassportHceService] as
 * Android's NFC stack does. For one profile it unlocks the chip with every access method it offers (BAC; each PACE suite
 * of EF.CardAccess, in order, with the MRZ as the password), and with the first that works reads EF.COM, EF.SOD and DG1,
 * checks passive authentication against the test CSCA, and runs Chip Authentication and Active Authentication where the
 * profile has them. A step that throws is a [StepKind.FAIL], or a [StepKind.UNSUPPORTED] where a known limit of the
 * reader explains the very exception it threw.
 */
class JmrtdReader(private val hce: PassportHceService, private val limits: List<ReaderLimit> = KnownReaderLimits.jmrtd) {

    fun run(profile: ChipProfile, data: PassportData, runId: String = profile.id): ProfileRun {
        PassportHceService.setSharedPassportData(data)
        val steps = LinkedHashMap<String, StepOutcome>()
        val key = BACKey(data.passportNumber, data.mrzDateOfBirth(), data.mrzExpiryDate())
        var readDone = false
        // The reads happen in the first session that unlocks the chip, before the next attempt ends it
        fun readOnce(service: PassportService) {
            if (readDone) return
            readDone = true
            readSteps(profile, data, service, steps)
        }

        // EF.CardAccess offers the PACE suites; reading it needs no access control
        var suites: List<PACEInfo> = emptyList()
        steps[Steps.CARD_ACCESS] = if (data.accessControl.supportsPace) {
            attempt(Context.of(profile)) { suites = readPaceInfos(HceCardService.openPassportService(hce)) }
        } else {
            StepOutcome.notApplicable
        }

        for (info in suites) {
            val label = suiteLabel(info)
            steps[Steps.PACE_PREFIX + label] = attempt(Context.of(profile, info)) {
                hce.onDeactivated(HostApduService.DEACTIVATION_LINK_LOSS)
                val service = HceCardService.openPassportService(hce)
                val doPace = {
                    service.doPACE(key, info.objectIdentifier, PACEInfo.toParameterSpec(info.parameterId), info.parameterId)
                }
                val result = if (PACEInfo.toMappingType(info.objectIdentifier) == PACEInfo.MappingType.IM) withBouncyCastleFirst(doPace) else doPace()
                if (result is PACECAMResult) check(result.chipAuthenticationData != null) { "PACE-CAM returned no chip authentication data" }
                service.sendSelectApplet(true)
                checkDg1(service, data)
                readOnce(service)
            }
        }

        steps[Steps.BAC] = if (data.accessControl.supportsBac) {
            attempt(Context.of(profile)) {
                hce.onDeactivated(HostApduService.DEACTIVATION_LINK_LOSS)
                val service = HceCardService.openPassportService(hce)
                service.sendSelectApplet(false)
                service.doBAC(key)
                checkDg1(service, data)
                readOnce(service)
            }
        } else {
            StepOutcome.notApplicable
        }

        if (!readDone) {
            // Nothing unlocked the chip, so nothing could be read; steps the profile doesn't have stay n/a
            val notRun = StepOutcome(StepKind.FAIL, "not run: no access method worked")
            listOf(Steps.EF_COM, Steps.EF_SOD, Steps.DG1, Steps.PASSIVE_AUTHENTICATION).forEach { steps[it] = notRun }
            steps[Steps.CHIP_AUTHENTICATION] = if (profile.chipAuthentication == null) StepOutcome.notApplicable else notRun
            steps[Steps.ACTIVE_AUTHENTICATION] = if (profile.activeAuthentication == null) StepOutcome.notApplicable else notRun
        }
        return ProfileRun(runId, steps)
    }

    private fun readSteps(profile: ChipProfile, data: PassportData, service: PassportService, steps: MutableMap<String, StepOutcome>) {
        steps[Steps.EF_COM] = attempt(Context.of(profile)) { COMFile(ByteArrayInputStream(readFile(service, PassportService.EF_COM))) }
        steps[Steps.EF_SOD] = attempt(Context.of(profile)) { SODFile(ByteArrayInputStream(readFile(service, PassportService.EF_SOD))) }
        steps[Steps.DG1] = attempt(Context.of(profile)) { checkDg1(service, data) }
        steps[Steps.PASSIVE_AUTHENTICATION] = attempt(Context.of(profile)) {
            passiveAuthentication(profile, service, data.chipConfiguration().dataGroups)
        }
        steps[Steps.CHIP_AUTHENTICATION] = if (profile.chipAuthentication == null) StepOutcome.notApplicable else attempt(Context.of(profile)) {
            chipAuthentication(service)
        }
        steps[Steps.ACTIVE_AUTHENTICATION] = profile.activeAuthentication?.let { spec ->
            attempt(Context.of(profile)) { activeAuthentication(service, spec) }
        } ?: StepOutcome.notApplicable
    }

    /**
     * PACE with the MRZ on the first suite the chip offers, then DG1: the access step alone, for the grid of PACE variants
     * the developer settings can ask for. Returns the offered suite's label and the outcome; null if EF.CardAccess offers none.
     */
    fun paceOfFirstSuite(profile: ChipProfile, data: PassportData): Pair<String, StepOutcome>? {
        PassportHceService.setSharedPassportData(data)
        val key = BACKey(data.passportNumber, data.mrzDateOfBirth(), data.mrzExpiryDate())
        val info = readPaceInfos(HceCardService.openPassportService(hce)).firstOrNull() ?: return null
        hce.onDeactivated(HostApduService.DEACTIVATION_LINK_LOSS)
        val outcome = attempt(Context.of(profile, info)) {
            val service = HceCardService.openPassportService(hce)
            val doPace = { service.doPACE(key, info.objectIdentifier, PACEInfo.toParameterSpec(info.parameterId), info.parameterId) }
            val result = if (PACEInfo.toMappingType(info.objectIdentifier) == PACEInfo.MappingType.IM) withBouncyCastleFirst(doPace) else doPace()
            if (result is PACECAMResult) check(result.chipAuthenticationData != null) { "PACE-CAM returned no chip authentication data" }
            service.sendSelectApplet(true)
            checkDg1(service, data)
        }
        return suiteLabel(info) to outcome
    }

    /** What a limit may look at: the profile and, for a PACE step, the suite the chip offered. */
    class Context(val profile: ChipProfile, val suite: PACEInfo?) {
        companion object {
            fun of(profile: ChipProfile, suite: PACEInfo? = null) = Context(profile, suite)
        }
    }

    private fun attempt(context: Context, block: () -> Unit): StepOutcome = try {
        block()
        StepOutcome.pass
    } catch (e: Throwable) {
        val reason = describe(e)
        val limit = limits.firstOrNull { it.explains(context, e) }
        if (limit != null) StepOutcome(StepKind.UNSUPPORTED, limit.feature, evidence = reason) else StepOutcome(StepKind.FAIL, reason)
    }

    // --- the reader's steps ---------------------------------------------------------------------------------------

    private fun readFile(service: PassportService, fid: Short): ByteArray =
        service.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()

    private fun checkDg1(service: PassportService, data: PassportData) {
        val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo
        check(data.toMrzData() == mrzInfo.toString().replace("\n", "")) { "DG1 does not hold the document's MRZ" }
    }

    /** EF.CardAccess's PACEInfos in the file's order, which JMRTD's CardAccessFile, holding them in a set, loses. */
    private fun readPaceInfos(service: PassportService): List<PACEInfo> {
        val securityInfos = ASN1Set.getInstance(readFile(service, PassportService.EF_CARD_ACCESS))
        return securityInfos.map { ASN1Sequence.getInstance(it) }
            .filter { ASN1ObjectIdentifier.getInstance(it.getObjectAt(0)).id.startsWith(SecurityInfo.ID_PACE) }
            .map {
                PACEInfo(
                    ASN1ObjectIdentifier.getInstance(it.getObjectAt(0)).id,
                    ASN1Integer.getInstance(it.getObjectAt(1)).intValueExact(),
                    ASN1Integer.getInstance(it.getObjectAt(2)).value
                )
            }
    }

    /**
     * Runs [block] with Bouncy Castle as the first JCE provider: in Integrated Mapping's pseudo-random function JMRTD
     * names its keys' algorithm with the whole transformation, which the JVM's own provider refuses.
     */
    private fun <T> withBouncyCastleFirst(block: () -> T): T {
        val registered = Security.getProvider("BC")
        val position = Security.getProviders().indexOf(registered) + 1
        Security.removeProvider("BC")
        Security.insertProviderAt(registered ?: BouncyCastleProvider(), 1)
        try {
            return block()
        } finally {
            Security.removeProvider("BC")
            if (registered != null) Security.insertProviderAt(registered, position)
        }
    }

    /** The test CSCA that issued the profile's Document Signer, found by the country suffix of its name. */
    private fun cscaFor(profile: ChipProfile): X509Certificate {
        val suffix = profile.sod.passportSigner.removePrefix("smartemu-test-ds").substringBefore("-rsa").substringBefore("-p384")
        val country = if (suffix.length > "-id".length) suffix.removeSuffix("-id") else suffix
        return File("../test-pki/csca/smartemu-test-csca$country.cert.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    private fun passiveAuthentication(profile: ChipProfile, service: PassportService, dataGroups: List<Int>) {
        val sod = SODFile(ByteArrayInputStream(readFile(service, PassportService.EF_SOD)))
        val signer = sod.docSigningCertificate
        signer.verify(cscaFor(profile).publicKey, Util.getBouncyCastleProvider())
        val encoded = sod.encoded
        val first = encoded[1].toInt() and 0xFF
        val headerLength = if (first < 0x80) 2 else 2 + (first and 0x7F)
        val signedData = CMSSignedData(encoded.copyOfRange(headerLength, encoded.size))
        val verifier = JcaSimpleSignerInfoVerifierBuilder().setProvider(Util.getBouncyCastleProvider()).build(signer)
        check(signedData.signerInfos.signers.single().verify(verifier)) { "The SOD signature does not verify" }
        check(sod.dataGroupHashes.keys == dataGroups.toSet()) { "The SOD hashes data groups ${sod.dataGroupHashes.keys.sorted()}, not ${dataGroups.sorted()}" }
        // DG3 is behind Terminal Authentication; every other group is read and checked against its hash
        for (number in dataGroups.filter { it != 3 }) {
            val content = readFile(service, (PassportService.EF_DG1 + number - 1).toShort())
            check(MessageDigest.getInstance(sod.digestAlgorithm).digest(content).contentEquals(sod.dataGroupHashes[number])) { "The hash of DG$number does not match" }
        }
    }

    private fun chipAuthentication(service: PassportService) {
        val dg14 = DG14File(ByteArrayInputStream(readFile(service, PassportService.EF_DG14)))
        val caInfo = dg14.securityInfos.filterIsInstance<ChipAuthenticationInfo>().first()
        val keyInfo = dg14.securityInfos.filterIsInstance<ChipAuthenticationPublicKeyInfo>().first()
        val result = service.doEACCA(keyInfo.keyId, caInfo.objectIdentifier, keyInfo.objectIdentifier, keyInfo.subjectPublicKey)
        checkNotNull(result.wrapper) { "Chip Authentication left no secure messaging" }
        // The session restarted under the new keys: the data stays readable
        readFile(service, PassportService.EF_DG1)
    }

    private fun activeAuthentication(service: PassportService, spec: KeySpec) {
        val publicKey = DG15File(ByteArrayInputStream(readFile(service, PassportService.EF_DG15))).publicKey
        val challenge = ByteArray(8) { it.toByte() }
        when (spec) {
            is KeySpec.Ec -> {
                val response = service.doAA(publicKey, spec.curve.digestAlgorithm, "SHA256withECDSA", challenge).response
                check(verifiesPlain(publicKey, spec.curve, challenge, response)) { "The ECDSA Active Authentication signature does not verify" }
            }
            is KeySpec.Rsa -> {
                val response = service.doAA(publicKey, "SHA-1", "SHA1WithRSA/ISO9796-2", challenge).response
                check(verifiesIso9796(publicKey, challenge, response)) { "The RSA Active Authentication signature does not verify" }
            }
        }
    }

    private fun verifiesPlain(publicKey: PublicKey, curve: EcCurve, challenge: ByteArray, signature: ByteArray): Boolean {
        val half = signature.size / 2
        check(half == curve.fieldSize) { "The signature is $half+$half bytes, not ${curve.fieldSize}+${curve.fieldSize}" }
        val der = DERSequence(
            arrayOf(ASN1Integer(BigInteger(1, signature.copyOfRange(0, half))), ASN1Integer(BigInteger(1, signature.copyOfRange(half, signature.size))))
        ).encoded
        return Signature.getInstance("${curve.digestAlgorithm.replace("-", "")}withECDSA", Util.getBouncyCastleProvider()).run {
            initVerify(publicKey)
            update(challenge)
            verify(der)
        }
    }

    /** Verifies an ISO 9796-2 scheme 1 signature with SHA-1, as JMRTD-based readers verify RSA AA. */
    private fun verifiesIso9796(publicKey: PublicKey, challenge: ByteArray, signature: ByteArray): Boolean {
        val recovered = Cipher.getInstance("RSA/NONE/NoPadding", Util.getBouncyCastleProvider()).run {
            init(Cipher.DECRYPT_MODE, publicKey)
            doFinal(signature)
        }
        val plaintext = if ((recovered.last().toInt() and 0xF) == 0xC) {
            recovered
        } else {
            val modulus = (publicKey as RSAPublicKey).modulus
            val flipped = modulus.subtract(BigInteger(1, recovered)).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            ByteArray(recovered.size - flipped.size) + flipped
        }
        val m1 = Util.recoverMessage(20, plaintext)
        return Signature.getInstance("SHA1WithRSA/ISO9796-2", Util.getBouncyCastleProvider()).run {
            initVerify(publicKey)
            update(m1 + challenge)
            verify(signature)
        }
    }

    companion object {
        /** As in "PACE-CAM ECDH brainpoolP256r1 AES-128": the mapping, key agreement, domain parameters and cipher of a PACEInfo. */
        fun suiteLabel(info: PACEInfo): String {
            val mapping = PACEInfo.toMappingType(info.objectIdentifier).name
            val agreement = PACEInfo.toKeyAgreementAlgorithm(info.objectIdentifier)
            val id = info.parameterId.toInt()
            val domain = if (agreement == "DH") {
                DhGroup.entries.firstOrNull { it.paceParameterId == id }?.displayName ?: "DH parameter $id"
            } else {
                EcCurve.entries.firstOrNull { it.paceParameterId == id }?.displayName ?: "curve parameter $id"
            }
            val cipher = if (PACEInfo.toCipherAlgorithm(info.objectIdentifier) == "AES") "AES-${PACEInfo.toKeyLength(info.objectIdentifier)}" else "3DES"
            return "PACE-$mapping $agreement $domain $cipher"
        }

        /** The exception as a reason: its class, its message and a status word the chip answered with, with nothing variable. */
        fun describe(e: Throwable): String {
            val root = generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }.last()
            val sw = (e as? CardServiceException)?.sw?.takeIf { it > 0 }?.let { " [SW=%04X]".format(it) }.orEmpty()
            val message = (e.message ?: root.message)?.lineSequence()?.firstOrNull()?.take(140)?.replace(Regex("\\s+"), " ").orEmpty()
            val cause = if (root !== e) " <- ${root.javaClass.simpleName}" + (root.message?.lineSequence()?.firstOrNull()?.take(100)?.let { ": $it" } ?: "") else ""
            return "${e.javaClass.simpleName}${if (message.isNotEmpty()) ": $message" else ""}$sw$cause"
        }
    }
}
