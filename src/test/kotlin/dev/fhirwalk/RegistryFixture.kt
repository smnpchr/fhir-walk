package dev.fhirwalk

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.TimeZone

/**
 * A small registry in the shape fhir-pipes writes, built in a temp directory: a git
 * repository can't be committed inside this one. Resources live one per file under
 * `import/<Type>/`.
 *
 * `master` holds what was imported, `branch/out` what the rules made of it:
 *
 * ```
 * master      m1 import ── m2 import (adds s3 and a dummy patient)
 *               │
 * branch/out    └ out1 sync ── out2 sync ── out3 reapply ── out4 fixup
 *                 s1, s2       + s3,        ~ s2 only      - dummy
 *                 mapped       + dummy
 * ```
 */
class RegistryFixture(val root: Path = Files.createTempDirectory("registry")) {

    val import: Path = root.resolve("import")
    private val git = Git.init().setDirectory(root.toFile()).setInitialBranch("master").call()
    private var clock = Instant.parse("2026-10-01T08:00:00Z")

    val m1: RevCommit
    val m2: RevCommit
    val out1: RevCommit
    val out2: RevCommit
    val out3: RevCommit
    val out4: RevCommit

    init {
        write("README.md", "not FHIR, and not under import/")
        write("import/notes.txt", "not FHIR either")
        write("import/Patient/p1.json", patient("p1"))
        write("import/Specimen/s1.json", specimen("s1", "PLASMA1CENTRIFUGATION"))
        write("import/Specimen/s2.json", specimen("s2", "STOOL1"))
        m1 = commit("import")

        git.branchCreate().setName("branch/out").call()
        git.checkout().setName("branch/out").call()
        write("import/Specimen/s1.json", specimen("s1", "EDTA_PLASMA"))
        write("import/Specimen/s2.json", specimen("s2", "STOOL"))
        out1 = commit("sync")

        git.checkout().setName("master").call()
        write("import/Specimen/s3.json", specimen("s3", "PLASMA1CENTRIFUGATION"))
        write("import/Patient/dummy.json", patient("dummy"))
        m2 = commit("import")

        git.checkout().setName("branch/out").call()
        write("import/Specimen/s3.json", specimen("s3", "EDTA_PLASMA"))
        write("import/Patient/dummy.json", patient("dummy"))
        out2 = commit("sync")
        write("import/Specimen/s2.json", specimen("s2", "STOOL", note = "rule changed"))
        out3 = commit("sync")
        git.rm().addFilepattern("import/Patient/dummy.json").call()
        out4 = commit("remove dummy patient")
        git.close()
    }

    val spec: String get() = "$import@branch/out"

    private fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun commit(message: String): RevCommit {
        clock = clock.plusSeconds(3600)
        val who = PersonIdent(PersonIdent("fixture", "fixture@example.org"), clock.toEpochMilli(), TimeZone.getTimeZone("UTC").rawOffset)
        git.add().addFilepattern(".").call()
        return git.commit().setMessage(message).setAuthor(who).setCommitter(who).call()
    }

    private fun patient(id: String) = """{"resourceType":"Patient","id":"$id","name":[{"family":"F-$id"}]}"""

    private fun specimen(id: String, code: String, note: String? = null) =
        """{"resourceType":"Specimen","id":"$id",""" +
            """"type":{"coding":[{"system":"urn:example:types","code":"$code"}]},""" +
            """"subject":{"reference":"Patient/p1"}""" +
            (note?.let { ""","note":[{"text":"$it"}]""" } ?: "") + "}"
}

/** The id as the REPL abbreviates it. */
val RevCommit.short: String get() = name.take(7)
