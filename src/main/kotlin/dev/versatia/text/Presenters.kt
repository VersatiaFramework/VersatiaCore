package dev.versatia.text

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Presentations by type; the type nearest to a value's class wins, then [fallback] is asked. A
 * registration swaps in a new list, so [of] may run on any thread while another registers.
 */
internal class Presenters(private val fallback: Presenters? = null) {
    @Volatile
    private var entries = emptyList<Entry<*>>()

    /** The style names found fit so far, so a value shown often is checked once. */
    private val fit = ConcurrentHashMap.newKeySet<String>()

    private class Entry<T : Any>(val type: Class<T>, val style: String?, val presenter: (T) -> Presentation) {
        fun present(value: Any) = Presented(presenter(type.cast(value)), style)
    }

    /**
     * Registering a type again replaces its presenter; two entries of one type would rule each other
     * out in [of]. The component of a value presented this way is shown in the server's style named
     * [style], when there is one; a name no style can have is an [InvalidStyleNameException].
     */
    @Synchronized
    fun <T : Any> register(type: KClass<T>, style: String? = null, presenter: (T) -> Presentation) {
        entries = entries.filter { it.type != type.java } + Entry(type.java, checked(style, type), presenter)
    }

    /** The presentation of [value]: its own when [Presentable], otherwise the nearest registered type's, or null. */
    fun of(value: Any): Presented? {
        if (value is Presentable) return Presented(value.presentation(), checked(value.style, value::class))
        val matching = entries.filter { it.type.isInstance(value) }
        val nearest = matching.firstOrNull { candidate -> matching.none { it !== candidate && candidate.type.isAssignableFrom(it.type) } }
        return nearest?.present(value) ?: fallback?.of(value)
    }

    private fun checked(style: String?, type: KClass<*>): String? {
        if (style != null && style !in fit) {
            StyleFile.objection(style)?.let { throw InvalidStyleNameException(style, type, it) }
            fit += style
        }
        return style
    }
}

/** How a value appears, and the style its component is shown in when the server defines one of that name. */
internal class Presented(val presentation: Presentation, val style: String?)
