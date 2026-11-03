package com.github.marcoral.versatia.text

import kotlin.reflect.KClass

/**
 * Presentations by type; the type nearest to a value's class wins, then [fallback] is asked. A
 * registration swaps in a new list, so [of] may run on any thread while another registers.
 */
internal class Presenters(private val fallback: Presenters? = null) {
    @Volatile
    private var entries = emptyList<Entry<*>>()

    private class Entry<T : Any>(val type: Class<T>, val presenter: (T) -> Presentation) {
        fun present(value: Any) = presenter(type.cast(value))
    }

    /** Registering a type again replaces its presenter; two entries of one type would rule each other out in [of]. */
    @Synchronized
    fun <T : Any> register(type: KClass<T>, presenter: (T) -> Presentation) {
        entries = entries.filter { it.type != type.java } + Entry(type.java, presenter)
    }

    /** The presentation of [value]: its own when [Presentable], otherwise the nearest registered type's, or null. */
    fun of(value: Any): Presentation? {
        if (value is Presentable) return value.presentation()
        val matching = entries.filter { it.type.isInstance(value) }
        val nearest = matching.firstOrNull { candidate -> matching.none { it !== candidate && candidate.type.isAssignableFrom(it.type) } }
        return nearest?.present(value) ?: fallback?.of(value)
    }
}
