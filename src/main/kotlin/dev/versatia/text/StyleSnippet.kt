package dev.versatia.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.minimessage.Context
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.ParsingException
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * The MiniMessage a style stands for, read once: [source] followed by what the style encloses, so
 * every tag it opens is closed where the style ends. A style that only formats, which is nearly
 * every one, comes down to a [Style]; one that writes text of its own or colours its content
 * letter by letter (`<gradient>`) is parsed again around each content.
 */
internal class StyleSnippet private constructor(val source: String, private val style: Style?) {
    /** Whether the style can be applied only once its content is known. */
    val needsContent get() = style == null

    /** What a tag of this style opens with, its content following as children; empty when the style [needsContent]. */
    val opening: Component get() = style?.let { Component.text("", it) } ?: Component.empty()

    /** [content] in this style. */
    fun around(content: Component): Component = when (style) {
        null -> MINI_MESSAGE.deserialize(source + CONTENT_TAG, Placeholder.component(CONTENT, content))
        else -> Component.text().style(style).append(content).build()
    }

    /** Notes the tags of a snippet that MiniMessage does not have; a style holds nothing else. */
    private class UnknownTags : TagResolver {
        val names = LinkedHashSet<String>()

        override fun resolve(name: String, arguments: ArgumentQueue, ctx: Context): Tag? = null

        override fun has(name: String): Boolean {
            if (name != CONTENT && !TagNames.STANDARD.has(name) && TagNames.isMeantAsTag(name)) names += name
            return false
        }
    }

    companion object {
        private val MINI_MESSAGE = MiniMessage.miniMessage()
        private const val CONTENT = "${TagNames.RESERVED}style-content"
        private const val CONTENT_TAG = "<$CONTENT>"

        /** Stands in for the content while a snippet is read; private-use characters, which no text holds. */
        private const val PROBE = ""

        /**
         * Reads [source]; an [IllegalArgumentException] says what is wrong with it, worded to
         * follow the name of the style: `uses <gol>, which is not a MiniMessage tag`.
         */
        fun of(source: String): StyleSnippet {
            val legacy = LegacyColorCodes.find(source)
            require(legacy.isEmpty()) { "uses legacy colour codes, which MiniMessage shows as text: ${legacy.joinToString { "$it -> ${LegacyColorCodes.tag(it)}" }}; write the tags instead" }
            val unknown = UnknownTags()
            val read = try {
                MINI_MESSAGE.deserialize(source + CONTENT_TAG, unknown, Placeholder.unparsed(CONTENT, PROBE))
            } catch (e: ParsingException) {
                throw IllegalArgumentException("is not valid MiniMessage: ${e.detailMessage() ?: e.message}")
            }
            require(unknown.names.isEmpty()) {
                val tags = unknown.names.joinToString { "<$it>" }
                "uses $tags, which ${if (unknown.names.size == 1) "is not a MiniMessage tag" else "are not MiniMessage tags"}; a style holds MiniMessage tags only, never another style"
            }
            require(PROBE in PlainTextComponentSerializer.plainText().serialize(read)) { "would hide the text it is applied to" }
            val formatsOnly = read is TextComponent && read.content() == PROBE && read.children().isEmpty()
            return StyleSnippet(source, if (formatsOnly) read.style() else null)
        }
    }
}
