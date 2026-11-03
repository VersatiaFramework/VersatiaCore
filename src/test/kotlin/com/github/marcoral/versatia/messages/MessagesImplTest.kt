package com.github.marcoral.versatia.messages

import com.github.marcoral.versatia.text.TextImpl
import java.io.File
import java.lang.reflect.Proxy
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.io.TempDir

class MessagesImplTest {
    @TempDir
    lateinit var dataFolder: File

    /** Answers the three calls `MessagesImpl` makes on its plugin. */
    private val plugin by lazy {
        Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { _, method, _ ->
            when (method.name) {
                "getName" -> "Shop"
                "getDataFolder" -> dataFolder
                "getLogger" -> Logger.getLogger("Shop")
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Plugin
    }

    private fun file(content: String) = dataFolder.resolve("messages/a.yml").apply { parentFile.mkdirs(); writeText(content) }

    @Test
    fun `a failing reload keeps the previous messages`() {
        val messages = MessagesImpl(plugin, TextImpl())
        file("greeting: hi")
        messages.reload()
        assertEquals("hi", messages["greeting"].raw)

        file("greeting: [unclosed")
        assertFailsWith<MessagesLoadException> { messages.reload() }
        assertEquals("hi", messages["greeting"].raw)
        assertEquals(setOf("greeting"), messages.keys)
    }

    @Test
    fun `a missing key names the plugin`() =
        assertEquals("Plugin Shop has no message \"nope\"; it has no messages at all; is there a messages/ directory in its data folder?", assertFailsWith<MissingMessageException> { MessagesImpl(plugin, TextImpl())["nope"] }.message)
}
