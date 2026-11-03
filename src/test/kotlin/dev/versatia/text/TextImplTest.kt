package dev.versatia.text

import java.lang.reflect.Proxy
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.Translatable
import org.bukkit.entity.Entity

/** A value describing itself. */
class Money(val amount: Long) : Presentable {
    override fun presentation() = presentation {
        component { text("$amount coins") }
        property("amount") { amount }
        property("owner") { Account("bank") }
    }
}

class Account(val holder: String)

open class Animal(val name: String)

class Dog(name: String) : Animal(name)

/** Stands in for the Paper values with a translation key, such as Material or GameMode. */
class Rank(private val key: String) : Translatable {
    override fun translationKey() = key
}

class TextImplTest {
    private val text = TextImpl()

    private fun plain(input: String, vararg arguments: Argument) = PlainTextComponentSerializer.plainText().serialize(text.parse(input, *arguments))

    @Test
    fun `braces insert arguments and angle brackets stay MiniMessage`() {
        val component = text.parse("<green>Hello {who}!", arg("who", "Steve"))

        assertEquals("Hello Steve!", PlainTextComponentSerializer.plainText().serialize(component))
        assertEquals(NamedTextColor.GREEN, component.color())
        assertEquals("Steve and Steve", plain("<gold>{who}</gold> and {who}", arg("who", "Steve")))
    }

    @Test
    fun `strings are inserted verbatim, numbers and booleans as text, null as nothing`() {
        assertEquals("<red>x</red> is 3.5, true, ", plain("{a} is {b}, {c}, {d}", arg("a", "<red>x</red>"), arg("b", 3.5), arg("c", true), arg("d", null)))
    }

    @Test
    fun `braces that name nothing known are plain text or an error`() {
        assertEquals("{} { x } {1,2} 1", plain("{} { x } {1,2} {a}", arg("a", 1)))
        val missing = assertFailsWith<UnknownArgumentException> { text.parse("{nmae}", arg("name", "x")) }
        assertEquals("No argument \"nmae\"; the arguments are: name", missing.message)
        assertEquals("No argument \"x\"; the arguments are: (none)", assertFailsWith<UnknownArgumentException> { text.parse("{x}") }.message)
    }

    @Test
    fun `a Presentable value shows itself and its properties, nested through registered types`() {
        text.present<Account> { account -> presentation { property("holder") { account.holder } } }

        assertEquals("5 coins (5) held by bank", plain("{m} ({m.amount}) held by {m.owner.holder}", arg("m", Money(5))))
    }

    @Test
    fun `the registered type nearest to the value wins and registrations are per instance`() {
        text.present<Animal> { presentation { component { text("some animal") } } }
        text.present<Dog> { dog -> presentation { component { text("dog ${dog.name}") } } }

        assertEquals("dog Rex", plain("{pet}", arg("pet", Dog("Rex"))))
        assertEquals("some animal", plain("{pet}", arg("pet", Animal("Cat"))))
        val other = TextImpl()
        assertTrue(PlainTextComponentSerializer.plainText().serialize(other.parse("{pet}", arg("pet", Dog("Rex")))).startsWith("${Dog::class.qualifiedName}@"), "another instance knows nothing of the registration")
    }

    @Test
    fun `registering a type again replaces its presentation`() {
        text.present<Account> { presentation { component { text("first") } } }
        text.present<Account> { presentation { component { text("second") } } }

        assertEquals("second", plain("{a}", arg("a", Account("bank"))))
    }

    @Test
    fun `unknown properties and values without a text form are reported with what is available`() {
        text.present<Account> { account -> presentation { property("holder") { account.holder } } }

        val unknown = assertFailsWith<UnknownPropertyException> { text.parse("{m.colour}", arg("m", Money(1))) }
        assertEquals("{m} has no property \"colour\"; it has: amount, owner", unknown.message)
        val noText = assertFailsWith<NoTextFormException> { text.parse("{m.owner}", arg("m", Money(1))) }
        assertEquals("{m.owner} cannot be inserted as text; use one of its properties: holder", noText.message)
        val onPrimitive = assertFailsWith<UnknownPropertyException> { text.parse("{n.x}", arg("n", 1)) }
        assertEquals("{n} has no property \"x\"; it has none", onPrimitive.message)
    }

    @Test
    fun `properties are evaluated once per call`() {
        var evaluations = 0
        text.present<Account> { account -> presentation { property("holder") { evaluations++; account.holder } } }

        assertEquals("bank and bank", plain("{a.holder} and {a.holder}", arg("a", Account("bank"))))
        assertEquals(1, evaluations)
    }

    @Test
    fun `argument names may be anything MiniMessage uses, tag names may not`() {
        assertEquals("x y", plain("{color} {b}", arg("color", "x"), arg("b", "y")))
        assertFailsWith<InvalidArgumentNameException> { arg("Player Name", "x") }
        assertEquals("Argument \"a\" is given twice", assertFailsWith<DuplicateArgumentException> { text.parse("{a}", arg("a", 1), arg("a", 2)) }.message)
        assertFailsWith<ReservedTagNameException> { text.tag("bold") { _, _, _ -> null } }
        assertFailsWith<ReservedTagNameException> { text.tag("My Tag") { _, _, _ -> null } }
        assertFailsWith<ReservedTagNameException> { text.tag("versatia-argument-0") { _, _, _ -> null } }
    }

    @Test
    fun `a null on the way and a failing presentation name the brace`() {
        text.present<Account> { account -> presentation { property("holder") { account.holder.takeIf { it.isNotEmpty() } }; property("broken") { error("no connection") } } }

        assertEquals("{a.holder} is null, so it has no property \"length\"", assertFailsWith<NullValueException> { text.parse("{a.holder.length}", arg("a", Account(""))) }.message)
        val broken = assertFailsWith<PresentationException> { text.parse("{a.broken}", arg("a", Account("bank"))) }
        assertEquals("{a.broken} cannot be shown: java.lang.IllegalStateException: no connection", broken.message)
        assertTrue(broken.cause is IllegalStateException)
    }

    @Test
    fun `argument names are found in a text`() = assertEquals(setOf("who", "item"), text.argumentNames("<green>{who} bought {item.name}, {item}"))

    @Test
    fun `an argument tag sees the arguments of the call`() {
        text.tag("upper") { arguments, _, values -> Tag.selfClosingInserting(text(values.require<String>(arguments.pop().value()).uppercase())) }

        assertEquals("STEVE joined", plain("<upper:who> joined", arg("who", "Steve")))
        val missing = assertFailsWith<UnknownArgumentException> { text.parse("<upper:nobody>", arg("who", "Steve")) }
        assertEquals("No argument \"nobody\"; the arguments are: who", missing.message)
        assertTrue(assertFailsWith<ArgumentTypeException> { text.parse("<upper:n>", arg("n", 1)) }.message!!.startsWith("Argument \"n\" is a java.lang.Integer, expected kotlin.String"))
    }

    @Test
    fun `a brace inside a tag argument inserts the escaped plain text, quoted or not`() {
        val hostile = "x'><click:run_command:'/op x'>"
        for (input in listOf("<click:suggest_command:'/msg {name} '>Reply", "<click:suggest_command:\"/msg {name} \">Reply", "<click:suggest_command:/msg {name} >Reply")) {
            for (name in listOf("Steve", hostile, "a\\'b\"c\\")) {
                val component = text.parse(input, arg("name", name))
                assertEquals("Reply", PlainTextComponentSerializer.plainText().serialize(component), input)
                assertEquals(listOf(ClickEvent.suggestCommand("/msg $name ")), component.clickEvents(), "$input with $name")
            }
        }
        assertEquals("x'><click:run_command:'/op x'>", text.parse("<insert:{name}>X", arg("name", hostile)).deep().firstNotNullOf { it.insertion() })
        assertEquals("Steve and Alex", text.parse("<insert:'{a} and {b}'>X", arg("a", "Steve"), arg("b", "Alex")).deep().firstNotNullOf { it.insertion() })
        assertEquals(NamedTextColor.RED, text.parse("<color:{c}>X", arg("c", "red")).deep().firstNotNullOf { it.color() })
        assertEquals(NamedTextColor.RED, text.parse("<color:'{c}'>X", arg("c", "red")).deep().firstNotNullOf { it.color() })
    }

    @Test
    fun `a brace inside a property chain of a tag argument walks the presentation`() {
        text.present<Account> { account -> presentation { property("holder") { account.holder } } }

        assertEquals(listOf(ClickEvent.runCommand("/pay bank 5")), text.parse("<click:run_command:'/pay {a.holder} {n}'>X", arg("a", Account("bank")), arg("n", 5)).clickEvents())
    }

    @Test
    fun `hover text keeps the component of a brace and never parses a value`() {
        val hover = text.parse("<hover:show_text:'Sold by {who}'>X", arg("who", text("Steve", NamedTextColor.RED))).deep().firstNotNullOf { it.hoverEvent() }.value() as Component
        assertEquals("Sold by Steve", PlainTextComponentSerializer.plainText().serialize(hover))
        assertTrue(hover.deep().any { it.color() == NamedTextColor.RED })

        val unquoted = text.parse("<hover:show_text:{who}>X", arg("who", "<red>x'>")).deep().firstNotNullOf { it.hoverEvent() }.value() as Component
        assertEquals("<red>x'>", PlainTextComponentSerializer.plainText().serialize(unquoted))
    }

    @Test
    fun `a backslash before a brace writes it as text, in the text and in tag arguments`() {
        val nick = arg("nick", "Steve")
        assertEquals("{nick} is Steve", plain("\\{nick} is {nick}", nick))
        assertEquals("\\Steve, \\{nick}, { x }, <b>", plain("\\\\{nick}, \\\\\\{nick}, \\{ x }, \\<b>", nick))
        assertEquals("{nick}", plain("\\{nick}"))
        assertEquals(setOf("b"), text.argumentNames("\\{a} \\\\{b} \\\\\\{c}"))
        assertEquals(listOf(ClickEvent.suggestCommand("/msg {nick} Steve")), text.parse("<click:suggest_command:'/msg \\{nick} {nick}'>X", nick).clickEvents())
        assertEquals(listOf(ClickEvent.suggestCommand("/msg {nick}")), text.parse("<click:suggest_command:/msg \\{nick}>X").clickEvents())
        assertEquals(listOf(ClickEvent.suggestCommand("\\Steve")), text.parse("<click:suggest_command:'\\\\{nick}'>X", nick).clickEvents())
        assertEquals("{nick}", text.parse("<insert:\"\\{nick}\">X").deep().firstNotNullOf { it.insertion() })
        assertEquals("{nick}", text.parse("<insert:\\{nick}>X").deep().firstNotNullOf { it.insertion() })
        assertEquals("\\\\Steve", text.parse("<insert:\\\\{nick}>X", nick).deep().firstNotNullOf { it.insertion() })
        val hover = text.parse("<hover:show_text:'\\{nick} {nick}'>X", nick).deep().firstNotNullOf { it.hoverEvent() }.value() as Component
        assertEquals("{nick} Steve", PlainTextComponentSerializer.plainText().serialize(hover))
    }

    @Test
    fun `braces in escaped or unknown tags stay text`() {
        assertEquals("<click:x:Steve>", plain("\\<click:x:{name}>", arg("name", "Steve")))
        assertEquals("<nope:Steve>", plain("<nope:{name}>", arg("name", "Steve")))
    }

    @Test
    fun `presentations registered for strings and numbers apply to the bare brace`() {
        text.present<Double> { value -> presentation { component { text("%.1f".format(Locale.ROOT, value)) } } }

        assertEquals("x = 1.5, 3", plain("x = {x}, {n}", arg("x", 1.4999), arg("n", 3)))
    }

    @Test
    fun `a text without braces and registered tags is parsed once, until a tag is registered`() {
        val first = text.parse("<green>Hello")
        assertSame(first, text.parse("<green>Hello"))

        text.tag("server") { _, _, _ -> Tag.selfClosingInserting(text("Lobby")) }
        assertNotSame(first, text.parse("<green>Hello"))
        assertNotSame(text.parse("<server>"), text.parse("<server>"))
        assertEquals("Lobby", plain("<server>"))
    }

    private fun Component.deep(): Sequence<Component> = sequenceOf(this) + children().asSequence().flatMap { it.deep() }

    private fun Component.clickEvents() = deep().mapNotNull { it.clickEvent() }.toList()

    @Test
    fun `a Translatable value inserts a translatable component, unless a nearer presentation exists`() {
        fun keys(component: Component) = component.iterable(ComponentIteratorType.DEPTH_FIRST).filterIsInstance<TranslatableComponent>().map { it.key() }

        assertEquals(listOf("rank.gold"), keys(text.parse("<gold>Rank: {rank}", arg("rank", Rank("rank.gold")))))
        val zombie = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Entity::class.java, Translatable::class.java)) { _, method, _ ->
            when (method.name) {
                "name" -> text("Zombie")
                "translationKey" -> "entity.minecraft.zombie"
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        assertEquals("Zombie", plain("{mob}", arg("mob", zombie)), "the Entity presentation wins over Translatable")
        text.present<Rank> { presentation { component { text("custom") } } }
        assertEquals("custom", plain("{rank}", arg("rank", Rank("rank.gold"))))
    }

    @Test
    fun `a collection inserts its elements in their own presentations, joined with commas`() {
        assertEquals("Steve, Alex", plain("{names}", arg("names", listOf("Steve", "Alex"))))
        assertEquals("1, 2.5, 3 (3)", plain("{n} ({n.size})", arg("n", listOf(1, 2.5, 3L))))
        assertEquals("5 coins, 7 coins (2)", plain("{m} ({m.size})", arg("m", arrayOf(Money(5), Money(7)))))
        assertEquals("[]", plain("[{none}]", arg("none", emptyList<String>())))
        assertEquals("/give Steve, Alex", text.parse("<insert:'/give {names}'>x", arg("names", listOf("Steve", "Alex"))).deep().firstNotNullOf { it.insertion() })

        text.present<Account> { account -> presentation { component { text(account.holder) } } }
        assertEquals("bank, mint", plain("{a}", arg("a", setOf(Account("bank"), Account("mint")))))
        val silent = Presentable { presentation { property("x") { 1 } } }
        assertContains(assertFailsWith<NoTextFormException> { plain("{p}", arg("p", listOf(silent))) }.message!!, "{p[0]}")

        text.present<List<*>> { presentation { component { text("a list") } } }
        assertEquals("a list", plain("{names}", arg("names", listOf("x"))))
    }
}
