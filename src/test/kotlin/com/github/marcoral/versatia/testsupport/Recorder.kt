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
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.Server
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.RegisteredListener

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
 * A `Plugin` that knows its name, logger, component logger (which answers only its name), data
 * folder, main class and a server whose plugin manager records listener registrations in
 * [registrations], registers the executors of
 * `registerEvent` with the event's real `HandlerList`, delivers `callEvent` to them and reports
 * [enabledPlugins] as enabled, and nothing else. A real `JavaPlugin` cannot exist outside a Bukkit
 * plugin class loader. The proxy class is defined in [classLoader], so `plugin.javaClass.classLoader`
 * behaves like a real plugin's; with [listener] it implements `Listener` too, like a main class that
 * listens to events. The server answers that the caller runs on the main thread while
 * [primaryThread] returns true, by default on the thread creating the plugin, and its scheduler is
 * the [FakeScheduler].
 */
fun fakePlugin(
    name: String,
    classLoader: ClassLoader = Recorder::class.java.classLoader,
    dataFolder: File? = null,
    listener: Boolean = false,
    registrations: MutableList<Any>? = null,
    main: String? = null,
    enabledPlugins: Set<String> = emptySet(),
    installedPlugins: Set<String> = enabledPlugins,
    primaryThread: () -> Boolean = Thread.currentThread().let { creator -> { Thread.currentThread() === creator } },
): Plugin {
    val logger = Logger.getLogger("test.$name")
    val componentLogger = Proxy.newProxyInstance(classLoader, arrayOf(ComponentLogger::class.java)) { _, method, _ ->
        if (method.name == "getName") "test.$name" else throw UnsupportedOperationException("FakePlugin's ComponentLogger does not support ${method.name}")
    } as ComponentLogger
    val server = fakeServer(registrations ?: mutableListOf(), enabledPlugins, installedPlugins, primaryThread)
    val interfaces = if (listener) arrayOf(Plugin::class.java, Listener::class.java) else arrayOf(Plugin::class.java)
    return Proxy.newProxyInstance(classLoader, interfaces) { proxy, method, args ->
        when (method.name) {
            "getName" -> name
            "getLogger" -> logger
            "getComponentLogger" -> componentLogger
            "getDataFolder" -> dataFolder ?: throw UnsupportedOperationException("FakePlugin $name has no data folder")
            "getDescription", "getPluginMeta" -> PluginDescriptionFile(name, "0.0.1", main ?: throw UnsupportedOperationException("FakePlugin $name has no main class"))
            "getServer" -> server
            "isEnabled" -> true
            "toString" -> "FakePlugin($name)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            else -> throw UnsupportedOperationException("FakePlugin does not support ${method.name}")
        }
    } as Plugin
}

/**
 * A `Server` whose plugin manager records the listeners registered with it, registers event
 * executors with the event's `HandlerList`, calls them for `callEvent` on the calling thread and
 * knows which plugins are installed and enabled, and that knows which thread is the main one.
 */
private fun fakeServer(registrations: MutableList<Any>, enabledPlugins: Set<String>, installedPlugins: Set<String>, primaryThread: () -> Boolean): Server {
    val pluginManager = Proxy.newProxyInstance(Recorder::class.java.classLoader, arrayOf(PluginManager::class.java)) { _, method, args ->
        when {
            method.name == "registerEvents" -> registrations.add(args[0]).let { null }
            method.name == "registerEvent" -> handlersOf(args[0] as Class<*>)
                .register(RegisteredListener(args[1] as Listener, args[3] as EventExecutor, args[2] as EventPriority, args[4] as Plugin, args.getOrNull(5) == true))
            method.name == "callEvent" -> (args[0] as Event).let { event -> event.handlers.registeredListeners.forEach { it.callEvent(event) } }
            method.name == "isPluginEnabled" && args[0] is String -> args[0] in enabledPlugins
            method.name == "getPlugin" -> (args[0] as String).takeIf { it in installedPlugins }?.let { fakePlugin(it) }
            else -> throw UnsupportedOperationException("FakePluginManager does not support ${method.name}")
        }
    } as PluginManager
    return Proxy.newProxyInstance(Recorder::class.java.classLoader, arrayOf(Server::class.java)) { _, method, _ ->
        when (method.name) {
            "getPluginManager" -> pluginManager
            "isPrimaryThread" -> primaryThread()
            "getScheduler" -> FakeScheduler.scheduler
            else -> throw UnsupportedOperationException("FakeServer does not support ${method.name}")
        }
    } as Server
}

/** The `HandlerList` of [event], found as Bukkit finds it: through its static `getHandlerList()`. */
fun handlersOf(event: Class<*>) = event.getMethod("getHandlerList").invoke(null) as HandlerList

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
