package dev.fhirwalk

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.TimeZone
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

/**
 * A specimen pipe's registry, built in a temp directory from `samples/hdrp`: a git
 * repository can't be committed inside this one. Resources live one per file under
 * `import/<Type>/<id>.json`, as fhir-pipes writes them.
 *
 * `master` holds the local HDRP export as imported; `branch/out` what the rules made
 * of it for the central system, in the NUM vocabulary:
 *
 * ```
 * master      m1 import ──────────── m2 import (serum, saliva, PBMC, a dummy patient)
 *               │
 * branch/out    └ out1 sync ── out2 sync ── out3 reapply ── out4 fixup
 *                 11 mapped    + 13         ~ 3 citrate    - dummy
 * ```
 *
 * out1 and out2 map every `PL1` aliquot to EDTA plasma, as a rule that sees only the
 * aliquot must. out3 re-applies the fixed rule, which looks two parents up to the
 * master's primary container: only the three citrate samples change, to CIT.
 */
class RegistryFixture(val root: Path = Files.createTempDirectory("registry")) {

    val import: Path = root.resolve("import")
    private val git = Git.init().setDirectory(root.toFile()).setInitialBranch("master").call()
    private var clock = Instant.parse("2026-10-01T08:00:00Z")
    private val json = ObjectMapper()

    /** The local export, by `Type/id`. */
    private val local: Map<String, ObjectNode> = readLocal(Paths.get("samples/hdrp"))

    val m1: RevCommit
    val m2: RevCommit
    val out1: RevCommit
    val out2: RevCommit
    val out3: RevCommit
    val out4: RevCommit

    init {
        write("README.md", "not FHIR, and not under import/")
        write("import/notes.txt", "not FHIR either")
        FIRST.forEach { writeResource(local.getValue(it)) }
        m1 = commit("import")

        git.branchCreate().setName("branch/out").call()
        git.checkout().setName("branch/out").call()
        FIRST.forEach { writeResource(central(it, contextAware = false)) }
        out1 = commit("sync")

        git.checkout().setName("master").call()
        SECOND.forEach { writeResource(local.getValue(it)) }
        writeResource(DUMMY)
        m2 = commit("import")

        git.checkout().setName("branch/out").call()
        SECOND.forEach { writeResource(central(it, contextAware = false)) }
        writeResource(DUMMY)
        out2 = commit("sync")
        (FIRST + SECOND).forEach { writeResource(central(it, contextAware = true)) }
        out3 = commit("sync")
        git.rm().addFilepattern("import/Patient/9999.json").call()
        out4 = commit("remove dummy patient")
        git.close()
    }

    val spec: String get() = "$import@branch/out"

    // ------------------------------------------------------------------- mapping

    /**
     * The local sample [key] as the central system wants it, after the HUB-to-DZHK
     * mapping in kairos-fhir-dsl-mapping-example: NUM sample types, the NUM
     * organisation unit, the NAPKON id as the sample id, NUM receptacles and
     * processing. A patient goes over as it is.
     *
     * @param contextAware whether the type rule may look past the sample itself. The
     *   primary container is recorded on the master only, so without it every PL1
     *   aliquot becomes EDTA plasma, citrate ones included.
     */
    private fun central(key: String, contextAware: Boolean): ObjectNode {
        val resource = local.getValue(key).deepCopy()
        if (resource["resourceType"].asText() != "Specimen") return resource
        val category = extension(resource, "$EXT/sampleCategory")!!["valueCoding"]["code"].asText()
        val coding = resource["type"]["coding"][0] as ObjectNode
        val type = coding["code"].asText()
        val receptacle = resource["container"][0]["identifier"][0]["value"].asText()
        val container = if (category == "MASTER" || contextAware) primaryContainer(masterOf(resource)) else null
        coding.put("code", numType(type, receptacle, container))
        coding.remove("display")
        (resource["container"][0]["identifier"][0] as ObjectNode)
            .put("value", if (category == "MASTER") "ORG" else "NUMCryoAliquot500")
        extension(resource, "$EXT/sample/organizationUnit")?.let {
            (it["valueReference"]["identifier"] as ObjectNode).put("value", "NUM_Hannover")
        }
        extension(resource, "$EXT/sprec")?.get("extension")?.forEach { sub ->
            if (sub["url"].asText() == "$EXT/sprec/stockProcessing") {
                (sub["valueCoding"] as ObjectNode).put("code", numProcessing(sub["valueCoding"]["code"].asText()))
            }
        }
        // The NAPKON id is the central sample id; the local one becomes external.
        if (category == "MASTER") {
            resource["identifier"].forEach { id ->
                val typeCoding = id["type"]["coding"][0] as ObjectNode
                typeCoding.put("code", if (typeCoding["code"].asText() == "SAMPLEID") "EXTSAMPLEID" else "SAMPLEID")
            }
        }
        return resource
    }

    /** As `toDzhkType` has it, for the types in the fixture. */
    private fun numType(type: String, receptacle: String, primaryContainer: String?): String = when {
        type == "BLD" && primaryContainer == "SST" -> "SER"
        type == "BLD" && primaryContainer == "PED" -> "EDTAWB"
        type == "BLD" && primaryContainer == "SCI" -> "CIT"
        type == "SAL" && receptacle == "StSali001" -> "NUM_speichel"
        type == "ZZZ(pbm)" -> "NUM_pbmc_edta"
        type == "SER" -> "SER"
        type == "PL1" && primaryContainer == "SCI" -> "CIT"
        type == "PL1" -> "EDTA"
        type == "BFF" -> "EDTABUF"
        type == "ZZZ(ppm)" -> "NUM_PBMC_C"
        else -> "Unbekannt (XXX)"
    }

    private fun numProcessing(code: String): String = if (code == "B(15)") "NUM_RT15min2000g" else "Sprec-X"

    private fun masterOf(specimen: ObjectNode): ObjectNode {
        var current = specimen
        while (true) {
            val parent = current["parent"]?.get(0)?.get("reference")?.asText() ?: return current
            current = local.getValue(parent)
        }
    }

    private fun primaryContainer(master: ObjectNode): String? =
        extension(master, "$EXT/sprec")?.get("extension")
            ?.firstOrNull { it["url"].asText() == "$EXT/sprec/primarySampleContainer" }
            ?.get("valueCoding")?.get("code")?.asText()

    private fun extension(resource: JsonNode, url: String): JsonNode? =
        resource["extension"]?.firstOrNull { it["url"].asText() == url }

    // ------------------------------------------------------------------ the files

    private fun readLocal(dir: Path): Map<String, ObjectNode> {
        val bundle = json.readTree(dir.resolve("00-bundle.json").toFile())["entry"] as ArrayNode
        val resources = bundle.map { it["resource"] as ObjectNode } +
            dir.resolve("aliquot-groups").listDirectoryEntries("*.json").map { json.readTree(it.toFile()) as ObjectNode } +
            dir.resolve("aliquots.ndjson").readLines().filter { it.isNotBlank() }.map { json.readTree(it) as ObjectNode }
        return resources.associateBy { "${it["resourceType"].asText()}/${it["id"].asText()}" }
    }

    private fun writeResource(resource: JsonNode) {
        write(
            "import/${resource["resourceType"].asText()}/${resource["id"].asText()}.json",
            json.writerWithDefaultPrettyPrinter().writeValueAsString(resource) + "\n",
        )
    }

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

    private companion object {
        const val EXT = "https://fhir.centraxx.de/extension"

        /** Patient 2001's EDTA and citrate blood, and everything aliquoted from it. */
        val FIRST = listOf("Patient/2001") +
            listOf("30001", "30002", "30003", "30004", "30005", "30006", "30007", "30010", "30011", "30012", "30013")
                .map { "Specimen/$it" }

        /** The serum and saliva, and patient 2002 with the PBMC chain. */
        val SECOND = listOf("Patient/2002") +
            listOf("30020", "30021", "30022", "30023", "30030", "30040", "30041", "30042", "30043", "30044", "30045")
                .map { "Specimen/$it" }

        /** Fetched by mistake, and removed by hand. */
        val DUMMY: ObjectNode = ObjectMapper().readTree(
            """{"resourceType":"Patient","id":"9999","name":[{"family":"Dummy","given":["Test"]}]}""",
        ) as ObjectNode
    }
}

/** The id as the REPL abbreviates it. */
val RevCommit.short: String get() = name.take(7)
