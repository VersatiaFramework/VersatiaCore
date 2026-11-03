package dev.versatia.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.minimessage.Context
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * The arguments of one call. [substitute] rewrites every `{name.property...}` of the input through
 * [Substitution]: a brace in the text becomes a private MiniMessage tag that [resolve] answers, one
 * inside a tag argument the value's plain text. A brace is rendered once, walking one property per
 * segment. The resolver also serves the registered argument tags, recording the first exception
 * one throws in [failure], because MiniMessage swallows it.
 */
internal class ArgumentResolver(
    private val values: Map<String, Any?>,
    private val tags: Map<String, ArgumentTag>,
    private val presenters: Presenters,
    private val standardTags: TagResolver,
) : TagResolver, Arguments {
    private val placeholders = HashMap<String, Tag>()
    private val placeholderOf = HashMap<String, String>()
    private val components = HashMap<String, Component>()

    var failure: RuntimeException? = null
        private set

    override val names get() = values.keys

    override fun contains(name: String) = name in values

    override fun get(name: String) = if (name in values) values[name] else throw UnknownArgumentException(name, names)

    fun substitute(input: String) = Substitution({ it in tags || standardTags.has(it) }, ::placeholder, ::plain).rewrite(input)

    override fun has(name: String) = name in placeholders || name in tags

    override fun resolve(name: String, arguments: ArgumentQueue, ctx: Context): Tag? = placeholders[name] ?: try {
        tags[name]?.resolve(arguments, ctx, this)
    } catch (e: RuntimeException) {
        if (failure == null) failure = e
        null
    }

    private fun placeholder(reference: String) = "<" + placeholderOf.getOrPut(reference) {
        "$PLACEHOLDER${placeholders.size}".also { placeholders[it] = Tag.selfClosingInserting(rendered(reference)) }
    } + ">"

    private fun plain(reference: String) = PlainTextComponentSerializer.plainText().serialize(rendered(reference))

    private fun rendered(reference: String) = components.getOrPut(reference) {
        val segments = reference.split('.')
        render(segments.first(), segments.drop(1))
    }

    private fun render(name: String, properties: List<String>): Component {
        if (name !in values) throw UnknownArgumentException(name, names)
        var path = name
        var value = values[name]
        for (property in properties) {
            if (value == null) throw NullValueException(path, property)
            val presentation = presentation(path, value)
            val getter = presentation?.properties?.get(property) ?: throw UnknownPropertyException(path, property, presentation?.properties?.keys.orEmpty())
            path = "$path.$property"
            value = shown(path) { getter() }
        }
        return render(path, value)
    }

    // Presenters come first, so a registration for String or Number applies to the bare brace too
    private fun render(path: String, value: Any?): Component = when (val presentation = presentation(path, value)) {
        null -> when (value) {
            null -> Component.empty()
            is ComponentLike -> value.asComponent()
            else -> Component.text(value.toString())
        }
        else -> presentation.component?.let { shown(path, it) } ?: throw NoTextFormException(path, presentation.properties.keys)
    }

    private fun presentation(path: String, value: Any?) = value?.let {
        shown(path) { presenters.of(it) } ?: BuiltInPresentations.collection(it) { index, element -> render("$path[$index]", element) }
    }

    /** Runs code of a presentation, naming [path] when it throws; a nested brace's [TextException] passes as it is. */
    private inline fun <T> shown(path: String, block: () -> T) = try {
        block()
    } catch (e: TextException) {
        throw e
    } catch (e: RuntimeException) {
        throw PresentationException(path, e)
    }

    companion object {
        /** `{name}` or `{name.property.property}`. */
        val ARGUMENT = Regex("""\{([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)}""")

        /** An [ARGUMENT] that no odd run of backslashes escapes. */
        val UNESCAPED_ARGUMENT = Regex("""(?<!\\)(?:\\\\)*${ARGUMENT.pattern}""")
        const val PLACEHOLDER = "versatia-argument-"
    }
}
