package com.github.marcoral.versatia.config

import com.github.marcoral.versatia.LifecycleEvent
import com.github.marcoral.versatia.PluginEnhancer
import com.github.marcoral.versatia.PluginStates
import com.github.marcoral.versatia.ProcessorLoader
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
import kotlin.test.assertEquals
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir

/** End to end: a plugin with `@Value` properties is compiled with the real KSP processor and run through the runtime. */
@OptIn(ExperimentalCompilerApi::class)
class ValueIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private val source = """
        package com.example.values

        import com.github.marcoral.versatia.Phase
        import com.github.marcoral.versatia.VersatiaPlugin
        import com.github.marcoral.versatia.config.PreviousValueTracking
        import com.github.marcoral.versatia.config.Value
        import com.github.marcoral.versatia.di.LocalBean
        import com.github.marcoral.versatia.lifecycle.AutoInvoke
        import com.github.marcoral.versatia.testsupport.Recorder
        import com.github.marcoral.versatia.util.type.Percent

        class ValuesPlugin : VersatiaPlugin()

        enum class Mode { EASY, HARD }

        object Limits {
            @Value("limits.max")
            private var max = 0

            @Value("limits.mode", file = "rules.yml")
            var mode = Mode.EASY

            @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)
            fun report() = Recorder.record("Limits.report", max to mode)
        }

        @LocalBean
        class Shop {
            @Value("shop.hours")
            var hours: List<Int> = emptyList()

            @Value("shop.tax")
            var tax: Percent? = null

            @Value("shop.slots")
            val slots = PreviousValueTracking<Int>()

            @Value("shop.zone")
            private val zone = PreviousValueTracking<String?>()

            @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)
            fun report() = Recorder.record("Shop.report", hours to tax)

            @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)
            fun track() = Recorder.record("Shop.track", listOf(slots.previous, slots.value, slots.changed, zone.value))
        }
    """.trimIndent()

    @Test
    fun `values flow from the generated descriptor through the runtime`() {
        Recorder.reset()
        val pluginYml = File(tempDir, "plugin.yml").apply { writeText("name: Values\nmain: com.example.values.ValuesPlugin\n") }
        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Values.kt", source))
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

        val dataFolder = tempDir.resolve("plugins/Values").apply { mkdirs() }
        dataFolder.resolve("config.yml").writeText("limits:\n  max: 4\nshop:\n  hours: [8, 20]\n  slots: 3\n")
        dataFolder.resolve("rules.yml").writeText("limits:\n  mode: hard\n")
        val loader = URLClassLoader(arrayOf(result.outputDirectory.toURI().toURL()), Recorder::class.java.classLoader)
        val states = PluginStates()
        val enhancer = PluginEnhancer(ProcessorLoader())
        fun enhance(event: LifecycleEvent) {
            val context = testContext("Values", states, loader, event, fakePlugin("Values", loader, dataFolder))
            enhancer.enhance(DescriptorLoader.load("com.example.values.ValuesPlugin", context), context)
        }

        enhance(LifecycleEvent.START)
        assertEquals(listOf("Limits.report", "Shop.report", "Shop.track"), Recorder.names())
        assertEquals(4 to loader.enumConstant("HARD"), Recorder.instanceOf("Limits.report"))
        assertEquals(listOf(8, 20) to null, Recorder.instanceOf("Shop.report"))
        assertEquals(listOf(null, 3, true, null), Recorder.instanceOf("Shop.track"))

        Recorder.reset()
        dataFolder.resolve("config.yml").writeText("limits:\n  max: 5\nshop:\n  hours: [9]\n  tax: 2.5%\n  slots: 4\n  zone: UTC\n")
        enhance(LifecycleEvent.RELOAD)
        assertEquals(5 to loader.enumConstant("HARD"), Recorder.instanceOf("Limits.report"))
        assertEquals("([9], 2.5%)", Recorder.instanceOf("Shop.report").toString())
        assertEquals(listOf(3, 4, true, "UTC"), Recorder.instanceOf("Shop.track"), "the tracked value keeps the one it replaced")
    }

    private fun ClassLoader.enumConstant(name: String) = loadClass("com.example.values.Mode").enumConstants.single { (it as Enum<*>).name == name }
}
