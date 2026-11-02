package com.github.marcoral.versatia.engine.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * Colours of command replies: text gray, everything up to and including a colon gold, every value the
 * code fills in (names, versions, counts) aqua; a reply that reports success or failure keeps its text
 * green or red. See docs/runtime.md.
 */
object CommandText {
    val TEXT: TextColor = NamedTextColor.GRAY
    val LABEL: TextColor = NamedTextColor.GOLD
    val VALUE: TextColor = NamedTextColor.AQUA
    val SUCCESS: TextColor = NamedTextColor.GREEN
    val WARNING: TextColor = NamedTextColor.YELLOW
    val ERROR: TextColor = NamedTextColor.RED

    /** [template] in [color], each `{}` replaced by the next of [values]: a [Component] as it is, anything else as text in [VALUE]. */
    fun line(template: String, vararg values: Any?, color: TextColor = TEXT): Component {
        val parts = template.split("{}")
        require(parts.size == values.size + 1) { "\"$template\" has ${parts.size - 1} placeholders for ${values.size} values" }
        val line = text().color(color)
        parts.forEachIndexed { index, part ->
            if (part.isNotEmpty()) line.append(text(part))
            if (index < values.size) line.append(values[index] as? Component ?: text(values[index].toString(), VALUE))
        }
        return line.build()
    }

    /** `label:` in [LABEL], then [template] as in [line]. */
    fun field(label: String, template: String = "", vararg values: Any?): Component =
        if (template.isEmpty()) text("$label:", LABEL) else text("$label:", LABEL).append(line(" $template", *values))

    /** [items] in [VALUE], separated by commas in the surrounding colour. */
    fun values(items: Iterable<Any?>): Component = joined(items.map { text(it.toString(), VALUE) })

    /** [items] separated by commas, or `none` when there are none. */
    fun joined(items: List<Component>): Component = if (items.isEmpty()) line("none") else Component.join(JoinConfiguration.commas(true), items)

    fun lines(lines: List<Component>): Component = Component.join(JoinConfiguration.newlines(), lines)

    fun Component.plain() = PlainTextComponentSerializer.plainText().serialize(this)
}
