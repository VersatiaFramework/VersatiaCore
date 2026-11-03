package dev.versatia.task

import dev.versatia.LifecycleEvent
import dev.versatia.PluginEnhancer
import dev.versatia.PluginStates
import dev.versatia.ProcessorLoader
import dev.versatia.engine.descriptor.DescriptorLoader
import dev.versatia.ksp.VersatiaSymbolProcessorProvider
import dev.versatia.testsupport.FakeCore
import dev.versatia.testsupport.Recorder
import dev.versatia.testsupport.testContext
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir

/**
 * End to end: a plugin with a BackgroundTask, a bean launching from its PluginScope and a stop
 * function is compiled with the real KSP processor and run against a fake VersatiaCore through
 * start, ticks, a reload and the stop. See docs/runtime.md.
 */
@OptIn(ExperimentalCompilerApi::class)
class TaskIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private val source = """
        package com.example.ticks

        import dev.versatia.Phase
        import dev.versatia.VersatiaPlugin
        import dev.versatia.di.LocalBean
        import dev.versatia.lifecycle.AutoInvoke
        import dev.versatia.task.BackgroundTask
        import dev.versatia.task.PluginScope
        import dev.versatia.task.everyTicks
        import dev.versatia.testsupport.Recorder
        import kotlinx.coroutines.awaitCancellation
        import kotlinx.coroutines.launch

        class TicksPlugin : VersatiaPlugin()

        object Ticker : BackgroundTask {
            private var runs = 0

            override suspend fun run() {
                val run = ++runs
                try {
                    everyTicks(2, delayTicks = 0) { Recorder.record("tick", run) }
                } finally {
                    Recorder.record("Ticker.finally", run)
                }
            }
        }

        @LocalBean
        class Watcher(scope: PluginScope) {
            init {
                scope.launch {
                    try {
                        awaitCancellation()
                    } finally {
                        Recorder.record("Watcher.finally", "")
                    }
                }
            }
        }

        object Lifecycle {
            @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)
            fun ready() = Recorder.record("ready", "")

            @AutoInvoke(Phase.PLUGIN_STOP)
            fun stop() = Recorder.record("stop", "")
        }
    """.trimIndent()

    @Test
    fun `a background task ticks from the start, restarts at a reload and stops with the scope before the stop functions`() {
        Recorder.reset()
        val pluginYml = File(tempDir, "plugin.yml").apply { writeText("name: Ticks\nmain: com.example.ticks.TicksPlugin\n") }
        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Ticks.kt", source))
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
        val core = FakeCore(states)
        val enhancer = PluginEnhancer(ProcessorLoader())
        fun enhance(event: LifecycleEvent) {
            val context = testContext("Ticks", states, loader, event)
            enhancer.enhance(DescriptorLoader.load("com.example.ticks.TicksPlugin", context), context)
        }

        enhance(LifecycleEvent.START)
        core.heartbeat(4)
        enhance(LifecycleEvent.RELOAD)
        core.heartbeat(3)
        enhance(LifecycleEvent.STOP)
        core.heartbeat(2)

        assertEquals(
            listOf<Pair<String, Any>>(
                "ready" to "",
                "tick" to 1, "tick" to 1,
                "Ticker.finally" to 1, "ready" to "",
                "tick" to 2, "tick" to 2,
                "Watcher.finally" to "", "Ticker.finally" to 2, "stop" to "",
            ),
            Recorder.events,
        )
        core.stop()
    }
}
