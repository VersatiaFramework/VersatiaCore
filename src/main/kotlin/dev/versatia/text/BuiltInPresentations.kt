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

/** What is shown without any registration; a plugin's own registrations take precedence. */
internal object BuiltInPresentations {
    private val SEPARATOR = JoinConfiguration.separator(text(", "))

    /** Asked after every specific built-in, so a Player or an ItemStack that is also Translatable keeps its own. */
    private val generic = Presenters().apply {
        register(Translatable::class) { value -> presentation { component { Component.translatable(value) } } }
    }

    /**
     * Iterables and object arrays, which are not registered so that a plugin's own presentation of
     * a collection type wins: the elements joined with `, `, each shown by [element] with its index.
     */
    fun collection(value: Any, element: (Int, Any?) -> Component): Presentation? {
        val elements = when (value) {
            is Iterable<*> -> value.toList()
            is Array<*> -> value.asList()
            else -> return null
        }
        return presentation {
            component { Component.join(SEPARATOR, elements.mapIndexed(element)) }
            property("size") { elements.size }
        }
    }

    val presenters = Presenters(generic).apply {
        register(Component::class) { component ->
            presentation {
                component { component }
                property("plain") { PlainTextComponentSerializer.plainText().serialize(component) }
            }
        }
        register(Entity::class) { entity ->
            presentation {
                component { entity.name() }
                property("name") { entity.name() }
                property("type") { entity.type.key.value() }
                property("uuid") { entity.uniqueId }
                property("location") { entity.location }
                property("world") { entity.world }
            }
        }
        register(Player::class) { player ->
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
        register(OfflinePlayer::class) { player ->
            presentation {
                component { text(player.name ?: player.uniqueId.toString()) }
                property("name") { player.name }
                property("uuid") { player.uniqueId }
            }
        }
        register(ItemStack::class) { item ->
            presentation {
                component { item.effectiveName() }
                property("name") { item.effectiveName() }
                property("hoverable") { item.displayName() }
                property("type") { item.type.key.value() }
                property("amount") { item.amount }
                property("maxStackSize") { item.maxStackSize }
            }
        }
        register(Location::class) { location ->
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
        register(World::class) { world ->
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
