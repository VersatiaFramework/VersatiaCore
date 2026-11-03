package dev.versatia.task

import dev.versatia.EnhancementException
import dev.versatia.LifecycleEvent
import dev.versatia.PluginEnhancer
import dev.versatia.PluginStates
import dev.versatia.ProcessorLoader
import dev.versatia.engine.descriptor.DescriptorLoader
import dev.versatia.ksp.VersatiaSymbolProcessorProvider
import dev.versatia.testsupport.FakeCore
import dev.versatia.testsupport.Recorder
import dev.versatia.testsupport.fakePlugin
import dev.versatia.testsupport.testContext
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir

/**
 * End to end: a plain BackgroundTask and a plain Listener, neither a bean, with `@Value`
 * properties are compiled with the real KSP processor and run against a fake VersatiaCore: the
 * values are in place before the listener is registered and before the task first runs, and a
 * reload sets new ones before the task restarts. See docs/runtime.md.
 */
@OptIn(ExperimentalCompilerApi::class)
class SubclassValueIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private val source = """
        package com.example.plain

        import dev.versatia.VersatiaPlugin
        import dev.versatia.config.PreviousValueTracking
        import dev.versatia.config.Value
        import dev.versatia.task.BackgroundTask
        import dev.versatia.testsupport.Recorder
        import kotlinx.coroutines.awaitCancellation
        import org.bukkit.event.Listener

        class PlainPlugin : VersatiaPlugin()

        class Announcer(@Value("prefix") var prefix: String, @Value("period") val period: PreviousValueTracking<Int>) : BackgroundTask {
            @Value("message")
            private var message = ""

            init {
                Recorder.record("Announcer.init", prefix to period.value)
            }

            override suspend fun run() {
                Recorder.record("run", "${'$'}prefix${'$'}message every ${'$'}{period.value}")
                awaitCancellation()
            }
        }

        class Greeter : Listener {
            @Value("greeting")
            var greeting = ""
        }
    """.trimIndent()

    @Test
    fun `a plain task and a plain listener get their values before they run and new ones at a reload`() {
        Recorder.reset()
        val pluginYml = File(tempDir, "plugin.yml").apply { writeText("name: Plain\nmain: com.example.plain.PlainPlugin\n") }
        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Plain.kt", source))
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

        val dataFolder = tempDir.resolve("plugins/Plain").apply { mkdirs() }
        val config = dataFolder.resolve("config.yml")
        config.writeText("prefix: '> '\nperiod: 5\nmessage: hello\ngreeting: hi\n")
        val loader = URLClassLoader(arrayOf(result.outputDirectory.toURI().toURL()), Recorder::class.java.classLoader)
        val states = PluginStates()
        val core = FakeCore(states)
        val registered = mutableListOf<Any>()
        val plugin = fakePlugin("Plain", loader, dataFolder, registrations = registered)
        val enhancer = PluginEnhancer(ProcessorLoader())
        fun enhance(event: LifecycleEvent) {
            val context = testContext("Plain", states, loader, event, plugin)
            enhancer.enhance(DescriptorLoader.load("com.example.plain.PlainPlugin", context), context)
        }
        fun greeting() = registered.single().let { it.javaClass.getMethod("getGreeting").invoke(it) }

        enhance(LifecycleEvent.START)
        assertEquals("hi", greeting(), "the listener is registered with its value set")
        core.heartbeat()
        assertEquals(listOf<Pair<String, Any>>("Announcer.init" to ("> " to 5), "run" to "> hello every 5"), Recorder.events)

        Recorder.reset()
        config.writeText("prefix: '>> '\nperiod: 7\nmessage: bye\ngreeting: hey\n")
        enhance(LifecycleEvent.RELOAD)
        core.heartbeat()
        assertEquals(listOf<Pair<String, Any>>("run" to ">> bye every 7"), Recorder.events, "the same instance restarts with the new values")
        assertEquals("hey", greeting())

        config.writeText("prefix: '! '\nperiod: soon\nmessage: oops\ngreeting: yo\n")
        assertFailsWith<EnhancementException> { enhance(LifecycleEvent.RELOAD) }
        assertEquals("hey", greeting(), "a broken file sets no value")
        core.stop()
    }
}
