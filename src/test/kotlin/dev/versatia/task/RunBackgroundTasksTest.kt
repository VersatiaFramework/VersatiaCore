package dev.versatia.task

import dev.versatia.DefaultEnhancementContext
import dev.versatia.EnhancementException
import dev.versatia.LifecycleEvent
import dev.versatia.Phase
import dev.versatia.PluginEnhancer
import dev.versatia.PluginStates
import dev.versatia.ProcessorLoader
import dev.versatia.config.ConfigProcessors
import dev.versatia.config.NoMapper
import dev.versatia.config.Value
import dev.versatia.di.BeanResolver
import dev.versatia.engine.descriptor.AnnotationOccurrence
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.MemberOwner
import dev.versatia.engine.descriptor.PropertyTarget
import dev.versatia.engine.descriptor.Setter
import dev.versatia.engine.descriptor.TypeReference
import dev.versatia.task.TaskElements.Companion.classTarget
import dev.versatia.task.TaskElements.Companion.function
import dev.versatia.testsupport.FakeCore
import dev.versatia.testsupport.LogCapture
import dev.versatia.testsupport.Recorder
import dev.versatia.testsupport.captureLog
import dev.versatia.testsupport.descriptor
import dev.versatia.testsupport.fakePlugin
import dev.versatia.testsupport.testContext
import java.io.File
import java.util.logging.Level
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.bukkit.event.Listener
import org.junit.jupiter.api.io.TempDir

object Greeting {
    var text = ""
}

/** Records each start of a task with the greeting it sees, and its end. */
private suspend fun recordRun(task: String) {
    Recorder.record("$task.run", Greeting.text)
    try {
        awaitCancellation()
    } finally {
        Recorder.record("$task.finally", Greeting.text)
    }
}

object ObjectTask : BackgroundTask {
    override suspend fun run() = recordRun("ObjectTask")
}

class BeanTask : BackgroundTask {
    override suspend fun run() = recordRun("BeanTask")
}

class PlainTask(val scope: PluginScope) : BackgroundTask, Listener {
    init {
        Recorder.record("PlainTask.init", this)
    }

    override suspend fun run() {
        Recorder.record("PlainTask.job", currentCoroutineContext().job)
        recordRun("PlainTask")
    }
}

/** A bean injecting the scope, with a coroutine of its own. */
class ScopeUser(val scope: PluginScope) {
    init {
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                Recorder.record("ScopeUser.finally", "")
            }
        }
    }
}

object FailingTask : BackgroundTask {
    var runs = 0

    override suspend fun run() {
        runs++
        throw IllegalStateException("broken task")
    }
}

object TaskLifecycle {
    fun ready() = Recorder.record("ready", Greeting.text)

    fun stop() = Recorder.record("stop", Greeting.text)
}

/** @RunBackgroundTasks and the PluginScope lifecycle over hand-written descriptors, with a fake VersatiaCore; see docs/runtime.md. */
class RunBackgroundTasksTest {
    @TempDir
    lateinit var dataFolder: File

    private val states = PluginStates()
    private val core = FakeCore(states)
    private val enhancer = PluginEnhancer(ProcessorLoader())
    private val registered = mutableListOf<Any>()
    private val plugin by lazy { fakePlugin("Tasks", dataFolder = dataFolder, registrations = registered) }
    private lateinit var log: LogCapture
    private val elements = TaskElements("com.example.tasks.TasksPlugin")

    @BeforeTest
    fun setUp() {
        Recorder.reset()
        Greeting.text = ""
        FailingTask.runs = 0
        log = plugin.captureLog()
    }

    @AfterTest
    fun tearDown() {
        log.close()
        core.stop()
    }

    private fun config(greeting: String) = dataFolder.resolve("config.yml").writeText("greeting: $greeting\n")

    private fun context(event: LifecycleEvent) = testContext("Tasks", states, event = event, plugin = plugin)

    private val plainTask = classTarget<PlainTask>(listOf(PluginScope::class), listOf(BackgroundTask::class, Listener::class)) { PlainTask(it[0] as PluginScope) }
    private val beanTask = classTarget<BeanTask>(supertypes = listOf(BackgroundTask::class)) { BeanTask() }
    private val objectTask = classTarget<ObjectTask>(supertypes = listOf(BackgroundTask::class)) { ObjectTask }
    private val scopeUser = classTarget<ScopeUser>(listOf(PluginScope::class)) { ScopeUser(it[0] as PluginScope) }

    private val greeting = EnhancedElement(
        ConfigProcessors.VALUE,
        Phase.PLUGIN_RELOAD_OR_START,
        AnnotationOccurrence(Value::class.java.name, mapOf("key" to "greeting", "file" to Value.DEFAULT_FILE, "mapper" to NoMapper::class.qualifiedName!!)),
        PropertyTarget(Greeting::class.qualifiedName!!, Greeting::class.java.name, "text", MemberOwner.OBJECT, TypeReference("kotlin.String"), emptyList(), Setter.Direct { _, value -> Greeting.text = value as String }),
    )

    /** The plugin in the order the KSP processor writes it: the enhancements of VersatiaPlugin first, then beans, values and functions. */
    private val tasksPlugin = descriptor(
        elements.pluginBean,
        elements.listener(plainTask),
        elements.task(beanTask),
        elements.task(objectTask),
        elements.task(plainTask),
        elements.scopeAtStart,
        elements.scopeAtStop,
        elements.bean(beanTask),
        elements.bean(scopeUser),
        greeting,
        function<TaskLifecycle>("ready", Phase.PLUGIN_RELOAD_OR_START) { TaskLifecycle.ready() },
        function<TaskLifecycle>("stop", Phase.PLUGIN_STOP) { TaskLifecycle.stop() },
    )

    private fun enhance(event: LifecycleEvent) = enhancer.enhance(tasksPlugin, context(event))

    @Test
    fun `object, bean and plain-class tasks run from the first heartbeat after the start, in the scope beans receive`() {
        config("hello")
        enhance(LifecycleEvent.START)
        assertEquals(listOf("PlainTask.init", "ready"), Recorder.names(), "no task runs before the first heartbeat")

        Recorder.reset()
        core.heartbeat()
        assertEquals(
            listOf<Pair<String, Any>>("BeanTask.run" to "hello", "ObjectTask.run" to "hello", "PlainTask.run" to "hello"),
            Recorder.events.filter { it.first.endsWith(".run") },
        )
        val scope = states.of("Tasks")[PluginScopeBean.KEY]
        val user = states.of("Tasks")[BeanResolver.KEY]!!.instanceOf(ScopeUser::class.java.name) as ScopeUser
        assertSame<Any?>(scope, user.scope)
        val plain = assertIs<PlainTask>(registered.single(), "the plain task is registered as a listener")
        assertSame<Any?>(scope, plain.scope)
        assertTrue(assertIs<Job>(Recorder.instanceOf("PlainTask.job")) in scope!!.coroutineContext.job.children, "a task runs as a child of the scope")
    }

    @Test
    fun `a plain class that is a Listener and a BackgroundTask is created once`() {
        config("hello")
        enhance(LifecycleEvent.START)
        core.heartbeat()

        assertEquals(1, Recorder.names().count { it == "PlainTask.init" })
        assertSame(Recorder.instanceOf("PlainTask.init"), registered.single())
    }

    @Test
    fun `a reload cancels the runs once the new values are set, before the reload functions, and starts them again`() {
        config("hello")
        enhance(LifecycleEvent.START)
        core.heartbeat()

        Recorder.reset()
        config("bye")
        enhance(LifecycleEvent.RELOAD)
        assertEquals(
            listOf<Pair<String, Any>>("BeanTask.finally" to "bye", "ObjectTask.finally" to "bye", "PlainTask.finally" to "bye", "ready" to "bye"),
            Recorder.events,
        )

        Recorder.reset()
        core.heartbeat()
        assertEquals(
            listOf<Pair<String, Any>>("BeanTask.run" to "bye", "ObjectTask.run" to "bye", "PlainTask.run" to "bye"),
            Recorder.events.filter { it.first.endsWith(".run") },
        )
    }

    @Test
    fun `a failing task is logged once and starts again only at a reload`() {
        val failing = descriptor(
            elements.pluginBean,
            elements.task(classTarget<FailingTask>(supertypes = listOf(BackgroundTask::class)) { FailingTask }),
            elements.scopeAtStart,
            elements.scopeAtStop,
        )
        enhancer.enhance(failing, context(LifecycleEvent.START))
        core.heartbeat(5)
        assertEquals(1, FailingTask.runs)
        val failure = log.records.single { it.level == Level.SEVERE }
        assertEquals("Background task ${FailingTask::class.java.name} failed; it starts again at the next reload", failure.message)
        assertEquals("broken task", failure.thrown.message)

        enhancer.enhance(failing, context(LifecycleEvent.RELOAD))
        core.heartbeat(5)
        assertEquals(2, FailingTask.runs)
        assertEquals(2, log.records.count { it.level == Level.SEVERE })
    }

    @Test
    fun `the stop cancels the runs and the coroutines of the scope before the stop functions run`() {
        config("hello")
        enhance(LifecycleEvent.START)
        core.heartbeat()

        Recorder.reset()
        enhance(LifecycleEvent.STOP)
        assertEquals(listOf("ScopeUser.finally", "BeanTask.finally", "ObjectTask.finally", "PlainTask.finally", "stop"), Recorder.names())
        assertTrue(states.of("Tasks")[PluginScopeBean.KEY]!!.coroutineContext.job.isCancelled)
        assertEquals(emptyList(), log.messages(Level.WARNING))
    }

    @Test
    fun `tasks need a bean container`() {
        val error = assertFailsWith<EnhancementException> {
            enhancer.enhance(descriptor(elements.task(objectTask)), context(LifecycleEvent.START))
        }
        assertEquals(
            "Plugin Tasks: ${ObjectTask::class.qualifiedName} is a BackgroundTask, but the plugin has no bean container; extend VersatiaPlugin or put @PluginBean on the main class",
            error.message,
        )
    }

    @Test
    fun `the PluginScope processor takes the main class exactly once`() {
        val processor = PluginScopeEnhancementProcessor()
        val context = DefaultEnhancementContext(plugin, LifecycleEvent.START, states.of("Tasks"), states.runtime)
        for (wrong in listOf(emptyList(), listOf(elements.scopeAtStart, elements.scopeAtStart), listOf(elements.task(objectTask)))) {
            val error = assertFailsWith<EnhancementException> { processor.process(wrong, context) }
            assertContains(error.message!!, "${PluginScopeEnhancementProcessor::class.java.name} expects the plugin's main class exactly once, got ")
        }
    }

    @Test
    fun `ProcessorLoader loads both task processors`() {
        val loader = ProcessorLoader()
        val context = context(LifecycleEvent.START)
        assertIs<RunBackgroundTasksEnhancementProcessor>(loader.load(TaskProcessors.RUN_BACKGROUND_TASKS, context))
        assertIs<PluginScopeEnhancementProcessor>(loader.load(TaskProcessors.PLUGIN_SCOPE, context))
    }
}
