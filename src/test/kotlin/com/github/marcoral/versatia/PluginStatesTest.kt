package com.github.marcoral.versatia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginStatesTest {
    @Test
    fun `closes every store, plugins last started first and the runtime last, even when one fails`() {
        val closed = mutableListOf<String>()
        val states = PluginStates()
        fun closing(store: ClosableStateStore, name: String, fails: Boolean = false) = store.getOrPut(StateKey(name)) {
            AutoCloseable {
                closed += name
                if (fails) error("$name failed")
            }
        }
        closing(states.of("Alpha"), "Alpha", fails = true)
        closing(states.of("Beta"), "Beta", fails = true)
        closing(states.runtime, "runtime")

        val error = assertFailsWith<IllegalStateException> { states.close() }

        assertEquals(listOf("Beta", "Alpha", "runtime"), closed)
        assertEquals("Beta failed", error.message)
        assertEquals(listOf("Alpha failed"), error.suppressed.map { it.message })
    }
}
