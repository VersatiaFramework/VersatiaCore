package com.github.marcoral.versatia

import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.di.PluginBean
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.Invoker
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.lifecycle.AutoInvoke
import com.github.marcoral.versatia.lifecycle.LifecycleProcessors
import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.descriptor
import com.github.marcoral.versatia.testsupport.fakePlugin
import com.github.marcoral.versatia.testsupport.testContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bukkit.plugin.Plugin

class Bridge {
    init {
        Recorder.record("Bridge", this)
    }

    fun connect() = Recorder.record("Bridge.connect", this)
}

object Hooks {
    fun hook() = Recorder.record("Hooks.hook", this)
}

object Beacon {
    fun always() = Recorder.record("Beacon.always", this)
}

/** `@ConditionalOnPlugin` at runtime: elements whose plugins are missing are left out, functions of a conditional class or object with it. */
class PluginEnhancerTest {
    private val enhancer = PluginEnhancer(ProcessorLoader())

    @BeforeTest
    fun setUp() = Recorder.reset()

    private fun conditional(vararg plugins: String) = AnnotationOccurrence(ConditionalOnPlugin::class.java.name, mapOf("value" to plugins.toList()))

    private val mainName = "com.example.Main"
    private val bridgeName = Bridge::class.qualifiedName!!

    private val pluginBean = EnhancedElement(
        BeanProcessors.PLUGIN,
        EventType.PLUGIN_RELOAD_OR_START,
        AnnotationOccurrence(PluginBean::class.java.name),
        PluginTarget(mainName, listOf(mainName, Plugin::class.qualifiedName!!), emptyList()),
    )

    private val bridge = EnhancedElement(
        BeanProcessors.LOCAL,
        EventType.PLUGIN_START_ONLY,
        AnnotationOccurrence("test.Scope"),
        ClassTarget(bridgeName, listOf(bridgeName), listOf(conditional("Vault")), emptyList(), Instantiator.Direct { Bridge() }),
    )

    private fun function(className: String, name: String, classAnnotations: List<AnnotationOccurrence>, owner: MemberOwner, invoker: Invoker) = EnhancedElement(
        LifecycleProcessors.AUTO_INVOKE,
        EventType.PLUGIN_START_ONLY,
        AnnotationOccurrence(AutoInvoke::class.java.name, mapOf("phase" to EventType.PLUGIN_START_ONLY.name)),
        FunctionTarget(className, className, name, owner, emptyList(), emptyList(), invoker, classAnnotations),
    )

    private val connect = function(bridgeName, "connect", listOf(conditional("Vault")), MemberOwner.INSTANCE, Invoker.Direct { instance, _ -> (instance as Bridge).connect() })
    private val hook = function(Hooks::class.qualifiedName!!, "hook", listOf(conditional("CommandAPI", "Vault")), MemberOwner.OBJECT, Invoker.Direct { _, _ -> Hooks.hook() })
    private val always = function(Beacon::class.qualifiedName!!, "always", emptyList(), MemberOwner.OBJECT, Invoker.Direct { _, _ -> Beacon.always() })

    private fun enhance(vararg enabledPlugins: String) {
        val context = testContext("Alpha", PluginStates(), plugin = fakePlugin("Alpha", enabledPlugins = enabledPlugins.toSet()))
        enhancer.enhance(descriptor(pluginBean, bridge, connect, hook, always), context)
    }

    @Test
    fun `runs everything when the plugins are enabled`() {
        enhance("Vault", "CommandAPI")

        assertEquals(listOf("Bridge", "Bridge.connect", "Hooks.hook", "Beacon.always"), Recorder.names())
    }

    @Test
    fun `leaves out a conditional class with its functions, and an object's functions missing one of its plugins`() {
        enhance("CommandAPI")

        assertEquals(listOf("Beacon.always"), Recorder.names())
    }
}
