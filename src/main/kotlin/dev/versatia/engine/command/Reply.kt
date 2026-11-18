package dev.versatia.engine.command

import dev.versatia.text.Argument
import dev.versatia.text.Text
import dev.versatia.text.arg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * A reply of VersatiaCore's commands: [template] names the server's styles, never a colour, and
 * holds [arguments] in braces, so `styles.yml` decides how it looks. It is shown through
 * VersatiaCore's [Text] and logged as [plain] text. An argument that is a reply, or a list of
 * them, is shown as that reply; an empty list reads `none`.
 */
class Reply(private val template: String, private vararg val arguments: Argument) {
    fun component(text: Text): Component = text.parse(template, *arguments.map { arg(it.name, shown(it.value, text)) }.toTypedArray())

    /** The reply without its styles, for the log; the templates are VersatiaCore's own, so dropping every tag in them is exact. */
    fun plain(): String = ARGUMENT.replace(TAG.replace(template, "")) { match -> plainOf(arguments.first { it.name == match.groupValues[1] }.value) }

    companion object {
        private val TAG = Regex("</?[A-Za-z][A-Za-z0-9_-]*/?>")
        private val ARGUMENT = Regex("""\{([A-Za-z0-9_-]+)}""")
        private val NONE = text("none")

        /** [replies], one per line. */
        fun lines(replies: List<Reply>, text: Text) = Component.join(JoinConfiguration.newlines(), replies.map { it.component(text) })

        private fun shown(value: Any?, text: Text): Any? = when {
            value is Reply -> value.component(text)
            value is Iterable<*> && value.none() -> NONE
            value is Iterable<*> -> value.map { shown(it, text) }
            else -> value
        }

        private fun plainOf(value: Any?): String = when (value) {
            null -> ""
            is Reply -> value.plain()
            is Component -> PlainTextComponentSerializer.plainText().serialize(value)
            is Iterable<*> -> if (value.none()) "none" else value.joinToString(", ") { plainOf(it) }
            else -> value.toString()
        }
    }
}
