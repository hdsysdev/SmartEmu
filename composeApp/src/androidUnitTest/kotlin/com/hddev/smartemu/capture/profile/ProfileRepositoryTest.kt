package com.hddev.smartemu.capture.profile

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileRepositoryTest {
    private val directories = mutableListOf<File>()

    private fun tempDirectory(): File = kotlin.io.path.createTempDirectory("profiles-test").toFile().also { directories += it }

    @AfterTest
    fun tearDown() = directories.forEach { it.deleteRecursively() }

    @Test
    fun `saves a profile as its id dot json and loads it back`() {
        val repository = ProfileRepository(tempDirectory())
        val profile = GoldenMeasuredProfile.profile
        val file = repository.save(profile)
        assertEquals("golden-profile.json", file.name)
        val result = repository.load()
        assertEquals(listOf(profile), result.profiles)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `a file that does not decode, or is named for another profile, is reported and not loaded`() {
        val directory = tempDirectory()
        val repository = ProfileRepository(directory)
        repository.save(GoldenMeasuredProfile.profile)
        File(directory, "broken.json").writeText("{ not json")
        File(directory, "extra-field.json").writeText(MeasuredProfileJson.encode(GoldenMeasuredProfile.profile.copy(id = "extra-field")).replaceFirst("{", "{\"mrz\": \"x\","))
        File(directory, "wrong-name.json").writeText(MeasuredProfileJson.encode(GoldenMeasuredProfile.profile))
        File(directory, "notes.txt").writeText("ignored")
        val result = repository.load()
        assertEquals(listOf("golden-profile"), result.profiles.map { it.id })
        assertEquals(setOf("broken.json", "extra-field.json", "wrong-name.json"), result.errors.map { it.file }.toSet())
        assertTrue(result.errors.single { it.file == "wrong-name.json" }.message.contains("does not match"))
    }

    @Test
    fun `a missing directory holds no profiles`() {
        assertEquals(ProfileLoadResult(emptyList(), emptyList()), ProfileRepository(File(tempDirectory(), "nope")).load())
    }

    @Test
    fun `the repository's profiles directory loads cleanly, and follows the naming convention`() {
        val result = ProfileRepository(File("../profiles")).load()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString())
        result.profiles.forEach { assertTrue(Regex("[a-z0-9][a-z0-9-]*").matches(it.id), it.id) }
    }

    @Test
    fun `profiles can be decoded from texts for assets and resources`() {
        val texts = listOf(MeasuredProfileJson.encode(GoldenMeasuredProfile.profile))
        assertEquals(listOf(GoldenMeasuredProfile.profile), ProfileRepository.fromTexts(texts))
    }
}
