package com.github.marcoral.versatia

import com.github.marcoral.versatia.util.config.ConfigurationValueException
import java.nio.charset.CharacterCodingException
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.plugin.Plugin

/**
 * One plugin a reload went through, under its friendly [name]: asked for when [cause] is null,
 * otherwise reloaded after [cause], whose beans it holds; [failure] is null when it reloaded.
 * See docs/runtime.md.
 */
class ReloadOutcome(val name: String, val plugin: Plugin, val cause: String?, val failure: Throwable?) {
    /** What went wrong, said once: the messages of the failure and its causes without the repeated `Plugin X:` prefix. */
    val message
        get() = failure?.let {
            val reason = causesOf(it)
                .map { e -> (e.message ?: e.javaClass.simpleName).removePrefix("Plugin ${plugin.name}: ") }
                .fold(listOf<String>()) { messages, message -> if (messages.lastOrNull()?.contains(message) == true) messages else messages + message }
                .joinToString(": ")
            if (cause == null) "$name failed to reload: $reason"
            else "$name failed to reload after $cause, whose beans it uses: $reason. $cause stays reloaded; fix $name and reload it"
        }

    /** Logs the failure: a configuration mistake as one warning without a stack trace, anything else with it. */
    fun log(logger: Logger) {
        val failure = failure ?: return
        if (causesOf(failure).any { it is ConfigurationValueException || it is InvalidConfigurationException || it is CharacterCodingException }) logger.warning(message)
        else logger.log(Level.SEVERE, message, failure)
    }

    private fun causesOf(e: Throwable) = generateSequence(e) { it.cause }

    companion object {
        /** `Reloaded A; then B, C, which use beans of A.` for the plugins of [outcomes] that reloaded, or null when none did. */
        fun summary(outcomes: List<ReloadOutcome>): String? {
            val (asked, cascaded) = outcomes.filter { it.failure == null }.partition { it.cause == null }
            if (asked.isEmpty() && cascaded.isEmpty()) return null
            val then = if (cascaded.isEmpty()) "" else {
                val verb = if (cascaded.size == 1) "uses" else "use"
                "${if (asked.isEmpty()) "" else "; then "}${cascaded.joinToString { it.name }}, which $verb beans of ${cascaded.mapNotNull { it.cause }.distinct().joinToString()}"
            }
            return "Reloaded ${asked.joinToString { it.name }}$then."
        }
    }
}
