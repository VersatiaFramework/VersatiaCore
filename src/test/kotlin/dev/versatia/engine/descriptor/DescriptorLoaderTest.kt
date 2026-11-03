package dev.versatia.engine.descriptor

import dev.versatia.EnhancementException
import dev.versatia.PluginStates
import dev.versatia.testsupport.testContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DescriptorLoaderTest {
    private val context = testContext("Sample", PluginStates())

    @Test
    fun `derives the descriptor name from the main class`() {
        assertEquals("com.example.VersatiaDescriptor", VersatiaPluginDescriptor.classNameFor("com.example.MyPlugin"))
        assertEquals("VersatiaDescriptor", VersatiaPluginDescriptor.classNameFor("MyPlugin"))
    }

    @Test
    fun `loads the descriptor next to the main class`() {
        val descriptor = DescriptorLoader.load("dev.versatia.engine.descriptor.sample.SamplePlugin", context)
        assertSame(dev.versatia.engine.descriptor.sample.VersatiaDescriptor, descriptor)
        assertEquals(VersatiaPluginDescriptor.FORMAT_VERSION, descriptor.formatVersion)
    }

    @Test
    fun `reports a missing descriptor`() {
        val error = assertFailsWith<EnhancementException> {
            DescriptorLoader.load("dev.versatia.engine.descriptor.nothing.Plugin", context)
        }
        assertContains(error.message!!, "dev.versatia.engine.descriptor.nothing.VersatiaDescriptor")
    }

    @Test
    fun `reports another format before touching elements it cannot load`() {
        val error = assertFailsWith<EnhancementException> {
            DescriptorLoader.load("dev.versatia.engine.descriptor.newer.FuturePlugin", context)
        }
        assertContains(error.message!!, "Sample was built for descriptor format 7.0")
        assertContains(error.message!!, "Update VersatiaCore")
    }

    @Test
    fun `rejects an outdated format with the plugin name and both versions`() {
        val error = assertFailsWith<EnhancementException> {
            DescriptorLoader.load("dev.versatia.engine.descriptor.outdated.OldPlugin", context)
        }
        assertContains(error.message!!, "Sample was built for descriptor format 0.9")
        assertContains(error.message!!, VersatiaPluginDescriptor.FORMAT_VERSION.toString())
        assertContains(error.message!!, "Rebuild the plugin")
    }

    @Test
    fun `rejects a descriptor class that is not an object`() {
        val error = assertFailsWith<EnhancementException> {
            DescriptorLoader.load("dev.versatia.engine.descriptor.notobject.Plugin", context)
        }
        assertEquals("Plugin Sample: dev.versatia.engine.descriptor.notobject.VersatiaDescriptor is not a Kotlin object; it is not a Versatia descriptor", error.message)
    }
}
