package dev.versatia

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import dev.versatia.di.BeanProcessors
import dev.versatia.di.ExposePrototype
import dev.versatia.di.LocalBean
import dev.versatia.di.PluginBean
import dev.versatia.di.Reloadable
import dev.versatia.di.StartAware
import dev.versatia.engine.descriptor.AnnotationOccurrence
import dev.versatia.engine.descriptor.ClassTarget
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.Instantiator
import dev.versatia.engine.descriptor.Parameter
import dev.versatia.engine.descriptor.PluginTarget
import dev.versatia.ksp.VersatiaSymbolProcessorProvider
import dev.versatia.messages.Messages
import dev.versatia.messages.MessagesImpl
import dev.versatia.testsupport.FakeCore
import dev.versatia.testsupport.Recorder
import dev.versatia.testsupport.descriptor
import dev.versatia.testsupport.fakePlugin
import dev.versatia.testsupport.stylesFile
import dev.versatia.testsupport.testContext
import dev.versatia.text.CheckingText
import dev.versatia.text.ServerStyles
import dev.versatia.text.Text
import dev.versatia.text.TextImpl
import java.io.File
import java.net.URLClassLoader
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.bukkit.plugin.Plugin
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir

/**
 * End to end, compiled with the real KSP processor and run through the runtime: a plugin injects
 * `Messages` and `Text`, ships default styles, registers a tag in a start function and uses both
 * in a message. The beans VersatiaCore exposes for it are declared here as its descriptor declares
 * them. See docs/messages.md.
 */
@OptIn(ExperimentalCompilerApi::class)
class TextStylesIntegrationTest {
    @TempDir
    lateinit var server: File

    private val states = PluginStates().also(::FakeCore)
    private val runtime = VersatiaRuntime(states)

    @BeforeTest
    fun setUp() = Recorder.reset()

    private val plugin = Plugin::class.qualifiedName!!
    private val styles = ServerStyles::class.java.name
    private val checkingText = CheckingText::class.java.name

    /** The text beans of VersatiaCore: its styles, and the `Text` and `Messages` made for every plugin. */
    private val textBeans = descriptor(
        EnhancedElement(BeanProcessors.PLUGIN, Phase.PLUGIN_RELOAD_OR_START, AnnotationOccurrence(PluginBean::class.java.name), PluginTarget(TEXT_CORE_MAIN, listOf(TEXT_CORE_MAIN, plugin), emptyList())),
        EnhancedElement(
            BeanProcessors.LOCAL, Phase.PLUGIN_START_ONLY, AnnotationOccurrence(LocalBean::class.java.name),
            ClassTarget(styles, listOf(styles, Reloadable::class.java.name), emptyList(), listOf(Parameter(plugin)), Instantiator.Direct { args -> ServerStyles(args[0] as Plugin) }),
        ),
        EnhancedElement(
            BeanProcessors.EXPOSE_PROTOTYPE, Phase.PLUGIN_START_ONLY, AnnotationOccurrence(ExposePrototype::class.java.name),
            ClassTarget(
                TextImpl::class.java.name, listOf(TextImpl::class.java.name, checkingText, Text::class.java.name, Reloadable::class.java.name), emptyList(),
                listOf(Parameter(styles), Parameter(plugin)), Instantiator.Direct { args -> TextImpl(args[0] as ServerStyles, args[1] as Plugin) },
            ),
        ),
        EnhancedElement(
            BeanProcessors.EXPOSE_PROTOTYPE, Phase.PLUGIN_START_ONLY, AnnotationOccurrence(ExposePrototype::class.java.name),
            ClassTarget(
                MessagesImpl::class.java.name, listOf(MessagesImpl::class.java.name, Messages::class.java.name, Reloadable::class.java.name, StartAware::class.java.name), emptyList(),
                listOf(Parameter(plugin), Parameter(checkingText)), Instantiator.Direct { args -> MessagesImpl(args[0] as Plugin, args[1] as CheckingText) },
            ),
        ),
    )

    private fun startTextCore() {
        val core = fakePlugin("TextCore", dataFolder = stylesFile(server).parentFile, main = TEXT_CORE_MAIN)
        val context = testContext("TextCore", states, plugin = core)
        PluginEnhancer(ProcessorLoader()).enhance(textBeans, context)
    }

    private fun compile(name: String, source: String): JvmCompilationResult {
        val pluginYml = File(server, "$name.yml").apply { writeText("name: $name\nmain: ${mainOf(name)}\n") }
        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("$name.kt", source))
            inheritClassPath = true
            jvmTarget = "25"
            messageOutputStream = System.out
            configureKsp {
                symbolProcessorProviders += VersatiaSymbolProcessorProvider()
                withCompilation = true
                processorOptions["versatia.pluginYml"] = pluginYml.path
            }
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        return result
    }

    private fun mainOf(name: String) = "com.example.${name.lowercase()}.${name}Plugin"

    private fun dataFolder(name: String) = server.resolve("plugins/$name")

    /** The plugin [name] with [messages] in its data folder and [defaults] as the `styles.yml` of its JAR. */
    private fun plugin(name: String, messages: String, defaults: String): Plugin {
        dataFolder(name).resolve("messages/$name.yml").apply { parentFile.mkdirs() }.writeText(messages.trimIndent())
        val loader = URLClassLoader(arrayOf(compile(name, source(name)).outputDirectory.toURI().toURL()), Recorder::class.java.classLoader)
        return fakePlugin(name, loader, dataFolder(name), main = mainOf(name), resources = mapOf(ServerStyles.FILE to defaults.trimIndent()))
    }

    /** Registers the tag `<server>` in a start function, after the messages were read, and shows the message `open` at start and reload; functions run by name, so `addTags` comes first. */
    private fun source(name: String) = """
        package com.example.${name.lowercase()}

        import dev.versatia.Phase
        import dev.versatia.VersatiaPlugin
        import dev.versatia.lifecycle.AutoInvoke
        import dev.versatia.messages.Messages
        import dev.versatia.testsupport.Recorder
        import dev.versatia.text.Text
        import net.kyori.adventure.text.Component
        import net.kyori.adventure.text.minimessage.tag.Tag
        import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

        class ${name}Plugin : VersatiaPlugin()

        object Announcer {
            @AutoInvoke(Phase.PLUGIN_START_ONLY)
            fun addTags(text: Text) {
                Recorder.record("$name.text", text)
                text.tag("server") { _, _, _ -> Tag.selfClosingInserting(Component.text("Lobby")) }
            }

            @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)
            fun announce(messages: Messages) {
                Recorder.record("$name.messages", messages)
                Recorder.record("$name.open", PlainTextComponentSerializer.plainText().serialize(messages["open"].get()))
            }
        }
    """

    private fun causes(e: Throwable) = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" <- ")

    @Test
    fun `a plugin's defaults reach the server file before its messages are checked, and a tag of a start function counts`() {
        stylesFile(server).apply { parentFile.mkdirs() }.writeText("accent: \"<gold>\"\n")
        startTextCore()
        val shop = plugin("Shop", "open: \"<success>Open on <server>, <accent>today</accent>\"", "success: \"<green>\"\naccent: \"<red>\"")

        runtime.enhance(shop)

        assertEquals("Open on Lobby, today", Recorder.instanceOf("Shop.open"))
        assertEquals("accent: \"<gold>\"\n\n# Default styles of Shop\nsuccess: \"<green>\"\n", stylesFile(server).readText())

        dataFolder("Shop").resolve("messages/Shop.yml").writeText("open: \"<sucess>Open on <server>\"")
        val error = assertFailsWith<EnhancementException> { runtime.reload(shop) }
        assertContains(
            causes(error),
            "Plugin Shop: message \"open\" in messages/Shop.yml cannot be shown: <sucess> is neither a MiniMessage tag nor a style; " +
                "the styles in plugins/VersatiaCore/styles.yml are: accent, success. Write \\<sucess> to show it as text",
        )

        dataFolder("Shop").resolve("messages/Shop.yml").writeText("open: \"<success>Open again on <server>\"")
        Recorder.reset()
        runtime.reload(shop)
        assertEquals("Open again on Lobby", Recorder.instanceOf("Shop.open"))
    }

    @Test
    fun `a message with a tag that does not exist keeps the plugin from starting`() {
        startTextCore()
        val quests = plugin("Quests", "open: \"Quests open on <server>\"\ndone: \"<quest>Done\"", "success: \"<green>\"")

        val error = assertFailsWith<EnhancementException> { runtime.enhance(quests) }

        assertContains(causes(error), "${MessagesImpl::class.java.name} failed its check at the end of the start")
        assertContains(causes(error), "Plugin Quests: message \"done\" in messages/Quests.yml cannot be shown: <quest> is neither a MiniMessage tag nor a style")
        assertEquals("Quests open on Lobby", Recorder.instanceOf("Quests.open"), "the start functions had run: the bank is checked after them")
    }

    @Test
    fun `a plugin's Text and Messages are at hand on the plugin while it runs, the instances its beans are injected`() {
        startTextCore()
        val shop = plugin("Shop", "open: \"Open on <server>\"", "")
        fun noText() = assertFailsWith<IllegalStateException> { runtime.defaultBean(shop, Text::class) }.message

        val expected = "Plugin Shop has no Text at this point: plugin.text is there while a Versatia plugin runs, from the moment its beans exist until it stops. " +
            "In a constructor or a property initializer, take Text as a constructor parameter instead."
        assertEquals(expected, noText(), "before it starts")

        runtime.enhance(shop)
        assertSame(Recorder.instanceOf("Shop.text"), runtime.defaultBean(shop, Text::class))
        assertSame(Recorder.instanceOf("Shop.messages"), runtime.defaultBean(shop, Messages::class))

        runtime.disable(shop)
        assertEquals(expected, noText(), "once it stopped")
    }

    private companion object {
        const val TEXT_CORE_MAIN = "dev.versatia.testsupport.TextCorePlugin"
    }
}
