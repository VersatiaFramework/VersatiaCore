package dev.versatia.text

import dev.versatia.testsupport.serverStyles
import dev.versatia.testsupport.shownColours
import dev.versatia.testsupport.stylesFile
import dev.versatia.testsupport.textOf
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.junit.jupiter.api.io.TempDir

/** The styles of the server in a plugin's texts: what a style tag and a styled value come to, and what is an error. */
class TextStylesTest {
    @TempDir
    lateinit var server: File

    /** The file the design starts from: an accent that is another colour inside a success and inside an error. */
    private val scoped = """
        accent: "<gold>"
        success:
          style: "<green>"
          accent: "<#b4ff9a>"
        error:
          style: "<red>"
          accent: "<#ff9a9a>"
    """

    private val plain = textOf()

    private fun styled(styles: String) = textOf(serverStyles(server, styles))

    private fun player(name: String) = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "displayName" -> text(name)
            "getName" -> name
            else -> throw UnsupportedOperationException(method.name)
        }
    } as Player

    private fun Component.deep(): Sequence<Component> = sequenceOf(this) + children().asSequence().flatMap { it.deep() }

    @Test
    fun `a style formats what it encloses exactly as its MiniMessage would, by the scope it stands in`() {
        val text = styled(scoped)
        val item = arg("item", "Diamond")

        assertEquals(
            plain.parse("<green>You bought <#b4ff9a>{item}</#b4ff9a>!</green> Thanks, <gold>{item}</gold>.", item),
            text.parse("<success>You bought <accent>{item}</accent>!</success> Thanks, <accent>{item}</accent>.", item),
        )
        assertEquals(plain.parse("<red>No <#ff9a9a>{item}</#ff9a9a> left.", item), text.parse("<error>No <accent>{item}</accent> left.", item))
    }

    @Test
    fun `a flat file works the same, every name applying everywhere`() {
        val text = styled("""
            highlight: "<gold>"
            success: "<green>"
        """)

        assertEquals(plain.parse("<green>Done: <gold>x</gold>!</green> <gold>y"), text.parse("<success>Done: <highlight>x</highlight>!</success> <highlight>y"))
    }

    @Test
    fun `the nearest style around a name wins, and a style closed or reset no longer counts`() {
        val text = styled(scoped)

        assertEquals(plain.parse("<red><green><#b4ff9a>x"), text.parse("<error><success><accent>x"))
        assertEquals(plain.parse("<green><red><#ff9a9a>x"), text.parse("<success><error><accent>x"))
        assertEquals(plain.parse("<green><bold><#b4ff9a>x"), text.parse("<success><bold><accent>x"), "tags that are no styles do not hide the scope")
        assertEquals(plain.parse("<green>a</green><gold>b"), text.parse("<success>a</success><accent>b"))
        assertEquals(plain.parse("<bold><green>a</bold><gold>b"), text.parse("<bold><success>a</bold><accent>b"), "closing an outer tag closes the style in it")
        assertEquals(plain.parse("<green>a<reset><gold>b"), text.parse("<success>a<reset><accent>b"))
        assertEquals(plain.parse("<GREEN>a</green>b"), text.parse("<SUCCESS>a</Success>b"), "names are read as MiniMessage reads them, in any case")
    }

    @Test
    fun `a style may hold any MiniMessage, text and gradients included`() {
        val text = styled("""
            title: "<gold><bold>"
            link: "<hover:show_text:'Open the shop'><click:run_command:'/shop'><underlined>"
            fancy: "<gradient:red:blue>"
            warn: "<red>! "
            quiet: ""
        """)

        assertEquals(plain.parse("<gold><bold>Shop</bold></gold> open"), text.parse("<title>Shop</title> open"))
        assertEquals(plain.parse("<hover:show_text:'Open the shop'><click:run_command:'/shop'><underlined>here"), text.parse("<link>here"))
        assertEquals(shownColours(plain.parse("<gradient:red:blue>rainbow</gradient> end")), shownColours(text.parse("<fancy>rainbow</fancy> end")))
        assertEquals(listOf("! Low health" to NamedTextColor.RED, "?" to null), shownColours(text.parse("<warn>Low health</warn>?")))
        assertEquals(listOf("! " to NamedTextColor.RED, "x" to null), shownColours(text.parse("<warn/>x")))
        assertEquals(plain.parse("as it is"), text.parse("<quiet>as it is</quiet>"))
        assertEquals(
            listOf("a" to NamedTextColor.GOLD, "b" to NamedTextColor.GREEN, "c" to NamedTextColor.GOLD),
            shownColours(text.parse("<gradient:red:blue><title>a<green>b</green>c</title></gradient>")),
            "a style inside a gradient keeps its own colour",
        )
    }

    @Test
    fun `a style of several tags ends where its tag closes`() {
        val text = styled("""
            accent: "<gold>"
            success:
              style: "<green>"
              accent: "<#b4ff9a><bold>"
        """)
        val quest = arg("quest", "Dragon Hunt")

        assertEquals(
            plain.parse("<green>Quest <#b4ff9a><bold>{quest}</bold></#b4ff9a> done.</green> See <gold>/quest</gold>.", quest),
            text.parse("<success>Quest <accent>{quest}</accent> done.</success> See <accent>/quest</accent>.", quest),
        )
        assertEquals(
            plain.parse("<green>Quest <#b4ff9a><bold>{quest} done.</bold></#b4ff9a></green> See <gold>/quest</gold>.", quest),
            text.parse("<success>Quest <accent>{quest} done.</success> See <accent>/quest</accent>.", quest),
            "a style left open ends with the tag around it",
        )
    }

    @Test
    fun `a style that holds text is a tag a message inserts, in its own colours or those of the tone around it`() {
        val text = styled("""
            neutral: "<gray>"
            negative:
              style: "<red>"
              realmTag: "<dark_red>[Realm]</dark_red> "
            realmTag: "<gold><bold>[Realm]</bold></gold> "
            shopTag: "[Shop] "
            unset: ""
        """)
        val welcome = text.parse("<neutral><realmTag/>Welcome to the server!")

        assertEquals(listOf("[Realm]" to NamedTextColor.GOLD, " Welcome to the server!" to NamedTextColor.GRAY), shownColours(welcome))
        assertEquals(
            listOf("[Realm]"),
            welcome.deep().filter { it.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE }.map { PlainTextComponentSerializer.plainText().serialize(it) }.toList(),
            "the tag's formatting ends with the tag",
        )
        assertEquals(
            listOf("[Shop] Sold out." to NamedTextColor.GRAY),
            shownColours(text.parse("<neutral><shopTag/>Sold out.")),
            "a tag without a colour takes the tone's",
        )
        assertEquals(
            listOf("[Realm]" to NamedTextColor.DARK_RED, " No permission." to NamedTextColor.RED),
            shownColours(text.parse("<negative><realmtag/>No permission.")),
            "a tone may give the tag a look of its own, and a name is found in either case",
        )
        assertEquals(listOf("Welcome!" to NamedTextColor.GRAY), shownColours(text.parse("<neutral><unset/>Welcome!")), "an empty tag shows nothing, not even its space")
        assertEquals("[Realm] Welcome, Steve!", PlainTextComponentSerializer.plainText().serialize(text.parse("<neutral><realmTag/>Welcome, {p}!", arg("p", player("Steve")))))
    }

    @Test
    fun `hover text is a text of its own, whose styles start at the top level`() {
        val hover = styled(scoped).parse("<success><hover:show_text:'<accent>tip</accent>'>x").deep().firstNotNullOf { it.hoverEvent() }.value() as Component

        assertEquals(listOf("tip" to NamedTextColor.GOLD), shownColours(hover))
    }

    @Test
    fun `the text of a click takes the clickable style of its place, and a style inside the click wins over what it defines`() {
        val text = styled("""
            clickable: "<aqua><italic>"
            neutral: "<gray>"
            negative: "<red>"
            warning:
              style: "<yellow>"
              clickable: "<gold>"
        """)
        val accept = "<click:run_command:'/a'>[Accept]</click>"

        assertEquals(listOf("Offer: gray", "[Accept] aqua italic", " now. gray"), looks(text.parse("<neutral>Offer: $accept now.")))
        assertEquals(listOf("[Accept] gold"), looks(text.parse("<warning>$accept")), "a tone's own, replacing the whole look")
        assertEquals(listOf("[Decline] red italic"), looks(text.parse("<click:run_command:'/d'><negative>[Decline]</negative></click>")), "a style inside the click")
        assertEquals(listOf("Steve aqua italic"), looks(text.parse("<click:run_command:'/a'>{who}</click>", arg("who", text("Steve", NamedTextColor.YELLOW)))), "a value it holds alone")
        assertEquals(
            listOf(ClickEvent.runCommand("/trade accept Steve")),
            text.parse("<neutral><click:run_command:'/trade accept {name}'>[Accept]</click>", arg("name", "Steve")).deep().mapNotNull { it.clickEvent() }.toList(),
        )
        assertEquals(plain.parse(accept), styled("neutral: \"<gray>\"").parse(accept), "a server without the style")
    }

    @Test
    fun `a built-in presentation takes the style named after its type, in the scope the brace stands in`() {
        val text = styled("""
            player: "<yellow>"
            success:
              style: "<green>"
              player: "<aqua>"
        """)
        val steve = arg("p", player("Steve"))

        assertEquals(listOf("Steve" to NamedTextColor.YELLOW, " joined" to null), shownColours(text.parse("{p} joined", steve)))
        assertEquals(listOf("Steve" to NamedTextColor.AQUA, " joined" to NamedTextColor.GREEN), shownColours(text.parse("<success>{p} joined", steve)))
        assertEquals(listOf("Steve" to NamedTextColor.AQUA, " and " to NamedTextColor.GREEN, "Steve" to NamedTextColor.YELLOW), shownColours(text.parse("<success>{p} and </success>{p}", steve)))
        assertEquals(listOf("Steve joined" to null), shownColours(text.parse("{p.name} joined", steve)), "a property is a value of its own")
        assertEquals(
            listOf("Steve" to NamedTextColor.YELLOW, ", " to null, "Alex" to NamedTextColor.YELLOW, " online" to null),
            shownColours(text.parse("{all} online", arg("all", listOf(player("Steve"), player("Alex"))))),
        )
        assertEquals(ClickEvent.suggestCommand("/msg Steve "), text.parse("<click:suggest_command:'/msg {p} '>x", steve).deep().firstNotNullOf { it.clickEvent() })
        val hover = text.parse("<hover:show_text:'By {p}'>x", steve).deep().firstNotNullOf { it.hoverEvent() }.value() as Component
        assertEquals(listOf("By " to null, "Steve" to NamedTextColor.YELLOW), shownColours(hover))

        text.present<Player> { presentation { component { text("someone") } } }
        assertEquals(
            listOf("someone" to NamedTextColor.YELLOW, " joined" to null),
            shownColours(text.parse("{p} joined", steve)),
            "a plugin's own component keeps the style of its type",
        )
    }

    @Test
    fun `a presentation a plugin registers may name the style of its values, as the built-in ones do`() {
        val text = styled("""
            accent: "<gold>"
            quest: "<light_purple>"
            success:
              style: "<green>"
              quest: "<aqua>"
        """)
        fun describe(text: Text, style: String?) = text.present<Quest>(style) { quest ->
            presentation {
                component { text(quest.name) }
                property("reward") { quest.reward }
            }
        }
        val quest = arg("quest", Quest("Dragon Hunt", 120))
        describe(text, "Quest")

        assertEquals(
            listOf("Dragon Hunt" to NamedTextColor.LIGHT_PURPLE, " pays " to null, "120" to NamedTextColor.GOLD),
            shownColours(text.parse("{quest} pays {quest.reward}", quest)),
            "the value in its style, in either case of the name; a property is a value of its own",
        )
        assertEquals(
            listOf("Dragon Hunt" to NamedTextColor.AQUA, " done" to NamedTextColor.GREEN),
            shownColours(text.parse("<success>{quest} done", quest)),
            "looked up where the brace stands",
        )
        assertEquals(text.parse("<quest>Dragon Hunt</quest>"), text.parse("<quest>{quest}</quest>", quest), "not styled twice")

        text.present<Player>(style = "quest") { presentation { component { text("someone") } } }
        assertEquals(listOf("someone" to NamedTextColor.LIGHT_PURPLE, " joined" to null), shownColours(text.parse("{p} joined", arg("p", player("Steve")))))

        val unstyled = styled("accent: \"<gold>\"").also { describe(it, "quest") }
        assertEquals(listOf("Dragon Hunt" to NamedTextColor.GOLD), shownColours(unstyled.parse("{quest}", quest)), "the accent where the server defines no style of that name")
        describe(unstyled, null)
        assertEquals(listOf("Dragon Hunt" to NamedTextColor.GOLD), shownColours(unstyled.parse("{quest}", quest)))

        assertEquals(
            "\"bold\" cannot name the style of dev.versatia.text.TextStylesTest.Quest: it is a MiniMessage tag",
            assertFailsWith<InvalidStyleNameException> { describe(text, "bold") }.message,
        )
        assertEquals(
            "\"my quest\" cannot name the style of dev.versatia.text.TextStylesTest.Quest: letters, digits, _ and - only",
            assertFailsWith<InvalidStyleNameException> { describe(text, "my quest") }.message,
        )
    }

    @Test
    fun `a value that describes itself may name its style too`() {
        val text = styled("""
            accent: "<gold>"
            rank: "<light_purple>"
            success:
              style: "<green>"
              rank: "<aqua>"
        """)

        assertEquals(
            listOf("Knight" to NamedTextColor.LIGHT_PURPLE, " of " to null, "3" to NamedTextColor.GOLD),
            shownColours(text.parse("{rank} of {rank.level}", arg("rank", Rank("Knight", "rank")))),
        )
        assertEquals(
            listOf("Knight" to NamedTextColor.AQUA, " earned" to NamedTextColor.GREEN),
            shownColours(text.parse("<success>{rank} earned", arg("rank", Rank("Knight", "rank")))),
            "looked up where the brace stands",
        )
        assertEquals(listOf("Knight" to NamedTextColor.GOLD), shownColours(text.parse("{rank}", arg("rank", Rank("Knight", null)))), "the accent without a style")
        assertEquals(listOf("Knight" to NamedTextColor.GOLD), shownColours(text.parse("{rank}", arg("rank", Rank("Knight", "title")))), "and where the server defines none of that name")
        assertEquals(
            "\"bold\" cannot name the style of dev.versatia.text.TextStylesTest.Rank: it is a MiniMessage tag",
            assertFailsWith<InvalidStyleNameException> { text.parse("{rank}", arg("rank", Rank("Knight", "bold"))) }.message,
        )
    }

    private class Quest(val name: String, val reward: Int)

    private class Rank(private val name: String, override val style: String?) : Presentable {
        override fun presentation() = presentation {
            component { text(name) }
            property("level") { 3 }
        }
    }

    @Test
    fun `a value keeps the colours it brings and takes the style where it has none`() {
        val text = styled("player: \"<yellow><bold>\"")
        val ranked = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            if (method.name == "displayName") text("[VIP] ", NamedTextColor.RED).append(text("Steve")) else throw UnsupportedOperationException(method.name)
        } as Player

        val shown = text.parse("{p}!", arg("p", ranked))

        assertEquals(listOf("[VIP] Steve" to NamedTextColor.RED, "!" to null), shownColours(shown), "the name's own colour wins over the style's")
        assertEquals("[VIP] Steve!", PlainTextComponentSerializer.plainText().serialize(shown))
    }

    @Test
    fun `a value takes the accent of the place it stands in, after the style of its type`() {
        val text = styled(scoped)
        val quest = arg("quest", "Dragon Hunt")
        val reward = arg("reward", 120)
        val steve = arg("p", player("Steve"))

        assertEquals(
            plain.parse("<green>Quest <#b4ff9a>{quest}</#b4ff9a> done: <#b4ff9a>{reward}</#b4ff9a> coins.</green> <gold>{quest}</gold>", quest, reward),
            text.parse("<success>Quest {quest} done: {reward} coins.</success> {quest}", quest, reward),
        )
        assertEquals(
            listOf("Steve" to NamedTextColor.GOLD, ", " to null, "Alex" to NamedTextColor.GOLD, " and " to null, "Steve" to NamedTextColor.GOLD),
            shownColours(text.parse("{names} and {p.name}", arg("names", listOf("Steve", "Alex")), steve)),
            "each element, and a property, is a value of its own",
        )
        assertEquals(listOf("Steve" to NamedTextColor.GOLD, " joined" to null), shownColours(text.parse("{p} joined", steve)), "a type without a style of its own on this server")
        assertEquals(ClickEvent.suggestCommand("/quest Dragon Hunt"), text.parse("<click:suggest_command:'/quest {quest}'>x", quest).deep().firstNotNullOf { it.clickEvent() })

        val typed = styled("""
            accent: "<gold>"
            player: "<aqua>"
        """)
        assertEquals(
            listOf("Steve" to NamedTextColor.AQUA, " has " to null, "3" to NamedTextColor.GOLD),
            shownColours(typed.parse("{p} has {n}", steve, arg("n", 3))),
            "the style of the type comes first",
        )
    }

    @Test
    fun `a value styled by hand takes no accent, and none twice`() {
        val text = styled("""
            accent: "<gold>! "
            quest: "<light_purple>"
            success: "<green>"
        """)
        val quest = arg("quest", "Dragon Hunt")

        assertEquals(listOf("! Dragon Hunt" to NamedTextColor.GOLD), shownColours(text.parse("{quest}", quest)))
        assertEquals(listOf("Dragon Hunt" to NamedTextColor.LIGHT_PURPLE), shownColours(text.parse("<quest>{quest}</quest>", quest)), "alone in the tag of a style")
        assertEquals(listOf("Dragon Hunt" to NamedTextColor.LIGHT_PURPLE), shownColours(text.parse("<quest><bold>{quest}</bold></quest>", quest)))
        assertEquals(listOf("Dragon Hunt" to NamedTextColor.GREEN), shownColours(text.parse("<success>{quest}", quest)))
        assertEquals(
            listOf("The " to NamedTextColor.LIGHT_PURPLE, "! Dragon Hunt" to NamedTextColor.GOLD),
            shownColours(text.parse("<quest>The {quest}", quest)),
            "among other text it stands out",
        )
        assertEquals(listOf("! the Dragon Hunt" to NamedTextColor.GOLD), shownColours(text.parse("<accent>the {quest}</accent>", quest)), "already in its style")
        assertEquals(listOf("! built" to NamedTextColor.GOLD, " by code" to null), shownColours(text.parse("{made} by code", arg("made", text("built")))), "a component is a value too")
        assertEquals(
            listOf("! " to NamedTextColor.GOLD, "built" to NamedTextColor.RED, " by code" to null),
            shownColours(text.parse("{made} by code", arg("made", text("built", NamedTextColor.RED)))),
            "its own colour wins",
        )
        assertEquals(
            listOf("built" to NamedTextColor.GREEN, " by code" to null),
            shownColours(text.parse("<success>{made}</success> by code", arg("made", text("built")))),
            "the message decides when it takes another style",
        )
        assertEquals(
            listOf("! Steve" to NamedTextColor.GOLD, " and " to null, "! Steve" to NamedTextColor.GOLD),
            shownColours(text.parse("{p} and {p.displayName}", arg("p", player("Steve")))),
            "a component read from a property is a value like any other",
        )
    }

    @Test
    fun `a tag holding a value alone sets its look over the value's own, a tag beside other text does not`() {
        val text = styled("""
            accent: "<white>"
            hint: "<dark_gray><italic>"
            neutral: "<gray>"
            positive: "<green>"
        """)
        val vip = arg("who", text().append(text("[VIP] ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)).append(text("Steve")).build())
        val yellow = arg("who", text("Steve", NamedTextColor.YELLOW))

        assertEquals(listOf("[VIP] gold", "Steve white", " joined green"), looks(text.parse("<positive><!bold>{who}</!bold> joined", vip)), "off in every part, colours kept")
        assertEquals(listOf("[VIP] gold bold", "Steve white", " wins green"), looks(text.parse("<positive><!bold>{who} wins</!bold>", vip)), "beside other text")
        assertEquals(listOf("By gray", "Steve dark_gray italic"), looks(text.parse("<neutral>By <hint>{who}</hint>", yellow)), "a style too")
        assertEquals(listOf("By gray", "Steve red bold"), looks(text.parse("<neutral>By <bold><red>{who}</red></bold>", yellow)), "every tag around it")
        assertEquals(listOf("[VIP] white bold", "Steve white", " joined green"), looks(text.parse("<positive><accent>{who}</accent> joined", vip)), "the accent written out")
        assertEquals(listOf("[VIP] gold bold", "Steve white", " joined green"), looks(text.parse("<positive>{who} joined", vip)), "the accent a value takes by itself fills only what is open")
        assertEquals(listOf("Steve gray"), looks(text.parse("<neutral>{who}", yellow)), "a message that is nothing but the value")
        assertEquals(listOf("Steve yellow"), looks(text.parse("<gradient:red:blue>{who}</gradient>", yellow)), "a gradient colours only what is open")

        val hover = text.parse("<neutral><hover:show_text:'<hint>{who}</hint>'>Hover", yellow).deep().firstNotNullOf { it.hoverEvent() }.value() as Component
        assertEquals(listOf("Steve dark_gray italic"), looks(hover), "in a hover text too")
        assertTrue(text.parse("<positive>{who} joined", vip).deep().none { it.insertion() != null }, "no mark is left")
    }

    /** Each piece of text with the colour and the decorations a player sees it in. */
    private fun looks(component: Component): List<String> {
        val pieces = ArrayList<String>()
        fun walk(current: Component, style: Style) {
            val shown = style.merge(current.style())
            if (current is TextComponent && current.content().isNotEmpty()) {
                val decorations = listOf(TextDecoration.BOLD, TextDecoration.ITALIC, TextDecoration.UNDERLINED).filter { shown.decoration(it) == TextDecoration.State.TRUE }.map { it.name.lowercase() }
                pieces += (listOf(current.content().trimEnd(), shown.color()?.let { NamedTextColor.namedColor(it.value()).toString() } ?: "none") + decorations).joinToString(" ")
            }
            current.children().forEach { walk(it, shown) }
        }
        walk(component, Style.empty())
        return pieces
    }

    @Test
    fun `no style a value takes is a MiniMessage tag, and on a server with neither its style nor an accent a value is inserted as before`() {
        assertEquals(emptyList(), (BuiltInPresentations.STYLES + BuiltInPresentations.ACCENT).filter { TagNames.STANDARD.has(it) })
        val steve = arg("p", player("Steve"))

        assertEquals(plain.parse("{p} joined", steve), styled("success: \"<green>\"").parse("{p} joined", steve))
    }

    @Test
    fun `a tag that is neither MiniMessage's nor a style is an error naming the styles`() {
        val text = styled(scoped)

        assertEquals(
            "<sucess> is neither a MiniMessage tag nor a style; the styles in plugins/VersatiaCore/styles.yml are: accent, error, success. Write \\<sucess> to show it as text",
            assertFailsWith<UnknownTagException> { text.parse("<sucess>Done") }.message,
        )
        assertEquals(
            "<sucess>, <acent> are neither MiniMessage tags nor styles; the styles in plugins/VersatiaCore/styles.yml are: accent, error, success. Write \\<sucess> to show one as text",
            assertFailsWith<UnknownTagException> { text.parse("<sucess>Done <acent>{x}</acent>", arg("x", 1)) }.message,
        )
        assertFailsWith<UnknownTagException> { text.parse("<hover:show_text:'<acent>tip'>x") }
        assertEquals("<success> stays text", PlainTextComponentSerializer.plainText().serialize(text.parse("\\<success> stays text")))
    }

    @Test
    fun `a style used outside the styles that define it is an error naming them`() {
        val text = styled("""
            success:
              style: "<green>"
              accent: "<gold>"
            error:
              style: "<red>"
              accent: "<yellow>"
        """)

        assertEquals(
            "<accent> is a style only inside <error>, <success>; define it at the top level of plugins/VersatiaCore/styles.yml to use it anywhere",
            assertFailsWith<UnknownTagException> { text.parse("Buy <accent>now") }.message,
        )
        assertEquals(plain.parse("<red>Buy <yellow>now"), text.parse("<error>Buy <accent>now"))
        assertEquals("<accent> is a style, which takes no arguments", assertFailsWith<TextException> { text.parse("<success><accent:gold>now") }.message)
    }

    @Test
    fun `a tag the plugin registers wins over a style of its name`() {
        val text = styled(scoped)
        text.tag("accent") { _, _, _ -> Tag.selfClosingInserting(text("TAG")) }

        assertEquals("TAG and green", PlainTextComponentSerializer.plainText().serialize(text.parse("<accent> and <success>green")))
    }

    @Test
    fun `a constant text is parsed once per version of the styles`() {
        val styles = serverStyles(server, "accent: \"<gold>\"")
        val text = textOf(styles)
        val gold = text.parse("<accent>x")
        assertSame(gold, text.parse("<accent>x"))

        stylesFile(server).writeText("accent: \"<red>\"")
        styles.reload()

        val red = text.parse("<accent>x")
        assertNotSame(gold, red)
        assertEquals(NamedTextColor.RED, red.color())
        assertSame(red, text.parse("<accent>x"))
    }

    @Test
    fun `a text is checked for its tags without its arguments`() {
        val text = styled(scoped)
        var calls = 0
        text.tag("online") { _, _, _ -> Tag.selfClosingInserting(text(++calls)) }

        text.check("<success>{any.property} bought <accent>{item}</accent> <online> <click:run_command:'/x {y}'>go")

        assertEquals(0, calls)
        assertTrue(assertFailsWith<UnknownTagException> { text.check("<success>{item} <acent>x") }.message!!.startsWith("<acent> is neither"))
        assertTrue(assertFailsWith<UnknownTagException> { text.check("<nope:{item}>") }.message!!.startsWith("<nope> is neither"))
        assertFailsWith<UnknownTagException> { textOf(serverStyles(server, "success: { accent: \"<gold>\" }")).check("<accent>{item}") }
    }
}
