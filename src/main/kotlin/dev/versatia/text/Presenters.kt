package dev.versatia.text

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import net.kyori.adventure.text.Component

/**
 * Presentations by type, of one plugin or the built-in ones. [of] gathers every presentation of a
 * value in the order its parts are looked for: the value's own when it is [Presentable], then
 * those of the [server] and these together, the type nearest to the value's class first and the
 * server's first for the same type, then those of [fallback]. A registration swaps in a new list,
 * so [of] may run on any thread while another registers.
 */
internal class Presenters(private val server: ServerPresentations? = null, private val fallback: Presenters? = null) {
    @Volatile
    private var entries = emptyList<TypePresentation<*>>()

    /** The style names found fit so far, so a value shown often is checked once. */
    private val fit = ConcurrentHashMap.newKeySet<String>()

    /**
     * Registering a type again replaces its presenter; two entries of one type would rule each other
     * out in [of]. The component of a value presented this way is shown in the server's style named
     * [style], when there is one; a name no style can have is an [InvalidStyleNameException].
     */
    @Synchronized
    fun <T : Any> register(type: KClass<T>, style: String? = null, presenter: (T) -> Presentation) {
        entries = entries.filter { it.type != type.java } + TypePresentation(type.java, checked(style, type), presenter)
    }

    /** Every presentation of [value], in the order its parts are looked for, or null when none applies. */
    fun of(value: Any): Presented? {
        val layers = ArrayList<Presented.Layer>()
        if (value is Presentable) layers += Presented.Layer(checked(value.style, value::class), value::presentation)
        addLayers(value, layers)
        return if (layers.isEmpty()) null else Presented(layers)
    }

    private fun addLayers(value: Any, layers: MutableList<Presented.Layer>) {
        nearestFirst(server?.matching(value).orEmpty() + entries.filter { it.type.isInstance(value) }).mapTo(layers) { it.layer(value) }
        fallback?.addLayers(value, layers)
    }

    private fun checked(style: String?, type: KClass<*>): String? {
        if (style != null && style !in fit) {
            checkStyleName(style, type)
            fit += style
        }
        return style
    }

    private companion object {
        /** [candidates] with every type before its supertypes, otherwise in the order given. */
        fun nearestFirst(candidates: List<TypePresentation<*>>): List<TypePresentation<*>> {
            if (candidates.size < 2) return candidates
            val remaining = candidates.toMutableList()
            return List(candidates.size) {
                remaining.first { candidate -> remaining.none { it.type != candidate.type && candidate.type.isAssignableFrom(it.type) } }.also(remaining::remove)
            }
        }
    }
}

/** Throws an [InvalidStyleNameException] naming [owner] when [style] can never name a style. */
internal fun checkStyleName(style: String, owner: KClass<*>) {
    StyleFile.objection(style)?.let { throw InvalidStyleNameException(style, owner, it) }
}

/** A presentation registered for the values of [type], shown in the server's style named [style] when there is one. */
internal class TypePresentation<T : Any>(val type: Class<T>, val style: String?, private val presenter: (T) -> Presentation) {
    fun layer(value: Any) = Presented.Layer(style) { presenter(type.cast(value)) }
}

/**
 * How a value appears: the presentations that apply to it, in the order of [Presenters.of], each
 * made only when a part is looked for in it. The component is the first one a presentation
 * defines, the style the first one named and each property the first one defined, so a
 * presentation defining a single part leaves the rest to the others. Serves one call, on one
 * thread.
 */
internal class Presented(private val layers: List<Layer>) {
    class Layer(val style: String?, make: () -> Presentation) {
        val presentation by lazy(LazyThreadSafetyMode.NONE, make)
    }

    /**
     * The first component a presentation defines, in the first style one names: a presentation
     * defining its own component keeps the style of its type unless it names another, and one
     * naming a style alone restyles a component defined after it.
     */
    fun look(): Look? {
        val component = layers.firstNotNullOfOrNull { it.presentation.component } ?: return null
        return Look(component, layers.firstNotNullOfOrNull { it.style })
    }

    fun property(name: String) = layers.firstNotNullOfOrNull { it.presentation.properties[name] }

    val properties: Set<String> get() = layers.flatMapTo(LinkedHashSet()) { it.presentation.properties.keys }

    /** A component, and the style it is shown in where the server defines one of that name. */
    class Look(val component: () -> Component, val style: String?)
}
