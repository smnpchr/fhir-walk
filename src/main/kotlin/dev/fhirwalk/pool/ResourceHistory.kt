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
 * A label names one resource only where one file holds it. Where a commit's changed
 * files hold it more than once on either side -- two files, or twice in one bundle --
 * the commit says so ([Change.held]) and nothing is followed through it: which of
 * them was "the" resource would be a guess. As in [DeltaPairs], only what the commit
 * changed is looked at.
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

    /**
     * @param resource the resource as [commit] left it; null when [commit] deleted it,
     *   or when it is [held] more than once.
     * @param was the resource as it was before [commit], where one file held it; null
     *   when it was added, or held more than once.
     * @param held the files that held the label more than once on a side; null when
     *   each side held it once at most.
     */
    class Change(
        val commit: GitSource.Commit,
        val kind: GitSource.Kind,
        val resource: IBaseResource?,
        val was: IBaseResource? = null,
        val held: Held? = null,
    )

    /** The files holding a label in a commit, before and after, one entry per copy. */
    class Held(val before: List<String>, val after: List<String>) {
        /** How many copies the fuller side held. */
        val most: Int get() = maxOf(before.size, after.size)
    }

    /** One copy of the label in one file. */
    private class Copy(val path: String, val resource: IBaseResource)

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
        fun resourcesIn(commit: GitSource.Commit, path: String, blob: ObjectId): List<Copy> {
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
            return parse.resources.map { Copy(path, it) }
        }
        return line.asSequence().mapNotNull { commit ->
            val changed = changes(commit)
            val before = changed.flatMap { change -> change.oldBlob?.let { resourcesIn(commit, change.path, it) }.orEmpty() }
            val after = changed.flatMap { change -> change.newBlob?.let { resourcesIn(commit, change.path, it) }.orEmpty() }
            val kind = when {
                before.isEmpty() -> GitSource.Kind.ADDED
                after.isEmpty() -> GitSource.Kind.DELETED
                else -> GitSource.Kind.MODIFIED
            }
            when {
                before.isEmpty() && after.isEmpty() -> null
                before.size > 1 || after.size > 1 ->
                    // Every copy reads as it did: another resource in their files changed.
                    if (encoded(before) == encoded(after)) null
                    else Change(commit, kind, null, held = Held(before.map { it.path }, after.map { it.path }))
                before.isEmpty() -> Change(commit, kind, after.single().resource)
                after.isEmpty() -> Change(commit, kind, null, was = before.single().resource)
                // Another resource in the same file changed, or this one moved file.
                same(before.single().resource, after.single().resource) -> null
                else -> Change(commit, kind, after.single().resource, was = before.single().resource)
            }
        }
    }

    private fun same(one: IBaseResource, other: IBaseResource): Boolean =
        json.encodeResourceToString(one) == json.encodeResourceToString(other)

    /** The copies' content, whatever files hold them. */
    private fun encoded(copies: List<Copy>): List<String> = copies.map { json.encodeResourceToString(it.resource) }.sorted()

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
