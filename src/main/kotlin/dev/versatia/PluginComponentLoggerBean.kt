package dev.versatia

import dev.versatia.di.ExposePrototype
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.plugin.Plugin

/** Exposes `plugin.componentLogger` of the plugin injecting it as a `ComponentLogger`. See docs/runtime.md. */
@ExposePrototype
internal class PluginComponentLoggerBean(plugin: Plugin) : ComponentLogger by plugin.componentLogger
