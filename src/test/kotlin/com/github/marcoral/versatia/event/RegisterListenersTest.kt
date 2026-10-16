package com.github.marcoral.versatia.event

import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.PluginEnhancer
import com.github.marcoral.versatia.PluginStates
import com.github.marcoral.versatia.ProcessorLoader
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
import com.github.marcoral.versatia.testsupport.descriptor
import com.github.marcoral.versatia.testsupport.fakePlugin
import com.github.marcoral.versatia.testsupport.testContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin

class Clock

class Audit(val clock: Clock) : Listener

class Chat(val clock: Clock) : Listener

private object Watch : Listener

class RegisterListenersTest {
    private lateinit var states: PluginStates
    private lateinit var enhancer: PluginEnhancer
    private val registered = mutableListOf<Any>()

    @BeforeTest
    fun setUp() {
        states = PluginStates()
        enhancer = PluginEnhancer(ProcessorLoader())
    }

    private val mainName = "com.example.Main"
    private val origin = "com.github.marcoral.versatia.VersatiaPlugin"
    private val annotation = AnnotationOccurrence(RegisterListeners::class.java.name)

    private fun context(listener: Boolean = false) =
        testContext("Alpha", states, plugin = fakePlugin("Alpha", listener = listener, registrations = registered))

    private val pluginBean = EnhancedElement(
        BeanProcessors.PLUGIN,
        Phase.PLUGIN_RELOAD_OR_START,
        AnnotationOccurrence(PluginBean::class.java.name),
        PluginTarget(mainName, listOf(mainName, Plugin::class.qualifiedName!!), emptyList()),
        origin,
    )

    private inline fun <reified T : Any> classTarget(parameters: List<String> = emptyList(), instantiator: Instantiator) =
        ClassTarget(T::class.qualifiedName!!, listOf(T::class.qualifiedName!!, Listener::class.qualifiedName!!), emptyList(), parameters.map { Parameter(it) }, instantiator)

    private fun bean(target: ClassTarget) = EnhancedElement(BeanProcessors.LOCAL, Phase.PLUGIN_START_ONLY, AnnotationOccurrence("test.Scope"), target)

    private fun listener(target: ClassTarget) = EnhancedElement(EventProcessors.REGISTER_LISTENERS, Phase.PLUGIN_START_ONLY, annotation, target, origin)

    private val clockName = Clock::class.qualifiedName!!
    private val clock = classTarget<Clock>(instantiator = Instantiator.Direct { Clock() })
    private val audit = classTarget<Audit>(listOf(clockName), Instantiator.Direct { Audit(it[0] as Clock) })
    private val chat = classTarget<Chat>(listOf(clockName), Instantiator.Direct { Chat(it[0] as Clock) })

    @Test
    fun `registers a listener bean with its bean instance`() {
        val context = context()

        enhancer.enhance(descriptor(pluginBean, bean(clock), bean(audit), listener(audit)), context)

        val listener = assertIs<Audit>(registered.single())
        assertSame(listener, context.state[com.github.marcoral.versatia.di.BeanResolver.KEY]!!.instanceOf(Audit::class.qualifiedName!!))
    }

    @Test
    fun `creates a listener that is not a bean with injected parameters`() {
        val context = context()

        enhancer.enhance(descriptor(pluginBean, bean(clock), listener(chat)), context)

        val listener = assertIs<Chat>(registered.single())
        assertSame(listener.clock, context.state[com.github.marcoral.versatia.di.BeanResolver.KEY]!!.instanceOf(clockName))
    }

    @Test
    fun `registers objects and the main class`() {
        val context = context(listener = true)
        val watch = ClassTarget(Watch::class.qualifiedName!!, listOf(Listener::class.qualifiedName!!), emptyList(), emptyList(), Instantiator.ObjectInstance(Watch::class.java.name))
        val main = EnhancedElement(EventProcessors.REGISTER_LISTENERS, Phase.PLUGIN_START_ONLY, annotation, PluginTarget(mainName, listOf(mainName), emptyList()), origin)

        enhancer.enhance(descriptor(pluginBean, main, listener(watch)), context)

        assertEquals(listOf<Any>(context.plugin, Watch), registered)
    }

    @Test
    fun `rejects a listener with parameters when the plugin has no beans`() {
        val failure = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(listener(chat)), context()) }

        assertContains(failure.message!!, "needs injected parameters, but the plugin has no beans")
    }

    @Test
    fun `rejects anything but classes`() {
        val function = FunctionTarget(mainName, mainName, "start", MemberOwner.PLUGIN, emptyList(), emptyList(), Invoker.Direct { _, _ -> })
        val element = EnhancedElement(EventProcessors.REGISTER_LISTENERS, Phase.PLUGIN_START_ONLY, annotation, function, origin)

        val failure = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(element), context()) }

        assertContains(failure.message!!, "can only enhance classes")
    }
}
