package com.github.marcoral.versatia.lifecycle

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.failure
import com.github.marcoral.versatia.inject
import com.github.marcoral.versatia.receiverOf
import com.github.marcoral.versatia.runSteps
import com.github.marcoral.versatia.targetAs

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
