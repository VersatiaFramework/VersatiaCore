package dev.versatia.messages

import dev.versatia.VersatiaEngine
import dev.versatia.broadcast
import dev.versatia.text.Argument
import dev.versatia.text.CheckingText
import dev.versatia.text.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration

/**
 * Parsed when used, never when loaded, so the tags the plugin registers later apply; the plugin's
 * [Text] reuses the component of a text without braces and registered tags. Every line is a text of
 * its own, so a constant line is reused even when another line of the message has arguments. The
 * bank [check]s the tags of every message before any is used.
 */
internal class MessageImpl(
    private val plugin: String,
    override val key: String,
    private val texts: List<String>,
    override val file: String,
    private val text: Text,
) : Message {
    override val raw = texts.joinToString("\n")

    override fun get() = get(*emptyArray())

    override fun get(vararg arguments: Argument) = lines(*arguments).let { it.singleOrNull() ?: Component.join(JoinConfiguration.newlines(), it) }

    override fun lines(vararg arguments: Argument) = try {
        texts.map { text.parse(it, *arguments) }
    } catch (e: RuntimeException) {
        throw MessageRenderException(plugin, key, file, e)
    }

    /** Fails when a line uses a tag that does not exist, naming the key and the file as a load error does. */
    fun check(text: CheckingText) = try {
        texts.forEach(text::check)
    } catch (e: RuntimeException) {
        throw MessagesLoadException(plugin, "message \"$key\" in $file cannot be shown: ${e.message}")
    }

    override fun broadcast(vararg arguments: Argument) {
        VersatiaEngine.broadcast(get(*arguments))
    }

    override fun toString() = "$key ($file)"
}
