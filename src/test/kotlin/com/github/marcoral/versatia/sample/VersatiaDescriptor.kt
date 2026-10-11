package com.github.marcoral.versatia.sample

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.FriendlyName
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.di.PluginBean
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionOwner
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.Invoker
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor
import com.github.marcoral.versatia.lifecycle.AutoInvoke
import com.github.marcoral.versatia.lifecycle.LifecycleProcessors
import com.github.marcoral.versatia.testsupport.Recorder
import org.bukkit.plugin.Plugin

/** What a plugin named `TickerPlugin` with `@FriendlyName("ticker")` and three auto-invoked functions compiles to. */
object VersatiaDescriptor : VersatiaPluginDescriptor {
    override val formatMajor = VersatiaPluginDescriptor.FORMAT_VERSION.major
    override val formatMinor = VersatiaPluginDescriptor.FORMAT_VERSION.minor

    private const val MAIN = "com.github.marcoral.versatia.sample.TickerPlugin"

    override val elements = listOf(
        EnhancedElement(
            BeanProcessors.PLUGIN,
            EventType.PLUGIN_START_ONLY,
            AnnotationOccurrence(PluginBean::class.java.name),
            PluginTarget(MAIN, listOf(MAIN, Plugin::class.qualifiedName!!), listOf(AnnotationOccurrence(FriendlyName::class.java.name, mapOf("value" to "ticker")))),
        ),
        function("start", EventType.PLUGIN_START_ONLY) { Ticker.start() },
        function("tick", EventType.PLUGIN_RELOAD_OR_START) { Ticker.tick() },
        function("stop", EventType.PLUGIN_STOP) { Ticker.stop() },
    )

    private fun function(name: String, phase: EventType, call: () -> Unit) = EnhancedElement(
        LifecycleProcessors.AUTO_INVOKE,
        phase,
        AnnotationOccurrence(AutoInvoke::class.java.name, mapOf("phase" to phase.name)),
        FunctionTarget(Ticker::class.qualifiedName!!, Ticker::class.java.name, name, FunctionOwner.OBJECT, emptyList(), emptyList(), Invoker.Direct { _, _ -> call() }),
    )
}

object Ticker {
    fun start() = Recorder.record("Ticker.start", this)

    fun tick() = Recorder.record("Ticker.tick", this)

    fun stop() = Recorder.record("Ticker.stop", this)
}
