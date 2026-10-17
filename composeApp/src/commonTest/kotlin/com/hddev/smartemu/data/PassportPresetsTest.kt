package com.hddev.smartemu.data

import com.hddev.smartemu.capture.ProbePackRef
import com.hddev.smartemu.capture.profile.MeasuredObservations
import com.hddev.smartemu.capture.profile.MeasuredProfile
import com.hddev.smartemu.capture.profile.MeasuredProfileRegistry
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PassportPresetsTest {

    private val today = LocalDate(2026, 10, 16)

    private fun measured(id: String, linkedTo: String?) = MeasuredProfile(
        id = id,
        chipProfileId = linkedTo,
        generation = "gen-$id",
        probePack = ProbePackRef("tier-a", 2),
        sampleCount = 2,
        captureIds = listOf("a", "b"),
        observations = MeasuredObservations(bacGetChallenge = 0x9000)
    )

    @AfterTest
    fun clearRegistry() = MeasuredProfileRegistry.clear()

    @Test
    fun withNothingCapturedTheSampledCountriesComeFirstThenTheOthersThenTheMadeUpCases() {
        val groups = PassportPresets.grouped.map { it.first }
        assertEquals(listOf(PresetGroup.SAMPLED, PresetGroup.COUNTRIES, PresetGroup.EVERYDAY), groups.take(3))
        assertTrue(PresetGroup.CAPTURED !in groups)
    }

    @Test
    fun aCountryPresetIsSampledExactlyWhenItsProfileRestsOnARealChip() {
        val countries = PassportPresets.all.filter { it.group.countryProfiles }
        countries.forEach { preset ->
            val profile = ChipProfiles.byId(preset.id.removePrefix("profile-"))
            assertEquals(profile.sampledFromRealChip, preset.group == PresetGroup.SAMPLED, preset.id)
        }
        assertTrue(countries.any { it.id == "profile-ca-passport-2023" && it.group == PresetGroup.SAMPLED })
        assertTrue(countries.any { it.id == "profile-de-passport-2017" && it.group == PresetGroup.COUNTRIES })
    }

    @Test
    fun capturedProfilesComeFirstThenTheSampledOnesThenTheOtherCountries() {
        MeasuredProfileRegistry.link(listOf(measured("de-passport-2017", "de-passport-2017"), measured("xx-card", null)))

        val groups = PassportPresets.grouped
        assertEquals(listOf(PresetGroup.CAPTURED, PresetGroup.SAMPLED, PresetGroup.COUNTRIES), groups.take(3).map { it.first })
        val captured = groups.first().second
        assertEquals(listOf("Germany · passport (2017 design)", "Measured chip xx-card"), captured.map { it.title }.sorted())
        captured.forEach { preset ->
            val document = preset.build(today)
            assertTrue(ChipProfiles.byId(document.chipProfileId).measured != null, "${preset.id} replays what was measured")
        }
    }
}
