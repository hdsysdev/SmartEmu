package com.hddev.smartemu.capture.transcript

import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureSource
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ProbePacks
import com.hddev.smartemu.capture.RedactionLint
import java.io.File
import kotlin.system.exitProcess

/**
 * Command lines for lab captures (docs/lab-captures.md), run on the unit-test classpath by the Gradle tasks
 * `:composeApp:captureExportPack` and `:composeApp:captureImportTranscript`.
 *
 *   export --packs tier-a/1[,tier-b/1] --out FILE [--no-extended] [--reader NAME] [--source PCSC|PROXMARK]
 *   import --transcript FILE --out DIR [--source PCSC|PROXMARK] [--packs tier-a/1,...] [--sacrificial]
 *
 * `import` writes DIR/<capture id>.json only if the record passes the redaction lint and has a consistent fingerprint:
 * the file is then ready for tools/capture-intake/intake.sh. Exit status 0, or 1 with a message on standard error.
 */
object LabMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        System.setOut(System.err)
        val code = try {
            run(args.toList()) { out.println(it) }
        } catch (e: Exception) {
            System.err.println("lab: ${e.message}")
            1
        }
        exitProcess(code)
    }

    fun run(args: List<String>, print: (String) -> Unit): Int {
        val command = args.firstOrNull() ?: throw IllegalArgumentException("usage: export|import ...")
        val options = parse(args.drop(1))
        return when (command) {
            "export" -> export(options, print)
            "import" -> import(options, print)
            else -> throw IllegalArgumentException("Unknown command $command (export or import)")
        }
    }

    private class Options(val values: Map<String, String>, val flags: Set<String>) {
        fun required(name: String) = values[name] ?: throw IllegalArgumentException("--$name is required")
    }

    private fun parse(args: List<String>): Options {
        val values = mutableMapOf<String, String>()
        val flags = mutableSetOf<String>()
        val iterator = args.iterator()
        while (iterator.hasNext()) {
            val argument = iterator.next()
            require(argument.startsWith("--")) { "Unexpected argument $argument" }
            val name = argument.removePrefix("--")
            if (name in setOf("no-extended", "sacrificial")) flags += name
            else values[name] = if (iterator.hasNext()) iterator.next() else throw IllegalArgumentException("--$name needs a value")
        }
        return Options(values, flags)
    }

    private fun packsOf(text: String): List<ProbePack> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
        val version = it.substringAfterLast('/', "").toIntOrNull() ?: throw IllegalArgumentException("'$it' is not pack/version, as in tier-a/1")
        ProbePacks.byId(it.substringBeforeLast('/'), version) ?: throw IllegalArgumentException("No published probe pack $it")
    }

    private fun sourceOf(name: String?): CaptureSource? = name?.let { n ->
        CaptureSource.entries.firstOrNull { it.name == n } ?: throw IllegalArgumentException("The source is PCSC or PROXMARK")
    }

    private fun export(options: Options, print: (String) -> Unit): Int {
        val packs = packsOf(options.required("packs"))
        val sheet = PackTranscriptExporter.render(
            packs, extendedLength = "no-extended" !in options.flags, reader = options.values["reader"],
            source = sourceOf(options.values["source"]) ?: CaptureSource.PCSC
        )
        val target = File(options.required("out"))
        target.absoluteFile.parentFile?.mkdirs()
        target.writeText(sheet)
        print("Wrote the command sheet for ${packs.joinToString { "${it.id} v${it.version}" }} to $target (${sheet.lines().count { it.startsWith(">") }} commands).")
        print("Send each command to the chip in order and write '< <response hex>' after it, then import the file (docs/lab-captures.md).")
        return 0
    }

    private fun import(options: Options, print: (String) -> Unit): Int {
        val transcript = File(options.required("transcript")).readText()
        val packs = options.values["packs"]?.let(::packsOf)
        val result = TranscriptImporter.import(transcript, sourceOf(options.values["source"]), packs, acknowledgeSacrificialCard = "sacrificial" in options.flags)
        val record = when (result) {
            is CaptureResult.Complete -> result.record
            is CaptureResult.Aborted -> throw IllegalStateException("The run was aborted (${result.reason}): the chip answered with a blocked status word, so no capture is written")
        }
        val text = CaptureJson.encode(record) + "\n"
        val violations = RedactionLint.check(text)
        check(violations.isEmpty()) { "The imported record fails the redaction lint, so it is not written: " + violations.joinToString("; ") { "${it.rule} ${it.path}" } }
        check(CaptureFingerprint.isConsistent(record)) { "The imported record's fingerprint is inconsistent" }
        val directory = File(options.required("out")).also { it.mkdirs() }
        val target = File(directory, "${record.captureId}.json")
        target.writeText(text)
        print("Imported a ${record.source} capture (generation ${record.fingerprint}, ${record.probes.sumOf { it.steps.size }} probe steps) to $target.")
        print("It passed the redaction lint. Share it for intake like any capture.")
        return 0
    }
}
