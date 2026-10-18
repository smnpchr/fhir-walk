package dev.fhirwalk

import dev.fhirwalk.model.FhirVersion
import dev.fhirwalk.model.FhirVersionUnavailable
import dev.fhirwalk.repl.KeyedStore
import dev.fhirwalk.repl.Repl
import dev.fhirwalk.repl.Workspaces
import kotlin.system.exitProcess

private const val USAGE = """
fhir-walk — compose and evaluate FHIRPath against a local pool of resources.

usage: fhir-walk [--workspace <name>] [--fresh] [--fhir <version>] [path ...]

  --workspace <name> keep this session's expressions, names, views and state
  -w <name>          apart, in ~/.fhir-walk/workspaces/<name> (default: ~/.fhir-walk)
  --fresh            start with an empty pool and no focus; the workspace's saved
                     pool and focus stay as they are until something changes
  --fhir <version>   FHIR version for this session (default: the one the workspace
                     was left on, else r4). Available: %s
  path ...           files, bundles, NDJSON or directory trees to import at startup,
                     on top of what the workspace restores

Nothing leaves the machine: no engine endpoints, no package or terminology fetches.
"""

fun main(args: Array<String>) {
    var version: FhirVersion? = null
    var workspace = Workspaces.DEFAULT
    var fresh = false
    val paths = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--help", "-h" -> {
                println(USAGE.trimStart().format(FhirVersion.ids()))
                return
            }
            "--workspace", "-w" -> {
                val value = args.getOrNull(++i) ?: fail("--workspace needs a name")
                if (!Workspaces.valid(value)) fail("not a valid workspace name '$value' — ${KeyedStore.RULE}")
                workspace = value
            }
            "--fresh" -> fresh = true
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
        Repl(version, paths, Workspaces.inHome(), workspace, fresh).run()
    } catch (e: FhirVersionUnavailable) {
        fail(e.message ?: "FHIR version unavailable")
    }
}

private fun fail(message: String): Nothing {
    System.err.println("fhir-walk: $message")
    exitProcess(2)
}
