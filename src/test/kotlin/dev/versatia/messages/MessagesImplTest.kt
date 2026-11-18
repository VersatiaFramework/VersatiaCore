package dev.versatia.messages

import dev.versatia.testsupport.serverStyles
import dev.versatia.testsupport.textOf
import java.io.File
import java.lang.reflect.Proxy
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.io.TempDir

class MessagesImplTest {
    @TempDir
    lateinit var dataFolder: File

    @TempDir
    lateinit var server: File

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

    private fun file(content: String) = dataFolder.resolve("messages/a.yml").apply { parentFile.mkdirs(); writeText(content.trimIndent()) }

    @Test
    fun `a failing reload keeps the previous messages`() {
        val messages = MessagesImpl(plugin, textOf())
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
        assertEquals("Plugin Shop has no message \"nope\"; it has no messages at all; is there a messages/ directory in its data folder?", assertFailsWith<MissingMessageException> { MessagesImpl(plugin, textOf())["nope"] }.message)

    @Test
    fun `a tag that does not exist fails the start, naming the file, the key and the styles`() {
        val styles = serverStyles(server, """
            accent: "<gold>"
            success: "<green>"
        """)
        val messages = MessagesImpl(plugin, textOf(styles))
        file("""
            shop:
              open: "<success>The shop is open."
              bought:
                - "<success>You bought <accent>{item}</accent>."
                - "<sucess>Come again."
        """)
        messages.reload()

        val error = assertFailsWith<MessagesLoadException> { messages.started() }

        assertEquals(
            "Plugin Shop: message \"shop.bought\" in messages/a.yml cannot be shown: <sucess> is neither a MiniMessage tag nor a style; " +
                "the styles in plugins/VersatiaCore/styles.yml are: accent, success. Write \\<sucess> to show it as text",
            error.message,
        )
    }

    @Test
    fun `a tag registered by a start function counts, and once started a reload checks at once`() {
        val text = textOf()
        val messages = MessagesImpl(plugin, text)
        file("server: \"<servername> is up\"")
        messages.reload()
        text.tag("servername") { _, _, _ -> Tag.selfClosingInserting(text("Lobby")) }
        messages.started()
        assertEquals("Lobby is up", PlainTextComponentSerializer.plainText().serialize(messages["server"].get()))

        file("server: \"<servernme> is up\"")
        val error = assertFailsWith<MessagesLoadException> { messages.reload() }
        assertEquals(
            "Plugin Shop: message \"server\" in messages/a.yml cannot be shown: <servernme> is neither a MiniMessage tag nor a style; " +
                "plugins/VersatiaCore/styles.yml defines no styles. Write \\<servernme> to show it as text",
            error.message,
        )
        assertEquals("<servername> is up", messages["server"].raw, "the previous messages stay")
    }

    @Test
    fun `checking a message reads no argument and calls no tag`() {
        val text = textOf()
        var calls = 0
        text.tag("online") { _, _, _ -> Tag.selfClosingInserting(text(++calls)) }
        val messages = MessagesImpl(plugin, text)
        file("status: \"<online> players, last {player.name} <click:run_command:'/tp {player.name}'>go</click>\"")
        messages.reload()

        messages.started()

        assertEquals(0, calls)
    }
}
