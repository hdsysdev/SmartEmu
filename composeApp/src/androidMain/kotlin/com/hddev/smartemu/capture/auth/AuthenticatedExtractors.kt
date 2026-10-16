package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.AccessAttempt
import com.hddev.smartemu.capture.ActiveAuthenticationKeyObservation
import com.hddev.smartemu.capture.CvcaObservation
import com.hddev.smartemu.capture.SodObservation
import com.hddev.smartemu.capture.AccessCredentials
import com.hddev.smartemu.capture.AccessMethod
import com.hddev.smartemu.capture.AccessStop
import com.hddev.smartemu.capture.AuthenticatedObservation
import com.hddev.smartemu.capture.AuthenticationResult
import com.hddev.smartemu.capture.BlockedStatusException
import com.hddev.smartemu.capture.CaptureBuilder
import com.hddev.smartemu.capture.CaptureExtractor
import com.hddev.smartemu.capture.CaptureSession
import com.hddev.smartemu.capture.ComObservation
import com.hddev.smartemu.capture.PaceMappingType
import com.hddev.smartemu.capture.Redaction
import com.hddev.smartemu.capture.SecurityInfosObservation
import com.hddev.smartemu.capture.StatusClass
import com.hddev.smartemu.capture.TagLostException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.AccessKeySpec
import org.jmrtd.BACKey
import org.jmrtd.DefaultFileSystem
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.protocol.PACECAMResult
import org.jmrtd.protocol.PACEGMMappingResult
import org.jmrtd.protocol.PACEResult
import org.jmrtd.protocol.ReadBinaryAPDUSender
import net.sf.scuba.smartcards.CardFileInputStream
import java.math.BigInteger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import kotlin.time.Clock

/**
 * The extractors of an authenticated capture: one that opens the chip with [credentials] and reads what a capture may
 * keep. It is separate from [com.hddev.smartemu.capture.defaultExtractors], which stay unauthenticated; an
 * authenticated capture runs those first (see [AuthenticatedCapture]), then this.
 *
 * [confirmCanAttempt] is asked once, before the only attempt a CAN gets; [onStep] hears each stage's name as it begins.
 * If an earlier attempt with this CAN was begun and cut short ([canAttemptInterrupted]: the draft remembers it), the CAN is
 * not tried again on the strength of [confirmCanAttempt]: [confirmInterruptedCanAttempt] is asked instead, and its default
 * says no.
 */
fun authenticatedExtractors(
    credentials: AccessCredentials,
    confirmCanAttempt: () -> Boolean = { false },
    onStep: (String) -> Unit = {},
    canAttemptInterrupted: Boolean = false,
    confirmInterruptedCanAttempt: () -> Boolean = { false }
): List<CaptureExtractor> = listOf(
    AuthenticatedReadExtractor(credentials, confirmCanAttempt, onStep, canAttemptInterrupted, confirmInterruptedCanAttempt)
)

/** What an attempt with a suite or BAC came to; a [NOT_SENT] attempt sent no command, so it is no failure and costs nothing. */
private enum class Attempt { OPENED, FAILED, NOT_SENT }

private class CardSecurityRead(val bytes: ByteArray, val infos: List<OrderedSecurityInfo>)

/** Thrown out of the authenticated read when the person declines the CAN attempt; nothing was sent. */
class CanAttemptDeclinedException : Exception("The CAN attempt was declined")

/** The stages of the authenticated read, in order; [onStep] names them. */
object AuthenticatedSteps {
    const val CARD_ACCESS = "authenticated/ef-card-access"
    const val ACCESS = "authenticated/access"
    const val EF_COM = "authenticated/ef-com"
    const val EF_SOD = "authenticated/ef-sod"
    const val DATA_GROUPS = "authenticated/data-groups"
    const val DG14 = "authenticated/dg14"
    const val DG15 = "authenticated/dg15"
    const val CVCA = "authenticated/ef-cvca"
    const val ACTIVE_AUTHENTICATION = "authenticated/active-authentication"
    const val CHIP_AUTHENTICATION = "authenticated/chip-authentication"

    val all = listOf(CARD_ACCESS, ACCESS, EF_COM, EF_SOD, DATA_GROUPS, DG14, DG15, CVCA, ACTIVE_AUTHENTICATION, CHIP_AUTHENTICATION)
}

/**
 * Opens the chip the way a JMRTD reader does and records what it shows.
 *
 * Access. Every PACE suite in EF.CardAccess is tried in the chip's order with the key the credentials give, each at most
 * once, and then, for an MRZ, BAC once. The attempts stop at the first failure that may count against the document:
 * - an MRZ stops at ANY failure once authentication began (a GENERAL AUTHENTICATE or EXTERNAL AUTHENTICATE was sent):
 *   6300, 63Cx, 6982, 6985, 6988, 6A80, no answer, anything. Only a plain "not supported" (6A80, 6D00 and the like) at
 *   MSE:Set AT, before any key-dependent command, carries on to the next suite;
 * - a CAN gets ONE failed attempt: it stops at the first failure of any kind, never retries, and is not tried before
 *   [confirmCanAttempt] says yes. The attempt is noted in the draft before PACE starts ([CaptureBuilder.markCanAttemptStarted]),
 *   so after a lost tag it is not made again without [confirmInterruptedCanAttempt] saying yes.
 * Credentials are never retried with another key. A suite that is skipped without a command being sent (parameters this
 * reader can't build, or PACE-IM where the registered "BC" provider is the platform's and a CAN is used) is recorded
 * as not attempted (no status word) and doesn't count as a failure.
 *
 * Reading. The first attempt that opens the chip is read in full, at once: a later attempt ends its session. Then the
 * remaining suites are tried for the record. See [ChipFiles] for what is read of each file, and [AuthenticatedObservation]
 * for what is kept.
 */
class AuthenticatedReadExtractor(
    private val credentials: AccessCredentials,
    private val confirmCanAttempt: () -> Boolean,
    private val onStep: (String) -> Unit = {},
    private val canAttemptInterrupted: Boolean = false,
    private val confirmInterruptedCanAttempt: () -> Boolean = { false },
    private val random: SecureRandom = SecureRandom(),
    private val today: () -> LocalDate = { Clock.System.now().toLocalDateTime(TimeZone.UTC).date }
) : CaptureExtractor {
    override val id = "authenticated-read"

    override fun isComplete(out: CaptureBuilder): Boolean = out.hasAuthenticated()

    override fun extract(session: CaptureSession, out: CaptureBuilder) {
        require(credentials.validate(today()).isEmpty()) { "The credentials are not valid" }
        val run = Run(session, out)
        try {
            run.go()
        } catch (e: BlockedStatusException) {
            // The attempts so far are worth keeping: a resume doesn't go back to a blocked document
            out.setAuthenticated(run.observation(AccessStop.BLOCKED))
            throw e
        } catch (e: TagLostException) {
            // Nothing is kept, so that a resumed run reads again from the start
            throw e
        } finally {
            // Zeroes the last cryptogram and its answer that the card service held. The Strings made of the typed key
            // ([AccessCredentials.Mrz.documentNumber] and the like, which JMRTD's key classes take) can't be erased and are
            // left to the garbage collector: a known limit
            run.card.close()
        }
        out.setAuthenticated(run.observation(run.stopped))
        session.blockedStatusWord?.let { throw BlockedStatusException(it) }
    }

    private inner class Run(val session: CaptureSession, val out: CaptureBuilder) {
        val card = TransportCardService(session)

        /** Whether the last failed attempt got as far as a command that depends on the key (GENERAL AUTHENTICATE, EXTERNAL AUTHENTICATE). */
        var lastFailureAfterKeyStep = false
        var stopped: AccessStop? = null
        var camOffered = false
        val attempts = ArrayList<AccessAttempt>()
        var readUnder: Int? = null
        var com: ComObservation? = null
        var comGroups: Set<Int> = emptySet()
        val dataGroups = ArrayList<com.hddev.smartemu.capture.DataGroupObservation>()
        var sod: SodObservation? = null
        var sodGroups: Set<Int> = emptySet()
        var dg14: SecurityInfosObservation? = null
        var dg15: ActiveAuthenticationKeyObservation? = null
        var cardSecurity: SecurityInfosObservation? = null
        var cvca: CvcaObservation? = null
        var activeAuthentication: AuthenticationResult? = null
        var chipAuthentication: AuthenticationResult? = null

        fun observation(stop: AccessStop?) = AuthenticatedObservation(
            credential = credentials.kind,
            access = attempts.toList(),
            accessStopped = stop ?: stopped,
            readUnder = readUnder,
            com = com,
            dataGroups = dataGroups.sortedBy { it.number },
            sod = sod,
            dg14 = dg14,
            dg15 = dg15,
            cardSecurity = cardSecurity,
            cvca = cvca,
            activeAuthentication = activeAuthentication,
            chipAuthentication = chipAuthentication
        )

        private fun newService(): PassportService =
            PassportService(card, PassportService.NORMAL_MAX_TRANCEIVE_LENGTH, PassportService.DEFAULT_MAX_BLOCKSIZE, false, true).also { it.open() }

        fun go() {
            onStep(AuthenticatedSteps.CARD_ACCESS)
            val suites = SecurityInfoReader.paceSuites(readCardAccess())
            onStep(AuthenticatedSteps.ACCESS)
            camOffered = suites.any { PACEInfo.toMappingType(it.info.objectIdentifier) == PACEInfo.MappingType.CAM }
            when (credentials) {
                is AccessCredentials.Can -> openWithCan(suites)
                is AccessCredentials.Mrz -> openWithMrz(suites)
            }
        }

        private fun readCardAccess(): List<OrderedSecurityInfo> {
            val known = out.cardAccessContents()
            val bytes = known ?: run {
                session.selectMasterFile()
                guarded { newService().let { ChipFiles(it, card).readWhole(PassportService.EF_CARD_ACCESS, 2048).bytes } }
            }
            return bytes?.let { try { SecurityInfoReader.fromCardAccess(it) } catch (e: Exception) { emptyList() } } ?: emptyList()
        }

        private fun openWithCan(suites: List<PaceSuiteInfo>) {
            val can = credentials as AccessCredentials.Can
            if (suites.isEmpty()) {
                stopped = AccessStop.CAN_NEEDS_PACE
                return
            }
            val confirmed = if (canAttemptInterrupted) confirmInterruptedCanAttempt() else confirmCanAttempt()
            if (!confirmed) throw CanAttemptDeclinedException()
            // Before the first command: a chip may count the attempt even if the tag goes before it answers
            out.markCanAttemptStarted()
            for (suite in suites) {
                when (attemptPace(suite) { PACEKeySpec.createCANKey(can.can()) }) {
                    Attempt.OPENED, Attempt.NOT_SENT -> Unit
                    // One failed attempt ends it, whatever the failure was
                    Attempt.FAILED -> {
                        stopped = AccessStop.CAN_SINGLE_ATTEMPT_FAILED
                        return
                    }
                }
            }
        }

        private fun openWithMrz(suites: List<PaceSuiteInfo>) {
            val mrz = credentials as AccessCredentials.Mrz
            for (suite in suites) {
                val outcome = attemptPace(suite) { BACKey(mrz.documentNumber(), mrz.dateOfBirth(), mrz.dateOfExpiry()) }
                if (outcome == Attempt.FAILED) {
                    val failure = attempts.last().statusClass
                    if (endsAttempts(failure)) {
                        stopped = stopFor(failure)
                        return
                    }
                }
            }
            if (attemptBac { BACKey(mrz.documentNumber(), mrz.dateOfBirth(), mrz.dateOfExpiry()) } == Attempt.FAILED) {
                val failure = attempts.last().statusClass
                if (endsAttempts(failure)) stopped = stopFor(failure)
            }
        }

        /**
         * Whether a failed attempt ends the authentication. After a key-dependent command was sent every failure does;
         * before one (at MSE:Set AT, or GET CHALLENGE for BAC) only a plain "not supported" lets the next suite be tried.
         */
        private fun endsAttempts(failure: StatusClass): Boolean =
            if (lastFailureAfterKeyStep) failure.stopsFurtherAttempts else !failure.allowsNextSuiteBeforeKeyIsSent

        private fun stopFor(failure: StatusClass): AccessStop = when (failure) {
            StatusClass.BLOCKED -> AccessStop.BLOCKED
            StatusClass.CONDITIONS_NOT_SATISFIED -> AccessStop.CONDITIONS_NOT_SATISFIED
            else -> AccessStop.KEY_REJECTED
        }

        /** Runs [block] and puts a lost tag or blocked status word back after JMRTD has wrapped it in its own exception. */
        private inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: Exception) {
            card.rethrowPendingStop()
            session.blockedStatusWord?.let { throw BlockedStatusException(it) }
            throw e
        }

        /** One PACE attempt with the suite; reads the chip if it is the first to open it. */
        private fun attemptPace(suite: PaceSuiteInfo, key: () -> AccessKeySpec): Attempt {
            val oid = suite.info.objectIdentifier
            val mapping = mappingOf(oid)
            val cipher = cipherOf(oid)
            val parameterId = suite.parameterId
            fun record(succeeded: Boolean, statusWord: Int?, camVerified: Boolean? = null) {
                attempts += AccessAttempt(
                    AccessMethod.PACE, oid, mapping, cipher, parameterId?.takeIf { it.bitLength() < 16 }?.toInt(),
                    succeeded, statusWord, if (succeeded) StatusClass.OK else StatusClass.of(statusWord), camVerified
                )
            }
            // A suite without a parameter identifier, or with parameters this reader can't build, is not attempted
            if (parameterId == null) {
                record(false, null)
                return Attempt.NOT_SENT
            }
            val spec = try {
                suite.domainParameters?.parameters ?: PACEInfo.toParameterSpec(parameterId)
            } catch (e: RuntimeException) {
                record(false, null)
                return Attempt.NOT_SENT
            }
            // IM skipped: provider. Integrated Mapping needs the app's Bouncy Castle ahead of the JVM's; where the registered
            // "BC" is the platform's (Android's stripped copy) that is a swap of a global provider, and a CAN gets one try,
            // which a reader-side failure of the mapping would waste
            if (mapping == PaceMappingType.IM && credentials is AccessCredentials.Can && !BouncyCastleProviders.isAppProviderOrAbsent(Security.getProvider("BC"))) {
                record(false, null)
                return Attempt.NOT_SENT
            }
            session.selectMasterFile()
            val service = newService()
            val firstExchange = session.exchangeCount
            val result: PACEResult = try {
                val paceKey = key()
                if (mapping == PaceMappingType.IM) BouncyCastleProviders.withAppProviderFirst { service.doPACE(paceKey, oid, spec, parameterId) }
                else service.doPACE(paceKey, oid, spec, parameterId)
            } catch (e: Exception) {
                card.rethrowPendingStop()
                val statusWord = ChipFiles.statusWordOf(e)
                lastFailureAfterKeyStep = keyStepSent(firstExchange)
                session.blockedStatusWord?.let {
                    record(false, it)
                    throw BlockedStatusException(it)
                }
                record(false, statusWord)
                return Attempt.FAILED
            }
            val firstToOpen = readUnder == null
            // EF.CardSecurity is read only where the chip says it has one: a suite with Chip Authentication Mapping is offered.
            // Asking for a file a chip lacks is routine, but under secure messaging it is a command to get wrong for nothing
            val security = if ((firstToOpen && camOffered) || mapping == PaceMappingType.CAM) readCardSecurity(result) else null
            val camVerified = if (result is PACECAMResult) verifyCam(result, parameterId, security) else null
            val index = attempts.size
            record(true, null, camVerified)
            if (firstToOpen) {
                readUnder = index
                cardSecurity = security?.let { describeSecurity(it.bytes, it.infos) }
                security?.bytes?.fill(0)
                readFiles(service, afterPace = true)
            } else {
                security?.bytes?.fill(0)
            }
            return Attempt.OPENED
        }

        /** Whether a GENERAL AUTHENTICATE or EXTERNAL AUTHENTICATE was sent since the first [fromExchange] exchanges. */
        private fun keyStepSent(fromExchange: Int): Boolean =
            session.sentSince(fromExchange, INS_GENERAL_AUTHENTICATE) || session.sentSince(fromExchange, INS_EXTERNAL_AUTHENTICATE)

        private fun attemptBac(key: () -> BACKey): Attempt {
            session.selectMasterFile()
            val service = newService()
            val firstExchange = session.exchangeCount
            fun record(succeeded: Boolean, statusWord: Int?) {
                attempts += AccessAttempt(
                    AccessMethod.BAC, succeeded = succeeded, statusWord = statusWord,
                    statusClass = if (succeeded) StatusClass.OK else StatusClass.of(statusWord)
                )
            }
            try {
                service.sendSelectApplet(false)
                service.doBAC(key())
            } catch (e: Exception) {
                card.rethrowPendingStop()
                lastFailureAfterKeyStep = keyStepSent(firstExchange)
                session.blockedStatusWord?.let {
                    record(false, it)
                    throw BlockedStatusException(it)
                }
                record(false, ChipFiles.statusWordOf(e))
                return Attempt.FAILED
            }
            val firstToOpen = readUnder == null
            val index = attempts.size
            record(true, null)
            if (firstToOpen) {
                readUnder = index
                readFiles(service, afterPace = false)
            }
            return Attempt.OPENED
        }

        /**
         * EF.CardSecurity from the master file under the PACE session, as ICAO 9303 part 11 has a reader do before it
         * selects the application. PassportService reads the master file in plain, which would end the session, so this
         * uses JMRTD's file system with the PACE wrapper. Null if the chip has none.
         */
        private fun readCardSecurity(result: PACEResult): CardSecurityRead? = try {
            val fileSystem = DefaultFileSystem(ReadBinaryAPDUSender(card), false)
            fileSystem.setWrapper(result.wrapper)
            fileSystem.selectFile(PassportService.EF_CARD_SECURITY)
            val bytes = CardFileInputStream(PassportService.DEFAULT_MAX_BLOCKSIZE, fileSystem).readBytes()
            CardSecurityRead(bytes, SecurityInfoReader.fromCardSecurity(bytes))
        } catch (e: Exception) {
            card.rethrowPendingStop()
            session.blockedStatusWord?.let { throw BlockedStatusException(it) }
            null
        }

        private fun describeSecurity(bytes: ByteArray, infos: List<OrderedSecurityInfo>) = SecurityInfosObservation(
            size = Redaction.sizeRange(bytes.size),
            infos = infos.map(SecurityInfoReader::describe),
            paceCamPresent = SecurityInfoReader.paceCamPresent(infos)
        )

        /**
         * ICAO 9303 part 11, 4.4.3.5: the chip's public key from EF.CardSecurity, multiplied by the chip authentication
         * data PACE-CAM returned, must be the chip's mapping public key. Null if it can't be checked.
         */
        private fun verifyCam(result: PACECAMResult, parameterId: BigInteger, security: CardSecurityRead?): Boolean? {
            val keys = security?.infos?.mapNotNull { it.info as? ChipAuthenticationPublicKeyInfo } ?: return null
            val chipKey = (keys.singleOrNull { it.keyId == parameterId } ?: keys.singleOrNull())?.subjectPublicKey as? ECPublicKey ?: return null
            return try {
                val mappingKey = (result.mappingResult as PACEGMMappingResult).piccMappingPublicKey as ECPublicKey
                val parameters = PACEInfo.toParameterSpec(parameterId) as ECParameterSpec
                val expected = Util.toBouncyCastleECPoint(chipKey.w, parameters)
                    .multiply(Util.os2i(result.chipAuthenticationData)).normalize()
                Util.toBouncyCastleECPoint(mappingKey.w, parameters).normalize() == expected
            } catch (e: Exception) {
                false
            }
        }

        // ------------------------------------------------------------------------------------------------------
        // Reading, in the session the first successful attempt opened

        private fun readFiles(service: PassportService, afterPace: Boolean) {
            val files = ChipFiles(service, card)
            if (afterPace) {
                try {
                    service.sendSelectApplet(true)
                } catch (e: Exception) {
                    card.rethrowPendingStop()
                    session.blockedStatusWord?.let { throw BlockedStatusException(it) }
                    // The session didn't carry into the application; nothing more can be read under it
                    return
                }
            }
            onStep(AuthenticatedSteps.EF_COM)
            files.readWhole(PassportService.EF_COM, 512).let { file ->
                file.bytes?.let { bytes ->
                    try {
                        val parsed = ChipFileParsers.com(bytes)
                        com = parsed.observation
                        comGroups = parsed.dataGroups
                    } catch (e: Exception) {
                        // Not a COM file: left out
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
            onStep(AuthenticatedSteps.EF_SOD)
            files.readWhole(PassportService.EF_SOD, 65536).let { file ->
                file.bytes?.let { bytes ->
                    try {
                        val parsed = ChipFileParsers.sod(bytes)
                        sod = parsed.observation
                        sodGroups = parsed.hashedDataGroups
                    } catch (e: Exception) {
                        // Not a parsable EF.SOD: left out
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
            onStep(AuthenticatedSteps.DATA_GROUPS)
            for (number in DataGroups.numbers) {
                dataGroups += dataGroupObservation(number, number in comGroups, number in sodGroups, files.header(number))
            }
            val offered = dataGroups.filter { it.selectStatusWord == ChipFiles.SW_OK && it.readStatusWord == ChipFiles.SW_OK }.map { it.number }.toSet()

            onStep(AuthenticatedSteps.DG14)
            var caInfos = emptyList<ChipAuthenticationInfo>()
            var caKeys = emptyList<ChipAuthenticationPublicKeyInfo>()
            var aaInfo: ActiveAuthenticationInfo? = null
            if (14 in offered) {
                files.readWhole(DataGroups.fileId(14), 16384).bytes?.let { bytes ->
                    try {
                        val infos = SecurityInfoReader.fromDg14(bytes)
                        dg14 = SecurityInfosObservation(
                            Redaction.sizeRange(bytes.size), infos.map(SecurityInfoReader::describe), SecurityInfoReader.paceCamPresent(infos)
                        )
                        caInfos = infos.mapNotNull { it.info as? ChipAuthenticationInfo }
                        caKeys = infos.mapNotNull { it.info as? ChipAuthenticationPublicKeyInfo }
                        aaInfo = infos.firstNotNullOfOrNull { it.info as? ActiveAuthenticationInfo }
                    } catch (e: Exception) {
                        // Left out
                    } finally {
                        bytes.fill(0)
                    }
                }
            }

            onStep(AuthenticatedSteps.DG15)
            var aaKey: java.security.PublicKey? = null
            if (15 in offered) {
                files.readWhole(DataGroups.fileId(15), 4096).bytes?.let { bytes ->
                    try {
                        val parsed = ChipFileParsers.activeAuthenticationKey(bytes)
                        dg15 = parsed.first
                        aaKey = parsed.second
                    } catch (e: Exception) {
                        // Left out
                    } finally {
                        bytes.fill(0)
                    }
                }
            }

            onStep(AuthenticatedSteps.CVCA)
            files.readWhole(PassportService.EF_CVCA, 256).let { file ->
                file.bytes?.let { bytes ->
                    try {
                        cvca = ChipFileParsers.cvca(bytes)
                    } catch (e: Exception) {
                        // Left out
                    } finally {
                        bytes.fill(0)
                    }
                }
            }

            onStep(AuthenticatedSteps.ACTIVE_AUTHENTICATION)
            aaKey?.let { activeAuthentication = activeAuthentication(service, it, aaInfo?.signatureAlgorithmOID) }

            // Last, because it starts new secure messaging keys and the reads above were under the old ones
            onStep(AuthenticatedSteps.CHIP_AUTHENTICATION)
            chipAuthentication = chipAuthentication(service, files, caInfos, caKeys)
        }

        private fun activeAuthentication(service: PassportService, key: java.security.PublicKey, signatureOid: String?): AuthenticationResult {
            val challenge = ByteArray(8).also(random::nextBytes)
            return try {
                val response = service.doAA(key, null, null, challenge).response
                val algorithm = ActiveAuthenticationVerifier.verify(key, challenge, response, signatureOid)
                response.fill(0)
                AuthenticationResult(succeeded = algorithm != null, algorithm = algorithm)
            } catch (e: Exception) {
                card.rethrowPendingStop()
                session.blockedStatusWord?.let { throw BlockedStatusException(it) }
                AuthenticationResult(succeeded = false, statusWord = ChipFiles.statusWordOf(e))
            }
        }

        private fun chipAuthentication(
            service: PassportService,
            files: ChipFiles,
            infos: List<ChipAuthenticationInfo>,
            keys: List<ChipAuthenticationPublicKeyInfo>
        ): AuthenticationResult? {
            val info = infos.firstOrNull() ?: return null
            val keyInfo = keys.firstOrNull { it.keyId == info.keyId } ?: keys.firstOrNull() ?: return null
            val algorithm = caAlgorithm(info.objectIdentifier)
            return try {
                service.doEACCA(info.keyId ?: keyInfo.keyId, info.objectIdentifier, keyInfo.objectIdentifier, keyInfo.subjectPublicKey)
                // The new keys must work: a protected read of EF.COM's header is the proof
                val header = files.header(PassportService.EF_COM)
                AuthenticationResult(succeeded = header.readStatusWord == ChipFiles.SW_OK, algorithm = algorithm, statusWord = header.readStatusWord.takeIf { it != ChipFiles.SW_OK })
            } catch (e: Exception) {
                card.rethrowPendingStop()
                session.blockedStatusWord?.let { throw BlockedStatusException(it) }
                AuthenticationResult(succeeded = false, algorithm = algorithm, statusWord = ChipFiles.statusWordOf(e))
            }
        }
    }

    private fun caAlgorithm(oid: String): String? = try {
        val agreement = ChipAuthenticationInfo.toKeyAgreementAlgorithm(oid)
        val cipher = when (ChipAuthenticationInfo.toCipherAlgorithm(oid)) {
            "AES" -> "AES-${ChipAuthenticationInfo.toKeyLength(oid)}"
            else -> "3DES"
        }
        "$agreement-$cipher"
    } catch (e: RuntimeException) {
        null
    }

    private fun mappingOf(oid: String): PaceMappingType = when (PACEInfo.toMappingType(oid)) {
        PACEInfo.MappingType.GM -> PaceMappingType.GM
        PACEInfo.MappingType.IM -> PaceMappingType.IM
        PACEInfo.MappingType.CAM -> PaceMappingType.CAM
    }

    private fun cipherOf(oid: String): String = when (PACEInfo.toCipherAlgorithm(oid)) {
        "AES" -> "AES-${PACEInfo.toKeyLength(oid)}"
        else -> "3DES"
    }

    private companion object {
        const val INS_GENERAL_AUTHENTICATE = 0x86
        const val INS_EXTERNAL_AUTHENTICATE = 0x82
    }
}

/**
 * The JCE provider named "BC", for Integrated Mapping. In its pseudo-random function JMRTD's reader names its keys'
 * algorithm with the whole transformation, "AES/CBC/NoPadding", which the JVM's own provider refuses, and JMRTD asks the
 * JCE by name, so the app's Bouncy Castle has to be found first.
 *
 * On Android a "BC" is already registered: the platform's stripped copy (`com.android.org.bouncycastle`), not the app's
 * bcprov. Putting that one first, as the code once did, promotes a provider the app doesn't control. So [withAppProviderFirst]
 * puts the app's own instance (JMRTD's [Util.getBouncyCastleProvider]) first for the one call, whatever "BC" is
 * registered, and restores the registered provider at its place in a `finally`. The swap is of a process-wide registry for
 * the length of one PACE attempt: a JCE lookup on another thread meanwhile sees the app's provider first, a known limit.
 */
internal object BouncyCastleProviders {
    /** True if [registered] is the app's Bouncy Castle, or there is none (so the swap replaces nothing of the platform's). */
    fun isAppProviderOrAbsent(registered: Provider?): Boolean = registered == null || registered is BouncyCastleProvider

    fun <T> withAppProviderFirst(block: () -> T): T {
        val registered = Security.getProvider("BC")
        val position = if (registered != null) Security.getProviders().indexOf(registered) + 1 else 0
        val ours: Provider = if (registered != null && isAppProviderOrAbsent(registered)) registered else Util.getBouncyCastleProvider()
        Security.removeProvider("BC")
        Security.insertProviderAt(ours, 1)
        try {
            return block()
        } finally {
            Security.removeProvider("BC")
            if (registered != null) Security.insertProviderAt(registered, position)
        }
    }
}
