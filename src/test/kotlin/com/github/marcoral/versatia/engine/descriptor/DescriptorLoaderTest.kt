package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.PluginStates
import com.github.marcoral.versatia.testsupport.testContext
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
        val descriptor = DescriptorLoader.load("com.github.marcoral.versatia.engine.descriptor.sample.SamplePlugin", context)
        assertSame(com.github.marcoral.versatia.engine.descriptor.sample.VersatiaDescriptor, descriptor)
        assertEquals(VersatiaPluginDescriptor.FORMAT_VERSION, descriptor.formatVersion)
    }

    @Test
    fun `reports a missing descriptor`() {
        val error = assertFailsWith<EnhancementException> {
            DescriptorLoader.load("com.github.marcoral.versatia.engine.descriptor.nothing.Plugin", context)
        }
        assertContains(error.message!!, "com.github.marcoral.versatia.engine.descriptor.nothing.VersatiaDescriptor")
    }

    @Test
    fun `reports another format before touching elements it cannot load`() {
        val error = assertFailsWith<EnhancementException> {
            DescriptorLoader.load("com.github.marcoral.versatia.engine.descriptor.newer.FuturePlugin", context)
        }
        assertContains(error.message!!, "Sample was built for descriptor format 7.0")
        assertContains(error.message!!, "Update VersatiaCore")
    }

    @Test
    fun `rejects an outdated format with the plugin name and both versions`() {
        val error = assertFailsWith<EnhancementException> {
            DescriptorLoader.load("com.github.marcoral.versatia.engine.descriptor.outdated.OldPlugin", context)
        }
        assertContains(error.message!!, "Sample was built for descriptor format 0.9")
        assertContains(error.message!!, VersatiaPluginDescriptor.FORMAT_VERSION.toString())
        assertContains(error.message!!, "Rebuild the plugin")
    }
}
