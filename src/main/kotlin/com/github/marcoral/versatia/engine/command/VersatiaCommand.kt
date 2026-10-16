package com.github.marcoral.versatia.engine.command

import com.github.marcoral.versatia.ConditionalOnPlugin
import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.VersatiaCore
import com.github.marcoral.versatia.lifecycle.AutoInvoke
import com.github.marcoral.versatia.util.config.ConfigurationValueException
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.kotlindsl.anyExecutor
import dev.jorel.commandapi.kotlindsl.commandAPICommand
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import java.nio.charset.CharacterCodingException
import java.util.logging.Level
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.plugin.Plugin

/**
 * `/versatia reload [plugin]`: runs the reload-phase enhancements of every enhanced plugin, or of
 * the one with the given friendly name. Registered only while the CommandAPI plugin is on the
 * server; without it the object is never loaded. A CommandAPI that fails to take the command, such
 * as an incompatible release, costs only the command, never VersatiaCore. See docs/runtime.md.
 */
@ConditionalOnPlugin("CommandAPI")
object VersatiaCommand {
    const val NAME = "versatia"
    const val RELOAD_PERMISSION = "versatia.core.command.reload"

    private var registered = false

    @AutoInvoke(EventType.PLUGIN_START_ONLY)
    fun register(core: VersatiaCore) = failSoft(core, "registered") {
        commandAPICommand(NAME, core) {
            subcommand("reload") {
                withPermission(RELOAD_PERMISSION)
                stringArgument("plugin", optional = true) { replaceSuggestions(ArgumentSuggestions.stringCollection { core.runtime.registry.friendlyNames }) }
                anyExecutor { sender, args -> reload(core, sender, args["plugin"] as? String) }
            }
        }
        registered = true
    }

    @AutoInvoke(EventType.PLUGIN_STOP)
    fun unregister(core: VersatiaCore) {
        if (!registered) return
        registered = false
        failSoft(core, "unregistered") { CommandAPI.unregister(NAME, true) }
    }

    /** Runs [block]; a failure, typically a CommandAPI release this build is not compatible with, is logged as one warning. */
    private inline fun failSoft(core: VersatiaCore, action: String, block: () -> Unit) = try {
        block()
    } catch (e: Exception) {
        warnIncompatible(core, action, e)
    } catch (e: LinkageError) {
        warnIncompatible(core, action, e)
    }

    private fun warnIncompatible(core: VersatiaCore, action: String, e: Throwable) {
        val version = core.server.pluginManager.getPlugin("CommandAPI")?.pluginMeta?.version ?: "(unknown version)"
        core.logger.log(
            Level.WARNING,
            "/$NAME could not be $action. The installed CommandAPI $version may not be compatible with the 12.x release " +
                "VersatiaCore is built against; everything except the command keeps working.",
            e,
        )
    }

    private fun reload(core: VersatiaCore, sender: CommandSender, name: String?) {
        val plugins = core.runtime.registry.plugins
        val selected = when (name) {
            null -> plugins
            in plugins -> mapOf(name to plugins.getValue(name))
            else -> return sender.sendMessage(text("No Versatia plugin is called \"$name\". ${running(plugins)}", NamedTextColor.RED))
        }
        if (selected.isEmpty()) return sender.sendMessage(text(running(plugins), NamedTextColor.YELLOW))
        val reloaded = selected.filter { (name, plugin) -> reload(core, name, plugin, sender) }.keys
        if (reloaded.isNotEmpty()) sender.sendMessage(text("Reloaded ${reloaded.joinToString()}.", NamedTextColor.GREEN))
    }

    /** A configuration mistake is logged as one warning without a stack trace; anything but a VM error leaves the other plugins reloading. */
    private fun reload(core: VersatiaCore, name: String, plugin: Plugin, sender: CommandSender) = try {
        core.runtime.reload(plugin)
        true
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        val message = "$name failed to reload: ${reasonOf(e, plugin)}"
        if (causesOf(e).any { it is ConfigurationValueException || it is InvalidConfigurationException || it is CharacterCodingException }) core.logger.warning(message)
        else core.logger.log(Level.SEVERE, message, e)
        sender.sendMessage(text(message, NamedTextColor.RED))
        false
    }

    /** The messages of [e] and its causes, without the repeated `Plugin X:` prefix and without one the previous message already contains. */
    private fun reasonOf(e: Throwable, plugin: Plugin) = causesOf(e)
        .map { (it.message ?: it.javaClass.simpleName).removePrefix("Plugin ${plugin.name}: ") }
        .fold(listOf<String>()) { messages, message -> if (messages.lastOrNull()?.contains(message) == true) messages else messages + message }
        .joinToString(": ")

    private fun causesOf(e: Throwable) = generateSequence(e) { it.cause }

    private fun running(plugins: Map<String, Plugin>) =
        if (plugins.isEmpty()) "No Versatia plugin is running." else "Running: ${plugins.keys.joinToString()}."
}
