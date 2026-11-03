package dev.versatia

import dev.versatia.engine.command.CommandText
import dev.versatia.engine.command.CommandText.line
import dev.versatia.engine.command.CommandText.plain
import dev.versatia.engine.command.CommandText.values
import dev.versatia.util.config.ConfigurationValueException
import java.nio.charset.CharacterCodingException
import java.util.logging.Level
import java.util.logging.Logger
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.plugin.Plugin

/**
 * One plugin a reload went through, under its friendly [name]: asked for when [cause] is null,
 * otherwise reloaded after [cause], whose beans it holds; [failure] is null when it reloaded.
 * See docs/runtime.md.
 */
class ReloadOutcome(val name: String, val plugin: Plugin, val cause: String?, val failure: Throwable?) {
    /** What went wrong, said once: the messages of the failure and its causes without the repeated `Plugin X:` prefix. */
    val message get() = reply?.plain()

    /** [message] for a reply: the plugin names in the value colour, the rest red. */
    val reply
        get() = failure?.let {
            val reason = causesOf(it)
                .map { e -> (e.message ?: e.javaClass.simpleName).removePrefix("Plugin ${plugin.name}: ") }
                .fold(listOf<String>()) { messages, message -> if (messages.lastOrNull()?.contains(message) == true) messages else messages + message }
                .joinToString(": ")
            if (cause == null) line("{} failed to reload: {}", name, text(reason), color = CommandText.ERROR)
            else line("{} failed to reload after {}, whose beans it uses: {}. {} stays reloaded; fix {} and reload it", name, cause, text(reason), cause, name, color = CommandText.ERROR)
        }

    /** Logs the failure: a configuration mistake as one warning without a stack trace, anything else with it. */
    fun log(logger: Logger) {
        val failure = failure ?: return
        if (causesOf(failure).any { it is ConfigurationValueException || it is InvalidConfigurationException || it is CharacterCodingException }) logger.warning(message)
        else logger.log(Level.SEVERE, message, failure)
    }

    private fun causesOf(e: Throwable) = generateSequence(e) { it.cause }

    companion object {
        /** `Reloaded A; then B, C, which use beans of A.` for the plugins of [outcomes] that reloaded, or null when none did; plugin names in the value colour. */
        fun summary(outcomes: List<ReloadOutcome>): Component? {
            val (asked, cascaded) = outcomes.filter { it.failure == null }.partition { it.cause == null }
            if (asked.isEmpty() && cascaded.isEmpty()) return null
            val names = values(asked.map { it.name })
            if (cascaded.isEmpty()) return line("Reloaded {}.", names, color = CommandText.SUCCESS)
            val then = values(cascaded.map { it.name })
            val causes = values(cascaded.mapNotNull { it.cause }.distinct())
            val verb = if (cascaded.size == 1) "uses" else "use"
            return if (asked.isEmpty()) line("Reloaded {}, which $verb beans of {}.", then, causes, color = CommandText.SUCCESS)
            else line("Reloaded {}; then {}, which $verb beans of {}.", names, then, causes, color = CommandText.SUCCESS)
        }
    }
}
