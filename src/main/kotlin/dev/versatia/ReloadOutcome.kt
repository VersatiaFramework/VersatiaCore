package dev.versatia

import dev.versatia.engine.command.Reply
import dev.versatia.text.arg
import dev.versatia.util.config.ConfigurationValueException
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
    val message get() = reply?.plain()

    /** [message] as a reply: a negative one, the reason in the accent. */
    val reply
        get() = failure?.let {
            val reason = causesOf(it)
                .map { e -> (e.message ?: e.javaClass.simpleName).removePrefix("Plugin ${plugin.name}: ") }
                .fold(listOf<String>()) { messages, message -> if (messages.lastOrNull()?.contains(message) == true) messages else messages + message }
                .joinToString(": ")
            if (cause == null) Reply("<negative>{plugin} failed to reload: {reason}", arg("plugin", name), arg("reason", reason))
            else Reply(
                "<negative>{plugin} failed to reload after {cause}, whose beans it uses: {reason}. {cause} stays reloaded; fix {plugin} and reload it",
                arg("plugin", name), arg("cause", cause), arg("reason", reason),
            )
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
        fun summary(outcomes: List<ReloadOutcome>): Reply? {
            val (asked, cascaded) = outcomes.filter { it.failure == null }.partition { it.cause == null }
            if (asked.isEmpty() && cascaded.isEmpty()) return null
            val names = arg("asked", asked.map { it.name })
            if (cascaded.isEmpty()) return Reply("<positive>Reloaded {asked}.", names)
            val then = arg("then", cascaded.map { it.name })
            val causes = arg("causes", cascaded.mapNotNull { it.cause }.distinct())
            val verb = if (cascaded.size == 1) "uses" else "use"
            return if (asked.isEmpty()) Reply("<positive>Reloaded {then}, which $verb beans of {causes}.", then, causes)
            else Reply("<positive>Reloaded {asked}; then {then}, which $verb beans of {causes}.", names, then, causes)
        }
    }
}
