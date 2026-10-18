package com.github.marcoral.versatia.task

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/** Tells whether a coroutine of a Versatia plugin is running on the current thread right now; part of every PluginScope context. */
internal object CoroutineMarker : ThreadContextElement<Int>, CoroutineContext.Key<CoroutineMarker> {
    private val depth = ThreadLocal.withInitial { 0 }

    val onThisThread get() = depth.get() > 0

    override val key get() = this

    override fun updateThreadContext(context: CoroutineContext): Int = depth.get().also { depth.set(it + 1) }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int) = depth.set(oldState)
}
