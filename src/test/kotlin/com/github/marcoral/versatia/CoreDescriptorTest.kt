package com.github.marcoral.versatia

import com.github.marcoral.versatia.engine.command.VersatiaCommand
import com.github.marcoral.versatia.engine.descriptor.DescriptorLoader
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.occurrenceOf
import com.github.marcoral.versatia.testsupport.testContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** VersatiaCore enhances itself: its generated descriptor loads, names processors that exist, and keeps the command conditional. */
class CoreDescriptorTest {
    private val context = testContext("VersatiaCore", PluginStates())
    private val descriptor = DescriptorLoader.load(VersatiaCore::class.java.name, context)

    @Test
    fun `names only processors that load`() {
        val loader = ProcessorLoader()
        descriptor.elements.map { it.processorClass }.distinct().forEach { loader.load(it, context) }
    }

    @Test
    fun `hooks in the command only while CommandAPI is enabled`() {
        val command = descriptor.elements.map { it.target }.filterIsInstance<FunctionTarget>().filter { it.className == VersatiaCommand::class.qualifiedName }
        assertEquals(setOf("register", "unregister"), command.map { it.functionName }.toSet())
        for (function in command) {
            val condition = function.classAnnotations.occurrenceOf(ConditionalOnPlugin::class.java.name)
            assertEquals(listOf("CommandAPI"), condition?.arguments?.get("value"), function.functionName)
        }
    }
}
