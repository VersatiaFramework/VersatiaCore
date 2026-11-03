package dev.versatia

import java.util.logging.Logger
import org.bukkit.plugin.Plugin

/**
 * The INFO lines of debug mode (`debug: true` in VersatiaCore's config.yml): one per step of a
 * plugin's start, reload and stop, written to [logger] only while [enabled] answers true, so a
 * message is never even built otherwise. Kept in the runtime state; see docs/runtime.md.
 */
class DebugLog(private val logger: Logger, private val enabled: () -> Boolean) {
    fun log(plugin: Plugin, event: LifecycleEvent, message: () -> String) {
        if (enabled()) logger.info("[debug] ${plugin.name} $event: ${message()}")
    }

    companion object {
        val KEY = StateKey<DebugLog>("debug-log")
    }
}

/** Writes a debug-mode line about this event of the plugin; nothing when debug mode is off. */
internal fun EnhancementContext.debug(message: () -> String) = runtimeState[DebugLog.KEY]?.log(plugin, event, message)
