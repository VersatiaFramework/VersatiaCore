package dev.versatia.text

import dev.versatia.di.ExposePrototype
import dev.versatia.di.Reloadable
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.plugin.Plugin

/** A [Text] that can read a text for its tags alone, which is how a bank of messages is checked before any of them is used. */
internal interface CheckingText : Text {
    /** Throws what [parse] would throw for a tag of [input] that does not exist; no argument is needed and none is read. */
    fun check(input: String)
}

/**
 * One per consuming plugin: its presentations and tags are its own, the built-in presentations,
 * the presentations of the [server] and its [styles] are shared. At the start and at every reload
 * of [plugin] its default styles are handed to the server's, before its messages are read. A text
 * without braces and registered tags always parses to the same component, so the last [CONSTANTS]
 * of them are kept; registering a tag or a change of the styles drops them.
 */
@ExposePrototype
internal class TextImpl(private val styles: ServerStyles, val server: ServerPresentations, private val plugin: Plugin) : CheckingText, Reloadable {
    override val miniMessage: MiniMessage = MiniMessage.miniMessage()
    private val presenters = Presenters(server, BuiltInPresentations.presenters)
    private val tags: MutableMap<String, ArgumentTag> = ConcurrentHashMap()

    @Volatile
    private var constants = Constants(styles.sheet)

    override fun reload() = styles.adopt(plugin)

    // A constant text reads no argument, so the arguments are only checked and its component is reused
    override fun parse(input: String, vararg arguments: Argument): Component {
        val values = values(arguments)
        val sheet = styles.sheet
        if (!isConstant(input)) return build(input, values, sheet)
        val kept = constants.takeIf { it.sheet === sheet } ?: Constants(sheet).also { constants = it }
        return kept.components[input] ?: build(input, values, sheet).also { kept.components[input] = it }
    }

    override fun check(input: String) {
        read(input, ArgumentResolver(emptyMap(), tags, presenters, styles.sheet, checking = true))
    }

    private fun values(arguments: Array<out Argument>) = LinkedHashMap<String, Any?>().apply {
        for (argument in arguments) {
            if (argument.name in this) throw DuplicateArgumentException(argument.name)
            this[argument.name] = argument.value
        }
    }

    private fun build(input: String, values: Map<String, Any?>, sheet: StyleSheet) = read(input, ArgumentResolver(values, tags, presenters, sheet))

    // Braces are resolved before MiniMessage sees the text, so their errors surface directly; MiniMessage swallows
    // exceptions thrown by tags and prints tags it does not know, so the resolver records both and the call throws
    private fun read(input: String, resolver: ArgumentResolver): Component {
        val component = READER.deserialize(resolver.substitute(input), resolver)
        resolver.failure?.let { throw it }
        return ValueMarks.settle(component)
    }

    override fun argumentNames(input: String) = ArgumentResolver.UNESCAPED_ARGUMENT.findAll(input).map { it.groupValues[1].substringBefore('.') }.toSet()

    override fun <T : Any> present(type: KClass<T>, style: String?, presenter: (T) -> Presentation) = presenters.register(type, style, presenter)

    override fun tag(name: String, tag: ArgumentTag) {
        val key = TagNames.key(name)
        if (!TagNames.OWN.matches(name) || TagNames.STANDARD.has(key) || key.startsWith(ArgumentResolver.PLACEHOLDER)) throw ReservedTagNameException(name)
        tags[key] = tag
        constants = Constants(constants.sheet)
    }

    private fun isConstant(input: String) = !ArgumentResolver.UNESCAPED_ARGUMENT.containsMatchIn(input) &&
        (tags.isEmpty() || TAG_NAME_IN_TEXT.findAll(input).none { it.groupValues[1].lowercase() in tags })

    /** The components of constant texts as [sheet] styles them; a new one replaces it when the styles change. */
    private class Constants(val sheet: StyleSheet) {
        val components: MutableMap<String, Component> = Collections.synchronizedMap(object : LinkedHashMap<String, Component>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Component>?) = size > CONSTANTS
        })
    }

    private companion object {
        val TAG_NAME_IN_TEXT = Regex("<[!/]?([A-Za-z0-9_-]+)")
        const val CONSTANTS = 1024

        /** MiniMessage leaving its tree as built, so that [ValueMarks] still sees which tags hold a value alone. */
        val READER = MiniMessage.builder().postProcessor { it }.build()
    }
}
