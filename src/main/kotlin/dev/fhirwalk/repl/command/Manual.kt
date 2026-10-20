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

    companion object {
        private const val RESOURCE = "help.yaml"

        /** The manual shipped with the build. A broken one fails at startup, not at `:help`. */
        fun load(): Manual {
            val stream = Manual::class.java.getResourceAsStream(RESOURCE)
                ?: error("$RESOURCE is missing from the build")
            return stream.use { parse(ObjectMapper(YAMLFactory()).readTree(it)) }
        }

        private fun parse(root: JsonNode): Manual {
            val groups = root.path("commands").map { group ->
                Group(
                    group.field("group"),
                    group.path("commands").map { command ->
                        CommandDoc(
                            command.field("name"),
                            command.path("args").asText(""),
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
            val names = topics.map { it.name }.toSet()
            groups.flatMap { it.commands }.forEach { doc ->
                require(doc.topic == null || doc.topic in names) { "$RESOURCE: ${doc.name} refers to no topic ${doc.topic}" }
            }
            return Manual(groups, topics)
        }

        private fun JsonNode.field(field: String): String =
            fieldOrNull(field) ?: throw IllegalArgumentException("$RESOURCE: an entry has no $field: $this")

        private fun JsonNode.fieldOrNull(field: String): String? =
            get(field)?.takeIf { !it.isNull }?.asText()?.trimEnd()?.takeIf { it.isNotEmpty() }
    }
}
