package com.github.marcoral.versatia

import org.bukkit.plugin.Plugin

/**
 * The runtime's registry: a plugin's name is reserved when its start begins, so code running during
 * the start (a module's prototype, say) already sees it through [friendlyNameOf]; the plugin joins
 * [plugins], the running ones, once its start went through, and leaves both when it is disabled or
 * its start fails.
 */
class EnhancedPlugins : PluginRegistry {
    private val byName = LinkedHashMap<String, Plugin>()
    private val starting = HashMap<Plugin, String>()

    override val plugins: Map<String, Plugin> get() = LinkedHashMap(byName)

    override fun friendlyNameOf(plugin: Plugin) = starting[plugin] ?: byName.entries.firstOrNull { it.value === plugin }?.key

    /** True once [plugin]'s start went through, until it is disabled. */
    fun isRunning(plugin: Plugin) = byName.values.any { it === plugin }

    /** Reserves [friendlyName] for [plugin] while it starts; throws when another plugin holds it, so [plugin] stays disabled. */
    fun reserve(friendlyName: String, plugin: Plugin) {
        (byName[friendlyName] ?: starting.entries.firstOrNull { it.value == friendlyName }?.key)?.takeIf { it !== plugin }?.let {
            throw EnhancementException("Plugin ${plugin.name}: the friendly name \"$friendlyName\" is already used by ${it.name}")
        }
        starting[plugin] = friendlyName
    }

    /** Registers [plugin] as running under the name [reserve] kept for it. */
    fun register(plugin: Plugin) {
        starting.remove(plugin)?.let { byName[it] = plugin }
    }

    fun unregister(plugin: Plugin) {
        starting.remove(plugin)
        byName.values.remove(plugin)
    }
}
