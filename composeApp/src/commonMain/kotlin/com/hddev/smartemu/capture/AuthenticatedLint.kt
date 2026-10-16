package com.hddev.smartemu.capture

/**
 * Shape rules for [CaptureRecord.authenticated], run by [RedactionLint] after the strict decode has refused unknown
 * fields. Every field of an [AuthenticatedObservation] is short and structured, so a rule here is a tight bound: an
 * algorithm name that is not a name, an OID that is not dotted numbers, a size that is not a 256-byte bucket, a status
 * word outside 16 bits, a data group number that no data group has. Anything that doesn't fit is a rejection, and a hash
 * (20, 32, 48 or 64 bytes of hex, or the same in base64) is refused in every string whatever its field.
 *
 * Like every [RedactionLint.Violation], these never repeat the offending text.
 */
internal object AuthenticatedLint {
    private val version = Regex("[0-9.]{1,12}")
    private val algorithm = Regex("[A-Za-z0-9][A-Za-z0-9._/+-]{0,47}")
    private val oid = Regex("[0-2](\\.[0-9]{1,12}){1,24}")
    private val curve = Regex("[A-Za-z0-9-]{1,32}")
    private val keyType = Regex("[A-Za-z0-9-]{1,16}")
    private val issuerCountry = Regex("[A-Z]{2,3}")
    private val carCountry = Regex("[A-Z]{2}")
    private val cipher = Regex("AES-(128|192|256)|3DES")
    private val hexRun = Regex("[0-9A-Fa-f]{40,}")
    private val base64Run = Regex("[A-Za-z0-9+/_-]{27,}")

    private const val MAX_KEY_BITS = 16384
    private const val MIN_KEY_BITS = 64
    private const val MAX_ID = 0xFFFF

    fun check(record: CaptureRecord): List<RedactionLint.Violation> {
        val observation = record.authenticated ?: return emptyList()
        val out = mutableListOf<RedactionLint.Violation>()
        val rules = Rules(out)
        with(rules) {
            list("authenticated.access", observation.access.size, 32)
            observation.access.forEachIndexed { i, a ->
                val p = "authenticated.access[$i]"
                string("$p.oid", a.oid, oid)
                string("$p.cipher", a.cipher, cipher)
                number("$p.parameterId", a.parameterId, 0..MAX_ID)
                statusWord("$p.statusWord", a.statusWord)
                if (a.method == AccessMethod.BAC && (a.oid != null || a.mapping != null || a.cipher != null || a.parameterId != null)) {
                    bad("$p", "A BAC attempt has no PACE suite")
                }
                if (a.method == AccessMethod.PACE && a.camVerified != null && a.mapping != PaceMappingType.CAM) {
                    bad("$p.camVerified", "Only a CAM suite is verified")
                }
                if (a.succeeded != (a.statusClass == StatusClass.OK) && a.statusClass != StatusClass.NO_ANSWER) {
                    bad("$p.statusClass", "Success and status class disagree")
                }
            }
            observation.readUnder?.let { if (it !in observation.access.indices) bad("authenticated.readUnder", "Not an attempt") }
            observation.com?.let { c ->
                string("authenticated.com.ldsVersion", c.ldsVersion, version)
                string("authenticated.com.unicodeVersion", c.unicodeVersion, version)
                dataGroupNumbers("authenticated.com.dataGroups", c.dataGroups)
                size("authenticated.com.size", c.size)
            }
            list("authenticated.dataGroups", observation.dataGroups.size, 16)
            observation.dataGroups.forEachIndexed { i, d ->
                val p = "authenticated.dataGroups[$i]"
                if (d.number !in 1..16) bad("$p.number", "Not a data group number")
                statusWord("$p.selectStatusWord", d.selectStatusWord)
                statusWord("$p.readStatusWord", d.readStatusWord)
                size("$p.size", d.size)
            }
            if (observation.dataGroups.map { it.number }.let { it.size != it.toSet().size }) {
                bad("authenticated.dataGroups", "A data group appears twice")
            }
            observation.sod?.let { s ->
                val p = "authenticated.sod"
                size("$p.size", s.size)
                string("$p.digestAlgorithm", s.digestAlgorithm, algorithm)
                string("$p.signatureAlgorithm", s.signatureAlgorithm, algorithm)
                string("$p.ldsVersion", s.ldsVersion, version)
                string("$p.unicodeVersion", s.unicodeVersion, version)
                dataGroupNumbers("$p.hashedDataGroups", s.hashedDataGroups)
                if (s.certificateCount !in 0..8) bad("$p.certificateCount", "Out of range")
                s.signer?.let { c ->
                    string("$p.signer.signatureAlgorithm", c.signatureAlgorithm, algorithm)
                    string("$p.signer.keyType", c.keyType, keyType)
                    number("$p.signer.keyBits", c.keyBits, MIN_KEY_BITS..MAX_KEY_BITS)
                    string("$p.signer.curve", c.curve, curve)
                    string("$p.signer.issuerCountry", c.issuerCountry, issuerCountry)
                    number("$p.signer.validityMonths", c.validityMonths, 0..1200)
                }
            }
            securityInfos("authenticated.dg14", observation.dg14)
            securityInfos("authenticated.cardSecurity", observation.cardSecurity)
            observation.dg15?.let { k ->
                size("authenticated.dg15.size", k.size)
                string("authenticated.dg15.keyType", k.keyType, keyType)
                number("authenticated.dg15.keyBits", k.keyBits, MIN_KEY_BITS..MAX_KEY_BITS)
                string("authenticated.dg15.curve", k.curve, curve)
            }
            observation.cvca?.let { c ->
                size("authenticated.cvca.size", c.size)
                string("authenticated.cvca.carCountry", c.carCountry, carCountry)
            }
            observation.activeAuthentication?.let { authenticationResult("authenticated.activeAuthentication", it) }
            observation.chipAuthentication?.let { authenticationResult("authenticated.chipAuthentication", it) }
        }
        return out
    }

    private class Rules(val out: MutableList<RedactionLint.Violation>) {
        fun bad(path: String, detail: String) {
            out += RedactionLint.Violation(RedactionLint.Rule.BAD_FIELD, path, detail)
        }

        fun list(path: String, size: Int, max: Int) {
            if (size > max) bad(path, "More than $max entries")
        }

        fun string(path: String, value: String?, shape: Regex) {
            if (value == null) return
            if (hexRun.containsMatchIn(value) || base64Run.containsMatchIn(value) && value.any { it.isDigit() } && value.any { it.isUpperCase() } && value.any { it.isLowerCase() }) {
                out += RedactionLint.Violation(RedactionLint.Rule.HIGH_ENTROPY_BLOB, path, "A hash-like or key-like string")
            } else if (RedactionLint.looksLikeMrz(value)) {
                out += RedactionLint.Violation(RedactionLint.Rule.MRZ_PATTERN, path, "MRZ-like text")
            } else if (!shape.matches(value)) {
                bad(path, "Not the shape this field has")
            }
        }

        fun number(path: String, value: Int?, range: IntRange) {
            if (value != null && value !in range) bad(path, "Out of range")
        }

        fun statusWord(path: String, value: Int?) = number(path, value, 0..0xFFFF)

        /** A bucket of [Redaction.SIZE_BUCKET] bytes: `minBytes` a multiple of it and `maxBytes` the last byte of the bucket. */
        fun size(path: String, value: SizeRange?) {
            if (value == null) return
            if (value.minBytes < 0 || value.minBytes % Redaction.SIZE_BUCKET != 0 || value.maxBytes != value.minBytes + Redaction.SIZE_BUCKET - 1) {
                bad(path, "Not a ${Redaction.SIZE_BUCKET}-byte bucket")
            }
        }

        fun dataGroupNumbers(path: String, numbers: List<Int>) {
            if (numbers.size > 16 || numbers.any { it !in 1..16 } || numbers.size != numbers.toSet().size) bad(path, "Not a set of data group numbers")
        }

        fun securityInfos(path: String, value: SecurityInfosObservation?) {
            if (value == null) return
            size("$path.size", value.size)
            list("$path.infos", value.infos.size, 64)
            value.infos.forEachIndexed { i, info ->
                val p = "$path.infos[$i]"
                string("$p.oid", info.oid, oid)
                number("$p.version", info.version, 0..255)
                number("$p.parameterId", info.parameterId, 0..MAX_ID)
                string("$p.keyType", info.keyType, keyType)
                number("$p.keyBits", info.keyBits, MIN_KEY_BITS..MAX_KEY_BITS)
                string("$p.curve", info.curve, curve)
            }
        }

        fun authenticationResult(path: String, result: AuthenticationResult) {
            string("$path.algorithm", result.algorithm, algorithm)
            statusWord("$path.statusWord", result.statusWord)
        }
    }
}
