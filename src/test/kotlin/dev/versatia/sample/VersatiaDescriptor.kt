package dev.versatia.sample

import dev.versatia.Phase
import dev.versatia.FriendlyName
import dev.versatia.di.BeanProcessors
import dev.versatia.di.PluginBean
import dev.versatia.engine.descriptor.AnnotationOccurrence
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.MemberOwner
import dev.versatia.engine.descriptor.FunctionTarget
import dev.versatia.engine.descriptor.Invoker
import dev.versatia.engine.descriptor.PluginTarget
import dev.versatia.engine.descriptor.VersatiaPluginDescriptor
import dev.versatia.lifecycle.AutoInvoke
import dev.versatia.lifecycle.LifecycleProcessors
import dev.versatia.testsupport.Recorder
import org.bukkit.plugin.Plugin

/** What a plugin named `TickerPlugin` with `@FriendlyName("ticker")` and three auto-invoked functions compiles to. */
object VersatiaDescriptor : VersatiaPluginDescriptor {
    override val formatMajor = VersatiaPluginDescriptor.FORMAT_VERSION.major
    override val formatMinor = VersatiaPluginDescriptor.FORMAT_VERSION.minor

    private const val MAIN = "dev.versatia.sample.TickerPlugin"

    override val elements = listOf(
        EnhancedElement(
            BeanProcessors.PLUGIN,
            Phase.PLUGIN_RELOAD_OR_START,
            AnnotationOccurrence(PluginBean::class.java.name),
            PluginTarget(MAIN, listOf(MAIN, Plugin::class.qualifiedName!!), listOf(AnnotationOccurrence(FriendlyName::class.java.name, mapOf("value" to "ticker")))),
        ),
        function("start", Phase.PLUGIN_START_ONLY) { Ticker.start() },
        function("tick", Phase.PLUGIN_RELOAD_OR_START) { Ticker.tick() },
        function("stop", Phase.PLUGIN_STOP) { Ticker.stop() },
    )

    private fun function(name: String, phase: Phase, call: () -> Unit) = EnhancedElement(
        LifecycleProcessors.AUTO_INVOKE,
        phase,
        AnnotationOccurrence(AutoInvoke::class.java.name, mapOf("phase" to phase.name)),
        FunctionTarget(Ticker::class.qualifiedName!!, Ticker::class.java.name, name, MemberOwner.OBJECT, emptyList(), emptyList(), Invoker.Direct { _, _ -> call() }),
    )
}

object Ticker {
    var onStart: () -> Unit = {}
    var onTick: () -> Unit = {}

    fun start() {
        onStart()
        Recorder.record("Ticker.start", this)
    }

    fun tick() {
        onTick()
        Recorder.record("Ticker.tick", this)
    }

    fun stop() = Recorder.record("Ticker.stop", this)
}
