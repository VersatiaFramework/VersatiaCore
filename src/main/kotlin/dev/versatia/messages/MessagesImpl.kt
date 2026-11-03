package dev.versatia.messages

import dev.versatia.di.ExposePrototype
import dev.versatia.di.Reloadable
import dev.versatia.text.Text
import org.bukkit.plugin.Plugin

/**
 * One per consuming plugin: [plugin] is the consumer, whose `messages/` directory is read, and
 * [text] the consumer's own `Text`, so its presentations apply. The bank is replaced only when a
 * reload succeeds; a failing one keeps the previous messages.
 */
@ExposePrototype
internal class MessagesImpl(private val plugin: Plugin, private val text: Text) : Messages, Reloadable {
    @Volatile
    private var bank: Map<String, Message> = emptyMap()

    override val keys get() = bank.keys

    override fun contains(key: String) = key in bank

    override fun get(key: String) = bank[key] ?: throw MissingMessageException(plugin.name, key, keys)

    override fun getOrNull(key: String) = bank[key]

    override fun reload() {
        bank = MessageLoader(plugin.name, plugin.dataFolder, text, plugin.logger).load()
        plugin.logger.fine { "Loaded ${bank.size} messages" }
    }
}
