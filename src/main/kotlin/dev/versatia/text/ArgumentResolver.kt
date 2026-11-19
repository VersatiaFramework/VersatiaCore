package dev.versatia.text

import java.util.IdentityHashMap
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.minimessage.Context
import net.kyori.adventure.text.minimessage.tag.Inserting
import net.kyori.adventure.text.minimessage.tag.Modifying
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tree.Node
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * The arguments, tags and styles of one call. [substitute] rewrites every `{name.property...}` of
 * the input through [Substitution]: a brace in the text becomes a private MiniMessage tag that
 * [resolve] answers, one inside a tag argument the value's plain text. A brace is rendered once,
 * walking one property per segment. The resolver also serves the registered argument tags and the
 * styles of [sheet], which are tags that learn the styles around them from MiniMessage's tree, and
 * notes every tag nobody answers. MiniMessage swallows what a tag throws and prints a tag it does
 * not know, so both end up in [failure], which the caller throws. While [checking], a text is read
 * for its tags alone: nothing is rendered and no argument tag is called.
 */
internal class ArgumentResolver(
    private val values: Map<String, Any?>,
    private val tags: Map<String, ArgumentTag>,
    private val presenters: Presenters,
    private val sheet: StyleSheet,
    private val checking: Boolean = false,
) : TagResolver, Arguments {
    private val placeholders = HashMap<String, () -> Tag>()
    private val placeholderOf = HashMap<String, String>()
    private val renderings = HashMap<String, Rendered>()
    private val opened = IdentityHashMap<Node, StyleNode>()
    private val unknown = LinkedHashSet<String>()
    private var thrown: RuntimeException? = null

    /** What went wrong while MiniMessage read the text: the first exception of a tag, otherwise the tags that do not exist. */
    val failure get() = thrown ?: unknown.takeIf { it.isNotEmpty() }?.let { UnknownTagException(it, sheet.names, sheet.source) }

    override val names get() = values.keys

    override fun contains(name: String) = name in values

    override fun get(name: String) = if (name in values) values[name] else throw UnknownArgumentException(name, names)

    fun substitute(input: String) = Substitution({ it in tags || it in sheet.names || TagNames.STANDARD.has(it) || noted(it) }, ::placeholder, ::plain).rewrite(input)

    override fun has(name: String) = name in placeholders || name in tags || name in sheet.names || noted(name)

    /** A registered tag wins over a style of its name: it is the plugin's own. */
    override fun resolve(name: String, arguments: ArgumentQueue, ctx: Context): Tag? {
        placeholders[name]?.let { return it() }
        val tag = tags[name]
        return when {
            tag != null && checking -> NOTHING
            tag != null -> try {
                tag.resolve(arguments, ctx, this)
            } catch (e: RuntimeException) {
                failed(e)
                null
            }
            name !in sheet.names -> null
            arguments.hasNext() -> null.also { failed(TextException("<$name> is a style, which takes no arguments")) }
            else -> StyleTag(name)
        }
    }

    /** Notes [name] as a tag nobody answers, unless MiniMessage does or it was never meant as one; always false. */
    private fun noted(name: String): Boolean {
        if (TagNames.isMeantAsTag(name) && !TagNames.STANDARD.has(name)) unknown += name
        return false
    }

    private fun failed(e: RuntimeException) {
        if (thrown == null) thrown = e
    }

    private fun placeholder(reference: String) = "<" + placeholderOf.getOrPut(reference) {
        "$PLACEHOLDER${placeholders.size}".also { placeholders[it] = insertion(rendered(reference)) }
    } + ">"

    /** A value in no style of this server is one tag for the whole call; any other is styled where it stands. */
    private fun insertion(rendered: Rendered): () -> Tag {
        if (rendered.styles.any { it in sheet.names }) return { Insertion(rendered) }
        val tag = Tag.selfClosingInserting(ValueMarks.mark(rendered.component { null }))
        return { tag }
    }

    private fun plain(reference: String) = PlainTextComponentSerializer.plainText().serialize(rendered(reference).component { null })

    private fun rendered(reference: String) = renderings.getOrPut(reference) {
        val segments = reference.split('.')
        if (checking) Rendered.NOTHING else render(segments.first(), segments.drop(1))
    }

    private fun render(name: String, properties: List<String>): Rendered {
        if (name !in values) throw UnknownArgumentException(name, names)
        var path = name
        var value = values[name]
        for (property in properties) {
            val getter = propertyOf(path, value ?: throw NullValueException(path, property), property)
            path = "$path.$property"
            value = shown(path) { getter() }
        }
        return render(path, value)
    }

    /** The getter of [property]: the first presentation of [value] defining it, then a collection's own properties. */
    private fun propertyOf(path: String, value: Any, property: String): () -> Any? {
        val presented = shown(path) { presenters.of(value) }
        val elements = BuiltInPresentations.elements(value)?.properties.orEmpty()
        return shown(path) { presented?.property(property) } ?: elements[property]
            ?: throw UnknownPropertyException(path, property, shown(path) { presented?.properties.orEmpty() } + elements.keys)
    }

    // Presenters come first, so a registration for String or Number applies to the bare brace too
    private fun render(path: String, value: Any?): Rendered {
        if (value == null) return Rendered.NOTHING
        val presented = shown(path) { presenters.of(value) }
        val look = shown(path) { presented?.look() }
        if (look != null) return Rendered.Value(shown(path, look.component), stylesOf(look.style))
        val collection = BuiltInPresentations.elements(value)
        return when {
            collection != null -> Rendered.Elements(collection.elements.mapIndexed { index, element -> render("$path[$index]", element) })
            presented != null -> throw NoTextFormException(path, shown(path) { presented.properties })
            value is ComponentLike -> Rendered.Value(value.asComponent(), stylesOf())
            else -> Rendered.Value(Component.text(value.toString()), stylesOf())
        }
    }

    /**
     * The styles a value may be shown in, the first the server defines: its type's, then the accent
     * every value takes, a component too; what the value formats itself wins over the style.
     */
    private fun stylesOf(type: String? = null) = listOfNotNull(type, BuiltInPresentations.ACCENT)

    /** Runs code of a presentation, naming [path] when it throws; a nested brace's [TextException] passes as it is. */
    private inline fun <T> shown(path: String, block: () -> T) = try {
        block()
    } catch (e: TextException) {
        throw e
    } catch (e: RuntimeException) {
        throw PresentationException(path, e)
    }

    /** The styles around [node] in MiniMessage's tree, nearest first. */
    private fun around(node: Node) = generateSequence(node.parent()) { it.parent() }.mapNotNull { opened[it] }.toList()

    /** Whether [node] is all that the tag of a style holds, with nothing beside it up to that tag. */
    private fun fills(node: Node) = generateSequence(node.parent()) { it.parent() }.takeWhile { it.children().size == 1 }.any { it in opened }

    /**
     * A style as a tag. MiniMessage shows a tag its place in the tree only as a [Modifying] one,
     * before it asks an [Inserting] one for its component: [visit] finds what the name stands for
     * among the styles around it, and [value] opens with that formatting, the content following as
     * children, exactly as the tags of the style would. A style that needs its content first is
     * applied in [apply], which then answers for the content too.
     */
    private inner class StyleTag(private val name: String) : Inserting, Modifying {
        private var snippet: StyleSnippet? = null

        override fun visit(current: Node, depth: Int) {
            if (depth != 0) return
            val style = sheet.resolve(name, around(current)) ?: return failed(UnknownTagException(name, sheet.scopesOf(name), sheet.source))
            opened[current] = style
            snippet = style.snippet
        }

        override fun value() = snippet?.opening ?: Component.empty()

        override fun apply(current: Component, depth: Int): Component = when {
            snippet?.needsContent != true -> current.children(emptyList())
            depth == 0 -> snippet?.around(current) ?: current
            else -> Component.empty()
        }
    }

    /**
     * A brace whose value takes a style of the place it stands in, which [visit] finds as
     * [StyleTag] does: the first of the value's styles that the place defines. A value that is all
     * a style's tag holds was styled by hand and takes none, and neither does one already inside
     * the style it would take.
     */
    private inner class Insertion(private val rendered: Rendered) : Inserting, Modifying {
        private var scopes = emptyList<StyleNode>()
        private var alone = false

        override fun allowsChildren() = false

        override fun visit(current: Node, depth: Int) {
            if (depth != 0) return
            scopes = around(current)
            alone = fills(current)
        }

        override fun value() = ValueMarks.mark(rendered.component(::styleOf))

        private fun styleOf(names: List<String>) =
            if (alone) null else names.firstNotNullOfOrNull { name -> sheet.resolve(name, scopes)?.takeIf { it.snippet != null } }.takeUnless { it === scopes.firstOrNull() }

        override fun apply(current: Component, depth: Int): Component = current.children(emptyList())
    }

    companion object {
        /** `{name}` or `{name.property.property}`. */
        val ARGUMENT = Regex("""\{([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)}""")

        /** An [ARGUMENT] that no odd run of backslashes escapes. */
        val UNESCAPED_ARGUMENT = Regex("""(?<!\\)(?:\\\\)*${ARGUMENT.pattern}""")
        const val PLACEHOLDER = "${TagNames.RESERVED}argument-"

        private val NOTHING = Tag.selfClosingInserting(Component.empty())
    }
}

/** What a brace inserts before styles apply: components, each with the styles its value may be shown in. */
internal sealed interface Rendered {
    /** The styles the parts ask for. */
    val styles: Set<String>

    /** The component, each part in the style [lookup] answers for the names it may take, if any. */
    fun component(lookup: (List<String>) -> StyleNode?): Component

    class Value(private val component: Component, private val names: List<String> = emptyList()) : Rendered {
        override val styles get() = names.toSet()

        override fun component(lookup: (List<String>) -> StyleNode?) = lookup(names)?.snippet?.around(component) ?: component
    }

    class Elements(private val elements: List<Rendered>) : Rendered {
        override val styles get() = elements.flatMapTo(HashSet()) { it.styles }

        override fun component(lookup: (List<String>) -> StyleNode?) = Component.join(BuiltInPresentations.SEPARATOR, elements.map { it.component(lookup) })
    }

    companion object {
        val NOTHING: Rendered = Value(Component.empty())
    }
}
