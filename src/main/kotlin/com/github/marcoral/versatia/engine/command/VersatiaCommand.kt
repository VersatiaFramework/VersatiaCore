package com.github.marcoral.versatia.engine.command

import com.github.marcoral.versatia.ConditionalOnPlugin
import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.ReloadOutcome
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
 * the one with the given friendly name and then of the plugins using its beans; the reply and the
 * log name every plugin reloaded and every failure. Registered only while the CommandAPI plugin is on the
 * server; without it the object is never loaded. A CommandAPI that fails to take the command, such
 * as an incompatible release, costs only the command, never VersatiaCore. See docs/runtime.md.
 */
@ConditionalOnPlugin("CommandAPI")
object VersatiaCommand {
    const val NAME = "versatia"
    const val RELOAD_PERMISSION = "versatia.core.command.reload"

    private var registered = false

    @AutoInvoke(Phase.PLUGIN_START_ONLY)
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
            else -> return sender.sendMessage(text("No Versatia plugin is called \"$name\". ${running(plugins)}", NamedTextColor.RED))
        }
        if (selected.isEmpty()) return sender.sendMessage(text(running(plugins), NamedTextColor.YELLOW))
        val outcomes = core.runtime.reload(selected.values)
        for (outcome in outcomes) outcome.message?.let {
            outcome.log(core.logger)
            sender.sendMessage(text(it, NamedTextColor.RED))
        }

        ReloadOutcome.summary(outcomes)?.let { summary ->
            if (outcomes.any { it.cause != null }) core.logger.info(summary)
            sender.sendMessage(text(summary, NamedTextColor.GREEN))
        }
    }

    private fun running(plugins: Map<String, Plugin>) =
        if (plugins.isEmpty()) "No Versatia plugin is running." else "Running: ${plugins.keys.joinToString()}."
}
