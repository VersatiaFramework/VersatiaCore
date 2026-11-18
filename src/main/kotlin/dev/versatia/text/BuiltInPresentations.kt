package dev.versatia.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.Translatable
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * What is shown without any registration; a plugin's own registrations take precedence. The
 * presentations of the game's own things are shown in the server's style named after the type,
 * [STYLES], when it defines one, so a player's name looks the same in every message, and like
 * every other value in [ACCENT] when it does not.
 */
internal object BuiltInPresentations {
    /** Joins the elements of a collection. */
    val SEPARATOR = JoinConfiguration.separator(text(", "))

    const val PLAYER = "player"
    const val ENTITY = "entity"
    const val ITEM = "item"
    const val LOCATION = "location"
    const val WORLD = "world"

    /** The style of every value whose type has none on this server: what a message inserts stands out by itself. */
    const val ACCENT = "accent"

    /** The style names the presentations here ask for. */
    val STYLES = setOf(PLAYER, ENTITY, ITEM, LOCATION, WORLD)

    /** Asked after every specific built-in, so a Player or an ItemStack that is also Translatable keeps its own. */
    private val generic = Presenters().apply {
        register(Translatable::class) { value -> presentation { component { Component.translatable(value) } } }
    }

    /**
     * Iterables and object arrays, which are not registered so that a plugin's own presentation of
     * a collection type wins: the elements are shown joined with [SEPARATOR], each as a value of
     * its own.
     */
    class Elements(val elements: List<Any?>) {
        val properties: Map<String, () -> Any?> = mapOf("size" to { elements.size })
    }

    fun elements(value: Any) = when (value) {
        is Iterable<*> -> Elements(value.toList())
        is Array<*> -> Elements(value.asList())
        else -> null
    }

    val presenters = Presenters(generic).apply {
        register(Component::class) { component ->
            presentation {
                component { component }
                property("plain") { PlainTextComponentSerializer.plainText().serialize(component) }
            }
        }
        register(Entity::class, ENTITY) { entity ->
            presentation {
                component { entity.name() }
                property("name") { entity.name() }
                property("type") { entity.type.key.value() }
                property("uuid") { entity.uniqueId }
                property("location") { entity.location }
                property("world") { entity.world }
            }
        }
        register(Player::class, PLAYER) { player ->
            presentation {
                component { player.displayName() }
                property("name") { player.name }
                property("displayName") { player.displayName() }
                property("uuid") { player.uniqueId }
                property("level") { player.level }
                property("health") { player.health }
                property("foodLevel") { player.foodLevel }
                property("gameMode") { player.gameMode.name.lowercase() }
                property("ping") { player.ping }
                property("location") { player.location }
                property("world") { player.world }
            }
        }
        register(OfflinePlayer::class, PLAYER) { player ->
            presentation {
                component { text(player.name ?: player.uniqueId.toString()) }
                property("name") { player.name }
                property("uuid") { player.uniqueId }
            }
        }
        register(ItemStack::class, ITEM) { item ->
            presentation {
                component { item.effectiveName() }
                property("name") { item.effectiveName() }
                property("hoverable") { item.displayName() }
                property("type") { item.type.key.value() }
                property("amount") { item.amount }
                property("maxStackSize") { item.maxStackSize }
            }
        }
        register(Location::class, LOCATION) { location ->
            presentation {
                component { text("${location.blockX}, ${location.blockY}, ${location.blockZ}") }
                property("x") { location.x }
                property("y") { location.y }
                property("z") { location.z }
                property("blockX") { location.blockX }
                property("blockY") { location.blockY }
                property("blockZ") { location.blockZ }
                property("yaw") { location.yaw }
                property("pitch") { location.pitch }
                property("world") { location.world }
            }
        }
        register(World::class, WORLD) { world ->
            presentation {
                component { text(world.name) }
                property("name") { world.name }
                property("environment") { world.environment.name.lowercase() }
                property("time") { world.time }
                property("players") { world.players.size }
            }
        }
    }
}
