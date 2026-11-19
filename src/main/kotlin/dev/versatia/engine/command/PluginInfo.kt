package dev.versatia.engine.command

import dev.versatia.VersatiaRuntime
import dev.versatia.config.ConfigProcessors
import dev.versatia.di.BeanResolver
import dev.versatia.di.ExposedBeanRegistry
import dev.versatia.di.PluginBeanSession
import dev.versatia.engine.descriptor.DescriptorLoader
import dev.versatia.text.arg
import org.bukkit.plugin.Plugin

/**
 * The lines of `/versatia info`, read from the descriptors and the state the runtime keeps; nothing
 * is changed. Kept apart from [VersatiaCommand], so it needs no CommandAPI. See docs/runtime.md.
 */
class PluginInfo(private val runtime: VersatiaRuntime) {
    /** One line per running Versatia plugin: friendly name, version and descriptor format. */
    fun overview() = runtime.registry.plugins.map { (name, plugin) -> headline(name, plugin) }

    /** The beans of [plugin] by scope, its exposed beans other plugins use, the plugins its reloads cascade to and its `@Value` files. */
    fun details(plugin: Plugin): List<Reply> {
        val name = runtime.registry.friendlyNameOf(plugin) ?: return listOf(Reply("<neutral>{plugin} is not a running Versatia plugin.", arg("plugin", plugin.name)))
        val beans = (runtime.stateOf(plugin)?.get(BeanResolver.KEY) as? PluginBeanSession)?.beans.orEmpty()
        val exposed = runtime.runtimeState[ExposedBeanRegistry.KEY]
        val used = exposed?.ownedBy(plugin.name).orEmpty().filter { it.consumers.isNotEmpty() }
        val cascade = exposed?.consumersReloadedWith(plugin.name).orEmpty().map(::friendlyName)
        val files = DescriptorLoader.load(plugin).elements.filter { it.processorClass == ConfigProcessors.VALUE }.mapNotNull { it.annotation.arguments["file"] }.distinct()
        return buildList {
            add(headline(name, plugin))
            add(if (beans.isEmpty()) Reply("<neutral><label>Beans:</label> none") else Reply("<label>Beans ({count}):</label>", arg("count", beans.size)))
            beans.groupBy { it.scope }.toSortedMap().forEach { (scope, group) ->
                add(Reply("<label>  ${scope.label} ({count}):</label>", arg("count", group.size)))
                group.forEach { add(Reply("<neutral>    \"{bean}\" <hint>{class}</hint>", arg("bean", it.name), arg("class", it.className))) }
            }
            val usedBy = used.map { Reply("<neutral>\"{bean}\" by {consumers}", arg("bean", it.name), arg("consumers", it.consumers.map(::friendlyName))) }
            add(Reply("<neutral><label>Exposed beans used by others:</label> {used}", arg("used", usedBy)))
            add(Reply("<neutral><label>Reloads cascade to:</label> {plugins}", arg("plugins", cascade)))
            add(Reply("<neutral><label>@Value files:</label> {files}", arg("files", files)))
        }
    }

    /** `Shop 0.0.1 (descriptor format 1.1)`, with the `plugin.yml` name inside the brackets when the friendly name differs. */
    private fun headline(name: String, plugin: Plugin): Reply {
        val values = arrayOf(arg("name", name), arg("version", plugin.pluginMeta.version), arg("plugin", plugin.name), arg("format", DescriptorLoader.load(plugin).run { "$formatMajor.$formatMinor" }))
        return if (name == plugin.name) Reply("<neutral><heading>{name}</heading> {version} (descriptor format {format})", *values)
        else Reply("<neutral><heading>{name}</heading> {version} ({plugin}, descriptor format {format})", *values)
    }

    private fun friendlyName(pluginName: String) = runtime.registry.plugins.entries.firstOrNull { it.value.name == pluginName }?.key ?: pluginName
}
