package dev.versatia.text

import dev.versatia.testsupport.serverStyles
import dev.versatia.testsupport.shownColours
import dev.versatia.testsupport.textOf
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.junit.jupiter.api.io.TempDir

/** The presentations of the whole server in the texts of every plugin: precedence, what falls through, nearest types and competing offers. */
class ServerPresentationsTest {
    @TempDir
    lateinit var server: File

    private val presentations = ServerPresentations()

    private fun Text.plain(input: String, vararg arguments: Argument) = PlainTextComponentSerializer.plainText().serialize(parse(input, *arguments))

    private fun player(name: String, ping: Int = 20) = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Player::class.java)) { proxy, method, arguments ->
        when (method.name) {
            "getName" -> name
            "displayName" -> text(name)
            "getPing" -> ping
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.single()
            "toString" -> "Player $name"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as Player

    private fun <T : Any> offer(owner: String, type: Class<T>, primary: Boolean = false, style: String? = null, presenter: (T) -> Presentation) =
        ServerPresentations.Offer(owner, "com.example.${owner.lowercase()}.Card", primary, TypePresentation(type, style, presenter))

    private fun serve(vararg offers: ServerPresentations.Offer) = presentations.offer(offers.toList())

    @Test
    fun `a server presentation reaches the text of every plugin and wins over a plugin's own of the same type`() {
        serve(offer("Bank", Account::class.java) { presentation { component { text("server's ${it.holder}") } } })
        val shop = textOf(name = "Shop", server = presentations)
        val quests = textOf(name = "Quests", server = presentations)
        shop.present<Account> { account -> presentation { component { text("shop's") }; property("holder") { account.holder } } }

        assertEquals("server's bank", shop.plain("{a}", arg("a", Account("bank"))))
        assertEquals("server's bank", quests.plain("{a}", arg("a", Account("bank"))))
        assertEquals("bank", shop.plain("{a.holder}", arg("a", Account("bank"))), "what the server presentation leaves out comes from the plugin's own")
        assertEquals(
            "{a} has no property \"holder\"; it has none",
            assertFailsWith<UnknownPropertyException> { quests.parse("{a.holder}", arg("a", Account("bank"))) }.message,
        )
    }

    @Test
    fun `a server presentation replaces only what it defines, its style included`() {
        val styles = serverStyles(server, "player: \"<gold>\"\nhero: \"<aqua>\"\naccent: \"<white>\"")
        val text = textOf(styles, server = presentations)
        fun colours() = shownColours(text.parse("{p}", arg("p", player("Steve"))))
        serve(offer("Rpg", Player::class.java) { presentation { property("rank") { "Captain" } } })

        assertEquals("Steve (Steve, 42 ms) is Captain", text.plain("{p} ({p.name}, {p.ping} ms) is {p.rank}", arg("p", player("Steve", 42))))
        assertEquals(listOf("Steve" to NamedTextColor.GOLD), colours(), "the built-in component in the built-in style")

        presentations.withdraw("Rpg")
        serve(offer("Rpg", Player::class.java, style = "hero") { presentation { property("rank") { "Captain" } } })
        assertEquals(listOf("Steve" to NamedTextColor.AQUA), colours(), "a style alone restyles the built-in component")

        presentations.withdraw("Rpg")
        serve(offer("Rpg", Player::class.java) { card -> presentation { component { text("[${card.name}]") } } })
        assertEquals(listOf("[Steve]" to NamedTextColor.GOLD), colours(), "a component alone keeps the style of its type")
        assertEquals("Steve, 20", text.plain("{p.name}, {p.ping}", arg("p", player("Steve"))))

        presentations.withdraw("Rpg")
        serve(offer("Rpg", Player::class.java, style = "accent") { card -> presentation { component { text("[${card.name}]") } } })
        assertEquals(listOf("[Steve]" to NamedTextColor.WHITE), colours(), "the accent is named like any other style")
    }

    @Test
    fun `the nearest type comes first across the server and the plugin, and the server's first for the same type`() {
        serve(offer("Rpg", OfflinePlayer::class.java) { presentation { component { text("card") }; property("title") { "Sir" } } })
        val lobby = textOf(name = "Lobby", server = presentations)
        val shop = textOf(name = "Shop", server = presentations)
        shop.present<Player> { player -> presentation { component { text("shop's ${player.name}") } } }
        shop.present<OfflinePlayer> { presentation { component { text("shop's offline") }; property("title") { "Mister" } } }

        assertEquals("card, Sir, 20", lobby.plain("{p}, {p.title}, {p.ping}", arg("p", player("Steve"))), "OfflinePlayer covers Player where nothing presents Player")
        assertEquals("shop's Steve, Sir", shop.plain("{p}, {p.title}", arg("p", player("Steve"))), "a nearer type of the plugin comes before the server's farther one")
    }

    @Test
    fun `competing offers need one Primary, and a withdrawn one gives way`() {
        serve(offer("Bank", Account::class.java) { presentation { component { text("bank's") } } })
        val text = textOf(server = presentations)

        val conflict = assertFailsWith<IllegalArgumentException> { serve(offer("Shop", Account::class.java) { presentation { component { text("shop's") } } }) }
        assertEquals(
            "dev.versatia.text.Account would have several server presentations: com.example.bank.Card of Bank, com.example.shop.Card of Shop; " +
                "mark the one to use @Primary, or remove the others",
            conflict.message,
        )
        assertEquals("bank's", text.plain("{a}", arg("a", Account("x"))), "a refused offer adds nothing")

        serve(offer("Shop", Account::class.java, primary = true) { presentation { component { text("shop's") } } })
        assertEquals("shop's", text.plain("{a}", arg("a", Account("x"))))
        val second = assertFailsWith<IllegalArgumentException> { serve(offer("Mint", Account::class.java, primary = true) { presentation { } }) }
        assertContains(second.message!!, "and several are @Primary: com.example.shop.Card of Shop, com.example.mint.Card of Mint; keep @Primary on one")

        serve(offer("Mint", Account::class.java) { presentation { component { text("mint's") } } })
        presentations.withdraw("Shop")
        assertEquals("bank's", text.plain("{a}", arg("a", Account("x"))), "without the Primary one, the one offered first")
        presentations.withdraw("Bank")
        presentations.withdraw("Mint")
        assertEquals("{a} has no property \"x\"; it has none", assertFailsWith<UnknownPropertyException> { text.parse("{a.x}", arg("a", Account("x"))) }.message)
    }

    @Test
    fun `a value describing itself comes first, and a collection keeps its size under a presentation without it`() {
        serve(offer("Bank", Money::class.java) { presentation { property("currency") { "gold" } } })
        val text = textOf(server = presentations)
        text.present<List<*>> { presentation { component { text("a list") } } }

        assertEquals("5 coins in gold", text.plain("{m} in {m.currency}", arg("m", Money(5))))
        assertEquals("a list of 2", text.plain("{l} of {l.size}", arg("l", listOf(1, 2))))
    }
}
