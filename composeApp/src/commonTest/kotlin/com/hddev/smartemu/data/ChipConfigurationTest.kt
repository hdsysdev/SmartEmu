package com.hddev.smartemu.data

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChipConfigurationTest {

    private val holder = PassportData(
        passportNumber = "SPEC24681",
        dateOfBirth = LocalDate(1986, 3, 14),
        expiryDate = LocalDate(2035, 3, 13),
        issuingCountry = "SWE",
        nationality = "SWE",
        firstName = "Anna Maria",
        lastName = "Eriksson",
        gender = "F"
    )

    @Test
    fun `profile ids are unique and every profile's aspects have a provenance`() {
        assertEquals(ChipProfiles.all.size, ChipProfiles.all.map { it.id }.toSet().size)
        assertEquals(ChipProfiles.GENERIC_ID, ChipProfiles.default.id)
        assertEquals(ChipProfiles.default, ChipProfiles.byId("no-such-profile"))
        for (profile in ChipProfiles.all.filter { it.id != ChipProfiles.GENERIC_ID }) {
            assertTrue(profile.sources.isNotEmpty() || profile.provenance.values.all { it != Provenance.REPORTED }, profile.id)
        }
    }

    @Test
    fun `a Brainpool profile runs PACE on its own curve, never a NIST stand-in`() {
        val config = holder.withChipProfile(ChipProfiles.byId("de-passport-2017")).chipConfiguration()

        assertEquals(EcCurve.BRAINPOOL_P256R1, config.pace.curve)
        assertTrue(config.adaptations.isEmpty())
        assertEquals(EcCurve.BRAINPOOL_P256R1, config.chipAuthentication?.curve)
        assertEquals(KeySpec.Ec(EcCurve.BRAINPOOL_P256R1), config.sod.signerKey)
        for (profile in ChipProfiles.all.filter { it.accessControl.supportsPace }) {
            assertEquals(profile.pace, holder.withChipProfile(profile).chipConfiguration().pace, profile.id)
        }
    }

    @Test
    fun `a profile whose real chip offers two PACE mappings offers both, unless the document's mapping is changed`() {
        val expected = mapOf(
            "de-passport-2023" to listOf(PaceMapping.CHIP_AUTHENTICATION, PaceMapping.GENERIC),
            "de-id-card" to listOf(PaceMapping.CHIP_AUTHENTICATION, PaceMapping.GENERIC),
            "fi-id-card-2023" to listOf(PaceMapping.CHIP_AUTHENTICATION, PaceMapping.GENERIC),
            "fi-passport-2024" to listOf(PaceMapping.CHIP_AUTHENTICATION, PaceMapping.GENERIC),
            "fr-id-card-2024" to listOf(PaceMapping.GENERIC, PaceMapping.INTEGRATED)
        )
        for ((id, mappings) in expected) {
            val profile = ChipProfiles.byId(id)
            val config = holder.withChipProfile(profile).chipConfiguration()
            assertEquals(mappings.map { PaceSuite(it, profile.pace) }, config.paceSuites, id)
            assertEquals(mappings, config.paceMappings, id)
            assertTrue(config.adaptations.isEmpty(), id)
        }
        val chosen = holder.withChipProfile(ChipProfiles.byId("de-passport-2023")).copy(paceMapping = PaceMapping.INTEGRATED)
        assertEquals(listOf(PaceMapping.INTEGRATED), chosen.chipConfiguration().paceSuites.map { it.mapping })
        assertEquals(
            expected.keys,
            ChipProfiles.all.filter { it.otherPaceMappings.isNotEmpty() }.map { it.id }.toSet()
        )
    }

    @Test
    fun `data groups follow the features`() {
        val bacOnly = holder.copy(accessControl = AccessControl.BAC_ONLY).chipConfiguration()
        assertEquals(listOf(1, 2), bacOnly.dataGroups)

        val withPace = holder.chipConfiguration()
        assertEquals(listOf(1, 2, 14), withPace.dataGroups)

        val german = holder.withChipProfile(ChipProfiles.byId("de-passport-2017")).chipConfiguration()
        assertEquals(listOf(1, 2, 3, 14), german.dataGroups)

        val dutch = holder.withChipProfile(ChipProfiles.byId("nl-passport")).chipConfiguration()
        assertEquals(listOf(1, 2, 3, 11, 12, 14, 15), dutch.dataGroups)

        // RSA Active Authentication needs no DG14 on a BAC-only chip
        val australian = holder.withChipProfile(ChipProfiles.byId("au-passport-2016-p")).chipConfiguration()
        assertEquals(listOf(1, 2, 15), australian.dataGroups)
        assertEquals(KeySpec.Rsa(2048), australian.activeAuthentication)
    }

    @Test
    fun `a profile sets the chip settings, the document type and the country`() {
        val card = holder.copy(chipFault = ChipFault.CLONED_CHIP, activeAuthentication = true)
            .withChipProfile(ChipProfiles.byId("de-id-card"))

        assertEquals(AccessControl.PACE_ONLY, card.accessControl)
        assertEquals(DocumentType.ID_CARD, card.documentType)
        assertEquals("DEU", card.issuingCountry)
        assertEquals("DEU", card.nationality)
        assertFalse(card.activeAuthentication)
        assertEquals(ChipFault.NONE, card.chipFault, "a fault that needs Active Authentication goes with it")
        assertEquals("Anna Maria", card.firstName)
    }

    @Test
    fun `a profile that doesn't fit the document type falls back to the generic one`() {
        val data = holder.copy(chipProfileId = "de-passport-2017", documentType = DocumentType.ID_CARD)
        assertEquals(ChipProfiles.GENERIC_ID, data.chipProfile.id)
    }

    @Test
    fun `the German MRZ has D for the country and the Dutch one the personal number`() {
        val german = holder.copy(issuingCountry = "DEU", nationality = "DEU")
        assertTrue(german.toMrzData().startsWith("P<D<<ERIKSSON<<ANNA<MARIA"), german.toMrzData())

        val dutch = holder.withChipProfile(ChipProfiles.byId("nl-passport")).copy(personalNumber = "999999990")
        val line2 = dutch.toMrzData().substring(44)
        assertEquals("999999990<<<<<", line2.substring(28, 42))
        assertNull(holder.withChipProfile(ChipProfiles.byId("gb-passport")).chipConfiguration().activeAuthentication)
    }

    @Test
    fun `country presets are valid and use their profile`() {
        val today = LocalDate(2026, 9, 30)
        val presets = PassportPresets.all.filter { it.group.countryProfiles }
        assertTrue(presets.isNotEmpty())
        for (preset in presets) {
            val data = preset.build(today)
            assertEquals("profile-${data.chipProfileId}", preset.id)
            assertTrue(data.isValid(), preset.id)
        }
    }

    @Test
    fun `the generic profile assumes its PACE suite and the 2017 German signer is specified`() {
        assertEquals(Provenance.ASSUMED, ChipProfiles.default.provenanceOf(ProfileAspect.PACE_CRYPTOGRAPHY))
        // BSI TR-03116-2 Table 5 specifies the 2010-2019 Document Signer; the 2017 profile now says so
        assertEquals(Provenance.SPECIFICATION, ChipProfiles.byId("de-passport-2017").provenanceOf(ProfileAspect.SIGNATURE))
    }

    private fun configOf(id: String) = holder.withChipProfile(ChipProfiles.byId(id)).chipConfiguration()

    private val newProfileIds = listOf(
        "nl-passport-2024", "au-passport-2016-p", "de-passport-2023", "ca-passport-2023"
    )

    @Test
    fun `new generation profiles exist next to their country and keep every earlier id`() {
        val ids = ChipProfiles.all.map { it.id }
        for (id in listOf(
            "generic", "eu-passport", "eu-id-card", "de-passport-2017", "de-id-card", "nl-passport", "nl-id-card",
            "gb-passport", "us-passport-2021"
        )) assertTrue(id in ids, "saved documents reference $id")
        assertEquals(ids.indexOf("de-passport-2017") + 1, ids.indexOf("de-passport-2023"))
        assertEquals(ids.indexOf("nl-passport") + 1, ids.indexOf("nl-passport-2024"))
        val today = LocalDate(2026, 9, 30)
        for (id in newProfileIds) {
            val profile = ChipProfiles.byId(id)
            assertEquals(id, profile.id, "lookup must not fall back to generic")
            assertTrue(profile.evidence.isNotEmpty(), id)
            profile.evidence.forEach {
                assertTrue(it.url.startsWith("https://"), id)
                assertTrue(it.claim.isNotBlank() && it.sourceTitle.isNotBlank() && it.locator.isNotBlank(), id)
                assertTrue(it.provenance != Provenance.ASSUMED, id)
            }
            val data = PassportPresets.all.single { it.id == "profile-$id" }.build(today)
            assertTrue(data.isValid(), id)
            assertEquals(profile.accessControl, data.accessControl)
        }
    }

    @Test
    fun `the US passport is BAC only without Chip or Active Authentication, per the gmrtd sample`() {
        val profile = ChipProfiles.byId("us-passport-2021")
        val config = configOf("us-passport-2021")
        assertEquals(AccessControl.BAC_ONLY, config.accessControl)
        assertNull(config.activeAuthentication)
        assertNull(config.chipAuthentication)
        assertFalse(config.terminalAuthentication)
        assertEquals(listOf(1, 2, 11, 12), config.dataGroups)
        assertEquals(KeySpec.Rsa(2048), config.sod.signerKey)
        assertEquals("SHA-256", config.sod.digestAlgorithm)
        assertEquals("smartemu-test-ds-us-rsa", config.sod.passportSigner)
        assertEquals("SmartEmu Test CSCA US", config.sod.csca)
        assertEquals(Provenance.REPORTED, profile.provenanceOf(ProfileAspect.ACCESS_CONTROL))
        assertEquals(Provenance.REPORTED, profile.provenanceOf(ProfileAspect.ACTIVE_AUTHENTICATION))
        assertEquals(Provenance.REPORTED, profile.provenanceOf(ProfileAspect.SIGNATURE))
        assertFalse(profile.title.contains("assumed", ignoreCase = true) || profile.summary.contains("assumed AA"))
        assertTrue(profile.notes.any { "travel.state.gov" in it }, "the missing issuer source is stated")
    }

    @Test
    fun `the UK passport has the sampled PACE and CA suite, SOD data groups and no Active Authentication`() {
        val profile = ChipProfiles.byId("gb-passport")
        val config = configOf("gb-passport")
        assertEquals(AccessControl.BAC_AND_PACE, config.accessControl)
        assertEquals(PaceSpec(EcCurve.NIST_P256, SessionCipher.AES_256), config.pace)
        assertEquals(ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_256), config.chipAuthentication)
        assertNull(config.activeAuthentication)
        assertFalse(config.terminalAuthentication)
        assertTrue(profile.dataGroups.isEmpty())
        assertEquals(listOf(1, 2, 14), config.dataGroups)
        assertEquals(KeySpec.Ec(EcCurve.NIST_P256), config.sod.signerKey)
        assertEquals(Provenance.ASSUMED, profile.provenanceOf(ProfileAspect.ACCESS_CONTROL), "BAC alongside PACE is not shown")
        assertEquals(Provenance.REPORTED, profile.provenanceOf(ProfileAspect.PACE_CRYPTOGRAPHY))
        assertFalse(profile.summary.contains("Little else"))
    }

    @Test
    fun `Dutch profiles keep their ids and what the issuer does not say stays assumed`() {
        for (id in listOf("nl-passport", "nl-id-card")) {
            val profile = ChipProfiles.byId(id)
            assertEquals(Provenance.REPORTED, profile.provenanceOf(ProfileAspect.ACCESS_CONTROL), id)
            listOf(
                ProfileAspect.PACE_CRYPTOGRAPHY, ProfileAspect.ACTIVE_AUTHENTICATION,
                ProfileAspect.EXTENDED_ACCESS_CONTROL, ProfileAspect.SIGNATURE
            ).forEach { assertEquals(Provenance.ASSUMED, profile.provenanceOf(it), "$id $it") }
            assertTrue(profile.evidence.single().url.contains("rvig.nl"), id)
        }
        assertTrue(ChipProfiles.byId("nl-passport").terminalAuthentication)
        assertFalse(ChipProfiles.byId("nl-id-card").terminalAuthentication, "the 2014 ID card has CA only")
        assertTrue(ChipProfiles.byId("nl-id-card").chipAuthentication != null)
    }

    @Test
    fun `the 2024 Dutch passport is PACE only on Brainpool with an assumed AA stand-in`() {
        val profile = ChipProfiles.byId("nl-passport-2024")
        assertEquals("Netherlands · passport (2024 design)", profile.title)
        assertEquals(AccessControl.PACE_ONLY, profile.accessControl)
        assertEquals(PaceSpec(EcCurve.BRAINPOOL_P320R1, SessionCipher.AES_256), profile.pace)
        assertEquals(14, profile.pace.curve.paceParameterId)
        assertEquals(ChipAuthenticationSpec(EcCurve.BRAINPOOL_P512R1, SessionCipher.AES_256), profile.chipAuthentication)
        assertEquals(17, profile.chipAuthentication?.curve?.paceParameterId)
        assertTrue(profile.terminalAuthentication)
        assertEquals(KeySpec.Ec(EcCurve.NIST_P384), profile.activeAuthentication)
        assertEquals(Provenance.ASSUMED, profile.provenanceOf(ProfileAspect.ACTIVE_AUTHENTICATION), "AA key type unknown")
        assertEquals(Provenance.ASSUMED, profile.provenanceOf(ProfileAspect.SIGNATURE), "NL DS algorithm unknown")
        assertEquals("smartemu-test-ds-nl", profile.sod.passportSigner)
        assertTrue(profile.notes.any { "UNKNOWN" in it && "AA" in it })

        val config = configOf("nl-passport-2024")
        assertEquals(EcCurve.BRAINPOOL_P320R1, config.pace.curve)
        assertEquals(EcCurve.BRAINPOOL_P512R1, config.chipAuthentication?.curve)
        assertEquals(listOf(1, 2, 3, 14, 15), config.dataGroups)
    }

    @Test
    fun `the Australian P Series is the reported RSA sample`() {
        val p = ChipProfiles.byId("au-passport-2016-p")
        assertEquals("Australia · passport (P Series, 2016)", p.title)
        val config = configOf("au-passport-2016-p")
        assertEquals(AccessControl.BAC_ONLY, config.accessControl)
        assertEquals(KeySpec.Rsa(2048), config.activeAuthentication)
        assertNull(config.chipAuthentication)
        assertEquals(listOf(1, 2, 15), config.dataGroups)
        assertEquals("smartemu-test-ds-au-rsa", config.sod.passportSigner)
        assertEquals("PassportEmu TEST CSCA AU", config.sod.csca)
        assertEquals(Provenance.ASSUMED, p.provenanceOf(ProfileAspect.ACTIVE_AUTHENTICATION), "key size is assumed")
        assertEquals(Provenance.REPORTED, p.provenanceOf(ProfileAspect.ACCESS_CONTROL))
        assertEquals(Provenance.REPORTED, p.provenanceOf(ProfileAspect.PACE_CRYPTOGRAPHY), "README shows n/a (BAC)")
        assertEquals(Provenance.ASSUMED, p.provenanceOf(ProfileAspect.EXTENDED_ACCESS_CONTROL), "only DG14 absent from one SOD")
        assertEquals(Provenance.REPORTED, p.provenanceOf(ProfileAspect.SIGNATURE))
    }

    @Test
    fun `German profiles follow BSI TR-03116-2 and the sampled 2023 passport`() {
        val old = ChipProfiles.byId("de-passport-2017")
        assertEquals(AccessControl.BAC_AND_PACE, old.accessControl)
        assertEquals(PaceMapping.GENERIC, old.paceMapping)
        assertNull(old.activeAuthentication)
        listOf(ProfileAspect.PACE_CRYPTOGRAPHY, ProfileAspect.ACTIVE_AUTHENTICATION)
            .forEach { assertEquals(Provenance.SPECIFICATION, old.provenanceOf(it), "$it") }
        assertEquals(
            Provenance.ASSUMED, old.provenanceOf(ProfileAspect.EXTENDED_ACCESS_CONTROL),
            "Table 16 is a national application and the EU also required 3DES CA v1 until 2019"
        )
        assertEquals(Provenance.ASSUMED, old.provenanceOf(ProfileAspect.ACCESS_CONTROL), "BAC alongside PACE is not specified")
        assertEquals(KeySpec.Ec(EcCurve.BRAINPOOL_P256R1), old.sod.signerKey)
        assertEquals("smartemu-test-ds-de", old.sod.passportSigner)

        val new = ChipProfiles.byId("de-passport-2023")
        assertEquals(AccessControl.PACE_ONLY, new.accessControl)
        assertEquals(PaceMapping.CHIP_AUTHENTICATION, new.paceMapping)
        assertEquals(PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128), new.pace)
        assertEquals(ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128), new.chipAuthentication)
        assertTrue(new.terminalAuthentication)
        assertNull(new.activeAuthentication)
        assertTrue(new.dataGroups.isEmpty())
        assertEquals(SodSpec(
            digestAlgorithm = "SHA-384",
            signerKey = KeySpec.Ec(EcCurve.BRAINPOOL_P384R1),
            passportSigner = "smartemu-test-ds-de-p384",
            cardSigner = "smartemu-test-ds-de-p384",
            csca = "SmartEmu Test CSCA DE"
        ), new.sod)
        assertEquals(Provenance.REPORTED, new.provenanceOf(ProfileAspect.SIGNATURE))
        assertTrue(new.notes.any { "GM" in it && "CAM" in it }, "the real chip advertises both mappings")
        assertEquals(listOf(1, 2, 3, 14), configOf("de-passport-2023").dataGroups)
        assertEquals(PaceMapping.CHIP_AUTHENTICATION, holder.withChipProfile(new).paceMapping)

        val card = ChipProfiles.byId("de-id-card")
        assertEquals(PaceMapping.CHIP_AUTHENTICATION, card.paceMapping)
        assertEquals(AccessControl.PACE_ONLY, card.accessControl)
        assertEquals(PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128), card.pace)
        assertEquals("smartemu-test-ds-de-id", card.sod.cardSigner, "the ID card's signer is not sample-proven")
        assertEquals(Provenance.SPECIFICATION, card.provenanceOf(ProfileAspect.ACCESS_CONTROL))
        assertEquals(Provenance.ASSUMED, card.provenanceOf(ProfileAspect.SIGNATURE))
    }

    @Test
    fun `the 2023 Canadian passport has the sampled NIST P-384 suite and keeps the unknowns assumed`() {
        val profile = ChipProfiles.byId("ca-passport-2023")
        val config = configOf("ca-passport-2023")
        assertEquals(AccessControl.BAC_AND_PACE, config.accessControl)
        assertEquals(PaceSpec(EcCurve.NIST_P384, SessionCipher.AES_128), config.pace)
        assertEquals(ChipAuthenticationSpec(EcCurve.NIST_P384, SessionCipher.AES_128), config.chipAuthentication)
        assertTrue(config.adaptations.isEmpty(), "P-384 is already a NIST curve")
        assertNull(config.activeAuthentication)
        assertFalse(config.terminalAuthentication)
        assertEquals(listOf(1, 2, 14), config.dataGroups)
        assertEquals("smartemu-test-ds-ca", config.sod.passportSigner)
        assertEquals(Provenance.REPORTED, profile.provenanceOf(ProfileAspect.PACE_CRYPTOGRAPHY))
        listOf(
            ProfileAspect.ACCESS_CONTROL, ProfileAspect.ACTIVE_AUTHENTICATION, ProfileAspect.EXTENDED_ACCESS_CONTROL,
            ProfileAspect.SIGNATURE, ProfileAspect.DATA_GROUPS
        ).forEach { assertEquals(Provenance.ASSUMED, profile.provenanceOf(it), "$it") }
    }

    private val gmrtdRemainingIds = listOf(
        "at-passport-2023", "cn-passport-po-2018", "fi-id-card-2023", "fi-passport-2024", "fr-passport-2017",
        "fr-id-card-2024", "hk-passport-2025", "id-passport-2025", "my-passport-2023", "nz-passport-2017",
        "ph-passport-2020", "pt-passport-2026", "ru-passport-2020", "sg-passport-pa-2023", "sg-passport-pp-2025",
        "tw-passport-2024"
    )

    @Test
    fun `every remaining gmrtd sample country has a profile with scoped evidence, a preset and a country`() {
        val ids = ChipProfiles.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // France's profiles sit together, the rest after the other sampled countries
        assertEquals(ids.indexOf("fr-passport-2017") + 1, ids.indexOf("fr-id-card-2024"))
        assertEquals(ids.indexOf("fi-id-card-2023") + 1, ids.indexOf("fi-passport-2024"))
        assertEquals(ids.indexOf("sg-passport-pa-2023") + 1, ids.indexOf("sg-passport-pp-2025"))
        val today = LocalDate(2026, 9, 30)
        for (id in gmrtdRemainingIds) {
            val profile = ChipProfiles.byId(id)
            assertEquals(id, profile.id, "lookup must not fall back to generic")
            assertTrue(profile.country != null && Countries.all.any { it.first == profile.country }, "$id country")
            assertTrue(profile.evidence.isNotEmpty(), id)
            profile.evidence.forEach {
                assertTrue(it.url.startsWith("https://"), id)
                assertTrue(it.claim.isNotBlank() && it.sourceTitle.isNotBlank() && it.locator.isNotBlank(), id)
                assertTrue(it.aspect != null, id)
                assertTrue(it.provenance != Provenance.ASSUMED, id)
            }
            // An aspect the evidence does not cover must not be badged REPORTED
            for (aspect in ProfileAspect.entries) {
                if (profile.provenanceOf(aspect) != Provenance.ASSUMED) {
                    assertTrue(profile.evidence.any { it.aspect == aspect }, "$id $aspect is badged without evidence")
                }
            }
            assertEquals(Provenance.ASSUMED, profile.provenanceOf(ProfileAspect.MRZ), id)
            assertEquals(Provenance.ASSUMED, profile.provenanceOf(ProfileAspect.ERROR_RESPONSES), id)
            assertTrue(profile.notes.any { "ASSUMED" in it }, id)
            val data = PassportPresets.all.single { it.id == "profile-$id" }.build(today)
            assertTrue(data.isValid(), id)
            assertEquals(profile.accessControl, data.accessControl)
        }
    }

    @Test
    fun `no profile runs PACE on a curve that is not usable for it`() {
        for (profile in ChipProfiles.all) {
            if (profile.accessControl.supportsPace) assertTrue(profile.pace.curve.usableForPace, profile.id)
        }
        assertEquals(listOf(EcCurve.NIST_P192, EcCurve.BRAINPOOL_P192R1), EcCurve.entries.filter { !it.usableForPace })
    }

    @Test
    fun `the curves are the elliptic curves ICAO 9303 standardises for PACE, in parameter ID order`() {
        assertEquals((8..18).toList(), EcCurve.entries.map { it.paceParameterId })
    }

    @Test
    fun `a PACE curve and cipher from developer settings replace the profile's`() {
        val german = holder.withChipProfile(ChipProfiles.byId("de-passport-2017"))
        for (curve in EcCurve.entries) {
            val config = german.copy(paceCurve = curve, paceCipher = SessionCipher.AES_192).chipConfiguration()
            assertEquals(PaceSpec(curve, SessionCipher.AES_192), config.pace, "$curve")
        }
        // Choosing the profile's own curve changes nothing; each other choice says so
        val ownCurve = german.copy(paceCurve = EcCurve.BRAINPOOL_P256R1).chipConfiguration()
        assertTrue(ownCurve.adaptations.isEmpty())
        val both = german.copy(paceCurve = EcCurve.BRAINPOOL_P192R1, paceCipher = SessionCipher.AES_256).chipConfiguration()
        assertEquals(2, both.adaptations.size)
        // ICAO 9303 still defines PACE-CAM with AES only
        val cam = german.copy(paceMapping = PaceMapping.CHIP_AUTHENTICATION, paceCipher = SessionCipher.TDES).chipConfiguration()
        assertEquals(SessionCipher.AES_128, cam.pace.cipher)
        // A chip without PACE has nothing to replace
        val bacOnly = german.copy(accessControl = AccessControl.BAC_ONLY, paceCurve = EcCurve.NIST_P192).chipConfiguration()
        assertTrue(bacOnly.adaptations.isEmpty())
    }

    @Test
    fun `the PACE suite from developer settings reaches the document only in developer mode`() {
        val settings = AppSettings(paceCurve = EcCurve.BRAINPOOL_P224R1, paceCipher = SessionCipher.TDES)

        assertNull(settings.emulating(holder).paceCurve)
        assertNull(settings.emulating(holder).paceCipher)
        val developer = settings.copy(developerMode = true).emulating(holder)
        assertEquals(EcCurve.BRAINPOOL_P224R1, developer.paceCurve)
        assertEquals(SessionCipher.TDES, developer.paceCipher)
    }

    @Test
    fun `a DH group from developer settings takes over from the curve`() {
        val german = holder.withChipProfile(ChipProfiles.byId("de-passport-2017"))
        for (group in DhGroup.entries) {
            val config = german.copy(paceDhGroup = group, paceCurve = EcCurve.NIST_P384).chipConfiguration()
            assertEquals(group, config.pace.dhGroup, "$group")
            assertEquals(group.paceParameterId, config.pace.parameterId)
            assertEquals(listOf(PaceSuite(PaceMapping.GENERIC, config.pace)), config.paceSuites)
        }
        assertEquals(listOf(0, 1, 2), DhGroup.entries.map { it.paceParameterId })
        // ICAO 9303 defines PACE-CAM for ECDH only
        val cam = german.copy(paceMapping = PaceMapping.CHIP_AUTHENTICATION, paceDhGroup = DhGroup.MODP_2048_256)
            .chipConfiguration()
        assertEquals(PaceMapping.GENERIC, cam.paceMapping)
        assertEquals(DhGroup.MODP_2048_256, cam.pace.dhGroup)
        assertTrue(cam.adaptations.any { "ECDH only" in it })
    }

    @Test
    fun `Integrated Mapping is kept, except on NIST P-224 where it becomes generic`() {
        val im = holder.copy(paceMapping = PaceMapping.INTEGRATED)

        assertEquals(PaceMapping.INTEGRATED, im.chipConfiguration().paceMapping)
        assertTrue(im.chipConfiguration().adaptations.isEmpty())
        for (curve in EcCurve.entries) {
            val config = im.copy(paceCurve = curve).chipConfiguration()
            val expected = if (curve == EcCurve.NIST_P224) PaceMapping.GENERIC else PaceMapping.INTEGRATED
            assertEquals(expected, config.paceMapping, "$curve")
        }
        assertEquals(listOf(EcCurve.NIST_P224), EcCurve.entries.filter { !it.supportsIntegratedMapping })
        val dh = im.copy(paceDhGroup = DhGroup.MODP_1024_160).chipConfiguration()
        assertEquals(PaceSuite(PaceMapping.INTEGRATED, PaceSpec(dhGroup = DhGroup.MODP_1024_160)), dh.paceSuites.single())
    }

    @Test
    fun `extra PACE suites follow the document's own, or come first, without duplicates and as ICAO 9303 allows`() {
        val brainpoolIm = PaceSuite(PaceMapping.INTEGRATED, PaceSpec(EcCurve.BRAINPOOL_P384R1, SessionCipher.AES_256))
        val dhCam = PaceSuite(PaceMapping.CHIP_AUTHENTICATION, PaceSpec(dhGroup = DhGroup.MODP_1024_160))
        val own = PaceSuite(PaceMapping.GENERIC, PaceSpec())
        val data = holder.copy(extraPaceSuites = listOf(brainpoolIm, own, dhCam))

        val after = data.chipConfiguration()
        val dhGm = PaceSuite(PaceMapping.GENERIC, PaceSpec(dhGroup = DhGroup.MODP_1024_160))
        // Extra suites are kept as chosen, apart from what ICAO 9303 doesn't define
        assertEquals(listOf(own, brainpoolIm, dhGm), after.paceSuites)
        assertTrue(after.adaptations.any { "extra suite" in it && "ECDH only" in it })
        assertEquals(own, PaceSuite(after.paceMapping, after.pace))
        assertEquals(listOf(brainpoolIm, own, dhGm), data.copy(extraPaceSuitesFirst = true).chipConfiguration().paceSuites)
        assertTrue(data.copy(accessControl = AccessControl.BAC_ONLY).chipConfiguration().paceSuites.isEmpty())
        assertEquals(listOf(own), holder.chipConfiguration().paceSuites)
        assertEquals("PACE-IM brainpoolP384r1 AES-256", brainpoolIm.displayName)
    }

    @Test
    fun `DH groups and extra PACE suites reach the document only in developer mode`() {
        val extra = PaceSuite(PaceMapping.INTEGRATED, PaceSpec(dhGroup = DhGroup.MODP_2048_224))
        val settings = AppSettings(paceDhGroup = DhGroup.MODP_2048_256, extraPaceSuites = listOf(extra), extraPaceSuitesFirst = true)

        val guided = settings.emulating(holder)
        assertNull(guided.paceDhGroup)
        assertTrue(guided.extraPaceSuites.isEmpty())
        assertFalse(guided.extraPaceSuitesFirst)
        val developer = settings.copy(developerMode = true).emulating(holder)
        assertEquals(DhGroup.MODP_2048_256, developer.paceDhGroup)
        assertEquals(listOf(extra), developer.extraPaceSuites)
        assertTrue(developer.extraPaceSuitesFirst)
    }

    @Test
    fun `a curve that PACE cannot use appears only in Chip Authentication, never in PACE, however the document is resolved`() {
        val unusable = EcCurve.entries.filter { !it.usableForPace }
        assertTrue(unusable.isNotEmpty())
        var usedByChipAuthentication = false
        for (profile in ChipProfiles.all) {
            for (curve in unusable) {
                assertTrue(profile.pace.curve != curve, "${profile.id} PACE")
                if (profile.chipAuthentication?.curve == curve) usedByChipAuthentication = true
            }
            for (access in AccessControl.entries) {
                val config = holder.withChipProfile(profile).copy(accessControl = access).chipConfiguration()
                assertTrue(config.pace.curve.usableForPace, "${profile.id} $access")
                assertTrue(config.pace.curve !in unusable, "${profile.id} $access")
            }
        }
        assertTrue(usedByChipAuthentication, "the Russian Chip Authentication curve is the one such use")
        // The Chip Authentication curve is kept as the profile has it
        assertEquals(EcCurve.NIST_P192, configOf("ru-passport-2020").chipAuthentication?.curve)
    }

    @Test
    fun `switching a BAC only profile to PACE says that a stand-in suite is used`() {
        for (id in listOf("cn-passport-po-2018", "my-passport-2023", "ph-passport-2020", "ru-passport-2020", "us-passport-2021")) {
            val profile = ChipProfiles.byId(id)
            assertFalse(profile.accessControl.supportsPace, id)
            assertTrue(configOf(id).adaptations.isEmpty(), "$id as sampled needs no adaptation")
            val withPace = holder.withChipProfile(profile).copy(accessControl = AccessControl.BAC_AND_PACE).chipConfiguration()
            assertEquals(PaceSpec(), withPace.pace, id)
            assertTrue(withPace.adaptations.any { "no PACE" in it && "stand-in" in it }, id)
        }
        // A profile that has PACE says nothing of the kind
        val german = holder.withChipProfile(ChipProfiles.byId("de-passport-2017"))
        assertTrue(german.chipConfiguration().adaptations.isEmpty())
    }

    @Test
    fun `Active Authentication absence from one sampled SOD is never badged as reported`() {
        // The only basis is a missing DG15 in one parsed SOD, and the README row does not list AA either way
        for (id in listOf(
            "at-passport-2023", "fi-id-card-2023", "fr-passport-2017", "my-passport-2023", "ru-passport-2020", "gb-passport"
        )) {
            val profile = ChipProfiles.byId(id)
            assertNull(profile.activeAuthentication, id)
            assertEquals(Provenance.ASSUMED, profile.provenanceOf(ProfileAspect.ACTIVE_AUTHENTICATION), id)
            assertTrue(profile.notes.any { "does not show the chip has no Active" in it }, id)
            profile.evidence.filter { it.aspect == ProfileAspect.ACTIVE_AUTHENTICATION }.forEach {
                assertTrue("lacks DG15" in it.claim && "no Active Authentication key" !in it.claim, id)
            }
        }
        // "Cloneable" rows state the absence themselves
        for (id in listOf("sg-passport-pa-2023", "tw-passport-2024", "us-passport-2021")) {
            assertEquals(Provenance.REPORTED, ChipProfiles.byId(id).provenanceOf(ProfileAspect.ACTIVE_AUTHENTICATION), id)
        }
        // And no profile is badged for Chip Authentication on a missing DG14 alone: the README row must say so
        for (profile in ChipProfiles.all.filter { it.chipAuthentication == null }) {
            if (profile.provenanceOf(ProfileAspect.EXTENDED_ACCESS_CONTROL) != Provenance.ASSUMED) {
                assertTrue(
                    profile.evidence.any { it.aspect == ProfileAspect.EXTENDED_ACCESS_CONTROL && "Cloneable" in it.claim },
                    "${profile.id} CA absence needs a README statement"
                )
            }
        }
    }

    @Test
    fun `README PACE rows are BAC and PACE, and n-a BAC rows are BAC only with PACE absence reported`() {
        for (id in listOf("cn-passport-po-2018", "my-passport-2023", "ph-passport-2020", "ru-passport-2020")) {
            val profile = ChipProfiles.byId(id)
            assertEquals(AccessControl.BAC_ONLY, profile.accessControl, id)
            assertEquals(Provenance.REPORTED, profile.provenanceOf(ProfileAspect.ACCESS_CONTROL), id)
            assertEquals(Provenance.REPORTED, profile.provenanceOf(ProfileAspect.PACE_CRYPTOGRAPHY), id)
        }
        for (id in listOf(
            "at-passport-2023", "fi-id-card-2023", "fi-passport-2024", "fr-passport-2017", "fr-id-card-2024",
            "hk-passport-2025", "id-passport-2025", "nz-passport-2017", "pt-passport-2026", "sg-passport-pa-2023",
            "sg-passport-pp-2025", "tw-passport-2024"
        )) {
            val profile = ChipProfiles.byId(id)
            assertEquals(AccessControl.BAC_AND_PACE, profile.accessControl, id)
            assertEquals(Provenance.ASSUMED, profile.provenanceOf(ProfileAspect.ACCESS_CONTROL), "$id: BAC is not shown")
            assertEquals(Provenance.REPORTED, profile.provenanceOf(ProfileAspect.PACE_CRYPTOGRAPHY), id)
        }
    }

    @Test
    fun `the Austrian, French, Hong Kong and Indonesian passports carry their sampled PACE and CA suites`() {
        configOf("at-passport-2023").also {
            assertEquals(PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128), it.pace)
            assertEquals(ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128), it.chipAuthentication)
            assertEquals(KeySpec.Ec(EcCurve.BRAINPOOL_P256R1), it.sod.signerKey)
            assertEquals(listOf(1, 2, 3, 11, 12, 14), it.dataGroups)
            assertNull(it.activeAuthentication)
        }
        configOf("fr-passport-2017").also {
            assertEquals(PaceSpec(EcCurve.NIST_P256, SessionCipher.AES_256), it.pace)
            assertEquals(ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.TDES), it.chipAuthentication)
            assertEquals(KeySpec.Rsa(2048), it.sod.signerKey)
            assertEquals("smartemu-test-ds-fr-rsa", it.sod.passportSigner)
        }
        configOf("fr-id-card-2024").also {
            assertEquals(PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256), it.pace)
            assertEquals(PaceMapping.GENERIC, it.paceMapping)
            assertEquals(ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256), it.chipAuthentication)
        }
        configOf("hk-passport-2025").also {
            assertEquals(PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256), it.pace)
            assertEquals(KeySpec.Ec(EcCurve.BRAINPOOL_P256R1), it.activeAuthentication)
            assertNull(it.chipAuthentication)
        }
        configOf("id-passport-2025").also {
            assertEquals(PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256), it.pace)
            assertEquals(ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256), it.chipAuthentication)
            assertEquals("IDN", ChipProfiles.byId("id-passport-2025").country)
            assertEquals("smartemu-test-ds-idn", it.sod.passportSigner)
        }
        assertEquals(Provenance.REPORTED, ChipProfiles.byId("id-passport-2025").provenanceOf(ProfileAspect.DATA_GROUPS))
        assertEquals(listOf(1, 2, 3, 14, 15), configOf("id-passport-2025").dataGroups)
    }

    @Test
    fun `the Finnish cards and passports use PACE-CAM on Brainpool P-384 with AES-256`() {
        for (id in listOf("fi-id-card-2023", "fi-passport-2024")) {
            val config = configOf(id)
            assertEquals(PaceMapping.CHIP_AUTHENTICATION, config.paceMapping, id)
            assertEquals(PaceSpec(EcCurve.BRAINPOOL_P384R1, SessionCipher.AES_256), config.pace, id)
            assertNull(config.chipAuthentication, id)
        }
        val card = configOf("fi-id-card-2023")
        assertEquals(DocumentType.ID_CARD, ChipProfiles.byId("fi-id-card-2023").documentType)
        assertEquals("SHA-256", configOf("fi-passport-2024").sod.digestAlgorithm, "the passport keeps the default digest")
        assertEquals("SHA-512", card.sod.digestAlgorithm)
        assertEquals(KeySpec.Ec(EcCurve.BRAINPOOL_P512R1), card.sod.signerKey)
        assertEquals("smartemu-test-ds-fi-id", card.sod.passportSigner)
        assertEquals(Provenance.REPORTED, ChipProfiles.byId("fi-id-card-2023").provenanceOf(ProfileAspect.SIGNATURE))
        assertEquals(Provenance.ASSUMED, ChipProfiles.byId("fi-id-card-2023").provenanceOf(ProfileAspect.DATA_GROUPS), "DG7")
        assertEquals(Provenance.ASSUMED, ChipProfiles.byId("fi-passport-2024").provenanceOf(ProfileAspect.SIGNATURE))
    }

    @Test
    fun `the BAC only Asian passports follow their samples and replace RSASSA-PSS with PKCS1 v1_5`() {
        val china = configOf("cn-passport-po-2018")
        assertEquals(KeySpec.Rsa(2048), china.activeAuthentication)
        assertNull(china.chipAuthentication)
        assertEquals(listOf(1, 2, 11, 12, 15), china.dataGroups)
        assertEquals(Provenance.ASSUMED, ChipProfiles.byId("cn-passport-po-2018").provenanceOf(ProfileAspect.SIGNATURE), "id-data")
        assertTrue(ChipProfiles.byId("cn-passport-po-2018").notes.any { "id-data" in it })

        val malaysia = configOf("my-passport-2023")
        assertEquals(ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.TDES), malaysia.chipAuthentication)
        assertNull(malaysia.activeAuthentication)
        assertEquals(listOf(1, 2, 3, 11, 12, 14), malaysia.dataGroups)

        val philippines = configOf("ph-passport-2020")
        assertEquals(KeySpec.Rsa(2048), philippines.activeAuthentication)
        assertEquals(listOf(1, 2, 11, 12, 15), philippines.dataGroups)

        for (id in listOf("id-passport-2025", "my-passport-2023", "ph-passport-2020", "sg-passport-pa-2023", "sg-passport-pp-2025")) {
            val profile = ChipProfiles.byId(id)
            assertEquals(Provenance.ASSUMED, profile.provenanceOf(ProfileAspect.SIGNATURE), "$id PSS is replaced")
            assertTrue(profile.notes.any { "RSASSA-PSS" in it }, "$id says so")
            assertEquals("SHA-256", profile.sod.digestAlgorithm)
        }
    }

    @Test
    fun `New Zealand runs PACE with 3DES, Russia has CA on P-192 with 3DES and a SHA-1 SOD, and the cloneable ones have no CA or AA`() {
        val newZealand = configOf("nz-passport-2017")
        assertEquals(PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.TDES), newZealand.pace)
        assertEquals(KeySpec.Rsa(2048), newZealand.activeAuthentication)
        assertEquals(listOf(1, 2, 12, 14, 15), newZealand.dataGroups)

        val russia = configOf("ru-passport-2020")
        assertEquals(AccessControl.BAC_ONLY, russia.accessControl)
        assertEquals(ChipAuthenticationSpec(EcCurve.NIST_P192, SessionCipher.TDES), russia.chipAuthentication)
        assertEquals("SHA-1", russia.sod.digestAlgorithm)
        assertEquals(KeySpec.Ec(EcCurve.NIST_P256), russia.sod.signerKey)
        assertEquals(Provenance.ASSUMED, ChipProfiles.byId("ru-passport-2020").provenanceOf(ProfileAspect.EXTENDED_ACCESS_CONTROL))
        assertTrue(ChipProfiles.byId("ru-passport-2020").notes.any { "secp192" in it })

        for (id in listOf("sg-passport-pa-2023", "tw-passport-2024")) {
            val config = configOf(id)
            assertNull(config.activeAuthentication, id)
            assertNull(config.chipAuthentication, id)
            assertEquals(Provenance.REPORTED, ChipProfiles.byId(id).provenanceOf(ProfileAspect.ACTIVE_AUTHENTICATION), id)
        }
        assertEquals(EcCurve.NIST_P256, configOf("tw-passport-2024").pace.curve)
        assertEquals(SessionCipher.AES_256, configOf("tw-passport-2024").pace.cipher)
        assertEquals(KeySpec.Rsa(2048), configOf("sg-passport-pp-2025").activeAuthentication)
    }

    @Test
    fun `the README type codes are explained as undefined and the nearest document type is used`() {
        for (id in listOf("cn-passport-po-2018", "fi-id-card-2023", "fr-id-card-2024", "pt-passport-2026", "sg-passport-pa-2023", "sg-passport-pp-2025")) {
            assertTrue(ChipProfiles.byId(id).notes.any { "does not define" in it }, id)
        }
        assertEquals(DocumentType.ID_CARD, ChipProfiles.byId("fr-id-card-2024").documentType)
        assertEquals(DocumentType.PASSPORT, ChipProfiles.byId("sg-passport-pa-2023").documentType)
    }
}
