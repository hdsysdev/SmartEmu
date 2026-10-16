package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.GoldenCapture
import com.hddev.smartemu.data.ChipProfiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoverageMatrixTest {
    private val curated = ChipProfiles.all
    private val measured = GoldenMeasuredProfile.profile.copy(id = "m-de", chipProfileId = "de-passport-2017")
    private val single = ProfileMerger.merge(listOf(GoldenCapture.record), id = "m-nl", chipProfileId = "nl-passport")

    private fun row(report: CoverageReport, id: String) = assertNotNull(report.rows.firstOrNull { it.profileId == id }, id)

    @Test
    fun `with no captures every document is a row, with the status of its curated provenance`() {
        val report = CoverageMatrix.report(emptyList(), curated)
        assertTrue(report.rows.none { it.status.hasCaptures })
        assertEquals(0, report.rows.sumOf { it.n })
        // The generic profile fits any document and is not a target; the EU ones are stand-ins and are
        assertTrue(report.rows.none { it.profileId == ChipProfiles.GENERIC_ID })
        assertEquals("ANY", row(report, "eu-passport").country)
        assertEquals("DEU", row(report, "de-passport-2017").country)
        assertEquals("passport (2017 design)", row(report, "de-passport-2017").document)
        assertEquals(CoverageStatus.ASSUMED, row(report, "eu-passport").status)
    }

    @Test
    fun `a measured profile makes the row Measured with its n, a single capture makes it Captured n=1 and thin`() {
        val report = CoverageMatrix.report(listOf(measured, single), curated)
        val de = row(report, "de-passport-2017")
        assertEquals(CoverageStatus.MEASURED, de.status)
        assertEquals(3, de.n)
        assertEquals("Measured (n=3)", de.statusLabel)
        assertFalse(de.thin)
        val nl = row(report, "nl-passport")
        assertEquals(CoverageStatus.CAPTURED, nl.status)
        assertEquals("Captured (n=1)", nl.statusLabel)
        assertEquals(1, nl.n)
        assertTrue(nl.thin)
        assertTrue(report.unlinked.isEmpty())
    }

    @Test
    fun `documents with no captures rank first, the least established first, and captured ones never get a rank to capture`() {
        val report = CoverageMatrix.report(listOf(measured, single), curated)
        val firstCaptured = report.rows.indexOfFirst { it.status.hasCaptures }
        assertTrue(report.rows.take(firstCaptured).none { it.status.hasCaptures })
        assertTrue(report.rows.drop(firstCaptured).all { it.status.hasCaptures })
        val uncaptured = report.rows.take(firstCaptured)
        assertEquals(uncaptured.sortedBy { -it.status.ordinal }.map { it.status }, uncaptured.map { it.status })
        assertEquals((1..report.rows.size).toList(), report.rows.map { it.captureRank })
        // Within the captured ones, the thinnest first
        assertTrue(report.rows.drop(firstCaptured).map { it.n } == report.rows.drop(firstCaptured).map { it.n }.sorted())
    }

    @Test
    fun `a measured profile no curated one claims is listed on its own`() {
        val report = CoverageMatrix.report(listOf(single.copy(chipProfileId = null), single.copy(id = "m-x", chipProfileId = "no-such-profile")), curated)
        assertEquals(listOf("m-nl", "m-x"), report.unlinked.map { it.profileId })
        assertTrue(report.rows.none { it.status.hasCaptures })
    }

    @Test
    fun `the table is marked generated and the JSON has the same rows in the same order`() {
        val text = CoverageMatrix.render(listOf(measured, single), curated)
        assertTrue(text.startsWith(CoverageMatrix.BEGIN_MARKER))
        assertTrue(text.trimEnd().endsWith(CoverageMatrix.END_MARKER))
        assertTrue(text.contains("GENERATED. Do not edit this table."))
        assertTrue(text.contains("| - | DEU | passport (2017 design) | `de-passport-2017` | Measured (n=3) | 3 |"))
        assertTrue(text.contains("| - | NLD | passport (2014 design) | `nl-passport` | Captured (n=1) (thin) | 1 |"))

        val rows = Json.parseToJsonElement(CoverageMatrix.renderJson(listOf(measured, single), curated)).jsonObject.getValue("rows").jsonArray
        assertEquals(CoverageMatrix.report(listOf(measured, single), curated).rows.map { it.profileId }, rows.map { it.jsonObject.getValue("profileId").toString().trim('"') })
    }

    @Test
    fun `output carries counts only, no capture id`() {
        val text = CoverageMatrix.render(listOf(measured, single), curated) + CoverageMatrix.renderJson(listOf(measured, single), curated)
        (measured.captureIds + single.captureIds).forEach { assertFalse(text.contains(it), "capture id $it is in the matrix") }
    }

    @Test
    fun `inject replaces the generated part and keeps the hand-written header, and appends when there are no markers`() {
        val first = CoverageMatrix.render(emptyList(), curated)
        val page = "# Coverage matrix\n\nHand-written header.\n\n$first\nFooter\n"
        val second = CoverageMatrix.render(listOf(measured), curated)
        val updated = CoverageMatrix.inject(page, second)
        assertTrue(updated.startsWith("# Coverage matrix\n\nHand-written header.\n\n"))
        assertTrue(updated.endsWith("\nFooter\n"))
        assertEquals(1, Regex(Regex.escape(CoverageMatrix.BEGIN_MARKER)).findAll(updated).count())
        assertTrue(updated.contains("Measured (n=3)"))
        assertEquals(updated, CoverageMatrix.inject(updated, second))
        assertTrue(CoverageMatrix.inject("# Header\n", first).contains(CoverageMatrix.BEGIN_MARKER))
    }

    @Test
    fun `the committed pages are what the profiles directory renders to`() {
        val profiles = ProfileRepository(File("../profiles")).profiles()
        val page = File("../${CoverageMatrix.DOC_FILE}")
        val json = File("../${CoverageMatrix.JSON_FILE}")
        val renderedPage = CoverageMatrix.inject(page.readText(), CoverageMatrix.render(profiles, curated))
        val renderedJson = CoverageMatrix.renderJson(profiles, curated)
        if (System.getenv("UPDATE_GENERATED_DOCS") == "1") {
            page.writeText(renderedPage)
            json.writeText(renderedJson)
        }
        assertEquals(renderedPage, page.readText(), "docs/coverage-matrix.md is out of date: run with UPDATE_GENERATED_DOCS=1 to regenerate it")
        assertEquals(renderedJson, json.readText(), "docs/coverage-matrix.json is out of date: run with UPDATE_GENERATED_DOCS=1 to regenerate it")
    }
}
