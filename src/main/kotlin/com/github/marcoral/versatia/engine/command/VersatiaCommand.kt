package com.github.marcoral.versatia.engine.command

import com.github.marcoral.versatia.ConditionalOnPlugin
import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.VersatiaCore
import com.github.marcoral.versatia.lifecycle.AutoInvoke
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.kotlindsl.anyExecutor
import dev.jorel.commandapi.kotlindsl.commandAPICommand
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import java.util.logging.Level
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin

/**
 * `/versatia reload [plugin]`: runs the reload-phase enhancements of every enhanced plugin, or of
 * the one with the given friendly name. Registered only while the CommandAPI plugin is on the
 * server; without it the object is never loaded. See docs/runtime.md.
 */
@ConditionalOnPlugin("CommandAPI")
object VersatiaCommand {
    const val NAME = "versatia"
    const val RELOAD_PERMISSION = "versatia.core.command.reload"

    @AutoInvoke(EventType.PLUGIN_START_ONLY)
    fun register(core: VersatiaCore) = commandAPICommand(NAME, core) {
        subcommand("reload") {
            withPermission(RELOAD_PERMISSION)
            stringArgument("plugin", optional = true) { replaceSuggestions(ArgumentSuggestions.stringCollection { core.runtime.registry.friendlyNames }) }
            anyExecutor { sender, args -> reload(core, sender, args["plugin"] as? String) }
        }
    }

    @AutoInvoke(EventType.PLUGIN_STOP)
    fun unregister() = CommandAPI.unregister(NAME, true)

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

    private fun reload(core: VersatiaCore, name: String, plugin: Plugin, sender: CommandSender) = try {
        core.runtime.reload(plugin)
        true
    } catch (e: Exception) {
        core.logger.log(Level.SEVERE, "$name failed to reload", e)
        sender.sendMessage(text("$name failed to reload: ${e.message}", NamedTextColor.RED))
        false
    }

    private fun running(plugins: Map<String, Plugin>) =
        if (plugins.isEmpty()) "No Versatia plugin is running." else "Running: ${plugins.keys.joinToString()}."
}
