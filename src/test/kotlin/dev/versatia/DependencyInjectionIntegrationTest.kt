package dev.versatia

import dev.versatia.engine.descriptor.DescriptorLoader
import dev.versatia.ksp.VersatiaSymbolProcessorProvider
import dev.versatia.testsupport.FakeCore
import dev.versatia.testsupport.Recorder
import dev.versatia.testsupport.captureLog
import dev.versatia.testsupport.exposedBeanCount
import dev.versatia.testsupport.fakePlugin
import dev.versatia.testsupport.testContext
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import java.io.File
import java.net.URLClassLoader
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.plugin.Plugin
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir

/**
 * End to end: sample plugins are compiled with the VersatiaAPI KSP processor, their generated
 * descriptors are loaded the way the server would, and the runtime wires the beans.
 * See docs/runtime.md.
 */
@OptIn(ExperimentalCompilerApi::class)
class DependencyInjectionIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var states: PluginStates
    private lateinit var enhancer: PluginEnhancer

    @BeforeTest
    fun setUp() {
        Recorder.reset()
        states = PluginStates()
        enhancer = PluginEnhancer(ProcessorLoader())
        FakeCore(states)
    }

    /** Compiles one plugin; [dependsOn] plays the role of a plugin listed under `depend:`. */
    private fun compilePlugin(name: String, mainClass: String, source: String, dependsOn: JvmCompilationResult? = null): JvmCompilationResult {
        val pluginYml = File(tempDir, "$name.yml").apply { writeText("name: $name\nmain: $mainClass\n") }
        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("$name.kt", source))
            inheritClassPath = true
            jvmTarget = "21"
            messageOutputStream = System.out
            if (dependsOn != null) classpaths = listOf(dependsOn.outputDirectory)
            configureKsp {
                symbolProcessorProviders += VersatiaSymbolProcessorProvider()
                withCompilation = true
                processorOptions["versatia.pluginYml"] = pluginYml.path
            }
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        return result
    }

    /** Mirrors Bukkit: a plugin's loader delegates to the loaders of the plugins it depends on. */
    private fun pluginClassLoader(result: JvmCompilationResult, parent: ClassLoader) = URLClassLoader(arrayOf(result.outputDirectory.toURI().toURL()), parent)

    private fun enhance(mainClass: String, context: DefaultEnhancementContext) =
        enhancer.enhance(DescriptorLoader.load(mainClass, context), context)

    private val alphaSource = """
        package com.example.alpha

        import dev.versatia.Phase
        import dev.versatia.VersatiaPlugin
        import dev.versatia.di.ExposePrototype
        import dev.versatia.di.ExposeSingleton
        import dev.versatia.di.LocalBean
        import dev.versatia.di.Primary
        import dev.versatia.di.Qualifier
        import dev.versatia.lifecycle.AutoInvoke
        import dev.versatia.testsupport.Recorder
        import org.bukkit.plugin.Plugin

        class AlphaPlugin : VersatiaPlugin()

        interface Greeter {
            fun greet(): String
        }

        @LocalBean
        class Owner(val plugin: Plugin) {
            init { Recorder.record("Owner", this) }
        }

        @ExposeSingleton
        class Clock {
            init { Recorder.record("Clock", this) }
        }

        @ExposePrototype
        class Ticket(val clock: Clock) {
            init { Recorder.record("Ticket", this) }
        }

        // Open, so the compile-time step cannot rule out a provider in another plugin and the runtime reports it
        @LocalBean
        open class Secret {
            init { Recorder.record("Secret", this) }
        }

        @LocalBean
        @Qualifier("grumpy")
        internal class GrumpyGreeter : Greeter {
            init { Recorder.record("GrumpyGreeter", this) }
            override fun greet() = "go away"
        }

        @LocalBean
        @Primary
        internal class ConsoleGreeter(val clock: Clock, val secret: Secret) : Greeter {
            init { Recorder.record("ConsoleGreeter", this) }
            override fun greet() = "hello"
        }

        @LocalBean
        private class HiddenAudit(val greeter: Greeter, val ticket: Ticket) {
            init { Recorder.record("HiddenAudit", this) }

            @AutoInvoke(Phase.PLUGIN_START_ONLY)
            private fun report(secret: Secret) { Recorder.record("HiddenAudit.report", secret) }

            @AutoInvoke(Phase.PLUGIN_START_ONLY)
            fun complain(@Qualifier("grumpy") greeter: Greeter) { Recorder.record("HiddenAudit.complain", greeter) }
        }

        object Startup {
            @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)
            fun ready(clock: Clock) { Recorder.record("Startup.ready", clock) }

            @AutoInvoke(Phase.PLUGIN_STOP)
            fun bye() { Recorder.record("Startup.bye", this) }
        }
    """.trimIndent()

    private val betaSource = """
        package com.example.beta

        import com.example.alpha.Clock
        import com.example.alpha.Ticket
        import dev.versatia.VersatiaPlugin
        import dev.versatia.di.LocalBean
        import dev.versatia.testsupport.Recorder

        class BetaPlugin : VersatiaPlugin()

        @LocalBean
        class BetaService(val clock: Clock, val ticket: Ticket) {
            init { Recorder.record("BetaService", this) }
        }

        @LocalBean
        class BetaWorker(val service: BetaService, val ticket: Ticket) {
            init { Recorder.record("BetaWorker", this) }
        }
    """.trimIndent()

    private val gammaSource = """
        package com.example.gamma

        import com.example.alpha.Secret
        import dev.versatia.VersatiaPlugin
        import dev.versatia.di.LocalBean
        import dev.versatia.testsupport.Recorder

        class GammaPlugin : VersatiaPlugin()

        @LocalBean
        class Spy(val secret: Secret) {
            init { Recorder.record("Spy", this) }
        }
    """.trimIndent()

    @Test
    fun `beans flow from generated descriptors through the runtime`() {
        val alpha = compilePlugin("Alpha", "com.example.alpha.AlphaPlugin", alphaSource)
        val beta = compilePlugin("Beta", "com.example.beta.BetaPlugin", betaSource, dependsOn = alpha)
        val gamma = compilePlugin("Gamma", "com.example.gamma.GammaPlugin", gammaSource, dependsOn = alpha)

        val alphaLoader = pluginClassLoader(alpha, Recorder::class.java.classLoader)
        val betaLoader = pluginClassLoader(beta, alphaLoader)
        val gammaLoader = pluginClassLoader(gamma, alphaLoader)

        val coreExposed = states.exposedBeanCount()
        val alphaContext = testContext("Alpha", states, alphaLoader)
        enhance("com.example.alpha.AlphaPlugin", alphaContext)
        val alphaEvents = Recorder.names()
        assertEquals(
            setOf("Owner", "Clock", "Ticket", "Secret", "GrumpyGreeter", "ConsoleGreeter", "HiddenAudit", "HiddenAudit.complain", "HiddenAudit.report", "Startup.ready"),
            alphaEvents.toSet(),
        )
        assertEquals(10, alphaEvents.size)
        assertEquals(listOf("HiddenAudit.complain", "HiddenAudit.report", "Startup.ready"), alphaEvents.takeLast(3), "functions run after all beans exist")
        assertSame(alphaContext.plugin, Recorder.instanceOf("Owner").field("plugin"), "the plugin instance is injected as one of its supertypes")
        assertSame(Recorder.instanceOf("ConsoleGreeter"), Recorder.instanceOf("HiddenAudit").field("greeter"), "the @Primary greeter is injected without a qualifier")
        assertSame(Recorder.instanceOf("GrumpyGreeter"), Recorder.instanceOf("HiddenAudit.complain"), "the @Qualifier picks the other greeter")
        assertSame(Recorder.instanceOf("Secret"), Recorder.instanceOf("HiddenAudit.report"), "parameter injected from a local bean")
        assertSame(Recorder.instanceOf("Clock"), Recorder.instanceOf("Startup.ready"))
        assert(alphaEvents.indexOf("Clock") < alphaEvents.indexOf("Ticket"))
        assert(alphaEvents.indexOf("Clock") < alphaEvents.indexOf("ConsoleGreeter"))
        assert(alphaEvents.indexOf("Secret") < alphaEvents.indexOf("ConsoleGreeter"))
        assert(alphaEvents.indexOf("ConsoleGreeter") < alphaEvents.indexOf("HiddenAudit"))
        assert(alphaEvents.indexOf("Ticket") < alphaEvents.indexOf("HiddenAudit"))
        assertEquals(coreExposed + 2, states.exposedBeanCount(), "Clock and Ticket are exposed, local beans are forgotten")

        enhance("com.example.beta.BetaPlugin", testContext("Beta", states, betaLoader))
        assertEquals(listOf("Ticket", "BetaService", "BetaWorker"), Recorder.names().drop(10))

        val clock = Recorder.instanceOf("Clock")
        val betaService = Recorder.instanceOf("BetaService")
        val betaWorker = Recorder.instanceOf("BetaWorker")
        assertSame(clock, betaService.field("clock"), "singleton shared across plugins")
        val betaTicket = betaService.field("ticket")
        assertSame(betaTicket, betaWorker.field("ticket"), "one prototype instance per plugin")
        val alphaTicket = Recorder.events.first { it.first == "Ticket" }.second
        assertNotSame(alphaTicket, betaTicket, "fresh prototype instance for Beta")
        assertSame(clock, betaTicket.field("clock"), "prototype keeps the dependencies captured by its owner")

        val error = assertFailsWith<EnhancementException> {
            enhance("com.example.gamma.GammaPlugin", testContext("Gamma", states, gammaLoader))
        }
        assertContains(error.message!!, "Plugin Gamma")
        assertContains(error.message!!, "no bean provides com.example.alpha.Secret")

        Recorder.reset()
        enhance("com.example.alpha.AlphaPlugin", testContext("Alpha", states, alphaLoader, event = LifecycleEvent.RELOAD))
        assertEquals(listOf("Startup.ready"), Recorder.names(), "reload re-runs only reload-phase functions")
        assertSame(clock, Recorder.instanceOf("Startup.ready"), "the retained clock is injected again")

        Recorder.reset()
        enhance("com.example.alpha.AlphaPlugin", testContext("Alpha", states, alphaLoader, event = LifecycleEvent.STOP))
        assertEquals(listOf("Startup.bye"), Recorder.names())

        states.drop("Alpha")
        assertEquals(coreExposed, states.exposedBeanCount(), "closing the plugin's state withdraws what it exposed")
    }

    @Test
    fun `custom annotations carrying Qualifier and Primary select beans like the attributes themselves`() {
        val source = """
            package com.example.delta

            import dev.versatia.VersatiaPlugin
            import dev.versatia.di.LocalBean
            import dev.versatia.di.Primary
            import dev.versatia.di.Qualifier
            import dev.versatia.testsupport.Recorder

            @Qualifier("fast")
            @Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER)
            annotation class Fast

            @Primary
            annotation class Preferred

            interface Engine

            @Fast @LocalBean
            class FastEngine : Engine { init { Recorder.record("FastEngine", this) } }

            @Preferred @LocalBean
            class SteadyEngine : Engine { init { Recorder.record("SteadyEngine", this) } }

            @LocalBean
            class Car(@Fast val engine: Engine) { init { Recorder.record("Car", this) } }

            @LocalBean
            class Bus(val engine: Engine) { init { Recorder.record("Bus", this) } }

            class DeltaPlugin : VersatiaPlugin()
        """
        val loader = pluginClassLoader(compilePlugin("Delta", "com.example.delta.DeltaPlugin", source), Recorder::class.java.classLoader)

        enhance("com.example.delta.DeltaPlugin", testContext("Delta", states, loader))

        assertSame(Recorder.instanceOf("FastEngine"), Recorder.instanceOf("Car").field("engine"), "@Fast selects the bean named fast")
        assertSame(Recorder.instanceOf("SteadyEngine"), Recorder.instanceOf("Bus").field("engine"), "@Preferred makes its bean primary")
    }

    @Test
    fun `every plugin injects its own logger, component logger and plugin, and a prototype made for another plugin gets the consumer's`() {
        val owner = compilePlugin(
            "Owner",
            "com.example.owner.OwnerPlugin",
            """
            package com.example.owner

            import dev.versatia.VersatiaPlugin
            import dev.versatia.di.ExposePrototype
            import dev.versatia.di.LocalBean
            import dev.versatia.testsupport.Recorder
            import java.util.logging.Logger
            import net.kyori.adventure.text.logger.slf4j.ComponentLogger
            import org.bukkit.plugin.Plugin

            class OwnerPlugin : VersatiaPlugin()

            @LocalBean
            class Shop(logger: Logger) {
                init { logger.info("Shop open") }
            }

            @ExposePrototype
            class Greeter(val logger: Logger, val componentLogger: ComponentLogger, val plugin: Plugin) {
                init { Recorder.record("Greeter", this) }
                fun greet() = logger.info("Hello from ${'$'}{plugin.pluginMeta.name}")
            }
            """.trimIndent(),
        )
        val consumer = compilePlugin(
            "Consumer",
            "com.example.consumer.ConsumerPlugin",
            """
            package com.example.consumer

            import com.example.owner.Greeter
            import dev.versatia.VersatiaPlugin
            import dev.versatia.di.LocalBean

            class ConsumerPlugin : VersatiaPlugin()

            @LocalBean
            class Desk(greeter: Greeter) {
                init { greeter.greet() }
            }
            """.trimIndent(),
            dependsOn = owner,
        )
        val quiet = compilePlugin(
            "Quiet",
            "com.example.quiet.QuietPlugin",
            """
            package com.example.quiet

            import com.example.owner.Greeter
            import dev.versatia.VersatiaPlugin
            import dev.versatia.di.LocalBean
            import dev.versatia.testsupport.Recorder
            import java.util.logging.Logger

            class QuietPlugin : VersatiaPlugin()

            @LocalBean
            class QuietLogger : Logger("quiet", null)

            @LocalBean
            class Booth(val greeter: Greeter, val logger: Logger) {
                init { Recorder.record("Booth", this) }
            }
            """.trimIndent(),
            dependsOn = owner,
        )
        val ownerLoader = pluginClassLoader(owner, Recorder::class.java.classLoader)
        val consumerLoader = pluginClassLoader(consumer, ownerLoader)
        val quietLoader = pluginClassLoader(quiet, ownerLoader)
        val ownerPlugin = fakePlugin("Owner", ownerLoader, main = "com.example.owner.OwnerPlugin")
        val consumerPlugin = fakePlugin("Consumer", consumerLoader, main = "com.example.consumer.ConsumerPlugin")

        ownerPlugin.captureLog().use { ownerLog ->
            consumerPlugin.captureLog().use { consumerLog ->
                enhance("com.example.owner.OwnerPlugin", testContext("Owner", states, ownerLoader, plugin = ownerPlugin))
                assertEquals(listOf("Shop open"), ownerLog.messages(Level.INFO), "a bean logs through its plugin's logger")

                enhance("com.example.consumer.ConsumerPlugin", testContext("Consumer", states, consumerLoader, plugin = consumerPlugin))
                assertEquals(listOf("Hello from Consumer"), consumerLog.messages(Level.INFO), "the prototype made for Consumer logs as Consumer")
                assertEquals(listOf("Shop open"), ownerLog.messages(Level.INFO))
            }
        }
        val forConsumer = Recorder.events.last { it.first == "Greeter" }.second
        assertEquals("test.Consumer", (forConsumer.field("componentLogger") as ComponentLogger).name)
        assertEquals("Consumer", (forConsumer.field("plugin") as Plugin).name)

        enhance("com.example.quiet.QuietPlugin", testContext("Quiet", states, quietLoader, plugin = fakePlugin("Quiet", quietLoader, main = "com.example.quiet.QuietPlugin")))
        val booth = Recorder.instanceOf("Booth")
        val quietLogger = booth.field("logger") as Logger
        assertEquals("quiet", quietLogger.name, "a local Logger bean wins over the default one")
        assertSame(quietLogger, booth.field("greeter").field("logger"), "a prototype made for Quiet resolves Logger by Quiet's rules")
    }

    private fun Any.field(name: String) = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)!!
}
