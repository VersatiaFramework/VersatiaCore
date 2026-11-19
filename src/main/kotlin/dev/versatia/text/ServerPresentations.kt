package dev.versatia.text

import dev.versatia.di.LocalBean

/**
 * The presentations of the whole server: what the `@ServerPresentation` beans of the running
 * plugins present, which the `Text` of every plugin consults with its own registrations. A type
 * has one presentation in use: the only one, or the `@Primary` one among several. An offer that
 * would leave a type with several and no single `@Primary` among them is refused, so the plugin
 * making it fails its start; when the `@Primary` one is withdrawn and several remain, the one
 * offered first is used. Offered and withdrawn on the main thread, read on any. See
 * docs/messages.md.
 */
@LocalBean
internal class ServerPresentations {
    /** A presentation [owner] offers, made by its bean [className]. */
    class Offer(val owner: String, val className: String, val primary: Boolean, val presentation: TypePresentation<*>) {
        override fun toString() = "$className of $owner"
    }

    @Volatile
    private var offers = emptyList<Offer>()

    @Volatile
    private var inUse = emptyList<TypePresentation<*>>()

    /** The presentations in use whose type [value] is an instance of, in the order they were offered. */
    fun matching(value: Any) = inUse.filter { it.type.isInstance(value) }

    /** Adds [added], all or none: throws an [IllegalArgumentException] naming the competitors when one would be ambiguous. */
    @Synchronized
    fun offer(added: List<Offer>) {
        val next = offers + added
        for (type in added.map { it.presentation.type }.distinct()) {
            val competing = next.filter { it.presentation.type == type }
            val primaries = competing.filter { it.primary }
            if (competing.size == 1 || primaries.size == 1) continue
            val prefix = "${type.name} would have several server presentations: ${competing.joinToString()}"
            throw IllegalArgumentException(
                if (primaries.isEmpty()) "$prefix; mark the one to use @Primary, or remove the others"
                else "$prefix, and several are @Primary: ${primaries.joinToString()}; keep @Primary on one",
            )
        }
        update(next)
    }

    /** Removes what [owner] offered. */
    @Synchronized
    fun withdraw(owner: String) = update(offers.filter { it.owner != owner })

    private fun update(next: List<Offer>) {
        offers = next
        inUse = next.groupBy { it.presentation.type }.values.map { competing -> (competing.singleOrNull { it.primary } ?: competing.first()).presentation }
    }
}
