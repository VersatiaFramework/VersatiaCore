package com.github.marcoral.versatia

import com.github.marcoral.versatia.testsupport.captureLog
import com.github.marcoral.versatia.testsupport.fakePlugin
import java.util.logging.Level
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The injected `Logger` logs exactly as `plugin.logger`: same name, records, level and handlers. */
class PluginLoggerBeanTest {
    private val plugin = fakePlugin("LoggerAlpha")
    private val logger = PluginLoggerBean(plugin)
    private val log = plugin.captureLog()

    @AfterTest
    fun tearDown() {
        log.close()
        plugin.logger.level = null
    }

    @Test
    fun `records go to the plugin's logger under its name`() {
        logger.info("Shop open")
        logger.fine("hidden")
        assertEquals(listOf("Shop open"), log.messages(Level.INFO))
        assertEquals(plugin.logger.name, log.records.single().loggerName)
        assertEquals(plugin.logger.name, logger.name)
    }

    @Test
    fun `level and handlers are the plugin logger's`() {
        logger.level = Level.WARNING
        assertEquals(Level.WARNING, plugin.logger.level)
        logger.info("dropped")
        assertFalse(logger.isLoggable(Level.INFO))
        assertEquals(emptyList(), log.records)
        assertEquals(plugin.logger.handlers.toList(), logger.handlers.toList())
    }
}
