package com.github.marcoral.versatia.testsupport

import com.github.marcoral.versatia.DefaultEnhancementContext
import com.github.marcoral.versatia.LifecycleEvent
import com.github.marcoral.versatia.PluginStates
import com.github.marcoral.versatia.di.ExposedBeanRegistry
import com.github.marcoral.versatia.di.Primary
import com.github.marcoral.versatia.di.Qualifier
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor
import java.io.File
import java.lang.reflect.Proxy
import java.util.logging.Logger
import org.bukkit.plugin.Plugin

/** Shared with sample plugin sources compiled inside tests, so beans can report their creation. */
object Recorder {
    val events = mutableListOf<Pair<String, Any>>()

    fun record(name: String, instance: Any) {
        events += name to instance
    }

    fun names() = events.map { it.first }

    fun instanceOf(name: String) = events.single { it.first == name }.second

    fun reset() = events.clear()
}

/**
 * A `Plugin` that knows its name, logger and data folder and nothing else. A real `JavaPlugin`
 * cannot exist outside a Bukkit plugin class loader. The proxy class is defined in [classLoader],
 * so `plugin.javaClass.classLoader` behaves like a real plugin's.
 */
fun fakePlugin(name: String, classLoader: ClassLoader = Recorder::class.java.classLoader, dataFolder: File? = null): Plugin {
    val logger = Logger.getLogger("test.$name")
    return Proxy.newProxyInstance(classLoader, arrayOf(Plugin::class.java)) { proxy, method, args ->
        when (method.name) {
            "getName" -> name
            "getLogger" -> logger
            "getDataFolder" -> dataFolder ?: throw UnsupportedOperationException("FakePlugin $name has no data folder")
            "toString" -> "FakePlugin($name)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            else -> throw UnsupportedOperationException("FakePlugin does not support ${method.name}")
        }
    } as Plugin
}

/** A context for one event of [pluginName]; [states] keeps the plugin's state across contexts, like the runtime does. */
fun testContext(
    pluginName: String,
    states: PluginStates,
    classLoader: ClassLoader = Recorder::class.java.classLoader,
    event: LifecycleEvent = LifecycleEvent.START,
    plugin: Plugin = fakePlugin(pluginName, classLoader),
) = DefaultEnhancementContext(plugin, event, states.of(pluginName), states.runtime)

/** How many beans are exposed between plugins in [states]. */
fun PluginStates.exposedBeanCount() = runtime[ExposedBeanRegistry.KEY]?.size ?: 0

fun descriptor(vararg elements: EnhancedElement) = object : VersatiaPluginDescriptor {
    override val formatMajor = VersatiaPluginDescriptor.FORMAT_VERSION.major
    override val formatMinor = VersatiaPluginDescriptor.FORMAT_VERSION.minor
    override val elements = elements.toList()
}

fun qualifier(name: String) = AnnotationOccurrence(Qualifier::class.java.name, mapOf("value" to name))

val primary = AnnotationOccurrence(Primary::class.java.name)
