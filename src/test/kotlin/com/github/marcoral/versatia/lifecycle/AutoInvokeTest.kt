package com.github.marcoral.versatia.lifecycle

import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.LifecycleEvent
import com.github.marcoral.versatia.PluginEnhancer
import com.github.marcoral.versatia.PluginStates
import com.github.marcoral.versatia.ProcessorLoader
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.di.PluginBean
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionOwner
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.Invoker
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.descriptor
import com.github.marcoral.versatia.testsupport.fakePlugin
import com.github.marcoral.versatia.testsupport.testContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.bukkit.plugin.Plugin

object Registry {
    fun refresh() = Recorder.record("Registry.refresh", this)
}

private object HiddenRegistry {
    private fun reset(greeter: Greeter) = Recorder.record("HiddenRegistry.reset", greeter)
}

class Greeter {
    init {
        Recorder.record("Greeter", this)
    }

    fun greet() = Recorder.record("Greeter.greet", this)
}

class MainClass(name: String = "Main") : Plugin by fakePlugin(name) {
    fun start() = Recorder.record("MainClass.start", this)

    fun stop() = Recorder.record("MainClass.stop", this)

    fun welcome(greeter: Greeter) = Recorder.record("MainClass.welcome", greeter)

    fun introduce(self: MainClass) = Recorder.record("MainClass.introduce", self)

    fun explode(): Unit = throw IllegalStateException("boom")
}

/** Unit tests of `@AutoInvoke` at runtime with hand-written descriptors. */
class AutoInvokeTest {
    private lateinit var states: PluginStates
    private lateinit var enhancer: PluginEnhancer

    @BeforeTest
    fun setUp() {
        Recorder.reset()
        states = PluginStates()
        enhancer = PluginEnhancer(ProcessorLoader())
    }

    private fun context(pluginName: String, event: LifecycleEvent = LifecycleEvent.START, plugin: Plugin = fakePlugin(pluginName)) =
        testContext(pluginName, states, event = event, plugin = plugin)

    private val greeterName = Greeter::class.qualifiedName!!

    private inline fun <reified T : Any> function(
        name: String,
        owner: FunctionOwner,
        invoker: Invoker,
        phase: EventType = EventType.PLUGIN_START_ONLY,
        parameters: List<String> = emptyList(),
    ) = EnhancedElement(
        LifecycleProcessors.AUTO_INVOKE,
        phase,
        AnnotationOccurrence(AutoInvoke::class.java.name, mapOf("phase" to phase.name)),
        FunctionTarget(T::class.qualifiedName!!, T::class.java.name, name, owner, emptyList(), parameters.map { Parameter(it) }, invoker),
    )

    /** The element every real descriptor starts with; it gives the plugin its bean container. */
    private fun pluginElement(mainName: String = "com.example.Main") = EnhancedElement(
        BeanProcessors.PLUGIN,
        EventType.PLUGIN_START_ONLY,
        AnnotationOccurrence(PluginBean::class.java.name),
        PluginTarget(mainName, listOf(mainName, Plugin::class.qualifiedName!!), emptyList()),
    )

    private fun greeterBean(processorClass: String = BeanProcessors.LOCAL) = EnhancedElement(
        processorClass,
        EventType.PLUGIN_START_ONLY,
        AnnotationOccurrence("test.Scope"),
        ClassTarget(greeterName, listOf(greeterName), emptyList(), emptyList(), Instantiator.Direct { Greeter() }),
    )

    @Test
    fun `calls functions of the plugin, of objects and of beans after beans exist`() {
        val main = MainClass()
        val elements = descriptor(
            function<MainClass>("start", FunctionOwner.PLUGIN, Invoker.Direct { instance, _ -> (instance as MainClass).start() }, EventType.PLUGIN_RELOAD_OR_START),
            function<Greeter>("greet", FunctionOwner.INSTANCE, Invoker.Direct { instance, _ -> (instance as Greeter).greet() }),
            function<Registry>("refresh", FunctionOwner.OBJECT, Invoker.Direct { _, _ -> Registry.refresh() }),
            greeterBean(),
        )

        enhancer.enhance(elements, context("Alpha", plugin = main))

        assertEquals(listOf("MainClass.start", "Greeter", "Greeter.greet", "Registry.refresh"), Recorder.names())
        assertSame(main, Recorder.instanceOf("MainClass.start"))
        assertSame(Recorder.instanceOf("Greeter"), Recorder.instanceOf("Greeter.greet"), "called on the bean instance")
    }

    @Test
    fun `injects parameters from local and exposed beans`() {
        enhancer.enhance(descriptor(greeterBean(BeanProcessors.EXPOSE_SINGLETON)), context("Alpha"))
        val exposedGreeter = Recorder.instanceOf("Greeter")
        Recorder.reset()

        val welcome = function<MainClass>(
            "welcome",
            FunctionOwner.PLUGIN,
            Invoker.Direct { instance, args -> (instance as MainClass).welcome(args[0] as Greeter) },
            parameters = listOf(greeterName),
        )
        enhancer.enhance(descriptor(welcome, pluginElement()), context("Beta", plugin = MainClass("Beta")))
        assertSame(exposedGreeter, Recorder.instanceOf("MainClass.welcome"), "resolved from the exposed bean")

        Recorder.reset()
        enhancer.enhance(descriptor(welcome, greeterBean()), context("Gamma", plugin = MainClass("Gamma")))
        assertEquals(listOf("Greeter", "MainClass.welcome"), Recorder.names())
        assertSame(Recorder.instanceOf("Greeter"), Recorder.instanceOf("MainClass.welcome"), "local bean wins over the exposed one")
    }

    @Test
    fun `injects the plugin itself like a local bean`() {
        val main = MainClass()
        val mainName = MainClass::class.qualifiedName!!
        val mainClass = pluginElement(mainName)
        val introduce = function<MainClass>(
            "introduce",
            FunctionOwner.PLUGIN,
            Invoker.Direct { instance, args -> (instance as MainClass).introduce(args[0] as MainClass) },
            parameters = listOf(mainName),
        )

        enhancer.enhance(descriptor(introduce, mainClass), context("Alpha", plugin = main))

        assertEquals(listOf("MainClass.introduce"), Recorder.names())
        assertSame(main, Recorder.instanceOf("MainClass.introduce"))
    }

    @Test
    fun `beans stay available for reload and stop phases`() {
        val main = MainClass()
        val welcome = function<MainClass>(
            "welcome",
            FunctionOwner.PLUGIN,
            Invoker.Direct { instance, args -> (instance as MainClass).welcome(args[0] as Greeter) },
            phase = EventType.PLUGIN_RELOAD_OR_START,
            parameters = listOf(greeterName),
        )
        val greetOnStop = function<Greeter>("greet", FunctionOwner.INSTANCE, Invoker.Direct { instance, _ -> (instance as Greeter).greet() }, EventType.PLUGIN_STOP)
        val elements = descriptor(welcome, greetOnStop, greeterBean())

        enhancer.enhance(elements, context("Alpha", plugin = main))
        val greeter = Recorder.instanceOf("Greeter")
        assertEquals(listOf("Greeter", "MainClass.welcome"), Recorder.names())

        Recorder.reset()
        enhancer.enhance(elements, context("Alpha", LifecycleEvent.RELOAD, main))
        assertEquals(listOf("MainClass.welcome"), Recorder.names(), "no bean is re-created on reload")
        assertSame(greeter, Recorder.instanceOf("MainClass.welcome"))

        Recorder.reset()
        enhancer.enhance(elements, context("Alpha", LifecycleEvent.STOP, main))
        assertEquals(listOf("Greeter.greet"), Recorder.names())
        assertSame(greeter, Recorder.instanceOf("Greeter.greet"))
    }

    @Test
    fun `reaches private objects and functions through reflection`() {
        val hiddenName = "com.github.marcoral.versatia.lifecycle.HiddenRegistry"
        val hidden = EnhancedElement(
            LifecycleProcessors.AUTO_INVOKE,
            EventType.PLUGIN_START_ONLY,
            AnnotationOccurrence(AutoInvoke::class.java.name),
            FunctionTarget(hiddenName, hiddenName, "reset", FunctionOwner.OBJECT, emptyList(), listOf(Parameter(greeterName)), Invoker.Reflective(hiddenName, "reset", listOf(greeterName))),
        )

        enhancer.enhance(descriptor(hidden, greeterBean()), context("Alpha"))

        assertEquals(listOf("Greeter", "HiddenRegistry.reset"), Recorder.names())
        assertSame(Recorder.instanceOf("Greeter"), Recorder.instanceOf("HiddenRegistry.reset"))
    }

    @Test
    fun `respects the phase`() {
        val main = MainClass()
        val elements = descriptor(
            function<MainClass>("start", FunctionOwner.PLUGIN, Invoker.Direct { instance, _ -> (instance as MainClass).start() }, EventType.PLUGIN_START_ONLY),
            function<MainClass>("stop", FunctionOwner.PLUGIN, Invoker.Direct { instance, _ -> (instance as MainClass).stop() }, EventType.PLUGIN_STOP),
        )

        enhancer.enhance(elements, context("Alpha", plugin = main))
        assertEquals(listOf("MainClass.start"), Recorder.names())

        enhancer.enhance(elements, context("Alpha", LifecycleEvent.STOP, main))
        assertEquals(listOf("MainClass.start", "MainClass.stop"), Recorder.names())
    }

    @Test
    fun `wraps failures with the function and the event`() {
        val elements = descriptor(function<MainClass>("explode", FunctionOwner.PLUGIN, Invoker.Direct { instance, _ -> (instance as MainClass).explode() }))

        val error = assertFailsWith<EnhancementException> {
            enhancer.enhance(elements, context("Alpha", plugin = MainClass()))
        }
        assertContains(error.message!!, "MainClass.explode() failed at START")
        assertEquals("boom", error.cause?.message)
    }

    @Test
    fun `reports missing beans for the instance and for parameters`() {
        val onBean = descriptor(function<Greeter>("greet", FunctionOwner.INSTANCE, Invoker.Direct { instance, _ -> (instance as Greeter).greet() }))
        val forInstance = assertFailsWith<EnhancementException> { enhancer.enhance(onBean, context("Alpha")) }
        assertContains(forInstance.message!!, "no bean $greeterName exists")

        val withParameter = descriptor(
            function<MainClass>("welcome", FunctionOwner.PLUGIN, Invoker.Direct { instance, args -> (instance as MainClass).welcome(args[0] as Greeter) }, parameters = listOf(greeterName)),
        )
        val noBeans = assertFailsWith<EnhancementException> { enhancer.enhance(withParameter, context("Beta", plugin = MainClass())) }
        assertContains(noBeans.message!!, "${MainClass::class.qualifiedName}.welcome() needs injected parameters, but the plugin has no beans")

        val withBeans = descriptor(withParameter.elements.single(), pluginElement())
        val forParameter = assertFailsWith<EnhancementException> { enhancer.enhance(withBeans, context("Gamma", plugin = MainClass())) }
        assertContains(forParameter.message!!, "no bean provides $greeterName, required by ${MainClass::class.qualifiedName}.welcome()")
    }
}
