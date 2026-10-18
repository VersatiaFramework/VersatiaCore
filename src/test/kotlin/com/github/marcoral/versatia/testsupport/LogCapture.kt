package com.github.marcoral.versatia.testsupport

import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.bukkit.plugin.Plugin

/** Collects what is logged through [logger], from any thread, until closed. */
class LogCapture(private val logger: Logger) : Handler(), AutoCloseable {
    val records = CopyOnWriteArrayList<LogRecord>()

    init {
        logger.addHandler(this)
    }

    /** The messages logged at [level], in order. */
    fun messages(level: Level) = records.filter { it.level == level }.map { it.message }

    override fun publish(record: LogRecord) {
        records += record
    }

    override fun flush() = Unit

    override fun close() = logger.removeHandler(this)
}

/** Collects what this plugin logs; close it when done, since fake plugins of the same name share their logger. */
fun Plugin.captureLog() = LogCapture(logger)
