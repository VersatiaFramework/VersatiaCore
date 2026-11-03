package dev.versatia.text

/**
 * Rewrites the braces of a text before MiniMessage reads it, scanning the way MiniMessage does: `\<`
 * and `\\` escape in the text, and a tag runs from `<` to the next `>` outside quotes, unless an
 * unquoted `<` comes first. A `{` after an odd run of backslashes is escaped: the last backslash is
 * dropped and the brace stays text, in the text and in tag arguments alike. In the text a brace
 * becomes [component], a tag inserting the value.
 * Inside an argument of a known tag, where MiniMessage reads a plain string, it becomes [plain],
 * escaped for the argument's quotes, and an unquoted argument holding a brace is quoted. Hover text,
 * which MiniMessage parses again, takes [component] as well.
 */
internal class Substitution(
    private val isTag: (String) -> Boolean,
    private val component: (String) -> String,
    private val plain: (String) -> String,
) {
    fun rewrite(input: String) = buildString {
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val next = input.getOrNull(i + 1)
            if (c == '\\' && (next == '<' || next == '\\' || next == '{')) {
                if (next == '{') append(next) else append(input, i, i + 2)
                i += 2
                continue
            }
            val end = if (c == '<') tagEnd(input, i) else -1
            val tag = if (end < 0) null else tag(input.substring(i + 1, end))
            if (tag != null) {
                append('<').append(tag).append('>')
                i = end + 1
                continue
            }
            val brace = if (c == '{') ArgumentResolver.ARGUMENT.matchAt(input, i) else null
            if (brace == null) {
                append(c)
                i++
            } else {
                append(component(brace.groupValues[1]))
                i = brace.range.last + 1
            }
        }
    }

    private fun tagEnd(input: String, start: Int) = scan(input, start + 1) { _, c -> c == '>' || c == '<' }.takeIf { it >= 0 && input[it] == '>' } ?: -1

    /** The rewritten content of a known tag with a brace in an argument, or null to read it as text. */
    private fun tag(content: String): String? {
        if ('{' !in content) return null
        val parts = split(content)
        val name = parts.first().trimStart('!', '/').lowercase()
        if (parts.size == 1 || !isTag(name)) return null
        return parts.mapIndexed { index, part ->
            if (index == 0 || '{' !in part) part
            else argument(part, reparsed = name == HOVER && REPARSED[parts[1]] == index)
        }.joinToString(":")
    }

    private fun split(content: String): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        scan(content, 0) { i, c ->
            if (c == ':') {
                parts += content.substring(start, i)
                start = i + 1
            }
            false
        }
        parts += content.substring(start)
        return parts
    }

    // A quoted body is already escaped by its author, so only the backslash escaping a brace is dropped
    private fun argument(part: String, reparsed: Boolean): String {
        val quote = part[0].takeIf { (it == '\'' || it == '"') && part.length > 1 && part.last() == it && scan(part, 0) { _, _ -> true } < 0 }
        val body = if (quote == null) part else part.substring(1, part.length - 1)
        val delimiter = quote ?: '\''
        val literal = { text: String -> if (quote == null) text.escaped(delimiter) else text }
        return buildString {
            append(delimiter)
            var last = 0
            for (match in BRACE.findAll(body)) {
                val backslashes = match.groupValues[1].length
                val reference = match.groups[2]?.value
                if (backslashes % 2 == 1) {
                    append(literal(body.substring(last, match.range.first + backslashes - 1)))
                    last = match.range.first + backslashes
                } else if (reference != null) {
                    append(literal(body.substring(last, match.range.first + backslashes)))
                    append(if (reparsed) component(reference) else plain(reference).escaped(delimiter))
                    last = match.range.last + 1
                }
            }
            append(literal(body.substring(last)))
            append(delimiter)
        }
    }

    private fun String.escaped(quote: Char) = buildString {
        for (c in this@escaped) {
            if (c == '\\' || c == quote) append('\\')
            append(c)
        }
    }

    private companion object {
        const val HOVER = "hover"

        /** A run of backslashes, then `{`, then the reference of an argument, if one follows. */
        val BRACE = Regex("""(\\*)\{(?:([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)})?""")

        /** The hover actions whose argument at the given index is MiniMessage text. */
        val REPARSED = mapOf("show_text" to 2, "show_entity" to 4)

        /** Calls [stop] with every character of [text] from [from] outside quotes; the index it accepted, or -1. */
        inline fun scan(text: String, from: Int, stop: (Int, Char) -> Boolean): Int {
            var quote: Char? = null
            var i = from
            while (i < text.length) {
                val c = text[i]
                when {
                    quote == null && (c == '\'' || c == '"') -> quote = c
                    quote == null -> if (stop(i, c)) return i
                    c == '\\' && text.getOrNull(i + 1).let { it == quote || it == '\\' } -> i++
                    c == quote -> quote = null
                }
                i++
            }
            return -1
        }
    }
}
