package dev.versatia.text

import dev.versatia.testsupport.captureLog
import dev.versatia.testsupport.pluginWithStyles
import dev.versatia.testsupport.serverStyles
import dev.versatia.testsupport.stylesFile
import java.io.File
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** The styles file of the server and the defaults plugins add to it. */
class ServerStylesTest {
    @TempDir
    lateinit var server: File

    private val file get() = stylesFile(server)

    private val shopDefaults = """
        accent: "<gold>"
        success:
          style: "<green>"
          accent: "<#b4ff9a>"
    """

    /** Starts the plugin [name] as far as styles go: its `Text` hands over the defaults it ships. */
    private fun start(styles: ServerStyles, name: String, defaults: String?) = TextImpl(styles, ServerPresentations(), pluginWithStyles(name, defaults)).reload()

    @Test
    fun `the file is read at every reload, and one that cannot be read keeps the styles in use`() {
        val styles = serverStyles(server, "accent: \"<gold>\"")
        assertEquals(mapOf("accent" to "<gold>"), styles.sheet.paths)

        file.writeText("accent: \"<red>\"\nsuccess: \"<green>\"")
        styles.reload()
        assertEquals(mapOf("accent" to "<red>", "success" to "<green>"), styles.sheet.paths)

        file.writeText("accent: \"<red>")
        val error = assertFailsWith<StylesLoadException> { styles.reload() }
        assertTrue(error.message!!.startsWith("plugins/VersatiaCore/styles.yml is not valid YAML at line 1, column "), error.message)
        assertEquals(mapOf("accent" to "<red>", "success" to "<green>"), styles.sheet.paths)
    }

    @Test
    fun `without a file there are no styles, and a plugin without defaults creates none`() {
        val styles = serverStyles(server)

        start(styles, "Shop", null)

        assertEquals(emptyMap(), styles.sheet.paths)
        assertFalse(file.exists())
    }

    @Test
    fun `the defaults a plugin ships are appended under a comment naming it, once, and the file wins`() {
        val original = "# The palette of this server\naccent: \"<yellow>\"   # ours\n"
        val styles = serverStyles(server).also { file.apply { parentFile.mkdirs() }.writeText(original) }
        styles.reload()
        val shop = pluginWithStyles("Shop", shopDefaults)

        val logged = shop.captureLog().use { log ->
            TextImpl(styles, ServerPresentations(), shop).reload()
            log.messages(Level.INFO)
        }

        val withShop = original + "\n# Default styles of Shop\nsuccess:\n  style: \"<green>\"\n  accent: \"<#b4ff9a>\"\n"
        assertEquals(withShop, file.readText())
        assertEquals(mapOf("accent" to "<yellow>", "success" to "<green>", "success.accent" to "<#b4ff9a>"), styles.sheet.paths)
        assertEquals(listOf("Added the default styles success, success.accent to plugins/VersatiaCore/styles.yml"), logged)

        TextImpl(styles, ServerPresentations(), shop).reload()
        assertEquals(withShop, file.readText(), "a second start adds nothing")
    }

    @Test
    fun `of two plugins shipping one name the first to start is in the file, which the second reads`() {
        val styles = serverStyles(server)
        start(styles, "Shop", shopDefaults)

        start(styles, "Quests", """
            accent: "<red>"
            success:
              style: "<aqua>"
              accent: "<white>"
              quest: "<light_purple>"
            quest: "<light_purple>"
        """)

        assertEquals(
            """
            # Default styles of Shop
            accent: "<gold>"
            success:
              style: "<green>"
              accent: "<#b4ff9a>"

            # Default styles of Quests
            success.quest: "<light_purple>"
            quest: "<light_purple>"

            """.trimIndent(),
            file.readText(),
        )
        assertEquals("<gold>", styles.sheet.paths["accent"])
        assertEquals("<green>", styles.sheet.paths["success"])
    }

    @Test
    fun `a style lacking under a name the file has is a dotted line, its own style included`() {
        val styles = serverStyles(server, "success: { note: \"<gray>\" }")

        start(styles, "Shop", shopDefaults)

        assertEquals("success: { note: \"<gray>\" }\n\n# Default styles of Shop\naccent: \"<gold>\"\nsuccess.style: \"<green>\"\nsuccess.accent: \"<#b4ff9a>\"\n", file.readText())
        assertEquals(mapOf("success.note" to "<gray>", "accent" to "<gold>", "success" to "<green>", "success.accent" to "<#b4ff9a>"), styles.sheet.paths)
    }

    @Test
    fun `a default deleted from the file comes back at the plugin's next start or reload`() {
        val styles = serverStyles(server)
        start(styles, "Shop", shopDefaults)

        file.writeText("accent: \"<yellow>\"\n")
        styles.reload()
        start(styles, "Shop", shopDefaults)

        assertEquals("accent: \"<yellow>\"\n\n# Default styles of Shop\nsuccess:\n  style: \"<green>\"\n  accent: \"<#b4ff9a>\"\n", file.readText())
    }

    @Test
    fun `the file is read again before anything is added, so an edit not yet reloaded is neither lost nor doubled`() {
        val styles = serverStyles(server, "note: \"<gray>\"")
        file.writeText("note: \"<gray>\"\naccent: \"<yellow>\"\nsuccess: \"<dark_green>\"\nsuccess.accent: \"<white>\"\n")

        start(styles, "Shop", shopDefaults)

        assertEquals("note: \"<gray>\"\naccent: \"<yellow>\"\nsuccess: \"<dark_green>\"\nsuccess.accent: \"<white>\"\n", file.readText())
        assertEquals("<yellow>", styles.sheet.paths["accent"], "the styles in use are the file's")
    }

    @Test
    fun `a file with Windows line breaks keeps them`() {
        val styles = serverStyles(server).also { file.apply { parentFile.mkdirs() }.writeText("note: \"<gray>\"\r\n") }
        styles.reload()

        start(styles, "Shop", "accent: \"<gold>\"")

        assertEquals("note: \"<gray>\"\r\n\r\n# Default styles of Shop\r\naccent: \"<gold>\"\r\n", file.readText())
    }

    @Test
    fun `defaults that cannot be read name the plugin's JAR, and a server file that cannot be read stops the plugin`() {
        val styles = serverStyles(server)

        assertEquals(
            "\"my accent\" in styles.yml in the JAR of Shop cannot name a style: letters, digits, _ and - only",
            assertFailsWith<StylesLoadException> { start(styles, "Shop", "my accent: \"<gold>\"") }.message,
        )
        assertEquals(
            "style \"accent\" in styles.yml in the JAR of Shop uses <gol>, which is not a MiniMessage tag; a style holds MiniMessage tags only, never another style",
            assertFailsWith<StylesLoadException> { start(styles, "Shop", "accent: \"<gol>\"") }.message,
        )
        assertFalse(file.exists())

        file.apply { parentFile.mkdirs() }.writeText("accent: [")
        val error = assertFailsWith<StylesLoadException> { start(styles, "Shop", shopDefaults) }
        assertTrue(error.message!!.startsWith("plugins/VersatiaCore/styles.yml is not valid YAML"), error.message)
        assertEquals("accent: [", file.readText())
    }

    @Test
    fun `the styles VersatiaCore ships are in the file before any plugin's, win over a plugin's and come back when deleted`() {
        val styles = serverStyles(server, defaults = """
            neutral: "<gray>"
            accent: "<aqua>"
        """)
        assertEquals("# Default styles of VersatiaCore\nneutral: \"<gray>\"\naccent: \"<aqua>\"\n", file.readText())

        start(styles, "Shop", shopDefaults)
        assertEquals("<aqua>", styles.sheet.paths["accent"], "the shared style is there first, so a plugin's default for it is not used")

        file.writeText("accent: \"<white>\"\n")
        styles.reload()
        assertEquals("accent: \"<white>\"\n\n# Default styles of VersatiaCore\nneutral: \"<gray>\"\n", file.readText())
    }

    @Test
    fun `the styles VersatiaCore ships are the shared names, and the file it extracts defines none`() {
        fun shipped(path: String) = StyleFile(path).read(checkNotNull(ServerStyles::class.java.getResource(path)) { "$path is not shipped" }.readText())

        assertEquals(
            setOf("neutral", "positive", "negative", "warning", "warning.accent", "accent", "hint", "label", "heading", "serverTag"),
            shipped("/styles.yml").paths.keys,
        )
        assertEquals("", shipped("/styles.yml").paths["serverTag"], "no server has a tag until it writes one")
        assertEquals(emptySet(), shipped("/resources/styles.yml").paths.keys)
    }

    @Test
    fun `a default is there whatever case the file writes its name in, and one that is added keeps the plugin's spelling`() {
        val styles = serverStyles(server, "servertag: \"[Realm] \"", defaults = "serverTag: \"\"")
        assertEquals("servertag: \"[Realm] \"", file.readText(), "the server's own spelling of a shared name is the same style")

        start(styles, "Shop", "shopTag: \"[Shop] \"\nServerTag: \"[Shop's] \"")
        assertEquals("servertag: \"[Realm] \"\n\n# Default styles of Shop\nshopTag: \"[Shop] \"\n", file.readText())
        assertEquals(mapOf("servertag" to "[Realm] ", "shopTag" to "[Shop] "), styles.sheet.paths)
    }
}
