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
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.lifecycle.AutoInvoke
import com.github.marcoral.versatia.lifecycle.LifecycleProcessors
import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.descriptor
import com.github.marcoral.versatia.testsupport.fakePlugin
import com.github.marcoral.versatia.testsupport.primary
import com.github.marcoral.versatia.testsupport.testContext
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

interface Gateway

class PlainGateway : Gateway

class VaultGateway : Gateway

object Checkout {
    fun pay(gateway: Gateway) = Recorder.record("Checkout.pay", gateway)
}

class FirstRecordingProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        Recorder.record("First", elements.size)
        context.onPluginEnhanced {
            Recorder.record("First.completed", this)
            context.onPluginEnhanced { Recorder.record("First.nested", this) }
        }
    }
}

class SecondRecordingProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) = Recorder.record("Second", elements.size)
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
        Phase.PLUGIN_RELOAD_OR_START,
        AnnotationOccurrence(PluginBean::class.java.name),
        PluginTarget(mainName, listOf(mainName, Plugin::class.qualifiedName!!), emptyList()),
    )

    private val bridge = EnhancedElement(
        BeanProcessors.LOCAL,
        Phase.PLUGIN_START_ONLY,
        AnnotationOccurrence("test.Scope"),
        ClassTarget(bridgeName, listOf(bridgeName), listOf(conditional("Vault")), emptyList(), Instantiator.Direct { Bridge() }),
    )

    private fun function(
        className: String,
        name: String,
        classAnnotations: List<AnnotationOccurrence>,
        owner: MemberOwner,
        invoker: Invoker,
        phase: Phase = Phase.PLUGIN_START_ONLY,
        parameters: List<Parameter> = emptyList(),
    ) = EnhancedElement(
        LifecycleProcessors.AUTO_INVOKE,
        phase,
        AnnotationOccurrence(AutoInvoke::class.java.name, mapOf("phase" to phase.name)),
        FunctionTarget(className, className, name, owner, emptyList(), parameters, invoker, classAnnotations),
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

    @Test
    fun `keeps the start decision at reload when a required plugin is enabled later`() {
        val states = PluginStates()
        val elements = descriptor(pluginBean, bridge, connect.copy(phase = Phase.PLUGIN_RELOAD_OR_START))
        enhancer.enhance(elements, testContext("Alpha", states, plugin = fakePlugin("Alpha")))

        val reload = testContext("Alpha", states, event = LifecycleEvent.RELOAD, plugin = fakePlugin("Alpha", enabledPlugins = setOf("Vault")))
        enhancer.enhance(elements, reload)

        assertEquals(emptyList(), Recorder.names())
    }

    @Test
    fun `a skipped @Primary bean leaves the type to the other provider`() {
        val gateway = Gateway::class.qualifiedName!!
        fun bean(className: String, annotations: List<AnnotationOccurrence>, create: () -> Any) = EnhancedElement(
            BeanProcessors.LOCAL,
            Phase.PLUGIN_START_ONLY,
            AnnotationOccurrence("test.Scope"),
            ClassTarget(className, listOf(className, gateway), annotations, emptyList(), Instantiator.Direct { create() }),
        )
        val plain = bean(PlainGateway::class.qualifiedName!!, emptyList()) { PlainGateway() }
        val vault = bean(VaultGateway::class.qualifiedName!!, listOf(primary, conditional("Vault"))) { VaultGateway() }
        val pay = function(Checkout::class.qualifiedName!!, "pay", emptyList(), MemberOwner.OBJECT, Invoker.Direct { _, args -> Checkout.pay(args.single() as Gateway) }, parameters = listOf(Parameter(gateway)))
        fun start(vararg enabledPlugins: String) = enhancer.enhance(
            descriptor(pluginBean, plain, vault, pay),
            testContext("Alpha", PluginStates(), plugin = fakePlugin("Alpha", enabledPlugins = enabledPlugins.toSet())),
        )

        start()
        assertIs<PlainGateway>(Recorder.instanceOf("Checkout.pay"))

        Recorder.reset()
        start("Vault")
        assertIs<VaultGateway>(Recorder.instanceOf("Checkout.pay"))
    }

    @Test
    fun `logs each missing plugin once, telling one not installed from one not enabled yet`() {
        // A function conditional on its own, in a class without the condition, is named as the function
        val ownCondition = EnhancedElement(
            LifecycleProcessors.AUTO_INVOKE,
            Phase.PLUGIN_START_ONLY,
            AnnotationOccurrence(AutoInvoke::class.java.name, mapOf("phase" to Phase.PLUGIN_START_ONLY.name)),
            FunctionTarget(Beacon::class.qualifiedName!!, Beacon::class.qualifiedName!!, "always", MemberOwner.OBJECT, listOf(conditional("Vault")), emptyList(), Invoker.Direct { _, _ -> Beacon.always() }, emptyList()),
        )
        val logged = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                logged += record.message
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val plugin = fakePlugin("Alpha", installedPlugins = setOf("Vault"))
        plugin.logger.addHandler(handler)
        try {
            enhancer.enhance(descriptor(pluginBean, bridge, connect, hook, always, ownCondition), testContext("Alpha", PluginStates(), plugin = plugin))
        } finally {
            plugin.logger.removeHandler(handler)
        }

        assertEquals(
            listOf(
                "Skipped Bridge, Hooks, Beacon.always(): Vault is installed but was not enabled before Alpha; list it under softdepend: in plugin.yml",
                "Skipped Hooks: CommandAPI is not installed",
            ),
            logged,
        )
    }

    @Test
    fun `runs the processors in the order of their first element and drains completion actions added meanwhile`() {
        val first = pluginBean.copy(processorClass = FirstRecordingProcessor::class.java.name)
        val second = pluginBean.copy(processorClass = SecondRecordingProcessor::class.java.name)

        enhancer.enhance(descriptor(second, first, second), testContext("Alpha", PluginStates(), plugin = fakePlugin("Alpha")))

        assertEquals(listOf("Second", "First", "First.completed", "First.nested"), Recorder.names())
        assertEquals(2, Recorder.instanceOf("Second"), "a processor gets all its elements at once")
    }

    @Test
    fun `runs every stop function even when one fails`() {
        val states = PluginStates()
        val failing = function(Beacon::class.qualifiedName!!, "fail", emptyList(), MemberOwner.OBJECT, Invoker.Direct { _, _ -> error("save failed") }, Phase.PLUGIN_STOP)
        val closing = function(Hooks::class.qualifiedName!!, "close", emptyList(), MemberOwner.OBJECT, Invoker.Direct { _, _ -> Recorder.record("Hooks.close", Hooks) }, Phase.PLUGIN_STOP)
        val elements = descriptor(pluginBean, failing, closing)
        enhancer.enhance(elements, testContext("Alpha", states, plugin = fakePlugin("Alpha")))

        val error = assertFailsWith<EnhancementException> { enhancer.enhance(elements, testContext("Alpha", states, event = LifecycleEvent.STOP, plugin = fakePlugin("Alpha"))) }

        assertContains(error.message!!, "${Beacon::class.qualifiedName}.fail() failed at STOP")
        assertEquals(listOf("Hooks.close"), Recorder.names())
    }

    @Test
    fun `skips what is left of a start or a reload once the plugin is disabled`() {
        val states = PluginStates()
        var disableAt: LifecycleEvent? = null
        lateinit var current: DefaultEnhancementContext
        val disabling = function(Hooks::class.qualifiedName!!, "disable", emptyList(), MemberOwner.OBJECT, Invoker.Direct { _, _ ->
            if (current.event == disableAt) current.cancel()
        }, Phase.PLUGIN_RELOAD_OR_START)
        val elements = descriptor(pluginBean, disabling, always.copy(phase = Phase.PLUGIN_RELOAD_OR_START))
        fun run(event: LifecycleEvent, statesOfRun: PluginStates = states) {
            current = testContext("Alpha", statesOfRun, event = event, plugin = fakePlugin("Alpha"))
            enhancer.enhance(elements, current)
        }

        disableAt = LifecycleEvent.START
        run(LifecycleEvent.START, PluginStates())
        assertEquals(emptyList(), Recorder.names())

        disableAt = LifecycleEvent.RELOAD
        run(LifecycleEvent.START)
        assertEquals(listOf("Beacon.always"), Recorder.names())
        Recorder.reset()
        run(LifecycleEvent.RELOAD)
        assertEquals(emptyList(), Recorder.names())
    }
}
