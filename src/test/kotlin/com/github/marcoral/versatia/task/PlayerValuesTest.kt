package com.github.marcoral.versatia.task

import com.github.marcoral.versatia.testsupport.fakePlugin
import com.github.marcoral.versatia.testsupport.handlersOf
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/** `scope.perPlayer`: values cleared at quit, after a delay unless the player returns, and at the plugin's stop. */
class PlayerValuesTest {
    private val ticker = ServerTicker(fakePlugin("PlayerValuesCore"))
    private val loop = ticker.loop
    private val alpha = fakePlugin("PlayerValuesAlpha")
    private val scope = PluginScopeBean(alpha, ticker)
    private val steve = player("Steve")
    private val alex = player("Alex")

    @AfterTest
    fun tearDown() {
        HandlerList.unregisterAll(alpha)
        ticker.close()
    }

    private fun player(name: String) = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
        when (method.name) {
            "getName" -> name
            "getUniqueId" -> UUID.nameUUIDFromBytes(name.toByteArray())
            "hashCode" -> name.hashCode()
            "equals" -> proxy === args[0]
            "toString" -> name
            else -> throw UnsupportedOperationException(method.name)
        }
    } as Player

    private fun quit(player: Player) = alpha.server.pluginManager.callEvent(PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))

    private fun join(player: Player) = alpha.server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))

    private fun listeners() = handlersOf(PlayerQuitEvent::class.java).registeredListeners.count { it.plugin === alpha }

    @Test
    fun `a value goes when its player quits`() {
        val tags = scope.perPlayer<Int>()
        tags[steve] = 1
        tags[alex] = 2
        assertEquals(1, tags[steve])
        assertEquals(3, tags.getOrPut(player("Herobrine")) { 3 })
        assertEquals(3, tags.getOrPut(player("Herobrine")) { 4 })

        quit(steve)
        assertNull(tags[steve])
        assertFalse(steve in tags)
        assertEquals(2, tags[alex])
        assertEquals(2, tags.remove(alex))
        assertNull(tags[alex])
    }

    @Test
    fun `with keepAfterQuit a value outlives the quit, and a join keeps it`() {
        val tags = scope.perPlayer<String>(keepAfterQuit = 100.milliseconds)
        tags[steve] = "fighting"
        tags[alex] = "fighting"
        quit(steve)
        quit(alex)
        assertEquals("fighting", tags[steve])
        join(alex)
        repeat(3) { loop.heartbeat() }
        assertNull(tags[steve], "two ticks later the value is gone")
        assertEquals("fighting", tags[alex], "a join cancels the removal")
    }

    @Test
    fun `the plugin's stop clears the values and removes the listeners`() {
        val tags = scope.perPlayer<Int>()
        tags[steve] = 1
        assertEquals(1, listeners())
        scope.stop()
        assertNull(tags[steve])
        assertEquals(0, listeners())
        assertTrue(handlersOf(PlayerJoinEvent::class.java).registeredListeners.none { it.plugin === alpha })
    }
}
