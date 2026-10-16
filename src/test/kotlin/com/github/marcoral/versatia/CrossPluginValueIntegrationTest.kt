package com.github.marcoral.versatia

import com.github.marcoral.versatia.config.PreviousValueTracking
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.ksp.VersatiaSymbolProcessorProvider
import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.fakePlugin
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import java.io.File
import java.net.URLClassLoader
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import org.bukkit.plugin.Plugin
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir

/**
 * End to end, compiled with the real KSP processor and run through the runtime: the `@Value`
 * properties of a prototype exposed by Economy reach the instances made for Shop and Auction, and
 * a reload of Economy reloads them too. See docs/runtime.md.
 */
@OptIn(ExperimentalCompilerApi::class)
class CrossPluginValueIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private val states = PluginStates()
    private val runtime = VersatiaRuntime(states)
    private val priceList = "com.example.economy.PriceList"
    private val economyConfig by lazy { tempDir.resolve("Economy").apply { mkdirs() }.resolve("config.yml") }

    @BeforeTest
    fun setUp() = Recorder.reset()

    private fun compile(name: String, source: String, classpath: JvmCompilationResult? = null): JvmCompilationResult {
        val pluginYml = File(tempDir, "$name.yml").apply { writeText("name: $name\nmain: ${mainOf(name)}\n") }
        val result = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("$name.kt", source))
            inheritClassPath = true
            jvmTarget = "21"
            messageOutputStream = System.out
            if (classpath != null) classpaths = listOf(classpath.outputDirectory)
            configureKsp {
                symbolProcessorProviders += VersatiaSymbolProcessorProvider()
                withCompilation = true
                processorOptions["versatia.pluginYml"] = pluginYml.path
            }
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        return result
    }

    private fun mainOf(name: String) = "com.example.${name.lowercase()}.${name}Plugin"

    private fun plugin(name: String, result: JvmCompilationResult, parent: ClassLoader): Plugin {
        val loader = URLClassLoader(arrayOf(result.outputDirectory.toURI().toURL()), parent)
        return fakePlugin(name, loader, tempDir.resolve(name).apply { mkdirs() }, main = mainOf(name))
    }

    private val economySource = """
        package com.example.economy

        import com.github.marcoral.versatia.Phase
        import com.github.marcoral.versatia.VersatiaPlugin
        import com.github.marcoral.versatia.config.PreviousValueTracking
        import com.github.marcoral.versatia.config.Value
        import com.github.marcoral.versatia.di.ExposePrototype
        import com.github.marcoral.versatia.di.ExposeSingleton
        import com.github.marcoral.versatia.lifecycle.AutoInvoke
        import com.github.marcoral.versatia.testsupport.Recorder

        class EconomyPlugin : VersatiaPlugin()

        object Reports {
            @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)

            fun report() = Recorder.record("Economy.report", "")
        }

        @ExposeSingleton
        class Clock

        /** No values of its own, but it wraps Economy's PriceList, which has some. */
        @ExposeSingleton
        class Ledger(val prices: PriceList)

        @ExposePrototype
        class PriceList(@Value("base") var base: Int, @Value("rate") val rate: PreviousValueTracking<Int>) {
            @Value("tax")
            var tax = 0.0

            init {
                Recorder.record("PriceList.init", listOf(base, rate.value, tax))
            }
        }
    """

    private fun consumerSource(name: String) = """
        package com.example.${name.lowercase()}

        import com.example.economy.PriceList
        import com.github.marcoral.versatia.Phase
        import com.github.marcoral.versatia.VersatiaPlugin
        import com.github.marcoral.versatia.di.LocalBean
        import com.github.marcoral.versatia.lifecycle.AutoInvoke
        import com.github.marcoral.versatia.testsupport.Recorder

        class ${name}Plugin : VersatiaPlugin()

        @LocalBean
        class Counter(val prices: PriceList) {
            init {
                Recorder.record("$name.prices", prices)
            }

            @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)
            fun report() = Recorder.record("$name.report", listOf(prices.base, prices.tax, prices.rate.value, prices.rate.changed))
        }
    """

    /** Bank holds only Economy's `Clock`, which has no `@Value` property. */
    /** Audit holds only Economy's `Ledger`, which wraps a bean with values. */
    private val auditSource = """
        package com.example.audit

        import com.example.economy.Ledger
        import com.github.marcoral.versatia.Phase
        import com.github.marcoral.versatia.VersatiaPlugin
        import com.github.marcoral.versatia.di.LocalBean
        import com.github.marcoral.versatia.lifecycle.AutoInvoke
        import com.github.marcoral.versatia.testsupport.Recorder

        class AuditPlugin : VersatiaPlugin()

        @LocalBean
        class Inspector(val ledger: Ledger) {
            @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)
            fun report() = Recorder.record("Audit.report", ledger.prices.tax)
        }
    """

    private val bankSource = """
        package com.example.bank

        import com.example.economy.Clock
        import com.github.marcoral.versatia.Phase
        import com.github.marcoral.versatia.VersatiaPlugin
        import com.github.marcoral.versatia.di.LocalBean
        import com.github.marcoral.versatia.lifecycle.AutoInvoke
        import com.github.marcoral.versatia.testsupport.Recorder

        class BankPlugin : VersatiaPlugin()

        @LocalBean
        class Vault(val clock: Clock) {
            @AutoInvoke(Phase.PLUGIN_RELOAD_OR_START)
            fun report() = Recorder.record("Bank.report", "")
        }
    """

    private fun Any.property(name: String) = javaClass.getMethod("get" + name.replaceFirstChar { it.uppercaseChar() }).invoke(this)

    private fun Any.rate() = property("rate") as PreviousValueTracking<*>

    private fun reports() = Recorder.events.filter { it.first.endsWith(".report") }

    @Test
    fun `values of a prototype follow its owner's reloads in every consumer, and the consumers reload after it`() {
        val economyResult = compile("Economy", economySource)
        val economy = plugin("Economy", economyResult, Recorder::class.java.classLoader)
        val economyLoader = economy.javaClass.classLoader
        val shop = plugin("Shop", compile("Shop", consumerSource("Shop"), economyResult), economyLoader)
        val auction = plugin("Auction", compile("Auction", consumerSource("Auction"), economyResult), economyLoader)
        val bank = plugin("Bank", compile("Bank", bankSource, economyResult), economyLoader)
        val audit = plugin("Audit", compile("Audit", auditSource, economyResult), economyLoader)

        economyConfig.writeText("base: 1\nrate: 5\ntax: 0.1\n")
        runtime.enhance(economy)
        runtime.enhance(shop)
        runtime.enhance(bank)
        runtime.enhance(audit)
        val shopPrices = Recorder.instanceOf("Shop.prices")
        assertEquals(0.1, shopPrices.property("tax"), "the body value is set right after the constructor")

        Recorder.reset()
        economyConfig.writeText("base: 2\nrate: 6\ntax: 0.2\n")
        runtime.reload(economy)
        assertEquals(
            listOf("Economy.report" to "", "Shop.report" to listOf(2, 0.2, 6, true), "Audit.report" to 0.2),
            reports(),
            "Shop reloads after Economy and sees the new values, and so does Audit through a wrapper of values",
        )

        runtime.enhance(auction)
        assertEquals(listOf(2, 6, 0.0), Recorder.events.last { it.first == "PriceList.init" }.second, "a later consumer's constructor gets the current values")
        val auctionPrices = Recorder.instanceOf("Auction.prices")
        assertEquals(0.2, auctionPrices.property("tax"))
        val ownPrices = states.of("Economy")[BeanResolver.KEY]!!.instanceOf(priceList)!!
        assertNotSame(shopPrices.rate(), auctionPrices.rate(), "every instance has its own tracking cell")
        assertNotSame(ownPrices.rate(), shopPrices.rate())

        Recorder.reset()
        runtime.reload(economy)
        assertEquals(listOf("Economy", "Shop", "Audit", "Auction"), reports().map { it.first.substringBefore('.') }, "each consumer once, in start order; Bank holds no bean with values")

        listOf(ownPrices, shopPrices, auctionPrices).forEach { assertEquals(false, it.rate().changed, "the same value again is no change for ${it.rate()}") }
        assertEquals(6, auctionPrices.rate().previous, "Auction's cell tracks from the value it was created with")

        Recorder.reset()
        economyConfig.writeText("base: 3\nrate: many\ntax: 0.3\n")
        val failure = assertFailsWith<EnhancementException> { runtime.reload(economy) }
        assertContains(failure.message!!, "PriceList.rate")
        assertEquals(emptyList<String>(), reports().map { it.first }, "a broken file reloads nobody")
        listOf(ownPrices, shopPrices, auctionPrices).forEach { assertEquals(2, it.property("base")) }

        economyConfig.writeText("base: 4\nrate: 7\ntax: 0.4\n")
        runtime.disable(shop)
        assertEquals(listOf(ownPrices, auctionPrices), states.of("Economy")[BeanResolver.KEY]!!.instancesOf(priceList), "a disabled consumer's instance is forgotten")
        Recorder.reset()
        runtime.reload(economy)
        assertEquals(listOf("Economy", "Audit", "Auction"), reports().map { it.first.substringBefore('.') })

        assertEquals(2, shopPrices.property("base"))
        assertEquals(4, auctionPrices.property("base"))
    }
}
