package dev.versatia.text

import dev.versatia.EnhancementContext
import dev.versatia.EnhancementProcessor
import dev.versatia.StateKey
import dev.versatia.classLoader
import dev.versatia.di.BeanResolver
import dev.versatia.di.isPrimary
import dev.versatia.engine.descriptor.ClassTarget
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.Parameter
import dev.versatia.engine.descriptor.loadDescribedType
import dev.versatia.failure
import dev.versatia.targetAs

/**
 * Offers the plugin's `@ServerPresentation` beans to the presentations of the whole server once its
 * beans exist, before its start functions run, and withdraws them when the plugin stops: the offer
 * is an entry of the plugin's state, closed when that state goes, whether the plugin stops or its
 * start fails. The presented type is the argument of the bean's `Presenter` supertype, which the
 * descriptor records; the server's presentations are reached through the plugin's own `Text`,
 * which VersatiaCore makes for every plugin. See docs/runtime.md.
 */
class ServerPresentationEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) = context.onPluginEnhanced { offer(elements, context) }

    private fun offer(elements: List<EnhancedElement>, context: EnhancementContext) {
        val beans = context.state[BeanResolver.KEY]
            ?: throw context.failure("${elements.first().target.className} is a server presentation, but the plugin has no bean container; extend VersatiaPlugin")
        val text = beans.resolve("@ServerPresentation", listOf(Parameter(Text::class.java.name))).single()
        val server = (text as? TextImpl)?.server
            ?: throw context.failure("the Text bean is ${text.javaClass.name}, not VersatiaCore's; server presentations need VersatiaCore's")
        val offers = elements.map { offerOf(it, beans, context) }
        try {
            server.offer(offers)
        } catch (e: IllegalArgumentException) {
            throw context.failure(e.message ?: "its server presentations conflict with others", e)
        }
        context.state.getOrPut(OFFERED) { Offered(server, context.plugin.name) }
    }

    private fun offerOf(element: EnhancedElement, beans: BeanResolver, context: EnhancementContext): ServerPresentations.Offer {
        val target = element.targetAs<ClassTarget>(this)
        @Suppress("UNCHECKED_CAST")
        val presenter = beans.instanceOf(target.className) as? Presenter<Any> ?: throw context.failure("${target.className} is a server presentation but no Presenter")
        val style = (element.annotation.arguments["style"] as? String)?.takeIf { it.isNotEmpty() }
        try {
            style?.let { checkStyleName(it, presenter::class) }
        } catch (e: InvalidStyleNameException) {
            throw context.failure(e.message!!, e)
        }
        @Suppress("UNCHECKED_CAST")
        val type = presentedType(target, context) as Class<Any>
        return ServerPresentations.Offer(context.plugin.name, target.className, target.annotations.isPrimary(), TypePresentation(type, style, presenter::present))
    }

    private fun presentedType(target: ClassTarget, context: EnhancementContext): Class<*> {
        val type = target.genericSupertypes.firstOrNull { it.name == PRESENTER }?.arguments?.singleOrNull()
            ?: throw context.failure("the descriptor records no type ${target.className} presents; rebuild the plugin against the VersatiaAPI matching this VersatiaCore")
        return try {
            loadDescribedType(type.name, context.classLoader)
        } catch (e: ClassNotFoundException) {
            throw context.failure("${type.name}, which ${target.className} presents, cannot be loaded", e)
        } catch (e: LinkageError) {
            throw context.failure("${type.name}, which ${target.className} presents, cannot be loaded", e)
        }
    }

    /** What a plugin offered, withdrawn when its state goes. */
    private class Offered(private val server: ServerPresentations, private val owner: String) : AutoCloseable {
        override fun close() = server.withdraw(owner)
    }

    private companion object {
        val OFFERED = StateKey<Offered>("serverPresentations")
        val PRESENTER = Presenter::class.java.name
    }
}
