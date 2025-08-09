package xyz.bellbot.chestnut.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.ChestnutConfig
import xyz.bellbot.chestnut.model.Trigger
import xyz.bellbot.chestnut.store.TrackersStore
import xyz.bellbot.chestnut.util.TemplateRenderer
import xyz.bellbot.chestnut.webhook.WebhookSender

class InventoryOpenListener(
    private val plugin: JavaPlugin,
    private val store: TrackersStore,
    private val config: ChestnutConfig,
    private val webhook: WebhookSender,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(e: InventoryOpenEvent) {
        val loc = e.inventory.location ?: return
        val world = loc.world?.name ?: return
        val x = loc.blockX
        val y = loc.blockY
        val z = loc.blockZ
        val player = e.player

        for (t in store.all()) {
            if (t.trigger != Trigger.INVENTORY_OPEN) continue
            if (!t.options.enabled) continue
            if (t.world == world && t.x == x && t.y == y && t.z == z) {
                val now = System.currentTimeMillis()
                val debounceMs = (t.options.debounceTicks.coerceAtLeast(0) * 50L)
                if (now - t.lastEventAtTick < debounceMs) continue
                t.lastEventAtTick = now
                val rendered = TemplateRenderer.render(
                    t.templates["open"],
                    t,
                    "open",
                    TemplateRenderer.RenderOptions(
                        user = player.name,
                        uuid = player.uniqueId.toString(),
                        includeItems = t.options.includeItems,
                        inventory = e.inventory,
                        testPrefix = null
                    )
                )
                webhook.enqueue(t, rendered)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClose(e: InventoryCloseEvent) {
        val loc = e.inventory.location ?: return
        val world = loc.world?.name ?: return
        val x = loc.blockX
        val y = loc.blockY
        val z = loc.blockZ
        val player = e.player

        for (t in store.all()) {
            if (t.trigger != Trigger.INVENTORY_OPEN) continue
            if (!t.options.enabled) continue
            if (t.world == world && t.x == x && t.y == y && t.z == z) {
                val now = System.currentTimeMillis()
                val debounceMs = (t.options.debounceTicks.coerceAtLeast(0) * 50L)
                if (now - t.lastEventAtTick < debounceMs) continue
                t.lastEventAtTick = now
                val rendered = TemplateRenderer.render(
                    t.templates["close"],
                    t,
                    "close",
                    TemplateRenderer.RenderOptions(
                        user = player.name,
                        uuid = player.uniqueId.toString(),
                        includeItems = t.options.includeItems,
                        inventory = e.inventory,
                        testPrefix = null
                    )
                )
                webhook.enqueue(t, rendered)
            }
        }
    }
}
