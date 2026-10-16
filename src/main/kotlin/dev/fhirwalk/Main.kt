package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.FhirVersionUnavailable
import dev.fhirwalk.repl.Repl
import kotlin.system.exitProcess

private const val USAGE = """
fhir-walk — compose and evaluate FHIRPath against a local pool of resources.

usage: fhir-walk [--fhir <version>] [path ...]

  --fhir <version>   FHIR version for this session (default: r4).
                     Available: %s
  path ...           files, bundles, NDJSON or directory trees to import at startup

Nothing leaves the machine: no engine endpoints, no package or terminology fetches.
"""

fun main(args: Array<String>) {
    var version = FhirVersion.DEFAULT
    val paths = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--help", "-h" -> {
                println(USAGE.trimStart().format(FhirVersion.ids()))
                return
            }
            "--fhir" -> {
                val value = args.getOrNull(++i) ?: fail("--fhir needs a version (${FhirVersion.ids()})")
                version = FhirVersion.parse(value) ?: fail("unknown FHIR version '$value' (${FhirVersion.ids()})")
            }
            else ->
                if (arg.startsWith("-")) fail("unknown option '$arg' — try --help") else paths += arg
        }
        i++
    }

    try {
        Repl(version, paths).run()
    } catch (e: FhirVersionUnavailable) {
        fail(e.message ?: "FHIR version unavailable")
    }
}

private fun fail(message: String): Nothing {
    System.err.println("fhir-walk: $message")
    exitProcess(2)
}
