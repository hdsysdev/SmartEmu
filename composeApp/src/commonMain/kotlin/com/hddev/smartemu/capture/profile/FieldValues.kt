package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.AuthenticatedObservation
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.FileObservation
import com.hddev.smartemu.capture.LinkObservation
import com.hddev.smartemu.capture.SizeRange
import com.hddev.smartemu.capture.StepResult
import com.hddev.smartemu.data.ProfileAspect

/**
 * The names of the fields of a profile, which are also the fields of a capture the diff compares. A field is a
 * single value written as a string: a status word as four hex digits, a size as "min-max", bytes as hex.
 */
object FieldKeys {
    const val CARD_ACCESS = "cardAccess"
    const val ATR_INFO = "atrInfo"
    const val DIR = "dir"
    val publicFiles = listOf(CARD_ACCESS, ATR_INFO, DIR)

    const val APPLICATION_SELECT = "application:selectStatusWord"
    const val APPLICATION_SIZE = "application:responseSize"
    const val BAC_GET_CHALLENGE = "bac:getChallenge"
    const val BAC_EXTERNAL_AUTHENTICATE = "bac:externalAuthenticate"
    const val MAX_READ_LENGTH = "readBinary:maxReadLength"
    const val LINK_TECHNOLOGY = "link:technology"
    const val LINK_HISTORICAL_BYTES = "link:historicalBytes"
    const val LINK_UID_RANDOM = "link:uidRandom"

    fun fileContents(name: String) = "file:$name:contents"
    fun fileSelect(name: String) = "file:$name:selectStatusWord"
    fun fileSize(name: String) = "file:$name:size"

    /** The id a probe step has in [MeasuredObservations.statusWords] and in [com.hddev.smartemu.data.ErrorResponses.byProbe]. */
    fun stepId(probeId: String, stepId: String) = "$probeId/$stepId"
    fun stepStatus(step: String) = "step:$step:statusWord"
    fun stepSize(step: String) = "step:$step:responseSize"
    fun stepTiming(step: String) = "step:$step:timing"

    /** The step id inside a `step:` key, or null for another key. */
    fun stepOf(key: String): String? = key.takeIf { it.startsWith("step:") }?.removePrefix("step:")?.substringBeforeLast(':')

    fun statusWordText(statusWord: Int): String = statusWord.toString(16).uppercase().padStart(4, '0')
    fun sizeText(range: SizeRange): String = "${range.minBytes}-${range.maxBytes}"
}

/** Why a field can't be checked, in the words the report and the UI use. */
object NotComparableReasons {
    const val TIMING = "Timing is informational: the phone, the field strength and the chip all change it"
    const val LINK = "Link layer: not emulatable on Android HCE"
    const val READ_LENGTH_UNDETERMINED = "EF.CardAccess is shorter than the Le values the pack probes, so no read was truncated"
    const val AUTHENTICATED = "Behind authentication: the unauthenticated probe packs can't reach it"
}

/** How each kind of field is diffed, and which aspect of a profile it is evidence for. */
object FieldCatalog {
    /** Keys of what authenticated captures showed: see [MeasuredObservations.authenticated]. */
    const val AUTH_PREFIX = "auth:"

    private const val RANDOM_CHALLENGE =
        "GET CHALLENGE answers eight random bytes: compared by length bucket, never by value"

    fun fidelityOf(key: String): Pair<FieldFidelity, String?> = when {
        key.startsWith("link:") -> FieldFidelity.NOT_COMPARABLE to NotComparableReasons.LINK
        key.startsWith(AUTH_PREFIX) -> FieldFidelity.NOT_COMPARABLE to NotComparableReasons.AUTHENTICATED
        key.endsWith(":timing") -> FieldFidelity.NOT_COMPARABLE to NotComparableReasons.TIMING
        key == FieldKeys.MAX_READ_LENGTH -> FieldFidelity.COMPARED to null
        key.startsWith("step:get-challenge") && key.endsWith(":responseSize") -> FieldFidelity.STRUCTURAL to RANDOM_CHALLENGE
        else -> FieldFidelity.COMPARED to null
    }

    /**
     * The aspect of a profile a field is evidence for. PACE cryptography is never one: no unauthenticated probe runs
     * PACE, so EF.CardAccess's bytes show what the chip advertises, not that the emulator's PACE matches it.
     */
    fun aspectOf(key: String): ProfileAspect? = when {
        key.startsWith("link:") || key.startsWith(AUTH_PREFIX) || key.endsWith(":timing") -> null
        key.startsWith("file:") || key.startsWith("application:") || key == FieldKeys.BAC_GET_CHALLENGE -> ProfileAspect.ACCESS_CONTROL
        else -> ProfileAspect.ERROR_RESPONSES
    }
}

/** A capture or a profile flattened to the fields the diff compares. */
class FlatFields(
    /** Field key to value, for every field the diff can compare. */
    val values: Map<String, String>,
    /** Steps whose repeats answered differently within the capture: not a fact about the chip. */
    val unstable: Set<String> = emptySet(),
    val timing: Map<String, TimingAggregate> = emptyMap(),
    val link: LinkObservation? = null
)

/** Flattens captures and profiles to [FlatFields], the one form [ProfileMerger], [FidelityDiff] and the hold-out compare. */
object FieldValues {
    /** The Le of each READ BINARY step of the pack's `read-binary-max-length` probe. */
    private val probedLengths = mapOf(
        "read-binary-max-length/le-df" to 223,
        "read-binary-max-length/le-e0" to 224,
        "read-binary-max-length/le-ff" to 255,
        "read-binary-max-length/le-00" to 256
    )

    fun fromRecord(record: CaptureRecord): FlatFields {
        val values = linkedMapOf<String, String>()
        val unstable = linkedSetOf<String>()
        val timing = linkedMapOf<String, TimingAggregate>()

        fun file(name: String, file: FileObservation?) {
            if (file == null) return
            values[FieldKeys.fileSelect(name)] = FieldKeys.statusWordText(file.selectStatusWord)
            file.contentsHex?.let { values[FieldKeys.fileContents(name)] = it.uppercase() }
            file.size?.let { values[FieldKeys.fileSize(name)] = FieldKeys.sizeText(it) }
        }
        file(FieldKeys.CARD_ACCESS, record.files.cardAccess)
        file(FieldKeys.ATR_INFO, record.files.atrInfo)
        file(FieldKeys.DIR, record.files.dir)
        record.application?.let {
            values[FieldKeys.APPLICATION_SELECT] = FieldKeys.statusWordText(it.selectStatusWord)
            it.responseSize?.let { size -> values[FieldKeys.APPLICATION_SIZE] = FieldKeys.sizeText(size) }
        }
        record.bacCommands?.let {
            it.getChallenge?.let { sw -> values[FieldKeys.BAC_GET_CHALLENGE] = FieldKeys.statusWordText(sw) }
            it.externalAuthenticate?.let { sw -> values[FieldKeys.BAC_EXTERNAL_AUTHENTICATE] = FieldKeys.statusWordText(sw) }
        }
        val steps = linkedMapOf<String, StepResult>()
        record.probes.forEach { probe ->
            probe.steps.forEach { step ->
                val id = FieldKeys.stepId(probe.probeId, step.stepId)
                steps[id] = step
                val statusWord = step.statusWord ?: return@forEach
                if (!step.repeatsAgree) {
                    unstable += id
                    return@forEach
                }
                values[FieldKeys.stepStatus(id)] = FieldKeys.statusWordText(statusWord)
                step.responseSize?.let { values[FieldKeys.stepSize(id)] = FieldKeys.sizeText(it) }
                step.timing?.let { timing[id] = it.toAggregate() }
            }
        }
        maxReadLength(record.files.cardAccess?.contentsHex?.let { it.length / 2 }, steps, unstable)
            ?.let { values[FieldKeys.MAX_READ_LENGTH] = it.toString() }
        record.authenticated?.let { authenticatedValues(it, values) }
        return FlatFields(values, unstable, timing, record.link)
    }

    /**
     * The structure an authenticated read showed, flattened to "auth:" keys: algorithms, versions, which data groups
     * are where, sizes in buckets and status words. Never anything the capture doesn't hold, so nothing identifying.
     */
    private fun authenticatedValues(a: AuthenticatedObservation, into: MutableMap<String, String>) {
        fun put(key: String, value: Any?) {
            if (value != null && value.toString().isNotEmpty()) into[FieldCatalog.AUTH_PREFIX + key] = value.toString()
        }
        a.com?.let {
            put("com.ldsVersion", it.ldsVersion)
            put("com.unicodeVersion", it.unicodeVersion)
            put("com.dataGroups", it.dataGroups.joinToString(","))
            put("com.size", it.size?.let(FieldKeys::sizeText))
        }
        a.dataGroups.forEach { dg ->
            put(
                "dg${dg.number}",
                "inCom=${dg.inCom},inSod=${dg.inSod},select=${dg.selectStatusWord?.let(FieldKeys::statusWordText)}," +
                    "read=${dg.readStatusWord?.let(FieldKeys::statusWordText)},size=${dg.size?.let(FieldKeys::sizeText)}"
            )
        }
        a.sod?.let {
            put("sod.digestAlgorithm", it.digestAlgorithm)
            put("sod.signatureAlgorithm", it.signatureAlgorithm)
            put("sod.ldsVersion", it.ldsVersion)
            put("sod.unicodeVersion", it.unicodeVersion)
            put("sod.hashedDataGroups", it.hashedDataGroups.joinToString(","))
            put("sod.certificateCount", it.certificateCount)
            put("sod.size", it.size?.let(FieldKeys::sizeText))
            it.signer?.let { s ->
                put("sod.signer", "${s.signatureAlgorithm}/${s.keyType}/${s.keyBits}/${s.curve}/${s.issuerCountry}/${s.validityMonths}months")
            }
        }
        listOf("dg14" to a.dg14, "cardSecurity" to a.cardSecurity).forEach { (name, infos) ->
            infos?.let {
                put(name, it.infos.joinToString(";") { i -> "${i.kind}:${i.oid}:${i.version}:${i.parameterId}:${i.keyType}:${i.keyBits}:${i.curve}" } +
                    "|cam=${it.paceCamPresent}|size=${it.size?.let(FieldKeys::sizeText)}")
            }
        }
        a.dg15?.let { put("dg15", "${it.keyType}/${it.keyBits}/${it.curve}/${it.size?.let(FieldKeys::sizeText)}") }
        a.cvca?.let { put("cvca", "${it.carCountry}/alternate=${it.hasAlternateReference}/${it.size?.let(FieldKeys::sizeText)}") }
        a.activeAuthentication?.let { put("activeAuthentication", "succeeded=${it.succeeded},${it.algorithm},${it.statusWord?.let(FieldKeys::statusWordText)}") }
        a.chipAuthentication?.let { put("chipAuthentication", "succeeded=${it.succeeded},${it.algorithm},${it.statusWord?.let(FieldKeys::statusWordText)}") }
    }

    /**
     * The largest probed Le that was answered in full: a 9000 whose response size bucket holds exactly that many bytes,
     * for an Le that EF.CardAccess is long enough to fill. A lower bound, since the next probed Le may fail or be cut
     * short anywhere above it. Null if no probed Le fits in the file.
     */
    internal fun maxReadLength(fileSize: Int?, steps: Map<String, StepResult>, unstable: Set<String> = emptySet()): Int? {
        if (fileSize == null) return null
        var best: Int? = null
        for ((id, le) in probedLengths) {
            if (le > fileSize || id in unstable) continue
            val step = steps[id] ?: continue
            val size = step.responseSize ?: continue
            if (step.statusWord == 0x9000 && le in size.minBytes..size.maxBytes && le > (best ?: 0)) best = le
        }
        return best
    }

    fun fromProfile(profile: MeasuredProfile): FlatFields {
        val o = profile.observations
        val values = linkedMapOf<String, String>()
        fun file(name: String, contents: String?) {
            o.fileSelectStatusWords[name]?.let { values[FieldKeys.fileSelect(name)] = FieldKeys.statusWordText(it) }
            contents?.let { values[FieldKeys.fileContents(name)] = it.uppercase() }
            o.fileSizeBuckets[name]?.let { values[FieldKeys.fileSize(name)] = FieldKeys.sizeText(it) }
        }
        file(FieldKeys.CARD_ACCESS, o.cardAccessHex)
        file(FieldKeys.ATR_INFO, o.atrInfoHex)
        file(FieldKeys.DIR, o.dirHex)
        o.applicationSelectStatusWord?.let { values[FieldKeys.APPLICATION_SELECT] = FieldKeys.statusWordText(it) }
        o.applicationResponseSize?.let { values[FieldKeys.APPLICATION_SIZE] = FieldKeys.sizeText(it) }
        o.bacGetChallenge?.let { values[FieldKeys.BAC_GET_CHALLENGE] = FieldKeys.statusWordText(it) }
        o.bacExternalAuthenticate?.let { values[FieldKeys.BAC_EXTERNAL_AUTHENTICATE] = FieldKeys.statusWordText(it) }
        o.statusWords.forEach { (step, sw) -> values[FieldKeys.stepStatus(step)] = FieldKeys.statusWordText(sw) }
        o.responseSizes.forEach { (step, size) -> values[FieldKeys.stepSize(step)] = FieldKeys.sizeText(size) }
        o.maxReadLength?.let { values[FieldKeys.MAX_READ_LENGTH] = it.toString() }
        values.putAll(o.authenticated)
        val link = o.link?.let { LinkObservation(it.technology, it.historicalBytes, it.uidRandom) }
        return FlatFields(values, emptySet(), o.timing, link)
    }

    /** The observations a map of agreed field values stands for; the inverse of [fromProfile]. */
    internal fun observations(
        agreed: Map<String, String>,
        timing: Map<String, TimingAggregate>,
        link: LinkSummary?
    ): MeasuredObservations {
        fun status(key: String): Int? = agreed[key]?.toInt(16)
        fun size(key: String): SizeRange? = agreed[key]?.let(::parseSize)
        val selects = FieldKeys.publicFiles.mapNotNull { name -> status(FieldKeys.fileSelect(name))?.let { name to it } }.toMap()
        val sizes = FieldKeys.publicFiles.mapNotNull { name -> size(FieldKeys.fileSize(name))?.let { name to it } }.toMap()
        val statusWords = linkedMapOf<String, Int>()
        val responseSizes = linkedMapOf<String, SizeRange>()
        agreed.forEach { (key, value) ->
            val step = FieldKeys.stepOf(key) ?: return@forEach
            when {
                key.endsWith(":statusWord") -> statusWords[step] = value.toInt(16)
                key.endsWith(":responseSize") -> responseSizes[step] = parseSize(value)
            }
        }
        return MeasuredObservations(
            cardAccessHex = agreed[FieldKeys.fileContents(FieldKeys.CARD_ACCESS)],
            atrInfoHex = agreed[FieldKeys.fileContents(FieldKeys.ATR_INFO)],
            dirHex = agreed[FieldKeys.fileContents(FieldKeys.DIR)],
            fileSelectStatusWords = selects,
            fileSizeBuckets = sizes,
            applicationSelectStatusWord = status(FieldKeys.APPLICATION_SELECT),
            applicationResponseSize = size(FieldKeys.APPLICATION_SIZE),
            bacGetChallenge = status(FieldKeys.BAC_GET_CHALLENGE),
            bacExternalAuthenticate = status(FieldKeys.BAC_EXTERNAL_AUTHENTICATE),
            statusWords = statusWords,
            responseSizes = responseSizes,
            maxReadLength = agreed[FieldKeys.MAX_READ_LENGTH]?.toInt(),
            timing = timing,
            link = link,
            authenticated = agreed.filterKeys { it.startsWith(FieldCatalog.AUTH_PREFIX) }
        )
    }

    private fun parseSize(text: String): SizeRange {
        val (min, max) = text.split('-')
        return SizeRange(min.toInt(), max.toInt())
    }
}
