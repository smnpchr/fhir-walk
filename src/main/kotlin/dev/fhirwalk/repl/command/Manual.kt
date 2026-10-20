package dev.fhirwalk.repl.command

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

/**
 * The text of `:help` and `:manual`, read from `help.yaml` beside this class: how
 * each command is written and what it does, in [groups], and the manual's [topics].
 * Kept apart from the code so the text can be edited as text.
 */
internal class Manual(val groups: List<Group>, val topics: List<Topic>) {

    /**
     * @param details more than [summary], for `:help <command>`; null when the
     *   summary says it all.
     * @param topic the `:manual` topic that explains it in context.
     */
    data class CommandDoc(val name: String, val args: String, val summary: String, val details: String?, val topic: String?) {
        /** `:load <path>`. */
        val usage: String get() = if (args.isEmpty()) name else "$name $args"
    }

    data class Group(val title: String, val commands: List<CommandDoc>)

    /** @param text lines as they are printed. */
    data class Topic(val name: String, val title: String, val text: List<String>)

    /** Every command, in the order `:help` lists them. */
    val commands: List<CommandDoc> get() = groups.flatMap { it.commands }

    fun command(name: String): CommandDoc? = commands.firstOrNull { it.name == name }

    fun topic(name: String): Topic? = topics.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Fails unless the manual documents exactly the commands [defined]: a command
     * missing here would be absent from `:help` and the TAB menu, an entry with no
     * command would offer one that does not exist.
     */
    fun requireDocuments(defined: Set<String>) {
        val documented = commands.map { it.name }.toSet()
        check(documented == defined) {
            "$RESOURCE and the commands disagree: undocumented ${defined - documented}, no such command ${documented - defined}"
        }
    }

    companion object {
        private const val RESOURCE = "help.yaml"

        /** What `:manual` takes for every topic at once. */
        const val ALL = "all"

        /** The manual shipped with the build. A broken one fails at startup, not at `:help`. */
        fun load(): Manual {
            val stream = Manual::class.java.getResourceAsStream(RESOURCE)
                ?: error("$RESOURCE is missing from the build")
            return stream.use { parse(ObjectMapper(YAMLFactory()).readTree(it)) }
        }

        /** A manual from [yaml] written as help.yaml is; for tests. */
        fun parse(yaml: String): Manual = parse(ObjectMapper(YAMLFactory()).readTree(yaml))

        private fun parse(root: JsonNode): Manual {
            val groups = root.path("commands").map { group ->
                Group(
                    group.field("group"),
                    group.path("commands").map { command ->
                        CommandDoc(
                            command.field("name"),
                            command.fieldOrNull("args").orEmpty(),
                            command.field("summary"),
                            command.fieldOrNull("details"),
                            command.fieldOrNull("topic"),
                        )
                    },
                )
            }
            val topics = root.path("topics").map { topic ->
                Topic(topic.field("name"), topic.field("title"), topic.field("text").trimEnd().lines())
            }
            val commands = groups.flatMap { it.commands }
            duplicates(commands.map { it.name })?.let { throw IllegalArgumentException("$RESOURCE: $it documented twice") }
            duplicates(topics.map { it.name.lowercase() })?.let { throw IllegalArgumentException("$RESOURCE: topic $it given twice") }
            // `:manual all` prints every topic, so one called that could never be shown.
            require(topics.none { it.name.equals(ALL, ignoreCase = true) }) { "$RESOURCE: a topic cannot be called $ALL" }
            val names = topics.map { it.name }.toSet()
            commands.forEach { doc ->
                require(doc.topic == null || doc.topic in names) { "$RESOURCE: ${doc.name} refers to no topic ${doc.topic}" }
            }
            return Manual(groups, topics)
        }

        private fun duplicates(names: List<String>): String? =
            names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.firstOrNull()

        private fun JsonNode.field(field: String): String =
            fieldOrNull(field) ?: throw IllegalArgumentException("$RESOURCE: an entry has no $field: $this")

        /**
         * Text only: an unquoted `[n]` is a YAML list and `a: b` a map, which would
         * otherwise read as missing rather than as written wrongly.
         */
        private fun JsonNode.fieldOrNull(field: String): String? {
            val value = get(field)?.takeIf { !it.isNull } ?: return null
            require(value.isValueNode) { "$RESOURCE: $field is not text — quote it: $this" }
            return value.asText().trimEnd().takeIf { it.isNotEmpty() }
        }
    }
}
