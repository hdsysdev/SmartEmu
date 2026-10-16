package com.hddev.smartemu.ui.glossary

/** The groups the full glossary lists its terms in. */
enum class GlossaryCategory(val title: String) {
    ACCESS("Opening the chip"),
    GENUINE("Checking it's genuine"),
    CRYPTOGRAPHY("Cryptography"),
    FILES("Files on the chip"),
    STANDARDS("Standards and devices")
}

/**
 * An abbreviation or piece of jargon the app shows, with what it stands for in [expansion] and what it means in a
 * sentence or two in [meaning]. [patterns] are the regular expressions that find it in text, matched whole-word;
 * [related] names other terms by [id]. [tile] is the short form shown on its tile, if the term itself is too long.
 */
data class GlossaryTerm(
    val id: String,
    val term: String,
    val expansion: String?,
    val meaning: String,
    val category: GlossaryCategory,
    val patterns: List<String>,
    val related: List<String> = emptyList(),
    val tile: String? = null
)

/** Where [term] appears in a text, as a range of character indices. */
data class GlossaryMatch(val range: IntRange, val term: GlossaryTerm)

/**
 * Every term the app explains, and [find], which finds them in a piece of text so that it can be marked and
 * explained where it's shown.
 */
object Glossary {
    val terms: List<GlossaryTerm> = buildList {
        // Opening the chip
        add(GlossaryTerm(
            id = "MRZ",
            term = "MRZ",
            expansion = "Machine Readable Zone",
            meaning = "The two or three lines of letters, digits and < signs at the bottom of the data page. A reader " +
                "scans them for the document number, date of birth and expiry date, which make the key that opens the chip.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("MRZ"),
            related = listOf("BAC", "PACE", "CAN", "DG1")
        ))
        add(GlossaryTerm(
            id = "CAN",
            term = "CAN",
            expansion = "Card Access Number",
            meaning = "A short number, usually 6 digits, printed on the document. With PACE a reader can ask for it " +
                "instead of scanning the MRZ; it's easier to type in.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("CAN"),
            related = listOf("PACE", "MRZ")
        ))
        add(GlossaryTerm(
            id = "BAC",
            term = "BAC",
            expansion = "Basic Access Control",
            meaning = "The original way to open a passport chip, from 2004. The reader proves it knows the MRZ, then " +
                "everything is encrypted with 3DES. Most chips still accept it for older readers, but its keys are " +
                "weak enough to be guessed by someone who records the exchange.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("BAC"),
            related = listOf("PACE", "MRZ", "3DES", "SM")
        ))
        add(GlossaryTerm(
            id = "PACE",
            term = "PACE",
            expansion = "Password Authenticated Connection Establishment",
            meaning = "The modern replacement for BAC, required on EU documents since 2014. Reader and chip turn the " +
                "MRZ or CAN into strong session keys with a key exchange, so recording the exchange doesn't reveal " +
                "them. A chip advertises PACE in EF.CardAccess.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("PACE"),
            related = listOf("BAC", "PACE_SUITE", "GM", "IM", "CAM", "EF.CardAccess")
        ))
        add(GlossaryTerm(
            id = "PACE_SUITE",
            term = "PACE suite",
            expansion = null,
            meaning = "One combination PACE can run with: a mapping (GM, IM or CAM), a curve or Diffie-Hellman group, " +
                "and a cipher, as in PACE-GM NIST P-256 AES-128. EF.CardAccess lists the suites the chip accepts, and " +
                "the reader picks one.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("(?i:PACE suites?)"),
            related = listOf("PACE", "GM", "NIST", "AES", "EF.CardAccess"),
            tile = "Suite"
        ))
        add(GlossaryTerm(
            id = "GM",
            term = "GM",
            expansion = "Generic Mapping",
            meaning = "The usual kind of PACE. After reader and chip agree on the password, an extra key exchange " +
                "turns it into fresh parameters for the main key exchange. Every PACE reader supports it.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("GM"),
            related = listOf("PACE", "IM", "CAM")
        ))
        add(GlossaryTerm(
            id = "IM",
            term = "IM",
            expansion = "Integrated Mapping",
            meaning = "A rarer kind of PACE that derives the fresh parameters straight from random numbers both sides " +
                "send, with no extra key exchange. Many readers don't support it.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("IM"),
            related = listOf("PACE", "GM")
        ))
        add(GlossaryTerm(
            id = "CAM",
            term = "CAM",
            expansion = "Chip Authentication Mapping",
            meaning = "A kind of PACE that also proves the chip is genuine in the same step, with a key from " +
                "EF.CardSecurity. Newer documents use it instead of separate Chip Authentication.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("CAM"),
            related = listOf("PACE", "GM", "CA", "EF.CardSecurity")
        ))
        add(GlossaryTerm(
            id = "SM",
            term = "Secure messaging",
            expansion = null,
            meaning = "Once BAC or PACE succeeds, every command and answer is encrypted and carries a checksum, so " +
                "nobody nearby can read or change them.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("(?i:secure messaging)"),
            related = listOf("BAC", "PACE", "AES", "3DES")
        ))
        add(GlossaryTerm(
            id = "DOMAIN",
            term = "Domain parameters",
            expansion = null,
            meaning = "The elliptic curve or Diffie-Hellman group a key exchange runs on. ICAO numbers the ones PACE " +
                "may use, and EF.CardAccess names them by that number.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("(?i:domain parameters?)"),
            related = listOf("NIST", "BRAINPOOL", "DH")
        ))
        add(GlossaryTerm(
            id = "DO84",
            term = "DO'84",
            expansion = "Data object 84",
            meaning = "The part of the reader's MSE:Set AT command that names which domain parameters it picked, " +
                "needed when the chip offers several.",
            category = GlossaryCategory.ACCESS,
            patterns = listOf("DO'84"),
            related = listOf("DOMAIN", "PACE_SUITE")
        ))

        // Checking it's genuine
        add(GlossaryTerm(
            id = "PA",
            term = "Passive Authentication",
            expansion = null,
            meaning = "Checks that the data hasn't been changed: the reader hashes each data group and compares the " +
                "hashes with EF.SOD, whose signature leads back to the issuing country's CSCA. It doesn't prove the " +
                "chip isn't a copy; Active or Chip Authentication do.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("(?i:Passive Authentication)"),
            related = listOf("SOD", "DS", "CSCA", "AA", "CA")
        ))
        add(GlossaryTerm(
            id = "SOD",
            term = "SOD",
            expansion = "Document Security Object (EF.SOD)",
            meaning = "The file holding a hash of every data group, signed by the Document Signer. It's what Passive " +
                "Authentication checks.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("EF\\.SOD", "SOD"),
            related = listOf("PA", "DS", "SHA")
        ))
        add(GlossaryTerm(
            id = "DS",
            term = "Document Signer",
            expansion = null,
            meaning = "The certificate whose key signs EF.SOD. The issuing country's CSCA signs the Document Signer " +
                "in turn, so a reader can trace the signature back to the country.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("(?i:Document Signers?)"),
            related = listOf("SOD", "CSCA", "PKI")
        ))
        add(GlossaryTerm(
            id = "CSCA",
            term = "CSCA",
            expansion = "Country Signing Certificate Authority",
            meaning = "The issuing country's root certificate for its documents. A reader trusts a document whose " +
                "Document Signer leads back to a CSCA it trusts. PassportEmu signs with its own test CSCA, so a reader " +
                "has to be given that one to accept its documents.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("CSCA"),
            related = listOf("DS", "PKI", "PA")
        ))
        add(GlossaryTerm(
            id = "PKI",
            term = "PKI",
            expansion = "Public Key Infrastructure",
            meaning = "The chain of certificates, from a country's CSCA to its Document Signers, that lets a reader " +
                "trust a signature it has never seen before.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("PKI"),
            related = listOf("CSCA", "DS")
        ))
        add(GlossaryTerm(
            id = "AA",
            term = "AA",
            expansion = "Active Authentication",
            meaning = "Proves the chip isn't a copy. The reader sends a random challenge, and the chip signs it with " +
                "a private key that can't be read out of it. The matching public key is in DG15.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("AA"),
            related = listOf("CA", "CAM", "DG15", "INTERNAL_AUTH")
        ))
        add(GlossaryTerm(
            id = "CA",
            term = "CA",
            expansion = "Chip Authentication",
            meaning = "Another way to prove the chip is genuine, part of EAC. The reader runs a key exchange with the " +
                "chip's key from DG14, and only a chip holding the private key ends up with the same keys. Secure " +
                "messaging then restarts with them.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("CA"),
            related = listOf("AA", "CAM", "EAC", "DG14")
        ))
        add(GlossaryTerm(
            id = "TA",
            term = "TA",
            expansion = "Terminal Authentication",
            meaning = "The reader proves the issuing country has authorised it, with certificates leading back to the " +
                "country's CVCA, before the chip releases fingerprints (DG3) or iris images (DG4). Ordinary readers " +
                "can't, so those stay locked.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("TA"),
            related = listOf("EAC", "CVCA", "DG3")
        ))
        add(GlossaryTerm(
            id = "EAC",
            term = "EAC",
            expansion = "Extended Access Control",
            meaning = "Chip Authentication plus Terminal Authentication, the protection EU passports put on " +
                "fingerprints.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("EAC"),
            related = listOf("CA", "TA", "CVCA")
        ))
        add(GlossaryTerm(
            id = "CVCA",
            term = "CVCA",
            expansion = "Country Verifying Certificate Authority",
            meaning = "The country's root certificate for readers allowed to see fingerprints. Terminal " +
                "Authentication succeeds only with a reader certificate that leads back to it.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("EF\\.CVCA", "CVCA"),
            related = listOf("TA", "EAC")
        ))
        add(GlossaryTerm(
            id = "INTERNAL_AUTH",
            term = "INTERNAL AUTHENTICATE",
            expansion = null,
            meaning = "The command a reader sends for Active Authentication: it carries the challenge, and the chip " +
                "answers with its signature.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("INTERNAL AUTHENTICATE"),
            related = listOf("AA", "APDU")
        ))
        add(GlossaryTerm(
            id = "EXTERNAL_AUTH",
            term = "EXTERNAL AUTHENTICATE",
            expansion = null,
            meaning = "The BAC command in which the reader proves it knows the MRZ key. Sent without first asking " +
                "for a challenge, it's out of order, and chips answer with different error codes.",
            category = GlossaryCategory.GENUINE,
            patterns = listOf("EXTERNAL AUTHENTICATE"),
            related = listOf("BAC", "APDU")
        ))

        // Cryptography
        add(GlossaryTerm(
            id = "AES",
            term = "AES",
            expansion = "Advanced Encryption Standard",
            meaning = "The cipher that encrypts secure messaging after PACE or Chip Authentication. The number, as " +
                "in AES-128, is the key length in bits; longer is stronger.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("AES(?:-\\d+)?"),
            related = listOf("3DES", "SM", "CMAC")
        ))
        add(GlossaryTerm(
            id = "3DES",
            term = "3DES",
            expansion = "Triple DES",
            meaning = "The older cipher BAC uses: the 1970s DES cipher applied three times. Slower and weaker than " +
                "AES, but still in every chip for BAC.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("3DES"),
            related = listOf("BAC", "AES")
        ))
        add(GlossaryTerm(
            id = "CMAC",
            term = "CMAC",
            expansion = "Cipher-based Message Authentication Code",
            meaning = "A checksum made with AES and the session key, added to each message so that the other side " +
                "can tell it hasn't been altered.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("CMAC(?:-\\d+)?"),
            related = listOf("AES", "SM")
        ))
        add(GlossaryTerm(
            id = "ECDH",
            term = "ECDH",
            expansion = "Elliptic Curve Diffie-Hellman",
            meaning = "A key exchange on an elliptic curve: each side combines its private key with the other's " +
                "public key, and both arrive at the same secret, which an eavesdropper can't work out.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("ECDH"),
            related = listOf("DH", "NIST", "BRAINPOOL")
        ))
        add(GlossaryTerm(
            id = "DH",
            term = "DH",
            expansion = "Diffie-Hellman",
            meaning = "The original key exchange, on large numbers of 1024 or 2048 bits instead of a curve. PACE " +
                "allows it, but real documents almost always use elliptic curves.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("DH \\d+/\\d+", "DH", "(?i:Diffie-Hellman)", "MODP"),
            related = listOf("ECDH", "DOMAIN")
        ))
        add(GlossaryTerm(
            id = "ECDSA",
            term = "ECDSA",
            expansion = "Elliptic Curve Digital Signature Algorithm",
            meaning = "A digital signature made with an elliptic curve key. It's smaller and faster than RSA at the " +
                "same strength.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("ECDSA"),
            related = listOf("RSA", "NIST", "BRAINPOOL")
        ))
        add(GlossaryTerm(
            id = "RSA",
            term = "RSA",
            expansion = "Rivest-Shamir-Adleman",
            meaning = "The classic public key algorithm, named after its inventors and used here for signatures. " +
                "The number, as in RSA-2048, is the key size in bits.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("RSA(?:-\\d+)?", "RSASSA-PSS", "PSS"),
            related = listOf("ECDSA", "ISO9796")
        ))
        add(GlossaryTerm(
            id = "ISO9796",
            term = "ISO 9796-2",
            expansion = null,
            meaning = "A way of signing with RSA in which part of the signed message can be recovered from the " +
                "signature. Active Authentication with an RSA key uses it.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("ISO 9796-2"),
            related = listOf("RSA", "AA")
        ))
        add(GlossaryTerm(
            id = "SHA",
            term = "SHA",
            expansion = "Secure Hash Algorithm",
            meaning = "Turns any data into a short fingerprint, its hash; changing a single bit of the data changes " +
                "the hash. SHA-256 makes 256-bit hashes. SHA-1 is outdated and kept only where old standards need it.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("SHA(?:-\\d+)?"),
            related = listOf("SOD", "PA")
        ))
        add(GlossaryTerm(
            id = "NIST",
            term = "NIST curves",
            expansion = "Curves of the US National Institute of Standards and Technology",
            meaning = "The most widely supported elliptic curves. The number, as in P-256, is the curve's size in " +
                "bits; bigger is stronger. Almost every reader and phone handles them.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("NIST P-\\d+", "P-\\d{3}", "NIST"),
            related = listOf("BRAINPOOL", "ECDH", "DOMAIN"),
            tile = "P-256"
        ))
        add(GlossaryTerm(
            id = "BRAINPOOL",
            term = "Brainpool curves",
            expansion = "Curves of the German ECC Brainpool group",
            meaning = "Elliptic curves designed as an alternative to the NIST ones, used by Germany and several other " +
                "European countries. BP-256 is short for brainpoolP256r1. Some readers handle only NIST curves.",
            category = GlossaryCategory.CRYPTOGRAPHY,
            patterns = listOf("brainpoolP\\d+r1", "BP-\\d+", "(?i:Brainpool)"),
            related = listOf("NIST", "ECDH", "DOMAIN"),
            tile = "BP-256"
        ))

        // Files on the chip
        add(GlossaryTerm(
            id = "EF",
            term = "EF",
            expansion = "Elementary File",
            meaning = "A file on the chip, as in EF.COM or EF.SOD. The data groups are files too, EF.DG1 and on.",
            category = GlossaryCategory.FILES,
            patterns = listOf("EF"),
            related = listOf("LDS", "DG", "EF.COM")
        ))
        add(GlossaryTerm(
            id = "LDS",
            term = "LDS",
            expansion = "Logical Data Structure",
            meaning = "ICAO's layout of the files on the chip: EF.COM, the numbered data groups and EF.SOD.",
            category = GlossaryCategory.FILES,
            patterns = listOf("LDS"),
            related = listOf("EF", "DG", "ICAO")
        ))
        add(GlossaryTerm(
            id = "DG",
            term = "Data groups",
            expansion = "DG1 to DG16",
            meaning = "The numbered files holding the document's data: DG1 the MRZ, DG2 the photo, DG3 fingerprints, " +
                "DG11 and DG12 extra details, DG14 and DG15 keys. A chip has only some of them.",
            category = GlossaryCategory.FILES,
            patterns = listOf("(?i:data groups?)"),
            related = listOf("DG1", "DG2", "DG14", "SOD"),
            tile = "DG"
        ))
        DATA_GROUPS.forEach { (number, content) ->
            add(GlossaryTerm(
                id = "DG$number",
                term = "DG$number",
                expansion = "Data group $number",
                meaning = content,
                category = GlossaryCategory.FILES,
                patterns = listOf("EF\\.DG$number", "DG$number"),
                related = listOf("DG", "SOD")
            ))
        }
        add(GlossaryTerm(
            id = "EF.COM",
            term = "EF.COM",
            expansion = null,
            meaning = "The chip's table of contents: which data groups it has and which version of the LDS it follows.",
            category = GlossaryCategory.FILES,
            patterns = listOf("EF\\.COM"),
            related = listOf("DG", "LDS")
        ))
        add(GlossaryTerm(
            id = "EF.CardAccess",
            term = "EF.CardAccess",
            expansion = null,
            meaning = "A file any reader can read before opening the chip. It lists the PACE suites the chip accepts; " +
                "a chip without it supports only BAC.",
            category = GlossaryCategory.FILES,
            patterns = listOf("EF\\.CardAccess"),
            related = listOf("PACE", "PACE_SUITE", "BAC")
        ))
        add(GlossaryTerm(
            id = "EF.CardSecurity",
            term = "EF.CardSecurity",
            expansion = null,
            meaning = "The file PACE-CAM needs: the chip's public key, signed by the Document Signer, read once PACE " +
                "succeeds.",
            category = GlossaryCategory.FILES,
            patterns = listOf("EF\\.CardSecurity"),
            related = listOf("CAM", "DS")
        ))

        // Standards and devices
        add(GlossaryTerm(
            id = "ICAO",
            term = "ICAO",
            expansion = "International Civil Aviation Organization",
            meaning = "The UN agency behind the standard every electronic passport and ID card follows, Doc 9303, " +
                "which defines the MRZ, the files on the chip and how readers open and check it.",
            category = GlossaryCategory.STANDARDS,
            patterns = listOf("ICAO(?: Doc)? 9303", "Doc 9303", "ICAO"),
            related = listOf("LDS", "MRZ", "BSI")
        ))
        add(GlossaryTerm(
            id = "BSI",
            term = "BSI",
            expansion = "Federal Office for Information Security (Germany)",
            meaning = "Germany's cyber security agency. Its technical guidelines, TR-03110 above all, specify PACE, " +
                "Chip and Terminal Authentication in detail; TR-03116 sets the cryptography German documents use.",
            category = GlossaryCategory.STANDARDS,
            patterns = listOf("BSI(?: TR-\\d+)?", "TR-\\d{5}"),
            related = listOf("ICAO", "PACE", "EAC")
        ))
        add(GlossaryTerm(
            id = "TD",
            term = "TD1 and TD3",
            expansion = "Travel document size 1 and 3",
            meaning = "ICAO's document sizes. TD1 is the size of a bank card, for ID cards and residence permits, " +
                "with three MRZ lines of 30 characters. TD3 is the passport page, with two lines of 44. TD2 sits in " +
                "between.",
            category = GlossaryCategory.STANDARDS,
            patterns = listOf("TD[123]"),
            related = listOf("MRZ", "ICAO"),
            tile = "TD1"
        ))
        add(GlossaryTerm(
            id = "NFC",
            term = "NFC",
            expansion = "Near Field Communication",
            meaning = "The short-range radio, a few centimetres, that phones use for contactless payments. Readers " +
                "use it to talk to the chip, and PassportEmu uses it to answer as one.",
            category = GlossaryCategory.STANDARDS,
            patterns = listOf("NFC"),
            related = listOf("APDU")
        ))
        add(GlossaryTerm(
            id = "APDU",
            term = "APDU",
            expansion = "Application Protocol Data Unit",
            meaning = "One command from the reader, or one answer from the chip, in the ISO 7816-4 format smart " +
                "cards use. Each answer ends with a status code: 9000 means success, anything else an error.",
            category = GlossaryCategory.STANDARDS,
            patterns = listOf("APDUs?"),
            related = listOf("NFC")
        ))
    }

    private val byId: Map<String, GlossaryTerm> = terms.associateBy { it.id }

    operator fun get(id: String): GlossaryTerm? = byId[id]

    /**
     * Each pattern, whole-word: not inside a longer word or file name, though a hyphen may join it to another term,
     * as in PACE-GM.
     */
    private val matchers: List<Pair<Regex, GlossaryTerm>> = terms.flatMap { term ->
        term.patterns.map { Regex("(?<![\\p{L}\\p{N}.'])(?:$it)(?![\\p{L}\\p{N}])") to term }
    }

    /**
     * The terms in [text], each at its first appearance only, so that a line mentioning PACE three times is marked
     * once. Where two candidates overlap the longer wins, so NIST P-256 is one term rather than two.
     */
    fun find(text: String): List<GlossaryMatch> {
        if (text.isBlank()) return emptyList()
        val candidates = matchers.flatMap { (regex, term) ->
            regex.findAll(text).map { GlossaryMatch(it.range, term) }
        }.sortedWith(compareBy<GlossaryMatch> { it.range.first }.thenByDescending { it.range.last - it.range.first })
        val chosen = mutableListOf<GlossaryMatch>()
        val seen = mutableSetOf<String>()
        var end = -1
        candidates.forEach { match ->
            if (match.range.first > end) {
                end = match.range.last
                if (seen.add(match.term.id)) chosen += match
            }
        }
        return chosen
    }

    /** The distinct terms in [text], in order. */
    fun termsIn(text: String): List<GlossaryTerm> = find(text).map { it.term }.distinct()
}

/** What each data group ICAO 9303 defines holds. */
private val DATA_GROUPS = listOf(
    1 to "The details in the MRZ: name, document number, nationality, dates of birth and expiry.",
    2 to "The holder's face photo, the one readers compare with the person in front of them.",
    3 to "Fingerprints. Only readers that pass Terminal Authentication may read them.",
    4 to "Iris images, protected like fingerprints. Few countries store them.",
    5 to "The portrait as printed on the document.",
    6 to "Reserved for future use.",
    7 to "The holder's signature, as an image.",
    8 to "Machine-readable security features of the data.",
    9 to "Machine-readable security features of the document's structure.",
    10 to "Machine-readable security features of the document's material.",
    11 to "More personal details: the full name, personal number, place of birth and others.",
    12 to "More document details: the issuing authority, date of issue and others.",
    13 to "Details the issuing country chooses itself.",
    14 to "Security information: the PACE suites, the Chip Authentication key and the Active Authentication " +
        "algorithm, as the chip has them.",
    15 to "The Active Authentication public key, which a reader uses to check the chip's signature.",
    16 to "People to notify in an emergency."
)
