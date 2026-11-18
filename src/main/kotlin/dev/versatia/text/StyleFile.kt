package dev.versatia.text

import dev.versatia.engine.yaml.TextYaml

/** A styles file cannot be read: invalid YAML, a name that cannot be a style, or MiniMessage that does not exist. */
internal class StylesLoadException(message: String) : TextException(message)

/**
 * The format of a styles file, which errors call [source]: names mapped to MiniMessage, where a
 * section is a scope. Its [OWN] key is the section's own style and every other key a name that
 * applies only inside it; a dotted key, `positive.accent`, says the same as nesting. A name written
 * in another case is the same name, kept as it was first written. Whatever is wrong is a
 * [StylesLoadException] naming the file and the style.
 */
internal class StyleFile(private val source: String) {
    /** The styles [content] holds. */
    fun read(content: String): StyleSheet {
        val root = Draft("")
        when (val document = TextYaml.parse(content, source, ::StylesLoadException)) {
            null -> Unit
            is Map<*, *> -> collect(document, root, "")
            else -> throw StylesLoadException("$source must map the names of styles to MiniMessage")
        }
        return StyleSheet(source, root.children.mapValues { (_, draft) -> node(draft.name, draft) })
    }

    /**
     * The lines that give [sheet] every style of [defaults] it lacks, under a comment naming
     * [plugin]; empty when it lacks none. A name the sheet does not have comes whole, nested as
     * shipped; under a name it has, each lacking style is a dotted line of its own, since a
     * section cannot be opened twice.
     */
    fun additions(sheet: StyleSheet, defaults: StyleSheet, plugin: String): String {
        val lines = buildString {
            for ((key, node) in defaults.root) {
                val existing = sheet.root[key]
                if (existing == null) nested(node, "") else lacking(existing, node, existing.name)
            }
        }
        return if (lines.isEmpty()) "" else "# Default styles of $plugin\n$lines"
    }

    private fun StringBuilder.nested(node: StyleNode, indent: String) {
        if (node.children.isEmpty()) {
            appendLine("$indent${node.name}: ${quoted(node.snippet?.source.orEmpty())}")
            return
        }
        appendLine("$indent${node.name}:")
        node.snippet?.let { appendLine("$indent  $OWN: ${quoted(it.source)}") }
        for (child in node.children.values) nested(child, "$indent  ")
    }

    private fun StringBuilder.lacking(existing: StyleNode, shipped: StyleNode, path: String) {
        if (existing.snippet == null) shipped.snippet?.let { appendLine("$path.$OWN: ${quoted(it.source)}") }
        for ((key, node) in shipped.children) {
            val child = existing.children[key]
            if (child == null) dotted(node, "$path.${node.name}") else lacking(child, node, "$path.${child.name}")
        }
    }

    private fun StringBuilder.dotted(node: StyleNode, path: String) {
        node.snippet?.let { appendLine("$path: ${quoted(it.source)}") }
        for (child in node.children.values) dotted(child, "$path.${child.name}")
    }

    private fun quoted(text: String) = buildString {
        append('"')
        for (c in text) when (c) {
            '\\', '"' -> append('\\').append(c)
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    private fun collect(section: Map<*, *>, scope: Draft, prefix: String) {
        for ((key, value) in section) {
            if (key !is String) throw StylesLoadException("$source has a key that is not text: $key")
            val names = key.split('.')
            val own = names.last() == OWN
            val path = if (own) names.dropLast(1) else names
            if (own && path.isEmpty() && prefix.isEmpty())
                throw StylesLoadException("\"$OWN\" in $source is the key of a section's own style, so it cannot be a style at the top level")
            val target = path.fold(scope) { draft, name -> draft.children.getOrPut(TagNames.key(checked(name))) { Draft(name) } }
            val name = prefix + path.joinToString(".")
            when {
                value is String -> {
                    if (target.snippet != null) throw StylesLoadException("style \"${name.removeSuffix(".")}\" is defined twice in $source")
                    target.snippet = value
                }
                value is Map<*, *> && !own -> collect(value, target, "$name.")
                else -> throw StylesLoadException("style \"${prefix + key}\" in $source must be MiniMessage text${if (own) "" else " or a section of styles"}")
            }
        }
    }

    private fun checked(name: String): String {
        val reason = objection(name) ?: return name
        throw StylesLoadException("\"$name\" in $source cannot name a style: $reason")
    }

    private fun node(path: String, draft: Draft): StyleNode {
        if (draft.snippet == null && draft.children.isEmpty())
            throw StylesLoadException("style \"$path\" in $source is an empty section; give it a \"$OWN\" or the names that apply inside it")
        val snippet = draft.snippet?.let {
            try {
                StyleSnippet.of(it)
            } catch (e: IllegalArgumentException) {
                throw StylesLoadException("style \"$path\" in $source ${e.message}")
            }
        }
        return StyleNode(draft.name, snippet, draft.children.mapValues { (_, child) -> node("$path.${child.name}", child) })
    }

    private class Draft(val name: String) {
        var snippet: String? = null
        val children = LinkedHashMap<String, Draft>()
    }

    companion object {
        /** The key of a section's own style. */
        const val OWN = "style"

        /** What speaks against [name] as the name of a style, or null when it may be one. */
        fun objection(name: String) = when {
            name.equals(OWN, ignoreCase = true) -> "it is the key of a section's own style"
            !TagNames.OWN.matches(name) -> "letters, digits, _ and - only"
            TagNames.key(name).startsWith(TagNames.RESERVED) -> "names starting with ${TagNames.RESERVED} are reserved"
            TagNames.STANDARD.has(TagNames.key(name)) -> "it is a MiniMessage tag"
            else -> null
        }
    }
}
