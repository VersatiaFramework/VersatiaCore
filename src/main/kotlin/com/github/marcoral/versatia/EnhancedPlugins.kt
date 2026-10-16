package com.github.marcoral.versatia

import org.bukkit.plugin.Plugin

/** The runtime's registry: filled when a plugin's start event went through, emptied when it is disabled. */
class EnhancedPlugins : PluginRegistry {
    private val byName = LinkedHashMap<String, Plugin>()

    override val plugins: Map<String, Plugin> get() = LinkedHashMap(byName)

    /** Throws when another plugin holds [friendlyName]; checked before [plugin] starts, so it stays disabled. */
    fun checkAvailable(friendlyName: String, plugin: Plugin) {
        byName[friendlyName]?.takeIf { it !== plugin }?.let {
            throw EnhancementException("Plugin ${plugin.name}: the friendly name \"$friendlyName\" is already used by ${it.name}")
        }
    }

    /** Registers [plugin] under [friendlyName], once [checkAvailable] has let it start. */
    fun register(friendlyName: String, plugin: Plugin) {
        byName[friendlyName] = plugin
    }

    fun unregister(plugin: Plugin) = byName.values.remove(plugin)
}
