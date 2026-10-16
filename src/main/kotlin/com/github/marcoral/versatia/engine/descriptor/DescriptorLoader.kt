package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.util.reflect.objectInstanceOrNull

/**
 * Finds the generated descriptor by name; nothing is scanned. The descriptor must come from the
 * plugin's own class loader, not from a plugin or library it can see. See docs/runtime.md.
 */
object DescriptorLoader {
    val supportedFormat get() = VersatiaPluginDescriptor.FORMAT_VERSION

    fun load(pluginMainClass: String, context: EnhancementContext): VersatiaPluginDescriptor {
        val name = VersatiaPluginDescriptor.classNameFor(pluginMainClass)
        val plugin = context.plugin.name
        val clazz = try {
            Class.forName(name, false, context.classLoader)
        } catch (e: ClassNotFoundException) {
            throw EnhancementException("Plugin $plugin: descriptor $name not found. Is the VersatiaAPI KSP processor applied to the plugin build?", e)
        }
        if (clazz.classLoader !== context.classLoader)
            throw EnhancementException("Plugin $plugin: descriptor $name comes from ${clazz.classLoader}, not from the plugin itself; is the plugin built with the VersatiaAPI KSP processor and its main class in a package of its own?")
        val instance = try {
            clazz.objectInstanceOrNull()
        } catch (e: LinkageError) {
            throw EnhancementException("Plugin $plugin: descriptor $name cannot be loaded ($e); rebuild the plugin against this VersatiaAPI", e)
        } ?: throw EnhancementException("Plugin $plugin: $name is not a Kotlin object; it is not a Versatia descriptor")

        // Read the version through plain getters first: a descriptor of another format may not
        // implement the interface this runtime knows
        val version = DescriptorVersion(instance.intProperty("formatMajor", plugin), instance.intProperty("formatMinor", plugin))
        DescriptorCompatibility.check(plugin, version, supportedFormat)

        return instance as? VersatiaPluginDescriptor
            ?: throw EnhancementException("Plugin $plugin: $name does not implement VersatiaPluginDescriptor")
    }

    private fun Any.intProperty(name: String, plugin: String) = try {
        javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(this) as Int
    } catch (e: ReflectiveOperationException) {
        throw EnhancementException("Plugin $plugin: ${javaClass.name} has no readable $name; it is not a Versatia descriptor", e)
    }
}
