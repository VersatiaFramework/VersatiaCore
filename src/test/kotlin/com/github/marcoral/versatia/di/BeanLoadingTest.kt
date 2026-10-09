package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.LifecycleEvent
import com.github.marcoral.versatia.PluginEnhancer
import com.github.marcoral.versatia.PluginStates
import com.github.marcoral.versatia.ProcessorLoader
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.descriptor
import com.github.marcoral.versatia.testsupport.exposedBeanCount
import com.github.marcoral.versatia.testsupport.primary
import com.github.marcoral.versatia.testsupport.qualifier
import com.github.marcoral.versatia.testsupport.testContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

class Clock {
    init {
        Recorder.record("Clock", this)
    }
}

interface Greeter

class ConsoleGreeter(val clock: Clock) : Greeter {
    init {
        Recorder.record("ConsoleGreeter", this)
    }
}

class Audit(val greeter: Greeter, val clock: Clock) {
    init {
        Recorder.record("Audit", this)
    }
}

class Ticket(val clock: Clock) {
    init {
        Recorder.record("Ticket", this)
    }
}

class Consumer(val clock: Clock, val ticket: Ticket) {
    init {
        Recorder.record("Consumer", this)
    }
}

class SecondConsumer(val ticket: Ticket) {
    init {
        Recorder.record("SecondConsumer", this)
    }
}

class GrumpyGreeter(val clock: Clock) : Greeter {
    init {
        Recorder.record("GrumpyGreeter", this)
    }
}

class ComplaintDesk(val greeter: Greeter) {
    init {
        Recorder.record("ComplaintDesk", this)
    }
}

private class Hidden(val clock: Clock) {
    init {
        Recorder.record("Hidden", this)
    }
}

class Owner(val plugin: Plugin) {
    init {
        Recorder.record("Owner", this)
    }
}

class Session(val plugin: Plugin) {
    init {
        Recorder.record("Session", this)
    }
}

class SessionHolder(val session: Session)

class StrictSession(val plugin: Any)

class StrictHolder(val session: StrictSession)

/** Unit tests of the runtime half of dependency injection with hand-written descriptors. */
class BeanLoadingTest {
    private lateinit var states: PluginStates
    private lateinit var enhancer: PluginEnhancer

    @BeforeTest
    fun setUp() {
        Recorder.reset()
        states = PluginStates()
        enhancer = PluginEnhancer(ProcessorLoader())
    }

    private fun context(pluginName: String, event: LifecycleEvent = LifecycleEvent.START) = testContext(pluginName, states, event = event)

    private inline fun <reified T : Any> element(
        processorClass: String,
        dependencies: List<String> = emptyList(),
        provides: List<String> = emptyList(),
        instantiator: Instantiator,
        phase: EventType = EventType.PLUGIN_START_ONLY,
        name: String? = null,
        primary: Boolean = false,
        qualifiers: Map<String, String> = emptyMap(),
    ): EnhancedElement = EnhancedElement(
        processorClass = processorClass,
        phase = phase,
        annotation = AnnotationOccurrence("test.Scope"),
        target = ClassTarget(
            className = T::class.qualifiedName!!,
            assignableTypes = listOf(T::class.qualifiedName!!) + provides,
            annotations = listOfNotNull(name?.let(::qualifier), com.github.marcoral.versatia.testsupport.primary.takeIf { primary }),
            constructorParameters = dependencies.map { Parameter(it, listOfNotNull(qualifiers[it]?.let(::qualifier))) },
            instantiator = instantiator,
        ),
    )

    private val clockName = Clock::class.qualifiedName!!
    private val greeterName = Greeter::class.qualifiedName!!
    private val ticketName = Ticket::class.qualifiedName!!

    private val localClock = element<Clock>(BeanProcessors.LOCAL, instantiator = Instantiator.Direct { Clock() })
    private val exposedClock = element<Clock>(BeanProcessors.EXPOSE_SINGLETON, instantiator = Instantiator.Direct { Clock() })
    private val greeter = element<ConsoleGreeter>(
        BeanProcessors.LOCAL,
        dependencies = listOf(clockName),
        provides = listOf(greeterName),
        instantiator = Instantiator.Direct { ConsoleGreeter(it[0] as Clock) },
    )
    private val audit = element<Audit>(
        BeanProcessors.LOCAL,
        dependencies = listOf(greeterName, clockName),
        instantiator = Instantiator.Direct { Audit(it[0] as Greeter, it[1] as Clock) },
    )
    private val prototypeTicket = element<Ticket>(
        BeanProcessors.EXPOSE_PROTOTYPE,
        dependencies = listOf(clockName),
        instantiator = Instantiator.Direct { Ticket(it[0] as Clock) },
    )
    private val consumer = element<Consumer>(
        BeanProcessors.LOCAL,
        dependencies = listOf(clockName, ticketName),
        instantiator = Instantiator.Direct { Consumer(it[0] as Clock, it[1] as Ticket) },
    )
    private val secondConsumer = element<SecondConsumer>(
        BeanProcessors.LOCAL,
        dependencies = listOf(ticketName),
        instantiator = Instantiator.Direct { SecondConsumer(it[0] as Ticket) },
    )

    @Test
    fun `creates local beans in dependency order regardless of declaration order`() {
        enhancer.enhance(descriptor(audit, greeter, localClock), context("Alpha"))

        assertEquals(listOf("Clock", "ConsoleGreeter", "Audit"), Recorder.names())
        val auditInstance = Recorder.instanceOf("Audit") as Audit
        assertSame(Recorder.instanceOf("ConsoleGreeter"), auditInstance.greeter)
        assertSame(Recorder.instanceOf("Clock"), auditInstance.clock)
        assertEquals(0, states.exposedBeanCount())
    }

    @Test
    fun `local beans are invisible to other plugins`() {
        enhancer.enhance(descriptor(localClock), context("Alpha"))

        val error = assertFailsWith<EnhancementException> {
            enhancer.enhance(descriptor(greeter), context("Beta"))
        }
        assertContains(error.message!!, "Plugin Beta")
        assertContains(error.message!!, "no bean provides $clockName")
    }

    @Test
    fun `exposed singletons are shared with later plugins`() {
        enhancer.enhance(descriptor(exposedClock), context("Alpha"))
        enhancer.enhance(descriptor(greeter), context("Beta"))
        enhancer.enhance(descriptor(greeter), context("Gamma"))

        val clock = Recorder.instanceOf("Clock")
        val greeters = Recorder.events.filter { it.first == "ConsoleGreeter" }.map { it.second as ConsoleGreeter }
        assertEquals(2, greeters.size)
        greeters.forEach { assertSame(clock, it.clock) }
        assertEquals(1, states.exposedBeanCount())
    }

    @Test
    fun `exposed prototypes give one fresh instance per consuming plugin`() {
        enhancer.enhance(descriptor(exposedClock, prototypeTicket), context("Alpha"))
        enhancer.enhance(descriptor(consumer, secondConsumer), context("Beta"))
        enhancer.enhance(descriptor(consumer), context("Gamma"))

        val tickets = Recorder.events.filter { it.first == "Ticket" }.map { it.second as Ticket }
        assertEquals(3, tickets.size, "one for Alpha itself, one for Beta, one for Gamma")
        val betaConsumer = Recorder.events.filter { it.first == "Consumer" }.map { it.second as Consumer }
        val betaSecond = Recorder.instanceOf("SecondConsumer") as SecondConsumer
        assertSame(betaConsumer[0].ticket, betaSecond.ticket, "same instance within Beta")
        assertNotSame(betaConsumer[0].ticket, betaConsumer[1].ticket, "fresh instance for Gamma")
        assertNotSame(tickets[0], betaConsumer[0].ticket, "Alpha's own instance is not handed out")
        tickets.forEach { assertSame(Recorder.instanceOf("Clock"), it.clock) }
    }

    @Test
    fun `exposed prototypes receive the plugin they are made for`() {
        val session = element<Session>(BeanProcessors.EXPOSE_PROTOTYPE, dependencies = listOf(Plugin::class.qualifiedName!!), instantiator = Instantiator.Direct { Session(it[0] as Plugin) })
        val holder = element<SessionHolder>(BeanProcessors.LOCAL, dependencies = listOf(Session::class.qualifiedName!!), instantiator = Instantiator.Direct { SessionHolder(it[0] as Session) })
        val alpha = context("Alpha")
        val beta = context("Beta")

        enhancer.enhance(descriptor(mainClass, session), alpha)
        enhancer.enhance(descriptor(mainClass, holder), beta)

        val sessions = Recorder.events.filter { it.first == "Session" }.map { it.second as Session }
        assertEquals(2, sessions.size)
        assertSame(alpha.plugin, sessions[0].plugin, "the owner's own instance takes the owner")
        assertSame(beta.plugin, sessions[1].plugin, "a consumer's instance takes the consumer")
    }

    @Test
    fun `a prototype's plugin parameter must accept the consuming plugin`() {
        val javaPluginName = JavaPlugin::class.qualifiedName!!
        val javaMain = mainClass.copy(target = PluginTarget("com.example.Main", listOf("com.example.Main", javaPluginName, Plugin::class.qualifiedName!!), emptyList()))
        val strict = element<StrictSession>(BeanProcessors.EXPOSE_PROTOTYPE, dependencies = listOf(javaPluginName), instantiator = Instantiator.Direct { StrictSession(it[0]!!) })
        val holder = element<StrictHolder>(BeanProcessors.LOCAL, dependencies = listOf(StrictSession::class.qualifiedName!!), instantiator = Instantiator.Direct { StrictHolder(it[0] as StrictSession) })
        enhancer.enhance(descriptor(javaMain, strict), context("Alpha"))

        val error = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(holder), context("Beta")) }

        assertContains(error.message!!, "Plugin Beta: ${StrictSession::class.qualifiedName} takes its consuming plugin as $javaPluginName, which")
    }

    @Test
    fun `ambiguous exposed providers are reported`() {
        enhancer.enhance(descriptor(exposedClock), context("Alpha"))
        enhancer.enhance(descriptor(exposedClock), context("Beta"))

        val error = assertFailsWith<EnhancementException> {
            enhancer.enhance(descriptor(greeter), context("Gamma"))
        }
        assertContains(error.message!!, "$clockName required by ${ConsoleGreeter::class.qualifiedName} is ambiguous")
        assertContains(error.message!!, "from Alpha")
        assertContains(error.message!!, "from Beta")
    }

    @Test
    fun `beans of a disabled plugin disappear with it`() {
        enhancer.enhance(descriptor(exposedClock), context("Alpha"))
        enhancer.enhance(descriptor(exposedClock), context("Beta"))
        assertEquals(2, states.exposedBeanCount())

        states.drop("Alpha")

        assertEquals(1, states.exposedBeanCount())
        assertEquals(false, states.contains("Alpha"))
        enhancer.enhance(descriptor(greeter), context("Gamma"))
        assertSame(Recorder.events.filter { it.first == "Clock" }[1].second, (Recorder.instanceOf("ConsoleGreeter") as ConsoleGreeter).clock, "Beta's clock remains")
    }

    @Test
    fun `re-enabling a plugin replaces its beans`() {
        enhancer.enhance(descriptor(exposedClock), context("Alpha"))
        states.drop("Alpha")
        enhancer.enhance(descriptor(exposedClock), context("Alpha"))

        assertEquals(1, states.exposedBeanCount())
    }

    @Test
    fun `reflective instantiation reaches private classes`() {
        val hidden = EnhancedElement(
            BeanProcessors.LOCAL,
            EventType.PLUGIN_START_ONLY,
            AnnotationOccurrence("test.Scope"),
            ClassTarget(
                className = "com.github.marcoral.versatia.di.Hidden",
                assignableTypes = listOf("com.github.marcoral.versatia.di.Hidden"),
                annotations = emptyList(),
                constructorParameters = listOf(Parameter(clockName)),
                instantiator = Instantiator.Reflective("com.github.marcoral.versatia.di.Hidden", listOf(clockName)),
            ),
        )

        enhancer.enhance(descriptor(hidden, localClock), context("Alpha"))

        assertEquals(listOf("Clock", "Hidden"), Recorder.names())
        assertSame(Recorder.instanceOf("Clock"), (Recorder.instanceOf("Hidden") as Hidden).clock)
    }

    @Test
    fun `only elements of the current event are dispatched`() {
        val atStop = element<Clock>(BeanProcessors.LOCAL, instantiator = Instantiator.Direct { Clock() }, phase = EventType.PLUGIN_STOP)
        val onReloadOrStart = element<Ticket>(
            BeanProcessors.LOCAL,
            dependencies = listOf(clockName),
            instantiator = Instantiator.Direct { Ticket(it[0] as Clock) },
            phase = EventType.PLUGIN_RELOAD_OR_START,
        )

        enhancer.enhance(descriptor(atStop, exposedClock, onReloadOrStart), context("Alpha"))
        assertEquals(listOf("Clock", "Ticket"), Recorder.names(), "the STOP-phase clock is not created at start")

        Recorder.reset()
        val error = assertFailsWith<EnhancementException> {
            enhancer.enhance(descriptor(atStop, onReloadOrStart), context("Alpha", LifecycleEvent.STOP))
        }
        assertContains(error.message!!, "Plugin Alpha: beans can only be declared when the plugin starts, not at STOP")
        assertEquals(emptyList(), Recorder.names(), "nothing is created at stop")
    }

    private val grumpyGreeter = element<GrumpyGreeter>(
        BeanProcessors.LOCAL,
        dependencies = listOf(clockName),
        provides = listOf(greeterName),
        instantiator = Instantiator.Direct { GrumpyGreeter(it[0] as Clock) },
        name = "grumpy",
    )

    private val complaintDesk = element<ComplaintDesk>(
        BeanProcessors.LOCAL,
        dependencies = listOf(greeterName),
        instantiator = Instantiator.Direct { ComplaintDesk(it[0] as Greeter) },
        qualifiers = mapOf(greeterName to "grumpy"),
    )

    @Test
    fun `a Primary candidate wins and a Qualifier selects by name`() {
        val friendlyPrimary = greeter.copy(target = (greeter.target as ClassTarget).copy(annotations = listOf(primary)))

        enhancer.enhance(descriptor(audit, complaintDesk, grumpyGreeter, friendlyPrimary, localClock), context("Alpha"))

        assertSame(Recorder.instanceOf("ConsoleGreeter"), (Recorder.instanceOf("Audit") as Audit).greeter, "primary wins without a qualifier")
        assertSame(Recorder.instanceOf("GrumpyGreeter"), (Recorder.instanceOf("ComplaintDesk") as ComplaintDesk).greeter, "qualifier picks by name")
    }

    @Test
    fun `several candidates without a Primary are reported with a hint`() {
        val error = assertFailsWith<EnhancementException> {
            enhancer.enhance(descriptor(audit, greeter, grumpyGreeter, localClock), context("Alpha"))
        }
        assertContains(error.message!!, "$greeterName required by ${Audit::class.qualifiedName} is ambiguous")
        assertContains(error.message!!, "consoleGreeter")
        assertContains(error.message!!, "grumpy")
        assertContains(error.message!!, "Add @Qualifier at the injection point or mark one bean @Primary")
    }

    @Test
    fun `a Qualifier reaches an exposed bean past a local candidate of the same type`() {
        val exposedGrumpy = grumpyGreeter.copy(processorClass = BeanProcessors.EXPOSE_SINGLETON)
        enhancer.enhance(descriptor(exposedGrumpy, exposedClock), context("Alpha"))
        val grumpy = Recorder.instanceOf("GrumpyGreeter")

        enhancer.enhance(descriptor(complaintDesk, greeter, audit), context("Beta"))

        assertSame(grumpy, (Recorder.instanceOf("ComplaintDesk") as ComplaintDesk).greeter, "named exposed bean chosen over the local Greeter")
        assertSame(Recorder.instanceOf("ConsoleGreeter"), (Recorder.instanceOf("Audit") as Audit).greeter, "without a qualifier the local bean still wins")

        val unknown = complaintDesk.copy(
            target = (complaintDesk.target as ClassTarget).copy(constructorParameters = listOf(Parameter(greeterName, listOf(qualifier("nobody"))))),
        )
        val missing = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(unknown), context("Gamma")) }
        assertContains(missing.message!!, "no bean provides $greeterName named")
        assertContains(missing.message!!, "nobody")
    }

    private val pluginName = Plugin::class.qualifiedName!!
    private val mainClass = EnhancedElement(
        BeanProcessors.PLUGIN,
        EventType.PLUGIN_START_ONLY,
        null,
        PluginTarget("com.example.AlphaPlugin", listOf("com.example.AlphaPlugin", pluginName), emptyList()),
    )
    private val owner = element<Owner>(
        BeanProcessors.LOCAL,
        dependencies = listOf(pluginName),
        instantiator = Instantiator.Direct { Owner(it[0] as Plugin) },
    )

    @Test
    fun `the plugin instance is a local bean of its own plugin`() {
        val context = context("Alpha")
        enhancer.enhance(descriptor(owner, mainClass), context)

        assertEquals(listOf("Owner"), Recorder.names(), "nothing is created for the plugin itself")
        assertSame(context.plugin, (Recorder.instanceOf("Owner") as Owner).plugin)
        assertEquals(0, states.exposedBeanCount())
        assertEquals(listOf(BeanNames.PLUGIN, "owner"), PluginBeanSession.of(context).instantiateAll().keys.map { it.name })

        val error = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(owner), context("Beta")) }
        assertContains(error.message!!, "no bean provides $pluginName")
    }

    @Test
    fun `the plugin instance has a processor of its own`() {
        val error = assertFailsWith<EnhancementException> {
            enhancer.enhance(descriptor(mainClass.copy(processorClass = BeanProcessors.LOCAL)), context("Alpha"))
        }
        assertContains(error.message!!, "${BeanProcessors.LOCAL} can only enhance classes")

        val twice = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(mainClass, mainClass), context("Beta")) }
        assertContains(twice.message!!, "Plugin Beta must declare its main class exactly once")
    }

    @Test
    fun `unknown processors are rejected before anything is created`() {
        val foreign = element<Clock>("com.example.MissingEnhancementProcessor", instantiator = Instantiator.Direct { Clock() })

        val error = assertFailsWith<EnhancementException> {
            enhancer.enhance(descriptor(localClock, foreign), context("Alpha"))
        }
        assertContains(error.message!!, "Plugin Alpha needs the enhancement processor com.example.MissingEnhancementProcessor")
        assertEquals(emptyList(), Recorder.names())
    }
}
