package dev.versatia.engine.command

import dev.versatia.testsupport.serverStyles
import dev.versatia.testsupport.shownColours
import dev.versatia.testsupport.textOf
import dev.versatia.text.arg
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kyori.adventure.text.format.NamedTextColor
import org.junit.jupiter.api.io.TempDir

/** The replies of VersatiaCore's commands: in the server's styles for a player, plain for the log. */
class ReplyTest {
    @TempDir
    lateinit var server: File

    private val text by lazy {
        textOf(serverStyles(server, """
            neutral: "<gray>"
            positive: "<green>"
            negative: "<red>"
            accent: "<white>"
            label: "<gold>"
            heading: "<aqua><bold>"
        """))
    }

    @Test
    fun `a reply takes its colours from the styles and reads the same in the log`() {
        val reloaded = Reply("<positive>Reloaded {asked}; then {then}.", arg("asked", listOf("shop")), arg("then", listOf("quests", "bank")))

        assertEquals(
            listOf("Reloaded " to NamedTextColor.GREEN, "shop" to NamedTextColor.WHITE, "; then " to NamedTextColor.GREEN, "quests" to NamedTextColor.WHITE,
                ", " to NamedTextColor.GREEN, "bank" to NamedTextColor.WHITE, "." to NamedTextColor.GREEN),
            shownColours(reloaded.component(text)),
        )
        assertEquals("Reloaded shop; then quests, bank.", reloaded.plain())
    }

    @Test
    fun `a value stands out, a nested reply keeps its own tone, and an empty list reads none`() {
        val failed = Reply("<negative>{plugin} failed: {reason}. {running}", arg("plugin", "shop"), arg("reason", "<gol> is no tag"), arg("running", Reply("<neutral>Running: {plugins}.", arg("plugins", emptyList<String>()))))

        assertEquals(
            listOf(
                "shop" to NamedTextColor.WHITE, " failed: " to NamedTextColor.RED, "<gol> is no tag" to NamedTextColor.WHITE, ". " to NamedTextColor.RED,
                "Running: " to NamedTextColor.GRAY, "none" to NamedTextColor.WHITE, "." to NamedTextColor.GRAY,
            ),
            shownColours(failed.component(text)),
        )
        assertEquals("shop failed: <gol> is no tag. Running: none.", failed.plain())
    }

    @Test
    fun `a label and a heading are styles too`() {
        val headline = Reply("<neutral><heading>{name}</heading> {version}", arg("name", "Shop"), arg("version", "1.0"))
        val field = Reply("<neutral><label>Reloads cascade to:</label> {plugins}", arg("plugins", listOf("Bank")))

        assertEquals(listOf("Shop" to NamedTextColor.AQUA, " " to NamedTextColor.GRAY, "1.0" to NamedTextColor.WHITE), shownColours(headline.component(text)))
        assertEquals(listOf("Reloads cascade to:" to NamedTextColor.GOLD, " " to NamedTextColor.GRAY, "Bank" to NamedTextColor.WHITE), shownColours(field.component(text)))
        assertEquals("Shop 1.0", headline.plain())
    }
}
