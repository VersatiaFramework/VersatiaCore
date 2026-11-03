package com.github.marcoral.versatia.text

import com.github.marcoral.versatia.di.ExposePrototype
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags

/**
 * One per consuming plugin: its presentations and tags are its own, the built-in presentations are
 * shared. A text without braces and registered tags always parses to the same component, so the
 * last [CONSTANTS] of them are kept; registering a tag drops them.
 */
@ExposePrototype
internal class TextImpl : Text {
    override val miniMessage: MiniMessage = MiniMessage.miniMessage()
    private val presenters = Presenters(BuiltInPresentations.presenters)
    private val tags: MutableMap<String, ArgumentTag> = ConcurrentHashMap()
    private val constants: MutableMap<String, Component> = Collections.synchronizedMap(object : LinkedHashMap<String, Component>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Component>?) = size > CONSTANTS
    })

    // A constant text reads no argument, so the arguments are only checked and its component is reused
    override fun parse(input: String, vararg arguments: Argument): Component {
        val values = values(arguments)
        if (!isConstant(input)) return build(input, values)
        return constants[input] ?: build(input, values).also { constants[input] = it }
    }

    private fun values(arguments: Array<out Argument>) = LinkedHashMap<String, Any?>().apply {
        for (argument in arguments) {
            if (argument.name in this) throw DuplicateArgumentException(argument.name)
            this[argument.name] = argument.value
        }
    }

    // Braces are resolved before MiniMessage sees the text, so their errors surface directly; MiniMessage
    // swallows exceptions thrown by tags, so the resolver records the first one and the call rethrows it
    private fun build(input: String, values: Map<String, Any?>): Component {
        val resolver = ArgumentResolver(values, tags, presenters, STANDARD_TAGS)
        val component = miniMessage.deserialize(resolver.substitute(input), resolver)
        resolver.failure?.let { throw it }
        return component
    }

    override fun argumentNames(input: String) = ArgumentResolver.UNESCAPED_ARGUMENT.findAll(input).map { it.groupValues[1].substringBefore('.') }.toSet()

    override fun <T : Any> present(type: KClass<T>, presenter: (T) -> Presentation) = presenters.register(type, presenter)

    override fun tag(name: String, tag: ArgumentTag) {
        if (!TAG_NAME.matches(name) || STANDARD_TAGS.has(name) || name.startsWith(ArgumentResolver.PLACEHOLDER)) throw ReservedTagNameException(name)
        tags[name] = tag
        constants.clear()
    }

    private fun isConstant(input: String) = !ArgumentResolver.UNESCAPED_ARGUMENT.containsMatchIn(input) &&
        (tags.isEmpty() || TAG_NAME_IN_TEXT.findAll(input).none { it.groupValues[1].lowercase() in tags })

    private companion object {
        val TAG_NAME = Regex("[a-z0-9_-]+")
        val TAG_NAME_IN_TEXT = Regex("<[!/]?([A-Za-z0-9_-]+)")
        val STANDARD_TAGS: TagResolver = StandardTags.defaults()
        const val CONSTANTS = 1024
    }
}
