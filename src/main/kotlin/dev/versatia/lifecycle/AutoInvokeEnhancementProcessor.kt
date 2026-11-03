package dev.versatia.lifecycle

import dev.versatia.EnhancementContext
import dev.versatia.EnhancementException
import dev.versatia.EnhancementProcessor
import dev.versatia.classLoader
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.FunctionTarget
import dev.versatia.failure
import dev.versatia.inject
import dev.versatia.receiverOf
import dev.versatia.runSteps
import dev.versatia.targetAs

/**
 * Calls `@AutoInvoke` functions once every other processor has run for the event, in descriptor
 * order, injecting their parameters through the plugin's `BeanResolver`. At stop every function
 * runs even when another fails. See docs/runtime.md.
 */
class AutoInvokeEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        val targets = elements.map { it.targetAs<FunctionTarget>(this) }
        context.onPluginEnhanced { context.runSteps(targets.map { target -> { invoke(target, context) } }) }
    }

    private fun invoke(target: FunctionTarget, context: EnhancementContext) {
        val instance = context.receiverOf(target)
        val arguments = context.inject(target.description, target.parameters)
        try {
            target.invoker.invoke(context.classLoader, instance, arguments)
        } catch (e: EnhancementException) {
            throw e
        } catch (e: Exception) {
            throw failed(target, context, e)
        } catch (e: LinkageError) {
            throw failed(target, context, e)
        }
    }

    private fun failed(target: FunctionTarget, context: EnhancementContext, cause: Throwable) =
        context.failure("${target.description} failed at ${context.event}", cause)
}
