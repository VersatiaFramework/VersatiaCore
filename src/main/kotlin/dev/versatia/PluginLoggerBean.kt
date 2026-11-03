package dev.versatia

import dev.versatia.di.ExposePrototype
import java.util.logging.Filter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.bukkit.plugin.Plugin

/**
 * Exposes the logger of the plugin injecting it as a `Logger`: records, level, handlers and filter
 * all go to `plugin.logger`, so it logs exactly as the plugin does. See docs/runtime.md.
 */
@ExposePrototype
internal class PluginLoggerBean(plugin: Plugin) : Logger(plugin.logger.name, null) {
    private val target = plugin.logger

    override fun log(record: LogRecord) = target.log(record)

    override fun isLoggable(level: Level) = target.isLoggable(level)

    override fun getLevel(): Level? = target.level

    override fun setLevel(newLevel: Level?) {
        target.level = newLevel
    }

    override fun getParent(): Logger? = target.parent

    override fun getHandlers(): Array<Handler> = target.handlers

    override fun addHandler(handler: Handler) = target.addHandler(handler)

    override fun removeHandler(handler: Handler?) = target.removeHandler(handler)

    override fun getFilter(): Filter? = target.filter

    override fun setFilter(newFilter: Filter?) {
        target.filter = newFilter
    }

    override fun getUseParentHandlers() = target.useParentHandlers

    override fun setUseParentHandlers(useParentHandlers: Boolean) {
        target.useParentHandlers = useParentHandlers
    }

    override fun toString() = "Logger of ${target.name}"
}
