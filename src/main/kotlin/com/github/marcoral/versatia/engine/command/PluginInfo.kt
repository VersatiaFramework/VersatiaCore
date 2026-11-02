package com.github.marcoral.versatia.engine.command

import com.github.marcoral.versatia.VersatiaRuntime
import com.github.marcoral.versatia.config.ConfigProcessors
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.di.ExposedBeanRegistry
import com.github.marcoral.versatia.di.PluginBeanSession
import com.github.marcoral.versatia.engine.command.CommandText.field
import com.github.marcoral.versatia.engine.command.CommandText.joined
import com.github.marcoral.versatia.engine.command.CommandText.line
import com.github.marcoral.versatia.engine.command.CommandText.values
import com.github.marcoral.versatia.engine.descriptor.DescriptorLoader
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.plugin.Plugin

/**
 * The lines of `/versatia info`, read from the descriptors and the state the runtime keeps; nothing
 * is changed. Kept apart from [VersatiaCommand], so it needs no CommandAPI. See docs/runtime.md.
 */
class PluginInfo(private val runtime: VersatiaRuntime) {
    /** One line per running Versatia plugin: friendly name, version and descriptor format. */
    fun overview() = runtime.registry.plugins.map { (name, plugin) -> headline(name, plugin) }

    /** The beans of [plugin] by scope, its exposed beans other plugins use, the plugins its reloads cascade to and its `@Value` files. */
    fun details(plugin: Plugin): List<Component> {
        val name = runtime.registry.friendlyNameOf(plugin) ?: return listOf(line("{} is not a running Versatia plugin.", plugin.name))
        val beans = (runtime.stateOf(plugin)?.get(BeanResolver.KEY) as? PluginBeanSession)?.beans.orEmpty()
        val exposed = runtime.runtimeState[ExposedBeanRegistry.KEY]
        val used = exposed?.ownedBy(plugin.name).orEmpty().filter { it.consumers.isNotEmpty() }
        val cascade = exposed?.consumersReloadedWith(plugin.name).orEmpty().map(::friendlyName)
        val files = DescriptorLoader.load(plugin).elements.filter { it.processorClass == ConfigProcessors.VALUE }.mapNotNull { it.annotation.arguments["file"] }.distinct()
        return buildList {
            add(headline(name, plugin))
            add(if (beans.isEmpty()) field("Beans", "none") else field("Beans (${beans.size})"))
            beans.groupBy { it.scope }.toSortedMap().forEach { (scope, group) ->
                add(field("  ${scope.label} (${group.size})"))
                group.forEach { add(line("    \"{}\" {}", it.name, text(it.className))) }
            }
            add(field("Exposed beans used by others", "{}", joined(used.map { line("\"{}\" by {}", it.name, values(it.consumers.map(::friendlyName))) })))
            add(field("Reloads cascade to", "{}", values(cascade)))
            add(field("@Value files", "{}", values(files)))
        }
    }

    /** `Shop 0.0.1 (descriptor format 1.6)`, with the `plugin.yml` name inside the brackets when the friendly name differs. */
    private fun headline(name: String, plugin: Plugin): Component {
        val format = DescriptorLoader.load(plugin).run { "$formatMajor.$formatMinor" }
        val title = text(name, CommandText.VALUE, TextDecoration.BOLD)
        return if (name == plugin.name) line("{} {} (descriptor format {})", title, plugin.pluginMeta.version, format)
        else line("{} {} ({}, descriptor format {})", title, plugin.pluginMeta.version, plugin.name, format)
    }

    private fun friendlyName(pluginName: String) = runtime.registry.plugins.entries.firstOrNull { it.value.name == pluginName }?.key ?: pluginName
}
