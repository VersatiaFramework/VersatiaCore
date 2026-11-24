package dev.versatia.text

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StyleFileTest {
    private val file = StyleFile("styles.yml")

    private fun read(content: String) = file.read(content.trimIndent())

    private fun failure(content: String) = assertFailsWith<StylesLoadException> { read(content) }.message!!

    @Test
    fun `a section is a scope, a dotted key says the same as nesting and an empty value is a style that changes nothing`() {
        val sheet = read("""
            accent: "<gold>"
            success:
              style: "<green>"
              accent: "<#b4ff9a>"
              deep:
                accent: "<white>"
            error.style: "<red>"
            error.accent: "<#ff9a9a>"
            plain:
        """)

        assertEquals(
            listOf(
                "accent" to "<gold>", "success" to "<green>", "success.accent" to "<#b4ff9a>", "success.deep.accent" to "<white>",
                "error" to "<red>", "error.accent" to "<#ff9a9a>", "plain" to "",
            ),
            sheet.paths.toList(),
        )
        assertEquals(listOf("accent", "deep", "error", "plain", "success"), sheet.names.toList())
        assertEquals(setOf("deep", "error", "success"), sheet.scopesOf("accent"))
    }

    @Test
    fun `a name is looked up in the styles around it, nearest first, then at the top level`() {
        val sheet = read("""
            accent: "<gold>"
            success:
              style: "<green>"
              accent: "<#b4ff9a>"
            error:
              accent: "<#ff9a9a>"
            note: "<gray>"
        """)
        val success = sheet.root.getValue("success")
        val error = sheet.root.getValue("error")
        fun accent(vararg scopes: StyleNode) = sheet.resolve("accent", scopes.toList())?.snippet?.source

        assertEquals("<gold>", accent())
        assertEquals("<#b4ff9a>", accent(success))
        assertEquals("<#b4ff9a>", accent(success, error), "the nearest scope wins")
        assertEquals("<#ff9a9a>", accent(error, success))
        assertEquals("<gold>", accent(sheet.root.getValue("note")), "a style without the name leaves it to the top level")
        assertNull(sheet.resolve("nope", listOf(success)))
        assertNull(error.snippet, "a section without a style of its own only holds names")
    }

    @Test
    fun `a name in another case is the same name, shown as the file first writes it`() {
        val sheet = read("""
            realmTag: "[Realm] "
            negative:
              style: "<red>"
              RealmTag: "<dark_red>[Realm]</dark_red> "
            Negative.accent: "<gold>"
        """)

        assertEquals(
            listOf("realmTag" to "[Realm] ", "negative" to "<red>", "negative.RealmTag" to "<dark_red>[Realm]</dark_red> ", "negative.accent" to "<gold>"),
            sheet.paths.toList(),
        )
        assertEquals(listOf("accent", "negative", "realmTag"), sheet.names.toList())
        assertTrue("REALMTAG" in sheet.names)
        assertEquals("[Realm] ", sheet.resolve("realmtag", emptyList())?.snippet?.source)
        assertEquals("<dark_red>[Realm]</dark_red> ", sheet.resolve("realmTag", listOf(sheet.root.getValue("negative")))?.snippet?.source)
        assertEquals(setOf("negative"), sheet.scopesOf("REALMTAG"))
        assertEquals(emptyList(), sheet.lacking(read("realmtag: \"x\"\nNEGATIVE: { Accent: \"y\" }")))
        assertEquals(listOf("negative.hint"), sheet.lacking(read("negative: { hint: \"<gray>\", accent: \"y\" }")))
        assertEquals("style \"realmtag\" is defined twice in styles.yml", failure("realmTag: \"a\"\nrealmtag: \"b\""))
    }

    @Test
    fun `a file that is not a map of styles is reported with the file and the style`() {
        assertEquals("styles.yml must map the names of styles to MiniMessage", failure("- accent"))
        assertEquals("style \"accent\" in styles.yml must be MiniMessage text or a section of styles", failure("accent: [a]"))
        assertEquals("style \"success.style\" in styles.yml must be MiniMessage text", failure("success: { style: { a: b } }"))
        assertEquals("style \"success\" is defined twice in styles.yml", failure("success: \"<green>\"\nsuccess.style: \"<red>\""))
        assertEquals("style \"empty\" in styles.yml is an empty section; give it a \"style\" or the names that apply inside it", failure("empty: {}"))
        assertTrue(failure("accent: [unclosed").startsWith("styles.yml is not valid YAML at line 1, column "))
        assertContains(failure("accent: a\naccent: b"), "duplicate key accent")
    }

    @Test
    fun `a name that cannot be a tag of its own is reported`() {
        assertEquals("\"style\" in styles.yml is the key of a section's own style, so it cannot be a style at the top level", failure("style: \"<gold>\""))
        assertEquals("\"style\" in styles.yml cannot name a style: it is the key of a section's own style", failure("success.style.accent: \"<gold>\""))
        assertEquals("\"Style\" in styles.yml cannot name a style: it is the key of a section's own style", failure("success.Style: \"<gold>\""))
        assertEquals("\"my accent\" in styles.yml cannot name a style: letters, digits, _ and - only", failure("my accent: \"<gold>\""))
        assertEquals("\"\" in styles.yml cannot name a style: letters, digits, _ and - only", failure("success..accent: \"<gold>\""))
        assertEquals("\"bold\" in styles.yml cannot name a style: it is a MiniMessage tag", failure("success: { bold: \"<gold>\" }"))
        assertEquals("\"Bold\" in styles.yml cannot name a style: it is a MiniMessage tag", failure("Bold: \"<gold>\""))
        assertEquals("\"versatia-x\" in styles.yml cannot name a style: names starting with versatia- are reserved", failure("versatia-x: \"<gold>\""))
    }

    @Test
    fun `a style holds MiniMessage that exists and nothing else`() {
        assertEquals(
            "style \"accent\" in styles.yml uses <gol>, which is not a MiniMessage tag; a style holds MiniMessage tags only, never another style",
            failure("accent: \"<gol>\""),
        )
        assertEquals(
            "style \"success.accent\" in styles.yml uses <highlight>, which is not a MiniMessage tag; a style holds MiniMessage tags only, never another style",
            failure("highlight: \"<gold>\"\nsuccess: { accent: \"<highlight>\" }"),
        )
        assertEquals(
            "style \"link\" in styles.yml uses <tip>, <gol>, which are not MiniMessage tags; a style holds MiniMessage tags only, never another style",
            failure("link: \"<hover:show_text:'<tip>x'><gol>\""),
        )
        assertEquals(
            "style \"accent\" in styles.yml uses legacy colour codes, which MiniMessage shows as text: &6 -> <gold>, &l -> <bold>; write the tags instead",
            failure("accent: \"&6&l\""),
        )
        read("fine: \"R&D <3 <-> <gold>\"")
    }

    @Test
    fun `a name the sheet lacks is added whole, and under a name it has each lacking style is a dotted line`() {
        val defaults = read("""
            accent: "<gold>"
            success:
              style: "<green>"
              accent: "<#b4ff9a>"
              deep:
                accent: "<white>"
            error:
              style: "<red>"
              accent: "<#ff9a9a>"
        """)

        assertEquals(
            """
            # Default styles of Shop
            accent: "<gold>"
            success:
              style: "<green>"
              accent: "<#b4ff9a>"
              deep:
                accent: "<white>"
            error:
              style: "<red>"
              accent: "<#ff9a9a>"

            """.trimIndent(),
            file.additions(read(""), defaults, "Shop"),
        )
        assertEquals(
            """
            # Default styles of Shop
            success.accent: "<#b4ff9a>"
            success.deep.accent: "<white>"
            error.style: "<red>"

            """.trimIndent(),
            file.additions(read("accent: \"<yellow>\"\nsuccess: \"<dark_green>\"\nerror: { accent: \"<light_purple>\" }"), defaults, "Shop"),
        )
        assertEquals("", file.additions(defaults, defaults, "Shop"))
    }

    @Test
    fun `what is added reads back as the styles it came from, quotes and backslashes included`() {
        val defaults = read("""
            link: "<hover:show_text:'a \"quoted\" \\\\ tip'><underlined>"
            success:
              style: "<green>"
              accent: "<click:suggest_command:\"/shop\"><gold>"
        """)
        val server = "accent: \"<gold>\"\nsuccess: { note: \"<gray>\" }\n"

        val merged = file.read(server + file.additions(file.read(server), defaults, "Shop"))

        assertEquals(file.read(server).paths + defaults.paths, merged.paths)
    }
}
