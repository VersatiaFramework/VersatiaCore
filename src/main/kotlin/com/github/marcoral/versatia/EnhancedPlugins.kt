package com.github.marcoral.versatia

import org.bukkit.plugin.Plugin

/** The runtime's registry: filled when a plugin's start event went through, emptied when it is disabled. */
class EnhancedPlugins : PluginRegistry {
    private val byName = LinkedHashMap<String, Plugin>()

    override val plugins: Map<String, Plugin> get() = LinkedHashMap(byName)

    /** Registers [plugin] under [friendlyName]; a name another plugin holds is an error, so the plugin stays disabled. */
    fun register(friendlyName: String, plugin: Plugin) {
        byName[friendlyName]?.takeIf { it !== plugin }?.let {
            throw EnhancementException("Plugin ${plugin.name}: the friendly name \"$friendlyName\" is already used by ${it.name}")
        }
        byName[friendlyName] = plugin
    }

    fun unregister(plugin: Plugin) = byName.values.remove(plugin)
}
