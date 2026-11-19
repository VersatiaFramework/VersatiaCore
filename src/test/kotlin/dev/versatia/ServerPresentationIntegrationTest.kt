package dev.versatia

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import dev.versatia.di.BeanProcessors
import dev.versatia.di.ExposePrototype
import dev.versatia.di.LocalBean
import dev.versatia.di.PluginBean
import dev.versatia.di.Reloadable
import dev.versatia.engine.descriptor.AnnotationOccurrence
import dev.versatia.engine.descriptor.ClassTarget
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.Instantiator
import dev.versatia.engine.descriptor.Parameter
import dev.versatia.engine.descriptor.PluginTarget
import dev.versatia.ksp.VersatiaSymbolProcessorProvider
import dev.versatia.testsupport.FakeCore
import dev.versatia.testsupport.Recorder
import dev.versatia.testsupport.descriptor
import dev.versatia.testsupport.fakePlugin
import dev.versatia.testsupport.testContext
import dev.versatia.text.CheckingText
import dev.versatia.text.ServerPresentations
import dev.versatia.text.ServerStyles
import dev.versatia.text.Text
import dev.versatia.text.TextImpl
import dev.versatia.text.UnknownPropertyException
import dev.versatia.text.arg
import dev.versatia.text.present
import dev.versatia.text.presentation
import java.io.File
import java.net.URLClassLoader
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.Plugin
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir

/** A type of the server that plugins present: the provider compiled here adds a property to it, which a plugin knowing nothing of the provider shows. */
class Hero(val name: String)

/**
 * End to end, compiled with the real KSP processor and run through the runtime: a provider's
 * `@ServerPresentation` bean adds `{hero.rank}` to how every plugin shows a `Hero`, in use from
 * the provider's start until its stop, and a second provider of the same type needs `@Primary`.
 * The text beans of VersatiaCore are declared here as its descriptor declares them. See
 * docs/messages.md.
 */
@OptIn(ExperimentalCompilerApi::class)
class ServerPresentationIntegrationTest {
    @TempDir
    lateinit var server: File

    private val states = PluginStates().also(::FakeCore)
    private val runtime = VersatiaRuntime(states)

    @BeforeTest
    fun setUp() = Recorder.reset()

    private val plugin = Plugin::class.qualifiedName!!
    private val styles = ServerStyles::class.java.name
    private val presentations = ServerPresentations::class.java.name

    private val textBeans = descriptor(
        EnhancedElement(BeanProcessors.PLUGIN, Phase.PLUGIN_RELOAD_OR_START, AnnotationOccurrence(PluginBean::class.java.name), PluginTarget(TEXT_CORE_MAIN, listOf(TEXT_CORE_MAIN, plugin), emptyList())),
        EnhancedElement(
            BeanProcessors.LOCAL, Phase.PLUGIN_START_ONLY, AnnotationOccurrence(LocalBean::class.java.name),
            ClassTarget(styles, listOf(styles, Reloadable::class.java.name), emptyList(), listOf(Parameter(plugin)), Instantiator.Direct { args -> ServerStyles(args[0] as Plugin) }),
        ),
        EnhancedElement(
            BeanProcessors.LOCAL, Phase.PLUGIN_START_ONLY, AnnotationOccurrence(LocalBean::class.java.name),
            ClassTarget(presentations, listOf(presentations), emptyList(), emptyList(), Instantiator.Direct { ServerPresentations() }),
        ),
        EnhancedElement(
            BeanProcessors.EXPOSE_PROTOTYPE, Phase.PLUGIN_START_ONLY, AnnotationOccurrence(ExposePrototype::class.java.name),
            ClassTarget(
                TextImpl::class.java.name, listOf(TextImpl::class.java.name, CheckingText::class.java.name, Text::class.java.name, Reloadable::class.java.name), emptyList(),
                listOf(Parameter(styles), Parameter(presentations), Parameter(plugin)),
                Instantiator.Direct { args -> TextImpl(args[0] as ServerStyles, args[1] as ServerPresentations, args[2] as Plugin) },
            ),
        ),
    )

    private fun startTextCore() {
        val core = fakePlugin("TextCore", dataFolder = server.resolve("plugins/TextCore"), main = TEXT_CORE_MAIN)
        PluginEnhancer(ProcessorLoader()).enhance(textBeans, testContext("TextCore", states, plugin = core))
    }

    private fun mainOf(name: String) = "com.example.${name.lowercase()}.${name}Plugin"

    /** The plugin [name], compiled from [declarations] next to its main class, which records its `Text` when it starts. */
    private fun plugin(name: String, declarations: String): Plugin {
        val pluginYml = File(server, "$name.yml").apply { writeText("name: $name\nmain: ${mainOf(name)}\n") }
        val source = """
            package com.example.${name.lowercase()}

            import dev.versatia.Hero
            import dev.versatia.Phase
            import dev.versatia.VersatiaPlugin
            import dev.versatia.di.LocalBean
            import dev.versatia.di.Primary
            import dev.versatia.lifecycle.AutoInvoke
            import dev.versatia.testsupport.Recorder
            import dev.versatia.text.Presenter
            import dev.versatia.text.ServerPresentation
            import dev.versatia.text.Text
            import dev.versatia.text.presentation

            class ${name}Plugin : VersatiaPlugin()

            object Startup {
                @AutoInvoke(Phase.PLUGIN_START_ONLY)
                fun record(text: Text) = Recorder.record("$name.text", text)
            }

            $declarations
        """
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
        val loader = URLClassLoader(arrayOf(result.outputDirectory.toURI().toURL()), Recorder::class.java.classLoader)
        return fakePlugin(name, loader, server.resolve("plugins/$name"), main = mainOf(name))
    }

    /** A provider presenting a hero's rank, read from a bean of its own. */
    private fun provider(name: String, rank: String, primary: Boolean = false) = plugin(
        name,
        """
        @LocalBean
        class Ranks {
            fun of(hero: Hero) = "$rank"
        }

        @ServerPresentation${if (primary) " @Primary" else ""}
        class HeroCard(private val ranks: Ranks) : Presenter<Hero> {
            override fun present(value: Hero) = presentation { property("rank") { ranks.of(value) } }
        }
        """,
    )

    /** A plugin that presents a hero by its name and knows nothing of any provider. */
    private fun consumer(): Text {
        runtime.enhance(plugin("Shop", ""))
        return (Recorder.instanceOf("Shop.text") as Text).apply { present<Hero> { hero -> presentation { component { Component.text(hero.name) } } } }
    }

    private fun Text.plain(input: String) = PlainTextComponentSerializer.plainText().serialize(parse(input, arg("hero", Hero("Ann"))))

    private fun causes(e: Throwable) = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" <- ")

    @Test
    fun `a provider's presentation reaches a plugin that started before it, survives a reload and goes with its stop`() {
        startTextCore()
        val shop = consumer()
        assertFailsWith<UnknownPropertyException> { shop.plain("{hero.rank}") }

        val guild = provider("Guild", "Captain")
        runtime.enhance(guild)
        assertEquals("Ann is Captain", shop.plain("{hero} is {hero.rank}"), "the provider adds the rank, the consumer's own component stays")

        runtime.reload(guild)
        assertEquals("Ann is Captain", shop.plain("{hero} is {hero.rank}"))

        runtime.disable(guild)
        assertEquals("Ann", shop.plain("{hero}"))
        assertEquals("{hero} has no property \"rank\"; it has none", assertFailsWith<UnknownPropertyException> { shop.plain("{hero.rank}") }.message)
    }

    @Test
    fun `a second provider of the same type fails its start unless it is Primary`() {
        startTextCore()
        val shop = consumer()
        runtime.enhance(provider("Guild", "Captain"))

        val error = assertFailsWith<EnhancementException> { runtime.enhance(provider("Order", "Knight")) }
        assertContains(
            causes(error),
            "Plugin Order: dev.versatia.Hero would have several server presentations: com.example.guild.HeroCard of Guild, com.example.order.HeroCard of Order; " +
                "mark the one to use @Primary, or remove the others",
        )
        assertEquals("Captain", shop.plain("{hero.rank}"))

        val council = provider("Council", "Lord", primary = true)
        runtime.enhance(council)
        assertEquals("Lord", shop.plain("{hero.rank}"))
        runtime.disable(council)
        assertEquals("Captain", shop.plain("{hero.rank}"))
    }

    private companion object {
        const val TEXT_CORE_MAIN = "dev.versatia.testsupport.TextCorePlugin"
    }
}
