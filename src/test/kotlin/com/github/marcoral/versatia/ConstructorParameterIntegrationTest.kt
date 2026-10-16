package com.github.marcoral.versatia

import com.github.marcoral.versatia.config.PreviousValueTracking
import com.github.marcoral.versatia.engine.descriptor.DescriptorLoader
import com.github.marcoral.versatia.ksp.VersatiaSymbolProcessorProvider
import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.fakePlugin
import com.github.marcoral.versatia.testsupport.testContext
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir

/**
 * End to end, compiled with the real KSP processor and run through the runtime: `@Value`
 * constructor properties, optional constructor parameters and `@ConditionalOnPlugin` inherited
 * through a meta-annotation.
 */
@OptIn(ExperimentalCompilerApi::class)
class ConstructorParameterIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private val packageName = "com.example.ctor"
    private val dataFolder by lazy { tempDir.resolve("plugins/Ctor").apply { mkdirs() } }

    /** Compiles [code] as plugin `Ctor` and returns a function running one event of it, with [enabledPlugins] installed. */
    private fun plugin(code: String, enabledPlugins: Set<String> = emptySet()): (LifecycleEvent) -> Unit {
        Recorder.reset()
        val pluginYml = File(tempDir, "plugin.yml").apply { writeText("name: Ctor\nmain: $packageName.CtorPlugin\n") }
        val source = "package $packageName\n\nimport com.github.marcoral.versatia.testsupport.Recorder\n$code\nclass CtorPlugin : com.github.marcoral.versatia.VersatiaPlugin()\n"
        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Ctor.kt", source))
            inheritClassPath = true
            jvmTarget = "21"
            messageOutputStream = System.out
            configureKsp {
                symbolProcessorProviders += VersatiaSymbolProcessorProvider()
                withCompilation = true
                processorOptions["versatia.pluginYml"] = pluginYml.path
            }
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val loader = URLClassLoader(arrayOf(result.outputDirectory.toURI().toURL()), Recorder::class.java.classLoader)
        val states = PluginStates()
        val enhancer = PluginEnhancer(ProcessorLoader())
        val plugin = fakePlugin("Ctor", loader, dataFolder, enabledPlugins = enabledPlugins)
        return { event ->
            val context = testContext("Ctor", states, loader, event, plugin)
            enhancer.enhance(DescriptorLoader.load("$packageName.CtorPlugin", context), context)
        }
    }

    private fun Any.property(name: String) = javaClass.getMethod("get" + name.replaceFirstChar { it.uppercaseChar() }).invoke(this)

    @Test
    fun `a constructor var and a tracking parameter receive the values at start and follow every reload`() {
        val enhance = plugin(
            """
            import com.github.marcoral.versatia.EventType
            import com.github.marcoral.versatia.config.PreviousValueTracking
            import com.github.marcoral.versatia.config.Value
            import com.github.marcoral.versatia.di.LocalBean
            import com.github.marcoral.versatia.di.Reloadable
            import com.github.marcoral.versatia.lifecycle.AutoInvoke

            @LocalBean
            class Clock

            @LocalBean
            class Shop(
                val clock: Clock,
                @Value("shop.name") var name: String,
                @Value("shop.slots") val slots: PreviousValueTracking<Int>,
                @Value("shop.days") var days: List<Int>?,
            ) : Reloadable {
                @Value("shop.open")
                var open = false

                init {
                    Recorder.record("Shop", this)
                    Recorder.record("Shop.init", listOf(name, slots.value, slots.changed, days, open))
                }

                override fun reload() = Recorder.record("Shop.reload", listOf(name, slots.value, open))

                @AutoInvoke(EventType.PLUGIN_RELOAD_OR_START)
                fun track() = Recorder.record("Shop.track", listOf(name, slots.previous, slots.value, slots.changed, days))
            }
            """,
        )
        val config = dataFolder.resolve("config.yml")

        config.writeText("shop:\n  name: Alpha\n  slots: 3\n  days: [1, 2]\n  open: true\n")
        enhance(LifecycleEvent.START)
        assertEquals(listOf("Shop", "Shop.init", "Shop.reload", "Shop.track"), Recorder.names())
        assertEquals(listOf("Alpha", 3, true, listOf(1, 2), false), Recorder.instanceOf("Shop.init"), "constructor values exist before the body runs")
        assertEquals(listOf("Alpha", 3, true), Recorder.instanceOf("Shop.reload"))
        assertEquals(listOf("Alpha", null, 3, true, listOf(1, 2)), Recorder.instanceOf("Shop.track"))
        val shop = Recorder.instanceOf("Shop")

        Recorder.reset()
        config.writeText("shop:\n  name: Beta\n  slots: 4\n  open: false\n")
        enhance(LifecycleEvent.RELOAD)
        assertEquals(listOf("Shop.reload", "Shop.track"), Recorder.names(), "the bean is not created again")
        assertEquals(listOf("Beta", 4, false), Recorder.instanceOf("Shop.reload"))
        assertEquals(listOf("Beta", 3, 4, true, null), Recorder.instanceOf("Shop.track"), "the cell passed to the constructor keeps tracking")

        config.writeText("shop:\n  name: Gamma\n  slots: many\n  open: true\n")
        val failure = assertFailsWith<EnhancementException> { enhance(LifecycleEvent.RELOAD) }
        assertContains(failure.message!!, "Shop.slots")
        assertEquals("Beta", shop.property("name"), "nothing is set when a value fails")
        assertEquals(4, (shop.property("slots") as PreviousValueTracking<*>).value)
    }

    @Test
    fun `an optional constructor parameter takes a bean when one exists and its default otherwise`() {
        val enhance = plugin(
            """
            import com.github.marcoral.versatia.di.LocalBean

            interface Greeter {
                fun greet(): String
            }

            object Silent : Greeter {
                override fun greet() = "..."
            }

            @LocalBean
            class Clock

            @LocalBean
            class Desk(val greeter: Greeter = Silent, val clock: Clock = Clock()) {
                init {
                    Recorder.record("Desk", listOf(greeter.greet(), clock))
                }
            }

            @LocalBean
            class Shop(val clock: Clock) {
                init {
                    Recorder.record("Shop", clock)
                }
            }
            """,
        )

        enhance(LifecycleEvent.START)
        val (greeting, clock) = Recorder.instanceOf("Desk") as List<*>
        assertEquals("...", greeting)
        assertSame(Recorder.instanceOf("Shop"), clock, "a provided optional parameter is injected")
    }

    @Test
    fun `a provider conditional through a meta-annotation falls back while its plugin is missing`() {
        val code = """
            import com.github.marcoral.versatia.ConditionalOnPlugin
            import com.github.marcoral.versatia.di.LocalBean
            import com.github.marcoral.versatia.di.Primary

            @ConditionalOnPlugin("Vault")
            annotation class NeedsVault

            interface Economy

            @NeedsVault @Primary @LocalBean
            class VaultEconomy : Economy

            @LocalBean
            class FreeEconomy : Economy

            @LocalBean
            class Shop(val economy: Economy) {
                init {
                    Recorder.record("Shop", economy.javaClass.simpleName)
                }
            }
        """

        plugin(code)(LifecycleEvent.START)
        assertEquals("FreeEconomy", Recorder.instanceOf("Shop"))
        plugin(code, enabledPlugins = setOf("Vault"))(LifecycleEvent.START)
        assertEquals("VaultEconomy", Recorder.instanceOf("Shop"))
    }
}
