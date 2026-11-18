package dev.versatia.messages

import dev.versatia.di.ExposePrototype
import dev.versatia.di.Reloadable
import dev.versatia.di.StartAware
import dev.versatia.text.CheckingText
import org.bukkit.plugin.Plugin

/**
 * One per consuming plugin: [plugin] is the consumer, whose `messages/` directory is read, and
 * [text] the consumer's own `Text`, so its presentations apply. The bank is replaced only when a
 * reload succeeds; a failing one keeps the previous messages. Every message is checked for tags
 * that do not exist: the first bank once the plugin has [started], when the tags its start
 * functions register are in place, and each later one before it replaces the bank.
 */
@ExposePrototype
internal class MessagesImpl(private val plugin: Plugin, private val text: CheckingText) : Messages, Reloadable, StartAware {
    @Volatile
    private var bank: Map<String, MessageImpl> = emptyMap()
    private var started = false

    override val keys get() = bank.keys

    override fun contains(key: String) = key in bank

    override fun get(key: String) = bank[key] ?: throw MissingMessageException(plugin.name, key, keys)

    override fun getOrNull(key: String) = bank[key]

    // Tags are checked at once only after the start: the first load comes before the start functions that register tags
    override fun reload() {
        val loaded = MessageLoader(plugin.name, plugin.dataFolder, text, plugin.logger).load()
        if (started) loaded.values.forEach { it.check(text) }
        bank = loaded
        plugin.logger.fine { "Loaded ${bank.size} messages" }
    }

    override fun started() {
        bank.values.forEach { it.check(text) }
        started = true
    }
}
