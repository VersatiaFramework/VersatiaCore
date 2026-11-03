package dev.versatia.messages

import dev.versatia.text.Argument
import dev.versatia.text.Text
import dev.versatia.text.TextImpl
import dev.versatia.text.UnknownArgumentException
import dev.versatia.text.arg
import dev.versatia.text.present
import dev.versatia.text.presentation
import dev.versatia.text.require
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.io.TempDir

/** A `Text`, counting how often it parses. */
class CountingText(private val text: Text = TextImpl()) : Text by text {
    var parses = 0

    override fun parse(input: String, vararg arguments: Argument): Component = text.parse(input, *arguments).also { parses++ }
}

class Vault(val owner: String)

class MessageLoaderTest {
    @TempDir
    lateinit var dataFolder: File

    private val text = CountingText()

    private val warnings = ArrayList<String>()

    private val logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level == Level.WARNING) warnings += record.message
            }

            override fun flush() = Unit

            override fun close() = Unit
        })
    }

    private fun file(name: String, content: String) = dataFolder.resolve("messages/$name").apply { parentFile.mkdirs(); writeText(content.trimIndent()) }

    private fun load() = MessageLoader("Shop", dataFolder, text, logger).load()

    private fun plain(message: Message, vararg arguments: Argument) = PlainTextComponentSerializer.plainText().serialize(message.get(*arguments))

    @Test
    fun `reads nested keys, lists and scalars from every file, in path order`() {
        file("shop.yml", """
            shop:
              bought: "<green>You bought {item}."
              closed:
                - "<red>The shop is closed."
                - "<gray>Come back tomorrow."
              limit: 5
        """)
        file("admin/notices.yaml", """
            notices:
              maintenance: "<yellow>Maintenance at {time}."
        """)

        val messages = load()

        assertEquals(listOf("notices.maintenance", "shop.bought", "shop.closed", "shop.limit"), messages.keys.toList())
        assertEquals("You bought Diamond.", plain(messages.getValue("shop.bought"), arg("item", "Diamond")))
        val missing = assertFailsWith<MessageRenderException> { messages.getValue("shop.bought").get() }
        assertEquals("Plugin Shop: message \"shop.bought\" in messages/shop.yml cannot be shown: No argument \"item\"; the arguments are: (none)", missing.message)
        assertIs<UnknownArgumentException>(missing.cause)
        assertEquals("The shop is closed.\nCome back tomorrow.", plain(messages.getValue("shop.closed")))
        assertEquals("5", messages.getValue("shop.limit").raw)
        assertEquals("messages/admin/notices.yaml", messages.getValue("notices.maintenance").file)
    }

    @Test
    fun `messages are parsed when used, so tags registered after loading apply`() {
        file("a.yml", """
            joined: "<upper:who> joined"
            server: "<green><servername>"
            plain: "<green>Hello"
        """)
        val messages = load()
        assertEquals(0, text.parses, "nothing is parsed at load")
        assertEquals("<servername>", plain(messages.getValue("server")))

        var calls = 0
        text.tag("servername") { _, _, _ -> Tag.selfClosingInserting(text("Lobby ${++calls}")) }
        text.tag("upper") { arguments, _, values -> Tag.selfClosingInserting(text(values.require<String>(arguments.pop().value()).uppercase())) }

        val first = plain(messages.getValue("server"))
        assertTrue(first.startsWith("Lobby "), first)
        assertNotEquals(first, plain(messages.getValue("server")), "a message with a registered tag is parsed at every use")
        assertEquals("STEVE joined", plain(messages.getValue("joined"), arg("who", "Steve")))
        assertEquals("Plugin Shop: message \"joined\" in messages/a.yml cannot be shown: No argument \"who\"; the arguments are: (none)", assertFailsWith<MessageRenderException> { messages.getValue("joined").get() }.message)
        assertSame(messages.getValue("plain").get(), messages.getValue("plain").get(), "a text without braces and registered tags is parsed once")
    }

    @Test
    fun `tags registered before loading apply as well`() {
        text.tag("servername") { _, _, _ -> Tag.selfClosingInserting(text("Lobby")) }
        file("a.yml", "server: \"<servername>\"")

        assertEquals("Lobby", plain(load().getValue("server")))
    }

    @Test
    fun `scalars and keys are text exactly as written`() {
        file("a.yml", """
            confirm: Yes
            deny: off
            version: 1.10
            code: 010
            time: 12:30
            empty:
            answers:
              yes: Agreed
              no: Refused
            lines:
              - true
              - 2
        """)

        val raw = load().mapValues { it.value.raw }

        assertEquals(mapOf("confirm" to "Yes", "deny" to "off", "version" to "1.10", "code" to "010", "time" to "12:30", "empty" to "", "answers.yes" to "Agreed", "answers.no" to "Refused", "lines" to "true\n2"), raw)
    }

    @Test
    fun `values that are not text are reported with the file and key`() {
        file("a.yml", """
            menu:
              - title: Shop
        """)
        assertEquals("Plugin Shop: message \"menu\" in messages/a.yml is a list whose lines must all be text", assertFailsWith<MessagesLoadException> { load() }.message)
        file("a.yml", """
            - one
            - two
        """)
        assertEquals("Plugin Shop: messages/a.yml must map keys to messages", assertFailsWith<MessagesLoadException> { load() }.message)
    }

    @Test
    fun `a key defined twice in one file is reported`() {
        file("a.yml", """
            shop:
              bought: first
              bought: second
        """)
        assertContains(assertFailsWith<MessagesLoadException> { load() }.message!!, "duplicate key bought")
        file("a.yml", """
            shop.bought: first
            shop:
              bought: second
        """)
        assertEquals("Plugin Shop: message \"shop.bought\" is defined twice in messages/a.yml", assertFailsWith<MessagesLoadException> { load() }.message)
    }

    @Test
    fun `files are read in the order of their relative paths with slashes`() {
        file("aZ.yml", "second: b")
        file("a/x.yml", "first: a")

        assertEquals(listOf("first", "second"), load().keys.toList())
    }

    @Test
    fun `presentations registered on the plugin's Text apply to its messages`() {
        text.present<Vault> { vault -> presentation { component { text("the ${vault.owner}") } } }
        file("a.yml", "owner: \"Held by {vault}\"")

        assertEquals("Held by the bank", plain(load().getValue("owner"), arg("vault", Vault("bank"))))
    }

    @Test
    fun `a key defined in two files and invalid YAML are reported with the file`() {
        file("a.yml", "greeting: hi")
        file("b.yml", "greeting: hello")
        assertEquals("Plugin Shop: message \"greeting\" is defined in both messages/a.yml and messages/b.yml", assertFailsWith<MessagesLoadException> { load() }.message)

        dataFolder.resolve("messages/b.yml").delete()
        file("d.yml", "broken: [unclosed")
        assertEquals("Plugin Shop: messages/d.yml is not valid YAML at line 1, column 18: expected ',' or ']', but got <stream end>", assertFailsWith<MessagesLoadException> { load() }.message)
    }

    @Test
    fun `no messages directory means no messages`() = assertEquals(emptyMap<String, Message>(), load())

    @Test
    fun `a key that is a message in one place and a group of messages in another is reported`() {
        val messages = dataFolder.resolve("messages")
        fun failure() = assertFailsWith<MessagesLoadException> { load() }.message

        file("general.yml", "shop: \"<gold>Shop\"")
        file("shop.yml", """
            shop:
              open: "Open"
              closed: "Closed"
        """)
        assertEquals("Plugin Shop: \"shop\" is a message in messages/general.yml and a group of messages in messages/shop.yml; rename one of them", failure())

        messages.deleteRecursively()
        file("a.yml", "shop.open: Open")
        file("b.yml", "shop: Shop")
        assertEquals("Plugin Shop: \"shop\" is a message in messages/b.yml and a group of messages in messages/a.yml; rename one of them", failure())

        messages.deleteRecursively()
        file("a.yml", "shop: { open: Open }")
        file("b.yml", "shop.open.now: Now")
        assertEquals("Plugin Shop: \"shop.open\" is a message in messages/a.yml and a group of messages in messages/b.yml; rename one of them", failure())

        messages.deleteRecursively()
        file("a.yml", """
            shop.open: Open
            shop: Shop
        """)
        assertEquals("Plugin Shop: \"shop\" is both a message and a group of messages in messages/a.yml; rename one of them", failure())
    }

    @Test
    fun `the lines of a list are parsed apart, so formatting does not carry over`() {
        file("a.yml", """
            sword-lore:
              - "<red>Damage: {damage}"
              - "Sharp"
            single: "<gray>Only {x}"
        """)
        val messages = load()
        val lore = messages.getValue("sword-lore")
        fun colors(component: Component) = component.iterable(ComponentIteratorType.DEPTH_FIRST).mapNotNull { it.color() }

        val lines = lore.lines(arg("damage", 7))
        assertEquals(listOf("Damage: 7", "Sharp"), lines.map(PlainTextComponentSerializer.plainText()::serialize))
        assertEquals(listOf(NamedTextColor.RED), colors(lines[0]).distinct())
        assertEquals(emptyList(), colors(lines[1]))
        assertSame(lines[1], lore.lines(arg("damage", 8))[1], "a constant line is parsed once")

        val joined = lore.get(arg("damage", 7))
        assertEquals("Damage: 7\nSharp", PlainTextComponentSerializer.plainText().serialize(joined))
        assertEquals(lines, joined.children().filter { it != Component.newline() })
        assertEquals("<red>Damage: {damage}\nSharp", lore.raw)

        val single = messages.getValue("single")
        assertEquals(listOf(single.get(arg("x", 1))), single.lines(arg("x", 1)))
        assertEquals("Plugin Shop: message \"single\" in messages/a.yml cannot be shown: No argument \"x\"; the arguments are: (none)", assertFailsWith<MessageRenderException> { single.lines() }.message)
    }

    @Test
    fun `a message with legacy colour codes is reported once, naming the file, the key and the tags`() {
        file("a.yml", """
            sale: ["&a&lSale!", "&aToday §Ronly"]
            plain: "R&D, B&B, x&a and &z are text"
            tags: "<green>Fine"
        """)
        load()
        assertEquals(
            listOf("Message \"sale\" in messages/a.yml uses legacy colour codes, which MiniMessage shows as text: &a -> <green>, &l -> <bold>, §R -> <reset>; write the tags instead, or turn this warning off with \"legacy-color-warnings: false\" in messages/.versatia.yml"),
            warnings,
        )
    }

    @Test
    fun `the settings file turns the warnings off and holds no messages`() {
        file("a.yml", "sale: \"&aSale\"")
        file(".versatia.yml", "legacy-color-warnings: false")
        assertEquals(setOf("sale"), load().keys)
        assertEquals(emptyList(), warnings)

        file(".versatia.yml", "legacy-colour-warnings: false")
        assertEquals("Plugin Shop: messages/.versatia.yml has an unknown setting \"legacy-colour-warnings\"; the settings are: legacy-color-warnings", assertFailsWith<MessagesLoadException> { load() }.message)
        file(".versatia.yml", "legacy-color-warnings: no")
        assertEquals("Plugin Shop: setting \"legacy-color-warnings\" in messages/.versatia.yml must be true or false, not \"no\"", assertFailsWith<MessagesLoadException> { load() }.message)
    }
}
