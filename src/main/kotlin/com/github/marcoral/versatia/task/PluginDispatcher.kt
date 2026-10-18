package com.github.marcoral.versatia.task

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/** Runs a plugin's coroutines on the server thread: in place when already there, otherwise at the next heartbeat. See docs/runtime.md. */
internal class PluginDispatcher(private val lane: Lane, private val loop: TickLoop) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext) = !loop.isServerThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) = loop.submit(lane, context, block)

    override fun toString() = "Server thread of ${lane.plugin.name}"
}
