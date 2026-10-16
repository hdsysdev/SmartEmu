package com.hddev.smartemu.capture.intake

import java.io.File
import kotlin.system.exitProcess

/**
 * The command line of the maintainer's intake, run on the unit-test classpath by `:composeApp:captureIntake` (which
 * tools/capture-intake/intake.sh calls). The tool is in the test source set because the hold-out rule and the fidelity
 * diff need the JVM emulator harness (`MeasuredEmulator`), which is test code.
 *
 * Exit status: 0 when it ran (the report's last line says RESULT: CLEAN, REJECTIONS or DEVIATIONS), 1 for unusable arguments or
 * setup. The emulator logs to standard output, so for the run standard output is pointed at standard error and the report
 * alone is printed to the real standard output.
 */
object IntakeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val report = System.out
        System.setOut(System.err)
        val code = try {
            run(args.toList(), out = { report.print(it); report.flush() })
        } catch (e: IntakeSetupException) {
            System.err.println("intake: ${e.message}")
            1
        } catch (e: IllegalArgumentException) {
            System.err.println("intake: ${e.message}")
            System.err.println(USAGE)
            1
        }
        exitProcess(code)
    }

    /** Runs the intake for [args] and prints its report. Returns the exit status. */
    fun run(args: List<String>, out: (String) -> Unit = ::print): Int {
        val options = parse(args)
        val result = CaptureIntake(options).run()
        out(result.report + "RESULT: ${result.status}\n")
        return 0
    }

    fun parse(args: List<String>): IntakeOptions {
        var input: File? = null
        var store: File? = null
        var repo: File? = null
        var apply = false
        val links = mutableMapOf<String, String>()
        val iterator = args.iterator()
        fun value(flag: String): String = if (iterator.hasNext()) iterator.next() else throw IllegalArgumentException("$flag needs a value")
        while (iterator.hasNext()) {
            when (val flag = iterator.next()) {
                "--input" -> input = File(value(flag))
                "--store" -> store = File(value(flag))
                "--repo" -> repo = File(value(flag))
                "--apply" -> apply = true
                "--link" -> value(flag).split('=', limit = 2).let {
                    require(it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank()) { "--link takes GENERATION=PROFILE-ID" }
                    links[it[0].uppercase()] = it[1]
                }
                else -> throw IllegalArgumentException("Unknown argument $flag")
            }
        }
        return IntakeOptions(
            inputDir = requireNotNull(input) { "--input is required" },
            storeDir = requireNotNull(store) { "--store is required" },
            repoDir = requireNotNull(repo) { "--repo is required" },
            apply = apply,
            links = links
        )
    }

    private const val USAGE = "usage: --input DIR --store DIR --repo DIR [--apply] [--link GENERATION=PROFILE-ID]"
}
