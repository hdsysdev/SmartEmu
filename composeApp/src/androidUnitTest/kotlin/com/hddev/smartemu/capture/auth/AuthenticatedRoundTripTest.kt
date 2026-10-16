package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.ObservedPaceSuite
import com.hddev.smartemu.capture.ProfileRecovery
import com.hddev.smartemu.capture.RedactionLint
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.KeySpec
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.ProfileAspect
import com.hddev.smartemu.data.chipConfiguration
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The round trip with authentication: a profile's emulated chip, opened with its own MRZ, must give a record from which
 * the profile's facts behind PACE and BAC come back: the suites, the digest and signature algorithms, the data groups,
 * Chip Authentication, Active Authentication and the Terminal Authentication refusal.
 */
class AuthenticatedRoundTripTest {
    private lateinit var chip: EmulatedChip

    @Before fun setUp() { chip = EmulatedChip() }
    @After fun tearDown() { chip.close() }

    private fun expectedSuites(data: PassportData): List<ObservedPaceSuite> =
        data.chipConfiguration().paceSuites.map {
            ObservedPaceSuite(
                mapping = when (it.mapping) {
                    PaceMapping.GENERIC -> "GM"
                    PaceMapping.INTEGRATED -> "IM"
                    PaceMapping.CHIP_AUTHENTICATION -> "CAM"
                },
                cipher = it.spec.cipher.displayName,
                parameterId = it.spec.parameterId
            )
        }

    @Test
    fun `every profile's facts behind authentication come back from its capture`() {
        val failures = mutableListOf<String>()
        ChipProfiles.all.forEach { profile ->
            val data = chip.documentFor(profile)
            val config = data.chipConfiguration()
            val record = chip.record(data)
            val auth = record.authenticated
            if (auth == null) {
                failures += "${profile.id}: no authenticated part"
                return@forEach
            }
            val facts = AuthenticatedProfileRecovery.recover(record)

            if (record.aborted != null) failures += "${profile.id}: aborted ${record.aborted}"
            if (auth.accessStopped != null) failures += "${profile.id}: access stopped ${auth.accessStopped}"
            if (facts.acceptedPaceSuites != expectedSuites(data)) failures += "${profile.id}: PACE suites ${facts.acceptedPaceSuites} != ${expectedSuites(data)}"
            if (facts.paceSuitesRefused.isNotEmpty()) failures += "${profile.id}: refused ${facts.paceSuitesRefused}"
            if (facts.bacAccepted != config.accessControl.supportsBac) failures += "${profile.id}: BAC ${facts.bacAccepted}, profile ${config.accessControl.supportsBac}"
            if (config.paceSuites.any { it.mapping == PaceMapping.CHIP_AUTHENTICATION } != facts.camVerified) failures += "${profile.id}: CAM verified ${facts.camVerified}"
            if (auth.readUnder != 0) failures += "${profile.id}: read under ${auth.readUnder}"

            if (facts.sodDigestAlgorithm != profile.sod.digestAlgorithm) failures += "${profile.id}: digest ${facts.sodDigestAlgorithm} != ${profile.sod.digestAlgorithm}"
            when (val key = profile.sod.signerKey) {
                is KeySpec.Rsa -> if (facts.signerKeyType != "RSA" || facts.signerKeyBits != key.bits) failures += "${profile.id}: signer ${facts.signerKeyType}/${facts.signerKeyBits}, profile RSA-${key.bits}"
                is KeySpec.Ec -> if (facts.signerKeyType != "EC") failures += "${profile.id}: signer ${facts.signerKeyType}, profile EC"
            }
            val sigAlgorithm = auth.sod?.signatureAlgorithm.orEmpty()
            if (sigAlgorithm.isEmpty() || !sigAlgorithm.uppercase().contains(profile.sod.digestAlgorithm.replace("-", ""))) {
                failures += "${profile.id}: SOD signature algorithm '$sigAlgorithm' doesn't name ${profile.sod.digestAlgorithm}"
            }
            if (auth.sod?.hashedDataGroups != config.dataGroups.sorted()) failures += "${profile.id}: SOD hashes ${auth.sod?.hashedDataGroups}"

            if (facts.dataGroupsPresent != config.dataGroups.sorted()) failures += "${profile.id}: data groups ${facts.dataGroupsPresent} != ${config.dataGroups}"
            if (auth.com?.dataGroups != config.dataGroups.sorted()) failures += "${profile.id}: EF.COM lists ${auth.com?.dataGroups}"
            auth.dataGroups.forEach { dg ->
                if (dg.inCom != (dg.number in config.dataGroups) || dg.inSod != (dg.number in config.dataGroups)) failures += "${profile.id}: DG${dg.number} COM/SOD flags"
            }
            if (config.terminalAuthentication) {
                if (facts.dg3ReadStatusWord != 0x6982) failures += "${profile.id}: DG3 read ${facts.dg3ReadStatusWord?.toString(16)}, expected 6982"
            }
            if (3 !in config.dataGroups && facts.dg3ReadStatusWord != null) failures += "${profile.id}: DG3 readable but not in the profile"

            if ((facts.activeAuthentication != null) != (config.activeAuthentication != null)) failures += "${profile.id}: AA present ${facts.activeAuthentication != null}"
            if (config.activeAuthentication != null) {
                if (facts.activeAuthentication?.succeeded != true) failures += "${profile.id}: AA ${facts.activeAuthentication}"
                when (val key = config.activeAuthentication) {
                    is KeySpec.Rsa -> if (facts.aaKeyType != "RSA" || facts.aaKeyBits != key.bits) failures += "${profile.id}: AA key ${facts.aaKeyType}/${facts.aaKeyBits}"
                    is KeySpec.Ec -> if (facts.aaKeyType != "EC") failures += "${profile.id}: AA key ${facts.aaKeyType}"
                    null -> Unit
                }
            }
            if ((facts.chipAuthentication != null) != (config.chipAuthentication != null)) failures += "${profile.id}: CA present ${facts.chipAuthentication != null}"
            if (config.chipAuthentication != null && facts.chipAuthentication?.succeeded != true) failures += "${profile.id}: CA ${facts.chipAuthentication}"
            if (facts.hasCvca != config.terminalAuthentication) failures += "${profile.id}: CVCA present ${facts.hasCvca}, TA ${config.terminalAuthentication}"
            if ((auth.dg14 != null) != (14 in config.dataGroups)) failures += "${profile.id}: DG14 observed ${auth.dg14 != null}"
            val hasCam = config.paceSuites.any { it.mapping == PaceMapping.CHIP_AUTHENTICATION }
            if ((auth.cardSecurity != null) != hasCam) failures += "${profile.id}: EF.CardSecurity observed ${auth.cardSecurity != null}, CAM $hasCam"
            if (auth.cardSecurity != null && !auth.cardSecurity!!.paceCamPresent) failures += "${profile.id}: CardSecurity shows no PACE-CAM"
            if (config.chipAuthentication != null && auth.dg14?.infos?.any { it.kind == com.hddev.smartemu.capture.SecurityInfoKind.CHIP_AUTHENTICATION } != true) {
                failures += "${profile.id}: DG14 shows no Chip Authentication info"
            }
            if (config.terminalAuthentication && auth.dg14?.infos?.any { it.kind == com.hddev.smartemu.capture.SecurityInfoKind.TERMINAL_AUTHENTICATION } != true) {
                failures += "${profile.id}: DG14 shows no Terminal Authentication info"
            }
            if ((auth.dg15 != null) != (15 in config.dataGroups)) failures += "${profile.id}: DG15 observed ${auth.dg15 != null}"

            println(
                "round trip ${profile.id}: PACE ${facts.acceptedPaceSuites.joinToString { "${it.mapping}/${it.cipher}/${it.parameterId}" }}" +
                    " BAC=${facts.bacAccepted} DG=${facts.dataGroupsPresent} AA=${facts.activeAuthentication?.algorithm} CA=${facts.chipAuthentication?.algorithm}" +
                    " SOD=${auth.sod?.digestAlgorithm}/${auth.sod?.signatureAlgorithm} signer=${facts.signerKeyType}${facts.signerKeyBits}"
            )
            if (!CaptureFingerprint.isConsistent(record)) failures += "${profile.id}: fingerprint inconsistent"
            val lint = RedactionLint.check(CaptureJson.encode(record))
            if (lint.isNotEmpty()) failures += "${profile.id}: lint $lint"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the profiles exercise every kind of chip this phase reads`() {
        val configs = ChipProfiles.all.map { chip.documentFor(it).chipConfiguration() }
        assertTrue(configs.any { c -> c.paceSuites.any { it.mapping == PaceMapping.CHIP_AUTHENTICATION } }, "PACE-CAM")
        assertTrue(configs.any { c -> c.paceSuites.any { it.mapping == PaceMapping.GENERIC } }, "PACE-GM")
        assertTrue(configs.any { c -> c.paceSuites.any { it.mapping == PaceMapping.INTEGRATED } }, "PACE-IM")
        assertTrue(configs.any { it.accessControl.supportsBac && !it.accessControl.supportsPace }, "BAC only")
        assertTrue(configs.any { it.chipAuthentication != null }, "Chip Authentication")
        assertTrue(configs.any { it.activeAuthentication is KeySpec.Ec }, "ECDSA Active Authentication")
        assertTrue(configs.any { it.activeAuthentication is KeySpec.Rsa }, "RSA Active Authentication")
        assertTrue(configs.any { it.terminalAuthentication }, "Terminal Authentication and DG3")
        assertTrue(configs.any { 11 in it.dataGroups }, "DG11")
    }

    @Test
    fun `an authenticated capture sees every aspect of a profile but the MRZ`() {
        val observable = AuthenticatedProfileRecovery.observability.filterValues { it.first != ProfileRecovery.Visibility.NOT_OBSERVABLE }.keys
        assertEquals(ProfileAspect.entries.toSet(), observable + AuthenticatedProfileRecovery.unobservable)
        assertEquals(setOf(ProfileAspect.MRZ), AuthenticatedProfileRecovery.unobservable)
        // What was invisible to the unauthenticated capture is now seen, in whole or in part
        listOf(
            ProfileAspect.ACTIVE_AUTHENTICATION, ProfileAspect.EXTENDED_ACCESS_CONTROL, ProfileAspect.DATA_GROUPS, ProfileAspect.SIGNATURE
        ).forEach { assertTrue(it in ProfileRecovery.unobservable && it !in AuthenticatedProfileRecovery.unobservable, it.name) }
        println("Observable from an authenticated capture:")
        AuthenticatedProfileRecovery.observability.forEach { (aspect, why) -> println("  ${aspect.displayName}: ${why.first} - ${why.second}") }
    }
}
