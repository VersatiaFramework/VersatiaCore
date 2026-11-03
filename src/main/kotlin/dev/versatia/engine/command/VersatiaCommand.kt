package dev.versatia.engine.command

import dev.versatia.ConditionalOnPlugin
import dev.versatia.Phase
import dev.versatia.ReloadOutcome
import dev.versatia.VersatiaCore
import dev.versatia.engine.command.CommandText.line
import dev.versatia.engine.command.CommandText.lines
import dev.versatia.engine.command.CommandText.plain
import dev.versatia.engine.command.CommandText.values
import dev.versatia.lifecycle.AutoInvoke
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.kotlindsl.anyExecutor
import dev.jorel.commandapi.kotlindsl.commandAPICommand
import dev.jorel.commandapi.kotlindsl.stringArgument
import dev.jorel.commandapi.kotlindsl.subcommand
import java.util.logging.Level
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin

/**
 * `/versatia info [plugin]`: lists the running Versatia plugins, or shows what the runtime keeps for
 * one of them; see [PluginInfo]. `/versatia reload [plugin]`: runs the reload-phase enhancements of every enhanced plugin, or of
 * the one with the given friendly name and then of the plugins using its beans; the reply and the
 * log name every plugin reloaded and every failure. Registered only while the CommandAPI plugin is on the
 * server; without it the object is never loaded. A CommandAPI that fails to take the command, such
 * as an incompatible release, costs only the command, never VersatiaCore. See docs/runtime.md.
 */
@ConditionalOnPlugin("CommandAPI")
object VersatiaCommand {
    const val NAME = "versatia"
    const val RELOAD_PERMISSION = "versatia.core.command.reload"
    const val INFO_PERMISSION = "versatia.core.command.info"

    private var registered = false

    @AutoInvoke(Phase.PLUGIN_START_ONLY)
    fun register(core: VersatiaCore) = failSoft(core, "registered") {
        commandAPICommand(NAME, core) {
            subcommand("reload") {
                withPermission(RELOAD_PERMISSION)
                stringArgument("plugin", optional = true) { replaceSuggestions(ArgumentSuggestions.stringCollection { core.runtime.registry.friendlyNames }) }
                anyExecutor { sender, args -> reload(core, sender, args["plugin"] as? String) }
            }
            subcommand("info") {
                withPermission(INFO_PERMISSION)
                stringArgument("plugin", optional = true) { replaceSuggestions(ArgumentSuggestions.stringCollection { core.runtime.registry.friendlyNames }) }
                anyExecutor { sender, args -> info(core, sender, args["plugin"] as? String) }
            }
        }
        registered = true
    }

    @AutoInvoke(Phase.PLUGIN_STOP)
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
            else -> return unknown(sender, name, plugins)
        }
        if (selected.isEmpty()) return sender.sendMessage(running(plugins))
        val outcomes = core.runtime.reload(selected.values)
        for (outcome in outcomes) outcome.reply?.let {
            outcome.log(core.logger)
            sender.sendMessage(it)
        }

        ReloadOutcome.summary(outcomes)?.let { summary ->
            if (outcomes.any { it.cause != null }) core.logger.info(summary.plain())
            sender.sendMessage(summary)
        }
    }

    private fun info(core: VersatiaCore, sender: CommandSender, name: String?) {
        val plugins = core.runtime.registry.plugins
        val info = PluginInfo(core.runtime)
        val lines = when (name) {
            null -> info.overview().ifEmpty { listOf(running(plugins)) }
            in plugins -> info.details(plugins.getValue(name))
            else -> return unknown(sender, name, plugins)
        }
        sender.sendMessage(lines(lines))
    }

    private fun unknown(sender: CommandSender, name: String, plugins: Map<String, Plugin>) =
        sender.sendMessage(line("No Versatia plugin is called \"{}\". {}", name, running(plugins), color = CommandText.ERROR))

    private fun running(plugins: Map<String, Plugin>) =
        if (plugins.isEmpty()) line("No Versatia plugin is running.", color = CommandText.WARNING) else line("Running: {}.", values(plugins.keys))
}
