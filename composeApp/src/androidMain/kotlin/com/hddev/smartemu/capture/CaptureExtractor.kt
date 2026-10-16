package com.hddev.smartemu.capture

/**
 * Reads one kind of observation from the chip and hands it to the [CaptureBuilder] through a typed setter. An
 * extractor can emit only what the builder accepts, so a capture can't gain a field by adding an extractor.
 */
interface CaptureExtractor {
    /** Stable name of the step, shown as the progress label and used to skip it when a run resumes. */
    val id: String

    fun extract(session: CaptureSession, out: CaptureBuilder)

    /** Whether [out] already holds this extractor's result, so a resumed run doesn't ask the chip again. */
    fun isComplete(out: CaptureBuilder): Boolean = false

    /**
     * Whether this extractor reads what identifies the document by its public answers (the application SELECT, a public
     * file). A resume reads these again and compares them with the draft's, to see that the chip is the same one.
     */
    val readsPublicIdentity: Boolean get() = false

    /**
     * Whether this extractor sends an EXTERNAL AUTHENTICATE. A resume doesn't run it again once one was sent, and an
     * authenticated capture leaves it out (see [CaptureRunner.forAuthenticatedRead]).
     */
    val sendsExternalAuthenticate: Boolean get() = false
}

/** The extractors of an unauthenticated capture, in the order they run. */
fun defaultExtractors(): List<CaptureExtractor> = listOf(
    PublicFileExtractor.cardAccess,
    PublicFileExtractor.atrInfo,
    PublicFileExtractor.dir,
    EmrtdApplicationExtractor,
    BacCommandExtractor
)

/**
 * Reads a file of the master file that needs no authentication: SELECT by identifier, then READ BINARY in blocks. EF.CardAccess
 * has a short EF identifier, so a chip that won't SELECT it is also asked to READ BINARY by that. EF.ATR/INFO and EF.DIR
 * are never read by short identifier: theirs (01 and 1E) are those of DG1 and EF.COM in the eMRTD application, which a chip
 * with that application already selected would answer.
 *
 * What was read is kept only if it plausibly is the file: within [maxBytes] and, as MRZ text would not be, free of
 * MRZ-like lines (see [RedactionLint.looksLikeMrz]).
 */
class PublicFileExtractor(
    override val id: String,
    private val fileId: Int,
    private val shortId: Int?,
    private val maxBytes: Int,
    private val store: (CaptureBuilder, Int, ByteArray?) -> Unit,
    private val isStored: (CaptureBuilder) -> Boolean
) : CaptureExtractor {

    override fun isComplete(out: CaptureBuilder): Boolean = isStored(out)

    override val readsPublicIdentity: Boolean get() = true

    override fun extract(session: CaptureSession, out: CaptureBuilder) {
        session.selectMasterFile()
        val select = session.selectFileId(fileId)
        val selectStatusWord = select.statusWord ?: 0
        var read: CaptureSession.FileRead? = null
        if (select.isSuccess) {
            read = session.readSelectedFile(maxBytes)
        } else if (shortId != null) {
            val bySfi = session.readSelectedFile(maxBytes, sfi = shortId)
            if (bySfi.contents != null) read = bySfi
        }
        val contents = read?.contents?.takeIf { !RedactionLint.looksLikeMrz(it.decodeToString()) }
        store(out, selectStatusWord, contents)
    }

    companion object {
        const val CARD_ACCESS_ID = 0x011C
        const val ATR_INFO_ID = 0x2F01
        const val DIR_ID = 0x2F00

        val cardAccess = PublicFileExtractor("ef-card-access", CARD_ACCESS_ID, shortId = 0x1C, maxBytes = 2048,
            store = { out, sw, bytes -> out.setCardAccess(sw, bytes) }, isStored = CaptureBuilder::hasCardAccess)
        val atrInfo = PublicFileExtractor("ef-atr-info", ATR_INFO_ID, shortId = null, maxBytes = 512,
            store = { out, sw, bytes -> out.setAtrInfo(sw, bytes) }, isStored = CaptureBuilder::hasAtrInfo)
        val dir = PublicFileExtractor("ef-dir", DIR_ID, shortId = null, maxBytes = 512,
            store = { out, sw, bytes -> out.setDir(sw, bytes) }, isStored = CaptureBuilder::hasDir)
    }
}

/** SELECT of the eMRTD application by its AID, as ICAO 9303 part 10 says readers do it. */
object EmrtdApplicationExtractor : CaptureExtractor {
    override val id = "emrtd-application"

    override fun isComplete(out: CaptureBuilder): Boolean = out.hasApplication()

    override val readsPublicIdentity: Boolean get() = true

    override fun extract(session: CaptureSession, out: CaptureBuilder) {
        session.selectMasterFile()
        val response = session.selectEmrtdApplication()
        out.setApplication(response.statusWord ?: 0, response.data.size)
    }
}

/**
 * The status words of EXTERNAL AUTHENTICATE with no challenge before it, and then GET CHALLENGE, each after a fresh
 * SELECT of the application, so that the chip has no challenge pending for the first. The EXTERNAL AUTHENTICATE
 * carries zeros, not a cryptogram, and goes first and once: whether a chip counts failed BAC attempts isn't known, so
 * no more of them are sent than the one command shape needs.
 *
 * This is how the `bac-commands` observation is made for tier-a v1 and for a run with no tier-a v2 pack. In tier-a v2
 * the observation is taken from the pack's own steps (see [CaptureRunner]) and this extractor does nothing: its GET
 * CHALLENGE would come before the pack's EXTERNAL AUTHENTICATE probe, and its EXTERNAL AUTHENTICATE would be a third in the
 * run.
 */
object BacCommandExtractor : CaptureExtractor {
    override val id = "bac-commands"

    override val sendsExternalAuthenticate: Boolean get() = true

    override fun isComplete(out: CaptureBuilder): Boolean = out.hasBacCommands() || out.runsPack("tier-a", minVersion = 2)

    override fun extract(session: CaptureSession, out: CaptureBuilder) {
        session.selectMasterFile()
        session.selectEmrtdApplication()
        val externalAuthenticate = session.transmit(Apdu.externalAuthenticateWithoutChallenge).statusWord
        session.selectEmrtdApplication()
        val getChallenge = session.transmit(Apdu.getChallenge).statusWord
        out.setBacCommands(getChallenge, externalAuthenticate)
    }
}
