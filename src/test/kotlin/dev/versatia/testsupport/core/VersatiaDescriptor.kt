package dev.versatia.testsupport.core

import dev.versatia.Phase
import dev.versatia.PluginComponentLoggerBean
import dev.versatia.PluginLoggerBean
import dev.versatia.di.BeanProcessors
import dev.versatia.di.ExposePrototype
import dev.versatia.di.LocalBean
import dev.versatia.di.PluginBean
import dev.versatia.engine.descriptor.AnnotationOccurrence
import dev.versatia.engine.descriptor.ClassTarget
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.FunctionTarget
import dev.versatia.engine.descriptor.Instantiator
import dev.versatia.engine.descriptor.Invoker
import dev.versatia.engine.descriptor.MemberOwner
import dev.versatia.engine.descriptor.Parameter
import dev.versatia.engine.descriptor.PluginTarget
import dev.versatia.engine.descriptor.VersatiaPluginDescriptor
import dev.versatia.lifecycle.AutoInvoke
import dev.versatia.lifecycle.LifecycleProcessors
import dev.versatia.task.PluginScope
import dev.versatia.task.PluginScopeBean
import dev.versatia.task.ServerTicker
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.plugin.Plugin

/**
 * What VersatiaCore's descriptor holds of the tasks feature and the beans every plugin has: its
 * main class, the ServerTicker starting at START, and the PluginScope, Logger and ComponentLogger
 * prototypes every Versatia plugin injects.
 */
object VersatiaDescriptor : VersatiaPluginDescriptor {
    override val formatMajor = VersatiaPluginDescriptor.FORMAT_VERSION.major
    override val formatMinor = VersatiaPluginDescriptor.FORMAT_VERSION.minor

    const val MAIN = "dev.versatia.testsupport.core.FakeCorePlugin"

    private val plugin = Plugin::class.qualifiedName!!
    private val ticker = ServerTicker::class.qualifiedName!!
    private val scope = PluginScopeBean::class.qualifiedName!!

    private fun pluginPrototype(className: String, types: List<String>, create: (Plugin) -> Any) = EnhancedElement(
        BeanProcessors.EXPOSE_PROTOTYPE,
        Phase.PLUGIN_START_ONLY,
        AnnotationOccurrence(ExposePrototype::class.java.name),
        ClassTarget(className, listOf(className) + types, emptyList(), listOf(Parameter(plugin)), Instantiator.Direct { args -> create(args[0] as Plugin) }),
    )

    override val elements = listOf(
        EnhancedElement(
            BeanProcessors.PLUGIN,
            Phase.PLUGIN_RELOAD_OR_START,
            AnnotationOccurrence(PluginBean::class.java.name),
            PluginTarget(MAIN, listOf(MAIN, plugin), emptyList()),
        ),
        EnhancedElement(
            BeanProcessors.EXPOSE_PROTOTYPE,
            Phase.PLUGIN_START_ONLY,
            AnnotationOccurrence(ExposePrototype::class.java.name),
            ClassTarget(
                scope,
                listOf(scope, PluginScope::class.qualifiedName!!, AutoCloseable::class.java.name, CoroutineScope::class.qualifiedName!!),
                emptyList(),
                listOf(Parameter(plugin), Parameter(ticker)),
                Instantiator.Direct { args -> PluginScopeBean(args[0] as Plugin, args[1] as ServerTicker) },
            ),
        ),
        pluginPrototype(PluginLoggerBean::class.java.name, listOf(Logger::class.java.name), ::PluginLoggerBean),
        pluginPrototype(PluginComponentLoggerBean::class.java.name, listOf(ComponentLogger::class.java.name, org.slf4j.Logger::class.java.name), ::PluginComponentLoggerBean),
        EnhancedElement(
            BeanProcessors.LOCAL,
            Phase.PLUGIN_START_ONLY,
            AnnotationOccurrence(LocalBean::class.java.name),
            ClassTarget(ticker, listOf(ticker, AutoCloseable::class.java.name), emptyList(), listOf(Parameter(plugin)), Instantiator.Direct { args -> ServerTicker(args[0] as Plugin) }),
        ),
        EnhancedElement(
            LifecycleProcessors.AUTO_INVOKE,
            Phase.PLUGIN_START_ONLY,
            AnnotationOccurrence(AutoInvoke::class.java.name, mapOf("phase" to Phase.PLUGIN_START_ONLY.name)),
            FunctionTarget(ticker, ServerTicker::class.java.name, "start", MemberOwner.INSTANCE, emptyList(), emptyList(), Invoker.Direct { instance, _ -> (instance as ServerTicker).start() }),
        ),
    )
}
