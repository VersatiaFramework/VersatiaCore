package dev.versatia.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration

/**
 * Marks what a brace inserts, so that once MiniMessage has built the text [settle] gives every
 * value the look of the tags holding it alone: the colour, decorations and font those tags set win
 * over the value's own, in every part of it. Tags beside other text, and the style a value takes by
 * itself, only fill what the value leaves open, as Adventure's children do. Click, hover and
 * insertion are behaviour, not look, and stay the value's. A mark is an insertion only this class
 * writes, read from MiniMessage's tree before it is compacted.
 */
internal object ValueMarks {
    private const val MARK = "${TagNames.RESERVED}value"

    fun mark(value: Component): Component = Component.text().insertion(MARK).append(value).build()

    /** [root] without its marks, compacted as MiniMessage would have. */
    fun settle(root: Component) = settled(root, Style.empty()).compact()

    // [around] is the look of the tags that hold only this component, the nearest winning
    private fun settled(component: Component, around: Style): Component {
        val shown = withHover(component)
        val style = shown.style()
        if (style.insertion() == MARK) return forced(shown.style(style.insertion(null)), around)
        val inner = if (holdsOne(shown)) over(around, look(style)) else Style.empty()
        return shown.children(shown.children().map { settled(it, inner) })
    }

    private fun withHover(component: Component): Component {
        val hover = component.hoverEvent() ?: return component
        return when (val value = hover.value()) {
            is Component -> component.hoverEvent(HoverEvent.showText(settle(value)))
            is HoverEvent.ShowEntity -> value.name()?.let { component.hoverEvent(HoverEvent.showEntity(value.name(settle(it)))) } ?: component
            else -> component
        }
    }

    private fun holdsOne(component: Component) = component is TextComponent && component.content().isEmpty() && component.children().size == 1

    private fun look(style: Style) = TextDecoration.entries.fold(Style.style().color(style.color()).font(style.font()).build()) { look, decoration ->
        look.decoration(decoration, style.decoration(decoration))
    }

    /** [outer] with what [inner] sets on top. */
    private fun over(outer: Style, inner: Style) = TextDecoration.entries.fold(outer.color(inner.color() ?: outer.color()).font(inner.font() ?: outer.font())) { style, decoration ->
        inner.decoration(decoration).takeUnless { it == TextDecoration.State.NOT_SET }?.let { style.decoration(decoration, it) } ?: style
    }

    private fun forced(value: Component, look: Style): Component =
        if (look.isEmpty) value else value.style(over(value.style(), look)).children(value.children().map { cleared(it, look) })

    /** [component] and its parts without what [look] sets, so they show the look of the value they belong to. */
    private fun cleared(component: Component, look: Style): Component {
        var style = component.style()
        if (look.color() != null) style = style.color(null)
        if (look.font() != null) style = style.font(null)
        for (decoration in TextDecoration.entries) if (look.decoration(decoration) != TextDecoration.State.NOT_SET) style = style.decoration(decoration, TextDecoration.State.NOT_SET)
        return component.style(style).children(component.children().map { cleared(it, look) })
    }
}
