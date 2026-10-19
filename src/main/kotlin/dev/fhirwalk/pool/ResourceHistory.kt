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
 */
class ResourceHistory(
    private val source: GitSource,
    private val importer: Importer,
    private val ctx: FhirContext,
    private val changes: (GitSource.Commit) -> List<GitSource.Change>,
) {

    /** @param resource the resource as [commit] left it; null when [commit] deleted it. */
    class Change(val commit: GitSource.Commit, val kind: GitSource.Kind, val resource: IBaseResource?)

    private val json by lazy { ctx.newJsonParser() }

    /**
     * What happened to [label] in each commit of [line] (newest first) that touched
     * it, newest first and found as the sequence is read, so a page of a long history
     * stops reading once it is full.
     *
     * Only a label that names the resource in every revision works: `Type/id`, or a
     * fullUrl. A resource with neither is labelled by its place in one pool.
     */
    fun of(label: String, line: List<GitSource.Commit>): Sequence<Change> {
        val needle = if (label.contains("://") || label.startsWith("urn:")) label else label.substringAfter('/')
        // For this walk only: a blob is the same file in every commit that holds it.
        val holding = HashMap<ObjectId, List<IBaseResource>>()
        fun resourcesIn(path: String, blob: ObjectId): List<IBaseResource> = holding.getOrPut(blob) {
            val text = source.read(blob)
            if (!text.contains(needle)) return@getOrPut emptyList()
            val parsed = runCatching { importer.parse(path, text, Importer.Report()) }.getOrDefault(emptyList())
            parsed.filter { it.label(ctx) == label }.map { it.resource }
        }
        return line.asSequence().mapNotNull { commit ->
            val changed = changes(commit)
            val before = changed.flatMap { change -> change.oldBlob?.let { resourcesIn(change.path, it) }.orEmpty() }
            val after = changed.flatMap { change -> change.newBlob?.let { resourcesIn(change.path, it) }.orEmpty() }
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
         * than starting an expression: `Specimen/30012`, or a fullUrl.
         */
        fun isLabel(word: String): Boolean =
            LABEL.matches(word) || word.startsWith("urn:") || word.contains("://")

        private val LABEL = Regex("[A-Z][A-Za-z]*/[A-Za-z0-9\\-.]{1,64}")
    }
}
