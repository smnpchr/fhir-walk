package dev.fhirwalk

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.TimeZone

/**
 * A sync registry, built in a temp directory: a git repository can't be committed
 * inside this one. Resources live one per file under `import/<Type>/<id>.json`.
 *
 * `master` holds a local LIMS export as imported; `branch/out` what the rules made of
 * it for the central system:
 *
 * ```
 * master      m1 import ──────────── m2 import (serum, saliva, PBMC, a dummy patient)
 *               │
 * branch/out    └ out1 sync ── out2 sync ── out3 reapply ── out4 fixup
 *                 11 mapped    + 13         ~ 3 citrate    - dummy
 * ```
 *
 * Masters are aliquoted through aliquot groups, and only a master records the tube
 * its blood was drawn into. out1 and out2 map every `PLASMA` aliquot to
 * `EDTA_PLASMA`, as a rule that sees only the aliquot must. out3 re-applies the fixed
 * rule, which looks two parents up to the master's tube: only the three samples from
 * the citrate tube change, to `CITRATE_PLASMA`.
 */
class RegistryFixture(val root: Path = Files.createTempDirectory("registry")) {

    val import: Path = root.resolve("import")
    private val git = Git.init().setDirectory(root.toFile()).setInitialBranch("master").call()
    private var clock = Instant.parse("2026-10-01T08:00:00Z")

    private class Sample(val id: String, val category: String, val type: String, val patient: String, val parent: String?, val tube: String?)

    private val samples: Map<String, Sample> = listOf(
        master("30001", "BLOOD", "2001", "EDTA"),
        group("30002", "PLASMA", "2001", "30001"),
        aliquot("30003", "PLASMA", "2001", "30002"),
        aliquot("30004", "PLASMA", "2001", "30002"),
        aliquot("30005", "PLASMA", "2001", "30002"),
        group("30006", "BUFFY", "2001", "30001"),
        aliquot("30007", "BUFFY", "2001", "30006"),
        master("30010", "BLOOD", "2001", "CITRATE"),
        group("30011", "PLASMA", "2001", "30010"),
        aliquot("30012", "PLASMA", "2001", "30011"),
        aliquot("30013", "PLASMA", "2001", "30011"),
        master("30020", "BLOOD", "2001", "SERUM"),
        group("30021", "SERUM", "2001", "30020"),
        aliquot("30022", "SERUM", "2001", "30021"),
        aliquot("30023", "SERUM", "2001", "30021"),
        master("30030", "SALIVA", "2001", null),
        master("30040", "BLOOD", "2002", "EDTA"),
        group("30041", "PBMC", "2002", "30040"),
        aliquot("30042", "PBMC", "2002", "30041"),
        group("30043", "PBMC_PELLET", "2002", "30042"),
        aliquot("30044", "PBMC_PELLET", "2002", "30043"),
        aliquot("30045", "PBMC_PELLET", "2002", "30043"),
    ).associateBy { it.id }

    val m1: RevCommit
    val m2: RevCommit
    val out1: RevCommit
    val out2: RevCommit
    val out3: RevCommit
    val out4: RevCommit

    init {
        write("README.md", "not FHIR, and not under import/")
        write("import/notes.txt", "not FHIR either")
        writePatient("2001", "Muster")
        FIRST.forEach { writeSpecimen(it, central = null) }
        m1 = commit("import")

        git.branchCreate().setName("branch/out").call()
        git.checkout().setName("branch/out").call()
        FIRST.forEach { writeSpecimen(it, central = false) }
        out1 = commit("sync")

        git.checkout().setName("master").call()
        writePatient("2002", "Beispiel")
        writePatient("9999", "Dummy")
        SECOND.forEach { writeSpecimen(it, central = null) }
        m2 = commit("import")

        git.checkout().setName("branch/out").call()
        writePatient("2002", "Beispiel")
        writePatient("9999", "Dummy")
        SECOND.forEach { writeSpecimen(it, central = false) }
        out2 = commit("sync")
        (FIRST + SECOND).forEach { writeSpecimen(it, central = true) }
        out3 = commit("sync")
        git.rm().addFilepattern("import/Patient/9999.json").call()
        out4 = commit("remove dummy patient")
        git.close()
    }

    val spec: String get() = "$import@branch/out"

    // ------------------------------------------------------------------- mapping

    /**
     * The central type for [sample].
     *
     * @param contextAware whether the rule may look past the sample itself. The tube
     *   is recorded on the master only, so without it every plasma aliquot becomes
     *   EDTA plasma, citrate ones included.
     */
    private fun centralType(sample: Sample, contextAware: Boolean): String {
        val tube = if (sample.category == "MASTER" || contextAware) masterOf(sample).tube else null
        return when (sample.type) {
            "BLOOD" -> when (tube) {
                "EDTA" -> "EDTA_WHOLE_BLOOD"
                "CITRATE" -> "CITRATE_WHOLE_BLOOD"
                else -> "SERUM_WHOLE_BLOOD"
            }
            "PLASMA" -> if (tube == "CITRATE") "CITRATE_PLASMA" else "EDTA_PLASMA"
            "BUFFY" -> "BUFFY_COAT"
            else -> sample.type
        }
    }

    private fun masterOf(sample: Sample): Sample =
        generateSequence(sample) { s -> s.parent?.let { samples.getValue(it) } }.last()

    // ------------------------------------------------------------------ the files

    /** @param central null for the local export; else whether the type rule is context-aware. */
    private fun writeSpecimen(id: String, central: Boolean?) {
        val sample = samples.getValue(id)
        val type = if (central == null) sample.type else centralType(sample, central)
        val system = if (central == null) LOCAL else CENTRAL
        val extensions = listOfNotNull(
            """{"url":"$EXT/sample-category","valueCode":"${sample.category}"}""",
            sample.tube?.let { """{"url":"$EXT/primary-container","valueCode":"$it"}""" },
        )
        val parent = sample.parent?.let { ""","parent":[{"reference":"Specimen/$it"}]""" }.orEmpty()
        write(
            "import/Specimen/$id.json",
            """{"resourceType":"Specimen","id":"$id","extension":[${extensions.joinToString(",")}],""" +
                """"type":{"coding":[{"system":"$system","code":"$type"}]},""" +
                """"subject":{"reference":"Patient/${sample.patient}"}$parent}""" + "\n",
        )
    }

    private fun writePatient(id: String, family: String) =
        write("import/Patient/$id.json", """{"resourceType":"Patient","id":"$id","name":[{"family":"$family"}]}""" + "\n")

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

    private fun master(id: String, type: String, patient: String, tube: String?) = Sample(id, "MASTER", type, patient, null, tube)

    private fun group(id: String, type: String, patient: String, parent: String) = Sample(id, "ALIQUOTGROUP", type, patient, parent, null)

    private fun aliquot(id: String, type: String, patient: String, parent: String) = Sample(id, "DERIVED", type, patient, parent, null)

    companion object {
        /** Extension URLs; the tube is a master's `primary-container`. */
        const val EXT = "http://example.org/fhir/StructureDefinition"
        const val LOCAL = "http://example.org/fhir/CodeSystem/local-sample-type"
        const val CENTRAL = "http://example.org/fhir/CodeSystem/central-sample-type"

        /** Patient 2001's EDTA and citrate blood, and everything aliquoted from it. */
        private val FIRST = listOf("30001", "30002", "30003", "30004", "30005", "30006", "30007", "30010", "30011", "30012", "30013")

        /** The serum and saliva, and patient 2002 with the PBMC chain. */
        private val SECOND = listOf("30020", "30021", "30022", "30023", "30030", "30040", "30041", "30042", "30043", "30044", "30045")
    }
}

/** The id as the REPL abbreviates it. */
val RevCommit.short: String get() = name.take(7)
