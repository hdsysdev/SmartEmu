package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.capture.ApduTransport
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.EmulatorTransport
import com.hddev.smartemu.capture.Hex
import com.hddev.smartemu.capture.LinkObservation
import com.hddev.smartemu.capture.NfcTechnology
import com.hddev.smartemu.capture.ReaderDevice
import com.hddev.smartemu.capture.testReader
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import kotlin.test.assertIs

/**
 * The emulator, configured from a measured profile as capture mode's replay does it: the profile is installed as a
 * chip profile and the document is set to it. [close] puts the shared state back.
 *
 * It replays every checkable field of [measured] ([MeasuredProfile.forEvaluation]), as the fidelity diff and the hold-out
 * need: the diff asks whether the replay can reproduce what the profile says. The emulator in the app replays only the
 * fields that earned MEASURED; use [MeasuredChipProfile.from] with the profile as it is for that.
 */
class MeasuredEmulator(val measured: MeasuredProfile, base: ChipProfile = ChipProfiles.default) : AutoCloseable {
    val service = PassportHceService().also { it.onCreate() }
    val profile: ChipProfile = MeasuredChipProfile.from(measured.forEvaluation(), base, id = "measured-test-${measured.id}")

    init {
        MeasuredProfileRegistry.install(listOf(profile))
        PassportHceService.setSharedPassportData(sampleDocument.withChipProfile(profile))
    }

    fun transport(device: ReaderDevice = testReader): ApduTransport = EmulatorTransport(service, device)

    override fun close() {
        PassportHceService.setSharedPassportData(null)
        service.onDestroy()
        MeasuredProfileRegistry.clear()
    }

    companion object {
        val sampleDocument = PassportData(
            passportNumber = "SPEC24681",
            dateOfBirth = LocalDate(1986, 3, 14),
            expiryDate = LocalDate(2035, 3, 13),
            issuingCountry = "SWE",
            nationality = "SWE",
            firstName = "Anna Maria",
            lastName = "Eriksson",
            gender = "F"
        )

        /** Runs [block] with an emulator configured from [measured]. */
        fun <T> with(measured: MeasuredProfile, base: ChipProfile = ChipProfiles.default, block: (MeasuredEmulator) -> T): T =
            MeasuredEmulator(measured, base).use(block)
    }
}

/** Captures [chip] with the default extractors and the Tier A pack, which must complete. */
fun captureOf(chip: ApduTransport): CaptureRecord = assertIs<CaptureResult.Complete>(CaptureRunner().run(chip)).record

fun readerNamed(model: String, extended: Boolean = true) = ReaderDevice(
    model = model, androidVersion = "15", controllerMaxTransceiveLength = 65279, controllerExtendedLengthSupported = extended
)

/**
 * A chip that is not the emulator: scripted to answer like a made-up one that differs from the emulator's own
 * behaviour in every way capture mode replays (a long EF.CardAccess, a read limit of 255, B1, partial AID, EF.ATR/INFO,
 * EF.DIR, its own status words). It is a test double, and measures nothing about a real chip.
 */
class FakeChip(
    private val cardAccess: ByteArray? = defaultCardAccess(),
    private val atrInfo: ByteArray? = Hex.decode("7F66080202080002020800"),
    private val dir: ByteArray? = Hex.decode("61094F07A0000002471001"),
    private val device: ReaderDevice = testReader,
    private val maxRead: Int = 255,
    private val supportsOddRead: Boolean = true,
    private val acceptsPartialAid: Boolean = true,
    private val extendedLength: Boolean = true,
    private val outOfSequenceStatusWord: Int = 0x6982,
    /** Answers 9000 to SELECT of 011D from the master file, as a chip with EF.CardSecurity does: the emulator has none. */
    private val hasCardSecurity: Boolean = false
) : ApduTransport {
    private val aid = Hex.decode("A0000002471001")
    private var applicationSelected = false
    private var selected: Int? = null

    override fun linkInfo() = LinkObservation(NfcTechnology.NFC_A, historicalBytes = "8000")

    override fun readerDevice(): ReaderDevice = device

    override fun transmit(command: ByteArray): ByteArray = when (command[1].toInt() and 0xFF) {
        0xA4 -> select(command)
        0xB0 -> read(command)
        0xB1 -> oddRead(command)
        0x84 -> getChallenge(command)
        0x82 -> externalAuthenticate(command)
        else -> sw(0x6D00)
    }

    private fun sw(statusWord: Int, data: ByteArray = byteArrayOf()) =
        data + byteArrayOf((statusWord shr 8).toByte(), statusWord.toByte())

    private fun select(c: ByteArray): ByteArray {
        val p1 = c[2].toInt() and 0xFF
        val p2 = c[3].toInt() and 0xFF
        if (c.size < 5) return sw(0x6700)
        val lc = c[4].toInt() and 0xFF
        if (c.size < 5 + lc) return sw(0x6700)
        val data = c.copyOfRange(5, 5 + lc)
        if (p1 == 0x04) {
            val exact = data.contentEquals(aid)
            if (!exact && !(acceptsPartialAid && data.size in 5 until aid.size && aid.copyOfRange(0, data.size).contentEquals(data))) return sw(0x6A82)
            applicationSelected = true
            selected = null
            return if (p2 and 0x0C == 0x0C) sw(0x9000) else sw(0x9000, byteArrayOf(0x6F, 0x09, 0x84.toByte(), 0x07) + aid)
        }
        if (lc != 2) return sw(0x6700)
        val fid = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val exists = when (fid) {
            0x3F00 -> { applicationSelected = false; selected = null; return sw(0x9000) }
            0x011C -> cardAccess != null
            0x2F01 -> atrInfo != null
            0x2F00 -> dir != null
            0x011D -> applicationSelected || hasCardSecurity
            0x011E, 0x0101 -> applicationSelected
            else -> false
        }
        if (!exists) { selected = null; return sw(0x6A82) }
        selected = fid
        return sw(0x9000)
    }

    private fun fileContents(fid: Int): ByteArray? = when (fid) {
        0x011C -> cardAccess
        0x2F01 -> atrInfo
        0x2F00 -> dir
        else -> null
    }

    private fun read(c: ByteArray): ByteArray {
        val p1 = c[2].toInt() and 0xFF
        val p2 = c[3].toInt() and 0xFF
        val offset: Int
        if (p1 and 0x80 != 0) {
            val fid = when (p1 and 0x1F) { 0x1C -> 0x011C; 0x1E -> 0x011E; else -> return sw(0x6A82) }
            if (fid != 0x011C) return sw(0x6982)
            if (cardAccess == null) return sw(0x6A82)
            selected = fid
            offset = p2
        } else {
            offset = (p1 shl 8) or p2
        }
        val fid = selected ?: return sw(0x6986)
        val file = fileContents(fid) ?: return sw(0x6982)
        val le: Int = when {
            c.size == 5 && c[4].toInt() != 0 -> c[4].toInt() and 0xFF
            c.size == 5 -> 256
            c.size == 7 && c[4].toInt() == 0 -> if (extendedLength) ((c[5].toInt() and 0xFF) shl 8 or (c[6].toInt() and 0xFF)).let { if (it == 0) 65536 else it } else return sw(0x6700)
            else -> return sw(0x6700)
        }
        if (offset >= file.size) return sw(0x6B00)
        return sw(0x9000, file.copyOfRange(offset, offset + minOf(le, maxRead, file.size - offset)))
    }

    private fun oddRead(c: ByteArray): ByteArray {
        if (!supportsOddRead) return sw(0x6D00)
        val fid = selected ?: return sw(0x6986)
        val file = fileContents(fid) ?: return sw(0x6982)
        val lc = c[4].toInt() and 0xFF
        val offsetObject = c.copyOfRange(5, 5 + lc)
        var offset = 0
        for (i in 0 until (offsetObject[1].toInt() and 0xFF)) offset = (offset shl 8) or (offsetObject[2 + i].toInt() and 0xFF)
        val le = c[5 + lc].toInt().and(0xFF).let { if (it == 0) 256 else it }
        if (offset >= file.size) return sw(0x6B00)
        val data = file.copyOfRange(offset, offset + minOf(le, maxRead, file.size - offset))
        return sw(0x9000, byteArrayOf(0x53, data.size.toByte()) + data)
    }

    private fun getChallenge(c: ByteArray): ByteArray {
        if (!applicationSelected) return sw(0x6985)
        if (c.size != 5) return sw(0x6700)
        return when (c[4].toInt() and 0xFF) {
            0, 8 -> sw(0x9000, kotlin.random.Random.nextBytes(8))
            4, 7 -> sw(0x6C08)
            else -> sw(0x6700)
        }
    }

    private fun externalAuthenticate(c: ByteArray): ByteArray {
        if (!applicationSelected) return sw(0x6985)
        return if (c[4].toInt() == 0x28) sw(outOfSequenceStatusWord) else sw(0x6700)
    }

    companion object {
        /**
         * A SET of 300 bytes: longer than the 223 a READ BINARY block holds, so reads of it can be cut short. Fifteen
         * PACEInfos (so it is a real SecurityInfos that passes the lint), as a chip offering many suites has.
         */
        fun defaultCardAccess(): ByteArray {
            val mappings = listOf(0x01, 0x02, 0x03, 0x04)
            val body = (0 until 15).fold(ByteArray(0)) { acc, i ->
                val oid = byteArrayOf(0x06, 0x0A, 0x04, 0x00, 0x7F, 0x00, 0x07, 0x02, 0x02, 0x04, mappings[i % 4].toByte(), (2 + i % 3).toByte())
                val info = oid + byteArrayOf(0x02, 0x01, 0x02, 0x02, 0x01, (8 + i).toByte())
                acc + byteArrayOf(0x30, info.size.toByte()) + info
            }
            return byteArrayOf(0x31, 0x82.toByte(), 0x01, 0x2C) + body
        }
    }
}
