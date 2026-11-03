package dev.versatia.config

import dev.versatia.EnhancementException
import dev.versatia.Phase
import dev.versatia.LifecycleEvent
import dev.versatia.PluginEnhancer
import dev.versatia.PluginStates
import dev.versatia.ProcessorLoader
import dev.versatia.di.BeanProcessors
import dev.versatia.di.BeanResolver
import dev.versatia.di.PluginBean
import dev.versatia.di.Reloadable
import dev.versatia.engine.descriptor.AnnotationOccurrence
import dev.versatia.engine.descriptor.ClassTarget
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.FunctionTarget
import dev.versatia.engine.descriptor.Instantiator
import dev.versatia.engine.descriptor.Invoker
import dev.versatia.engine.descriptor.MemberOwner
import dev.versatia.engine.descriptor.PluginTarget
import dev.versatia.engine.descriptor.PropertyTarget
import dev.versatia.engine.descriptor.Setter
import dev.versatia.engine.descriptor.TypeReference
import dev.versatia.lifecycle.AutoInvoke
import dev.versatia.lifecycle.LifecycleProcessors
import dev.versatia.testsupport.Recorder
import dev.versatia.testsupport.descriptor
import dev.versatia.testsupport.fakePlugin
import dev.versatia.testsupport.testContext
import dev.versatia.util.config.getIntOrThrow
import dev.versatia.util.config.getLongOrThrow
import dev.versatia.util.type.Percent
import dev.versatia.util.type.Secret
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.io.TempDir

enum class Mode { EASY, HARD }

object Limits {
    var max = 0
    var names: List<String> = emptyList()
    var mode: Mode? = null
    var tax = Percent.of(0.0)
    var ticks = 0L
    var range: Range? = null
    private var secret = 0
    private var rate = Percent.of(0.0)
    private var token = Secret.EMPTY

    fun secret() = secret

    fun rate() = rate

    fun token() = token

    fun reset() {
        max = 0
        names = emptyList()
        mode = null
        tax = Percent.of(0.0)
        ticks = 0L
        range = null
        secret = 0
        rate = Percent.of(0.0)
        token = Secret.EMPTY
    }
}

class Shop {
    private var tax = Percent.of(0.0)
    var hours: Set<Int> = emptySet()
    var modes: Map<String, Mode> = emptyMap()
    var allowed: List<Mode> = emptyList()
    val interval = PreviousValueTracking<Int>()
    private val zone = PreviousValueTracking<String?>()

    fun tax() = tax

    fun zone() = zone
}

/** A reloadable bean reading a value of its own at reload, as a module's prototype would read the consumer's. */
class Prices : Reloadable {
    var factor = 1

    override fun reload() = Recorder.record("Prices.reload", factor)
}

class ValuesMain(dataFolder: File) : Plugin by fakePlugin("Values", dataFolder = dataFolder) {
    var motd = ""

    fun report() = Recorder.record("ValuesMain.report", motd)
}

object TicksFromSeconds : ValueMapper<Long> {
    override fun map(section: ConfigurationSection, key: String) = section.getLongOrThrow(key) * 20
}

data class Range(val min: Int, val max: Int) {
    private object Mapper : ValueMapper<Range> {
        override fun map(section: ConfigurationSection, key: String) = section.getConfigurationSection(key)!!.let {
            Range(it.getIntOrThrow("min"), it.getIntOrThrow("max")).also { range -> require(range.min <= range.max) { "min above max at $key" } }
        }
    }
}

class Doubling : ValueMapper<Int> {
    override fun map(section: ConfigurationSection, key: String) = section.getIntOrThrow(key) * 2
}

/** Unit tests of `@Value` at runtime with hand-written descriptors. */
@Suppress("UNCHECKED_CAST")
class ValueTest {
    @TempDir
    lateinit var dataFolder: File

    private lateinit var states: PluginStates
    private lateinit var enhancer: PluginEnhancer
    private lateinit var main: ValuesMain

    @BeforeTest
    fun setUp() {
        Recorder.reset()
        Limits.reset()
        states = PluginStates()
        enhancer = PluginEnhancer(ProcessorLoader())
        main = ValuesMain(dataFolder)
    }

    private fun context(event: LifecycleEvent = LifecycleEvent.START) = testContext("Values", states, event = event, plugin = main)

    private fun shop() = states.of("Values")[BeanResolver.KEY]!!.instanceOf(shopName) as Shop

    private fun file(name: String, content: String) = dataFolder.resolve(name).apply { parentFile.mkdirs() }.writeText(content)

    private inline fun <reified T : Any> value(
        name: String,
        owner: MemberOwner,
        type: TypeReference,
        setter: Setter,
        key: String = name,
        file: String = Value.DEFAULT_FILE,
        mapper: String = NoMapper::class.qualifiedName!!,
    ) = EnhancedElement(
        ConfigProcessors.VALUE,
        Phase.PLUGIN_RELOAD_OR_START,
        AnnotationOccurrence(Value::class.java.name, mapOf("key" to key, "file" to file, "mapper" to mapper)),
        PropertyTarget(T::class.qualifiedName!!, T::class.java.name, name, owner, type, emptyList(), setter),
    )

    private val int = TypeReference("kotlin.Int")
    private val string = TypeReference("kotlin.String")
    private val percent = TypeReference(Percent::class.qualifiedName!!)
    private val mode = TypeReference(Mode::class.qualifiedName!!)

    private val max = value<Limits>("max", MemberOwner.OBJECT, int, Setter.Direct { _, v -> Limits.max = v as Int })
    private val names = value<Limits>("names", MemberOwner.OBJECT, TypeReference("kotlin.collections.List", listOf(string)), Setter.Direct { _, v -> Limits.names = v as List<String> })
    private val optionalMode = value<Limits>("mode", MemberOwner.OBJECT, mode.copy(nullable = true), Setter.Direct { _, v -> Limits.mode = v as Mode? })
    private val motd = value<ValuesMain>("motd", MemberOwner.PLUGIN, string, Setter.Direct { instance, v -> (instance as ValuesMain).motd = v as String })

    private val pluginElement = EnhancedElement(
        BeanProcessors.PLUGIN,
        Phase.PLUGIN_RELOAD_OR_START,
        AnnotationOccurrence(PluginBean::class.java.name),
        PluginTarget(ValuesMain::class.qualifiedName!!, listOf(ValuesMain::class.qualifiedName!!, Plugin::class.qualifiedName!!), emptyList()),
    )

    private val shopName = Shop::class.qualifiedName!!
    private val shopBean = bean<Shop> { Shop() }
    private val pricesBean = bean<Prices> { Prices() }

    private inline fun <reified T : Any> bean(crossinline create: () -> T) = EnhancedElement(
        BeanProcessors.LOCAL,
        Phase.PLUGIN_START_ONLY,
        AnnotationOccurrence("test.Scope"),
        ClassTarget(T::class.qualifiedName!!, listOf(T::class.qualifiedName!!), emptyList(), emptyList(), Instantiator.Direct { create() }),
    )

    private val report = EnhancedElement(
        LifecycleProcessors.AUTO_INVOKE,
        Phase.PLUGIN_RELOAD_OR_START,
        AnnotationOccurrence(AutoInvoke::class.java.name),
        FunctionTarget(ValuesMain::class.qualifiedName!!, ValuesMain::class.java.name, "report", MemberOwner.PLUGIN, emptyList(), emptyList(), Invoker.Direct { instance, _ -> (instance as ValuesMain).report() }),
    )

    @Test
    fun `sets values of the plugin, objects and beans before functions run`() {
        file("config.yml", "motd: Welcome\nmax: 7\nnames: [a, b]\nmode: hard\nshop:\n  hours: [9, 17, 9]\n")
        val hours = value<Shop>("hours", MemberOwner.INSTANCE, TypeReference("kotlin.collections.Set", listOf(int)), Setter.Direct { instance, v -> (instance as Shop).hours = v as Set<Int> }, key = "shop.hours")

        enhancer.enhance(descriptor(pluginElement, shopBean, motd, max, names, optionalMode, hours, report), context())

        assertEquals("Welcome", main.motd)
        assertEquals(7, Limits.max)
        assertEquals(listOf("a", "b"), Limits.names)
        assertEquals(Mode.HARD, Limits.mode)
        assertEquals(setOf(9, 17), shop().hours)
        assertEquals(listOf<Pair<String, Any>>("ValuesMain.report" to "Welcome"), Recorder.events.toList(), "the function sees the value")
    }

    @Test
    fun `sets values of the plugin and objects before the beans exist, whatever the descriptor order`() {
        file("config.yml", "motd: Welcome\nmax: 7\nshop:\n  hours: [9]\n")
        val shop = bean<Shop> { Shop().also { Recorder.record("Shop.init", main.motd to Limits.max) } }
        val hours = value<Shop>("hours", MemberOwner.INSTANCE, TypeReference("kotlin.collections.Set", listOf(int)), Setter.Direct { instance, v -> (instance as Shop).hours = v as Set<Int> }, key = "shop.hours")

        enhancer.enhance(descriptor(pluginElement, shop, hours, motd, max), context())

        assertEquals("Welcome" to 7, Recorder.instanceOf("Shop.init"))
        assertEquals(setOf(9), shop().hours)
    }

    @Test
    fun `sets values before Reloadable beans reload, at start and at reload`() {
        file("config.yml", "factor: 3\n")
        val factor = value<Prices>("factor", MemberOwner.INSTANCE, int, Setter.Direct { instance, v -> (instance as Prices).factor = v as Int })
        val elements = descriptor(pluginElement, pricesBean, factor, report)

        enhancer.enhance(elements, context())
        assertEquals(listOf<Pair<String, Any>>("Prices.reload" to 3, "ValuesMain.report" to ""), Recorder.events.toList(), "reload() sees the value, then the function runs")

        Recorder.reset()
        file("config.yml", "factor: 4\n")
        enhancer.enhance(elements, context(LifecycleEvent.RELOAD))
        assertEquals(listOf<Pair<String, Any>>("Prices.reload" to 4, "ValuesMain.report" to ""), Recorder.events.toList())
    }

    @Test
    fun `re-reads values at reload and keeps them when the file is broken`() {
        file("config.yml", "max: 1\nnames: [a]\n")
        val elements = descriptor(max, names)
        enhancer.enhance(elements, context())

        file("config.yml", "max: 2\nnames: [b]\n")
        enhancer.enhance(elements, context(LifecycleEvent.RELOAD))
        assertEquals(2, Limits.max)
        assertEquals(listOf("b"), Limits.names)

        file("config.yml", "max: ten\nnames: [c]\n")
        val error = assertFailsWith<EnhancementException> { enhancer.enhance(elements, context(LifecycleEvent.RELOAD)) }
        assertEquals("Plugin Values: ${Limits::class.qualifiedName}.max could not be read from config.yml: Value at \"max\" is not an int: \"ten\"", error.message)
        assertEquals(2, Limits.max)
        assertEquals(listOf("b"), Limits.names, "nothing is set when any value fails")
    }

    @Test
    fun `treats a missing value as an error unless the type is nullable`() {
        file("config.yml", "names: []\n")

        enhancer.enhance(descriptor(optionalMode), context())
        assertNull(Limits.mode)

        val error = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(max), context()) }
        assertContains(error.message!!, "${Limits::class.qualifiedName}.max could not be read from config.yml: No value present at \"max\"")
    }

    @Test
    fun `reads from the named file and reports a missing one`() {
        file("rules/limits.yml", "max: 3\n")
        val fromRules = value<Limits>("max", MemberOwner.OBJECT, int, Setter.Direct { _, v -> Limits.max = v as Int }, file = "rules/limits.yml")

        enhancer.enhance(descriptor(fromRules), context())
        assertEquals(3, Limits.max)

        val missing = value<Limits>("max", MemberOwner.OBJECT, int, Setter.Direct { _, v -> Limits.max = v as Int }, file = "rules/other.yml")
        val error = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(missing), context()) }
        assertContains(error.message!!, "Plugin Values: rules/other.yml is missing from the data folder and the plugin JAR has no default copy")
    }

    @Test
    fun `refuses a file outside the data folder`() {
        val outside = value<Limits>("max", MemberOwner.OBJECT, int, Setter.Direct { _, v -> Limits.max = v as Int }, file = "../outside.yml")

        val error = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(outside), context()) }
        assertEquals("Plugin Values: the @Value file \"../outside.yml\" lies outside the data folder; name a path relative to it, such as config.yml", error.message)
    }

    @Test
    fun `names the plugin, the property and the file when a mapper cannot be used`() {
        file("config.yml", "max: 3\n")
        val property = "${Limits::class.qualifiedName}.max"
        for ((mapper, reason) in listOf("com.example.Missing" to "com.example.Missing cannot be loaded", String::class.java.name to "java.lang.String does not implement")) {
            val element = value<Limits>("max", MemberOwner.OBJECT, int, Setter.Direct { _, v -> Limits.max = v as Int }, mapper = mapper)
            val error = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(element), context()) }
            assertContains(error.message!!, "Plugin Values: $property could not be read from config.yml: $reason")
        }
    }

    @Test
    fun `rejects a list holding a scalar or an element of the wrong type`() {
        file("config.yml", "names: world\n")
        assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(names), context()) }

        file("config.yml", "names:\n  - world\n  - [nested]\n")
        assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(names), context()) }
        assertEquals(emptyList(), Limits.names)
    }

    @Test
    fun `reads enums, lists of enums and maps`() {
        file("config.yml", "shop:\n  allowed: [easy, HARD]\n  modes:\n    monday: easy\n    sunday: hard\n")
        val allowed = value<Shop>("allowed", MemberOwner.INSTANCE, TypeReference("kotlin.collections.List", listOf(mode)), Setter.Direct { instance, v -> (instance as Shop).allowed = v as List<Mode> }, key = "shop.allowed")
        val modes = value<Shop>("modes", MemberOwner.INSTANCE, TypeReference("kotlin.collections.Map", listOf(string, mode)), Setter.Direct { instance, v -> (instance as Shop).modes = v as Map<String, Mode> }, key = "shop.modes")

        enhancer.enhance(descriptor(pluginElement, shopBean, allowed, modes), context())

        val shop = shop()
        assertEquals(listOf(Mode.EASY, Mode.HARD), shop.allowed)
        assertEquals(mapOf("monday" to Mode.EASY, "sunday" to Mode.HARD), shop.modes)

        file("config.yml", "shop:\n  allowed: [medium]\n  modes: {}\n")
        val error = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(allowed), context(LifecycleEvent.RELOAD)) }
        assertContains(error.message!!, "Element 0 of list at \"shop.allowed\" is not one of EASY, HARD: \"medium\"")
    }

    @Test
    fun `reads through mappers, objects and classes alike, and reports their failures`() {
        file("config.yml", "restock: 3\nrange:\n  min: 1\n  max: 5\nmax: 21\n")
        val ticks = value<Limits>("ticks", MemberOwner.OBJECT, TypeReference("kotlin.Long"), Setter.Direct { _, v -> Limits.ticks = v as Long }, key = "restock", mapper = TicksFromSeconds::class.qualifiedName!!)
        val range = value<Limits>("range", MemberOwner.OBJECT, TypeReference(Range::class.qualifiedName!!, nullable = true), Setter.Direct { _, v -> Limits.range = v as Range? }, mapper = "${Range::class.qualifiedName}.Mapper")
        val doubled = value<Limits>("max", MemberOwner.OBJECT, int, Setter.Direct { _, v -> Limits.max = v as Int }, mapper = Doubling::class.qualifiedName!!)

        enhancer.enhance(descriptor(ticks, range, doubled), context())

        assertEquals(60L, Limits.ticks)
        assertEquals(Range(1, 5), Limits.range, "a private nested object is found by its Kotlin name")
        assertEquals(42, Limits.max)

        file("config.yml", "restock: 3\nmax: 21\n")
        enhancer.enhance(descriptor(range), context(LifecycleEvent.RELOAD))
        assertNull(Limits.range, "a missing value follows the property's nullability; the mapper is not called")

        file("config.yml", "range:\n  min: 9\n  max: 5\n")
        val error = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(range), context(LifecycleEvent.RELOAD)) }
        assertEquals("Plugin Values: ${Limits::class.qualifiedName}.range could not be read from config.yml: min above max at range", error.message)
    }

    @Test
    fun `reaches private properties through reflection, inline classes included`() {
        file("config.yml", "secret: 5\nrate: 12.5%\ntax: 8%\nshop:\n  tax: 3%\n")
        val secret = value<Limits>("secret", MemberOwner.OBJECT, int, Setter.Reflective(Limits::class.java.name, "secret"))
        val rate = value<Limits>("rate", MemberOwner.OBJECT, percent, Setter.Reflective(Limits::class.java.name, "rate"))
        val tax = value<Limits>("tax", MemberOwner.OBJECT, percent, Setter.Direct { _, v -> Limits.tax = v as Percent })
        val shopTax = value<Shop>("tax", MemberOwner.INSTANCE, percent, Setter.Reflective(Shop::class.java.name, "tax"), key = "shop.tax")

        enhancer.enhance(descriptor(pluginElement, shopBean, secret, rate, tax, shopTax), context())

        assertEquals(5, Limits.secret())
        assertEquals(Percent.of(12.5), Limits.rate())
        assertEquals(Percent.of(8.0), Limits.tax)
        assertEquals(Percent.of(3.0), shop().tax())
    }

    @Test
    fun `reads a secret through its references and keeps it out of the messages`() {
        val tokenFile = dataFolder.resolve("token.txt").apply { writeText("from-a-file\n") }
        file("config.yml", "token: \${file:${tokenFile.absolutePath.replace('\\', '/')}}\n")
        val token = value<Limits>("token", MemberOwner.OBJECT, TypeReference(Secret::class.qualifiedName!!), Setter.Reflective(Limits::class.java.name, "token"))
        val elements = descriptor(token)

        enhancer.enhance(elements, context())
        assertEquals("from-a-file", Limits.token().reveal())

        file("config.yml", "token: \${env:VERSATIA_TEST_UNSET}\n")
        val error = assertFailsWith<EnhancementException> { enhancer.enhance(elements, context(LifecycleEvent.RELOAD)) }
        assertEquals(
            "Plugin Values: ${Limits::class.qualifiedName}.token could not be read from config.yml: " +
                "Value at \"token\" uses \${env:VERSATIA_TEST_UNSET}, which is not set; set that environment variable for the server, or write the value instead",
            error.message,
        )
        assertEquals("from-a-file", Limits.token().reveal(), "a failed reload keeps the secret")
    }

    @Test
    fun `fills a PreviousValueTracking and keeps the value it replaced`() {
        file("config.yml", "shop:\n  interval: 5\n")
        val tracking = PreviousValueTracking::class.qualifiedName!!
        val interval = value<Shop>("interval", MemberOwner.INSTANCE, TypeReference(tracking, listOf(int)), Setter.Direct { instance, v -> (instance as Shop).interval.value = v as Int }, key = "shop.interval")
        val zone = value<Shop>("zone", MemberOwner.INSTANCE, TypeReference(tracking, listOf(string.copy(nullable = true))), Setter.Reflective(Shop::class.java.name, "zone", "value"), key = "shop.zone")
        val elements = descriptor(pluginElement, shopBean, interval, zone)

        enhancer.enhance(elements, context())
        val shop = shop()
        assertEquals(5, shop.interval.value)
        assertFalse(shop.interval.hasPrevious)
        assertTrue(shop.interval.changed, "the first value counts as a change")
        assertNull(shop.zone().value, "a missing value follows the nullability of the held type")

        file("config.yml", "shop:\n  interval: 8\n  zone: Europe/Warsaw\n")
        enhancer.enhance(elements, context(LifecycleEvent.RELOAD))
        assertEquals(8, shop.interval.value)
        assertEquals(5, shop.interval.previous)
        assertTrue(shop.interval.changed)
        assertEquals("Europe/Warsaw", shop.zone().value)

        enhancer.enhance(elements, context(LifecycleEvent.RELOAD))
        assertEquals(8, shop.interval.previous)
        assertFalse(shop.interval.changed, "the same value again is no change")

        file("config.yml", "shop:\n  zone: UTC\n")
        val error = assertFailsWith<EnhancementException> { enhancer.enhance(elements, context(LifecycleEvent.RELOAD)) }
        assertContains(error.message!!, "No value present at \"shop.interval\"")
        assertEquals("PreviousValueTracking(8, previous=8)", shop.interval.toString(), "a failed reload assigns nothing")
        assertEquals("Europe/Warsaw", shop.zone().value)
    }

    @Test
    fun `reports a bean that does not exist and accepts only properties`() {
        file("config.yml", "shop:\n  tax: 3%\n")
        val shopTax = value<Shop>("tax", MemberOwner.INSTANCE, percent, Setter.Reflective(Shop::class.java.name, "tax"), key = "shop.tax")
        val noBean = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(pluginElement, shopTax), context()) }
        assertContains(noBean.message!!, "no bean $shopName exists to set $shopName.tax on")

        val onClass = EnhancedElement(ConfigProcessors.VALUE, Phase.PLUGIN_RELOAD_OR_START, AnnotationOccurrence(Value::class.java.name), shopBean.target)
        val wrongTarget = assertFailsWith<EnhancementException> { enhancer.enhance(descriptor(onClass), context()) }
        assertContains(wrongTarget.message!!, "can only enhance properties")
    }
}
