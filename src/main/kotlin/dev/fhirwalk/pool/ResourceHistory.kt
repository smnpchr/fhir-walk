package dev.fhirwalk.pool

import ca.uhn.fhir.context.FhirContext
import org.eclipse.jgit.lib.ObjectId
import org.hl7.fhir.instance.model.api.IBaseResource

/**
 * One resource's history along a line: the commits that added, changed or deleted
 * it, found from what each commit changed rather than by loading every revision.
 *
 * A changed file is parsed only when its text holds the resource's id (or its
 * fullUrl, for a resource with none): a cheap test that every file holding the
 * resource passes, so most of a commit's files are never parsed. A file can hold
 * several resources -- a bundle, NDJSON -- so a change to the file is a change to
 * the resource only when the resource itself reads differently on the two sides.
 *
 * @param changes what a commit changed, as [GitSource.changes] has it for the files
 *   the importer reads; passed in so a caller can keep what it already asked.
 * @param problem told of a file that held the resource's id but could not be read
 *   or parsed. The walk goes on, as a load does, and the resource reads as absent
 *   from that file -- which is what a pool at that revision would hold.
 */
class ResourceHistory(
    private val source: GitSource,
    private val importer: Importer,
    private val ctx: FhirContext,
    private val changes: (GitSource.Commit) -> List<GitSource.Change>,
    private val problem: (String) -> Unit = {},
) {

    /** @param resource the resource as [commit] left it; null when [commit] deleted it. */
    class Change(val commit: GitSource.Commit, val kind: GitSource.Kind, val resource: IBaseResource?)

    private val json by lazy { ctx.newJsonParser() }

    /**
     * What happened to [label] in each commit of [line] (newest first) that touched
     * it, newest first and found as the sequence is read, so a page of a long history
     * stops reading once it is full.
     *
     * Only a label that names the resource in every revision works: `Type/id`, where
     * the id may be one HAPI took from a fullUrl (`Patient/urn:uuid:…`). A resource
     * with neither is labelled by its place in one pool.
     */
    fun of(label: String, line: List<GitSource.Commit>): Sequence<Change> {
        val needle = label.substringAfter('/')
        // For this walk only: a blob read as one format is the same parse in every commit
        // that holds it. Kept with the path it was parsed as, to say its problems under another.
        class Parse(val path: String, val resources: List<IBaseResource>, val problems: List<String>)
        val holding = HashMap<Pair<ObjectId, String>, Parse>()
        // Each file is told about once, at the first (newest) commit that holds it, under its own name.
        val told = HashSet<Pair<ObjectId, String>>()
        fun resourcesIn(commit: GitSource.Commit, path: String, blob: ObjectId): List<IBaseResource> {
            val parse = holding.getOrPut(blob to importer.formatOf(path)) {
                val text = runCatching { source.read(blob) }.getOrElse {
                    return@getOrPut Parse(path, emptyList(), listOf("$path: unreadable (${it.message})"))
                }
                if (!text.contains(needle)) return@getOrPut Parse(path, emptyList(), emptyList())
                val report = Importer.Report()
                val parsed = runCatching { importer.parse(path, text, report) }.getOrElse {
                    report.problems += "$path: unreadable (${it.message})"
                    emptyList()
                }
                Parse(path, parsed.filter { it.label(ctx) == label }.map { it.resource }, report.problems)
            }
            if (told.add(blob to path)) {
                parse.problems.forEach { text ->
                    problem("at ${commit.short}: " + if (text.startsWith(parse.path)) path + text.removePrefix(parse.path) else text)
                }
            }
            return parse.resources
        }
        return line.asSequence().mapNotNull { commit ->
            val changed = changes(commit)
            val before = changed.flatMap { change -> change.oldBlob?.let { resourcesIn(commit, change.path, it) }.orEmpty() }
            val after = changed.flatMap { change -> change.newBlob?.let { resourcesIn(commit, change.path, it) }.orEmpty() }
            when {
                before.isEmpty() && after.isEmpty() -> null
                before.isEmpty() -> Change(commit, GitSource.Kind.ADDED, after.first())
                after.isEmpty() -> Change(commit, GitSource.Kind.DELETED, null)
                // Another resource in the same file changed, or this one moved file.
                same(before.first(), after.first()) -> null
                else -> Change(commit, GitSource.Kind.MODIFIED, after.first())
            }
        }
    }

    private fun same(one: IBaseResource, other: IBaseResource): Boolean =
        json.encodeResourceToString(one) == json.encodeResourceToString(other)

    companion object {
        /**
         * Whether [word] names a resource the way a history can follow it, rather
         * than starting an expression: `Specimen/30012`, `Patient/urn:uuid:7f1c`. No
         * expression is written that way -- it would start with a type divided by
         * something -- and nothing with a bracket or a quote in it is a label.
         */
        fun isLabel(word: String): Boolean = LABEL.matches(word)

        private val LABEL = Regex("[A-Z][A-Za-z]*/[^\\s()\\[\\]'\"]+")
    }
}
