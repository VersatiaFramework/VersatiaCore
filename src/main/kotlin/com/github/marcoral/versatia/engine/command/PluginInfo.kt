package com.github.marcoral.versatia.engine.command

import com.github.marcoral.versatia.VersatiaRuntime
import com.github.marcoral.versatia.config.ConfigProcessors
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.di.ExposedBeanRegistry
import com.github.marcoral.versatia.di.PluginBeanSession
import com.github.marcoral.versatia.engine.descriptor.DescriptorLoader
import org.bukkit.plugin.Plugin

/**
 * The lines of `/versatia info`, read from the descriptors and the state the runtime keeps; nothing
 * is changed. Kept apart from [VersatiaCommand], so it needs no CommandAPI. See docs/runtime.md.
 */
class PluginInfo(private val runtime: VersatiaRuntime) {
    /** One line per running Versatia plugin: friendly name, plugin name, version and descriptor format. */
    fun overview() = runtime.registry.plugins.map { (name, plugin) -> headline(name, plugin) }

    /** The beans of [plugin], its exposed beans other plugins use, the plugins its reloads cascade to and its `@Value` files. */
    fun details(plugin: Plugin): List<String> {
        val name = runtime.registry.friendlyNameOf(plugin) ?: return listOf("${plugin.name} is not a running Versatia plugin.")
        val beans = (runtime.stateOf(plugin)?.get(BeanResolver.KEY) as? PluginBeanSession)?.beans.orEmpty()
        val exposed = runtime.runtimeState[ExposedBeanRegistry.KEY]
        val used = exposed?.ownedBy(plugin.name).orEmpty().filter { it.consumers.isNotEmpty() }
        val cascade = exposed?.consumersReloadedWith(plugin.name).orEmpty().map(::friendlyName)
        val files = DescriptorLoader.load(plugin).elements.filter { it.processorClass == ConfigProcessors.VALUE }.map { it.annotation.arguments["file"] }.distinct()
        return buildList {
            add(headline(name, plugin))
            add("Beans (${beans.size}):")
            beans.forEach { add("  \"${it.name}\" ${it.className}, ${it.scope.label}") }
            add("Exposed beans used by others: ${used.joinToString { "\"${it.name}\" by ${it.consumers.joinToString(transform = ::friendlyName)}" }.ifEmpty { "none" }}")
            add("Reloads cascade to: ${cascade.joinToString().ifEmpty { "none" }}")
            add("@Value files: ${files.joinToString().ifEmpty { "none" }}")
        }
    }

    private fun headline(name: String, plugin: Plugin) =
        "$name (${plugin.name} ${plugin.pluginMeta.version}), descriptor format ${DescriptorLoader.load(plugin).run { "$formatMajor.$formatMinor" }}"

    private fun friendlyName(pluginName: String) = runtime.registry.plugins.entries.firstOrNull { it.value.name == pluginName }?.key ?: pluginName
}
