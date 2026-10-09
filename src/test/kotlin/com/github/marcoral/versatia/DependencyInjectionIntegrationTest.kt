package com.github.marcoral.versatia

import com.github.marcoral.versatia.engine.descriptor.DescriptorLoader
import com.github.marcoral.versatia.ksp.VersatiaSymbolProcessorProvider
import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.exposedBeanCount
import com.github.marcoral.versatia.testsupport.testContext
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import java.io.File
import java.net.URLClassLoader
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
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

        import com.github.marcoral.versatia.EventType
        import com.github.marcoral.versatia.VersatiaPlugin
        import com.github.marcoral.versatia.di.ExposePrototype
        import com.github.marcoral.versatia.di.ExposeSingleton
        import com.github.marcoral.versatia.di.LocalBean
        import com.github.marcoral.versatia.di.Primary
        import com.github.marcoral.versatia.di.Qualifier
        import com.github.marcoral.versatia.lifecycle.AutoInvoke
        import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.exposedBeanCount
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

        @LocalBean
        class Secret {
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

            @AutoInvoke(EventType.PLUGIN_START_ONLY)
            private fun report(secret: Secret) { Recorder.record("HiddenAudit.report", secret) }

            @AutoInvoke(EventType.PLUGIN_START_ONLY)
            fun complain(@Qualifier("grumpy") greeter: Greeter) { Recorder.record("HiddenAudit.complain", greeter) }
        }

        object Startup {
            @AutoInvoke(EventType.PLUGIN_RELOAD_OR_START)
            fun ready(clock: Clock) { Recorder.record("Startup.ready", clock) }

            @AutoInvoke(EventType.PLUGIN_STOP)
            fun bye() { Recorder.record("Startup.bye", this) }
        }
    """.trimIndent()

    private val betaSource = """
        package com.example.beta

        import com.example.alpha.Clock
        import com.example.alpha.Ticket
        import com.github.marcoral.versatia.VersatiaPlugin
        import com.github.marcoral.versatia.di.LocalBean
        import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.exposedBeanCount

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
        import com.github.marcoral.versatia.VersatiaPlugin
        import com.github.marcoral.versatia.di.LocalBean
        import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.exposedBeanCount

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
        assertEquals(2, states.exposedBeanCount(), "Clock and Ticket are exposed, local beans are forgotten")

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
        assertEquals(0, states.exposedBeanCount(), "closing the plugin's state withdraws what it exposed")
    }

    private fun Any.field(name: String) = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)!!
}
