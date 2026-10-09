package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.classLoader

/** Finds the generated descriptor by name; nothing is scanned. See docs/runtime.md. */
object DescriptorLoader {
    val supportedFormat get() = VersatiaPluginDescriptor.FORMAT_VERSION

    fun load(pluginMainClass: String, context: EnhancementContext): VersatiaPluginDescriptor {
        val name = VersatiaPluginDescriptor.classNameFor(pluginMainClass)
        val clazz = try {
            Class.forName(name, true, context.classLoader)
        } catch (e: ClassNotFoundException) {
            throw EnhancementException("Descriptor $name not found. Is the VersatiaAPI KSP processor applied to the plugin build?", e)
        }
        val instance = clazz.getDeclaredField("INSTANCE").get(null)

        // Read the version through plain getters first: a descriptor of another format may not
        // implement the interface this runtime knows
        val version = DescriptorVersion(instance.intProperty("formatMajor"), instance.intProperty("formatMinor"))
        DescriptorCompatibility.check(context.plugin.name, version, supportedFormat)

        return instance as? VersatiaPluginDescriptor
            ?: throw EnhancementException("$name does not implement VersatiaPluginDescriptor")
    }

    private fun Any.intProperty(name: String) = try {
        javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(this) as Int
    } catch (e: ReflectiveOperationException) {
        throw EnhancementException("${javaClass.name} has no readable $name; it is not a Versatia descriptor", e)
    }
}
