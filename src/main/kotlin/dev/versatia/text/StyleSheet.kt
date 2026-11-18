package dev.versatia.text

import java.util.TreeSet

/**
 * The styles of a server: every name is a tag, and a style holds the names that apply only inside
 * it. A name is looked up in the styles around the place it stands in, nearest first, then at the
 * top level, so `accent` may be one thing inside `positive` and another inside `negative`. Names
 * are found whatever their case, as MiniMessage reads them, and shown as the file writes them.
 * [source] is the file the styles come from, as messages name it; [root] holds the top-level
 * styles by their [TagNames.key].
 */
internal class StyleSheet(val source: String, val root: Map<String, StyleNode>) {
    /** Every name, at any depth, sorted: what a text may write in angle brackets, in either case. */
    val names: Set<String> = TreeSet(String.CASE_INSENSITIVE_ORDER).apply { collect(root) }

    /** Every style that formats by its dotted path, `positive.accent`, with its MiniMessage. */
    val paths: Map<String, String> = LinkedHashMap<String, String>().apply { collect(root, "") }

    private val defined = paths.keys.mapTo(HashSet(), TagNames::key)

    /** What [name] stands for inside [scopes], the styles around it, nearest first; null when it is not defined there. */
    fun resolve(name: String, scopes: List<StyleNode>) = TagNames.key(name).let { key -> scopes.firstNotNullOfOrNull { it.children[key] } ?: root[key] }

    /** The names of the styles that define [name] inside them. */
    fun scopesOf(name: String): Set<String> = TreeSet<String>().apply { collectScopes(root, TagNames.key(name)) }

    /** The styles of [other] this sheet does not define, by their dotted paths as [other] writes them. */
    fun lacking(other: StyleSheet) = other.paths.keys.filter { TagNames.key(it) !in defined }

    private fun MutableSet<String>.collect(nodes: Map<String, StyleNode>) {
        for (node in nodes.values) {
            add(node.name)
            collect(node.children)
        }
    }

    private fun MutableMap<String, String>.collect(nodes: Map<String, StyleNode>, prefix: String) {
        for (node in nodes.values) {
            node.snippet?.let { put("$prefix${node.name}", it.source) }
            collect(node.children, "$prefix${node.name}.")
        }
    }

    private fun MutableSet<String>.collectScopes(nodes: Map<String, StyleNode>, key: String) {
        for (node in nodes.values) {
            if (key in node.children) add(node.name)
            collectScopes(node.children, key)
        }
    }

    companion object {
        fun none(source: String) = StyleSheet(source, emptyMap())
    }
}

/**
 * One style: its [name] as the file writes it, the MiniMessage it stands for, null for one that
 * only holds others, and the names that apply inside it, by their [TagNames.key].
 */
internal class StyleNode(val name: String, val snippet: StyleSnippet?, val children: Map<String, StyleNode>)
