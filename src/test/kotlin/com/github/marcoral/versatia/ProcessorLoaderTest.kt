package com.github.marcoral.versatia

import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.testsupport.testContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class PlainEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) = Unit
}

object ObjectEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) = Unit
}

class NeedsArgumentsEnhancementProcessor(val name: String) : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) = Unit
}

class NotAProcessor

class LookupFailingEnhancementProcessor : EnhancementProcessor {
    init {
        javaClass.getMethod("missingMethod")
    }

    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) = Unit
}

class ProcessorLoaderTest {
    private val loader = ProcessorLoader()
    private val context = testContext("Alpha", PluginStates())

    @Test
    fun `creates one instance per class with the no-argument constructor`() {
        val first = loader.load(PlainEnhancementProcessor::class.java.name, context)
        val second = loader.load(PlainEnhancementProcessor::class.java.name, testContext("Beta", PluginStates()))

        assertIs<PlainEnhancementProcessor>(first)
        assertSame(first, second, "the instance is shared by every plugin")
    }

    @Test
    fun `uses the instance of a Kotlin object`() =
        assertSame(ObjectEnhancementProcessor, loader.load(ObjectEnhancementProcessor::class.java.name, context))

    @Test
    fun `names the plugin and the class when the processor is missing`() {
        val error = assertFailsWith<EnhancementException> { loader.load("com.example.Missing", context) }
        assertContains(error.message!!, "Plugin Alpha needs the enhancement processor com.example.Missing")
        assertContains(error.message!!, "listed under depend:")
    }

    @Test
    fun `rejects classes that are not processors or cannot be created`() {
        val notProcessor = assertFailsWith<EnhancementException> { loader.load(NotAProcessor::class.java.name, context) }
        assertContains(notProcessor.message!!, "${NotAProcessor::class.java.name} does not implement ${EnhancementProcessor::class.java.name}")

        val noConstructor = assertFailsWith<EnhancementException> { loader.load(NeedsArgumentsEnhancementProcessor::class.java.name, context) }
        assertContains(noConstructor.message!!, "has no no-argument constructor")
    }

    @Test
    fun `reports what a throwing constructor threw instead of a missing constructor`() {
        val error = assertFailsWith<EnhancementException> { loader.load(LookupFailingEnhancementProcessor::class.java.name, context) }
        assertEquals("Enhancement processor ${LookupFailingEnhancementProcessor::class.java.name} failed to initialise", error.message)
        assertIs<NoSuchMethodException>(error.cause)
    }
}
