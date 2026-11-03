package dev.versatia.resources

import dev.versatia.EnhancementException
import dev.versatia.Phase
import dev.versatia.PluginEnhancer
import dev.versatia.PluginStates
import dev.versatia.ProcessorLoader
import dev.versatia.engine.descriptor.AnnotationOccurrence
import dev.versatia.engine.descriptor.ClassTarget
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.Instantiator
import dev.versatia.engine.descriptor.PluginTarget
import dev.versatia.testsupport.descriptor
import dev.versatia.testsupport.fakePlugin
import dev.versatia.testsupport.testContext
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

/** Stands in for a plugin's main class; its class file is copied into the code sources built by the tests. */
class SampleMain

/** Like a Bukkit plugin loader: the plugin's own classes come from its code source, everything else from the parent. */
private class PluginLoader(source: File) : URLClassLoader(arrayOf(source.toURI().toURL()), SampleMain::class.java.classLoader) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (name == SampleMain::class.java.name) findLoadedClass(name) ?: findClass(name) else super.loadClass(name, resolve)
}

class ExtractResourcesTest {
    @TempDir
    lateinit var tempDir: File

    private val mainName = SampleMain::class.java.name
    private val mainBytes = SampleMain::class.java.getResourceAsStream("SampleMain.class")!!.readBytes()
    private val resources = mapOf("config.yml" to "greeting: hello\n", "lang/en.yml" to "bye: goodbye\n")

    private val element = EnhancedElement(
        ResourceProcessors.EXTRACT,
        Phase.PLUGIN_START_ONLY,
        AnnotationOccurrence(ExtractResources::class.java.name),
        PluginTarget(mainName, listOf(mainName), emptyList()),
    )

    /** A class directory holding the main class and the given resources, like a build's output. */
    private fun classDirectory(resources: Map<String, String>): File {
        val root = tempDir.resolve("classes")
        root.resolve(mainName.replace('.', '/') + ".class").apply { parentFile.mkdirs() }.writeBytes(mainBytes)
        resources.forEach { (path, content) -> root.resolve("${ExtractResources.DIRECTORY}/$path").apply { parentFile.mkdirs() }.writeText(content) }
        return root
    }

    /** A plugin JAR holding the main class and the given resources. */
    private fun jar(resources: Map<String, String>): File {
        val file = tempDir.resolve("plugin.jar")
        JarOutputStream(file.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry(mainName.replace('.', '/') + ".class"))
            jar.write(mainBytes)
            jar.putNextEntry(JarEntry("${ExtractResources.DIRECTORY}/"))
            resources.forEach { (path, content) ->
                jar.putNextEntry(JarEntry("${ExtractResources.DIRECTORY}/$path"))
                jar.write(content.toByteArray())
            }
        }
        return file
    }

    /** Enhances a plugin whose code lives in [source]; the loader takes the main class from there, not from the test classpath. */
    private fun enhance(source: File, dataFolder: File, vararg elements: EnhancedElement = arrayOf(element)) {
        PluginLoader(source).use { loader ->
            val context = testContext("Alpha", PluginStates(), loader, plugin = fakePlugin("Alpha", loader, dataFolder))
            PluginEnhancer(ProcessorLoader()).enhance(descriptor(*elements), context)
        }
    }

    @Test
    fun `extracts the resources of a jar into the data folder`() {
        val dataFolder = tempDir.resolve("plugins/Alpha")

        enhance(jar(resources), dataFolder)

        assertEquals("greeting: hello\n", dataFolder.resolve("config.yml").readText())
        assertEquals("bye: goodbye\n", dataFolder.resolve("lang/en.yml").readText())
    }

    @Test
    fun `extracts the resources of a class directory`() {
        val dataFolder = tempDir.resolve("plugins/Alpha")

        enhance(classDirectory(resources), dataFolder)

        assertEquals("greeting: hello\n", dataFolder.resolve("config.yml").readText())
        assertEquals("bye: goodbye\n", dataFolder.resolve("lang/en.yml").readText())
    }

    @Test
    fun `keeps files that already exist`() {
        val dataFolder = tempDir.resolve("plugins/Alpha")
        dataFolder.resolve("config.yml").apply { parentFile.mkdirs() }.writeText("greeting: edited on the server\n")

        enhance(jar(resources), dataFolder)

        assertEquals("greeting: edited on the server\n", dataFolder.resolve("config.yml").readText())
        assertEquals("bye: goodbye\n", dataFolder.resolve("lang/en.yml").readText())
    }

    @Test
    fun `does nothing without a resources directory`() {
        val dataFolder = tempDir.resolve("plugins/Alpha")

        enhance(classDirectory(emptyMap()), dataFolder)

        assertFalse(dataFolder.exists())
    }

    @Test
    fun `accepts only the plugin's main class`() {
        val bean = EnhancedElement(
            ResourceProcessors.EXTRACT,
            Phase.PLUGIN_START_ONLY,
            AnnotationOccurrence(ExtractResources::class.java.name),
            ClassTarget("com.example.Config", listOf("com.example.Config"), emptyList(), emptyList(), Instantiator.Direct { Any() }),
        )

        val failure = assertFailsWith<EnhancementException> { enhance(jar(resources), tempDir.resolve("plugins/Alpha"), bean) }

        assertContains(failure.message!!, "expects the plugin's main class exactly once")
    }
}
