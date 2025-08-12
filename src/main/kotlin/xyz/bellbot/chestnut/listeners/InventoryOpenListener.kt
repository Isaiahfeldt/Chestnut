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

    private fun needsItems(bodyTemplate: String?, footerTemplate: String): Boolean {
        fun hasItems(s: String?): Boolean = s?.contains("<items>", ignoreCase = true) == true
        return hasItems(bodyTemplate) || hasItems(footerTemplate)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(e: InventoryOpenEvent) {
        val loc = e.inventory.location ?: return
        val world = loc.world?.name ?: return
        val x = loc.blockX
        val y = loc.blockY
        val z = loc.blockZ
        val player = e.player

        val matches = store.byLocationAndTrigger(world, x, y, z, Trigger.INVENTORY_OPEN)
        for (t in matches) {
            if (!t.options.enabled) continue
            val now = System.currentTimeMillis()
            val debounceMs = (t.options.debounceTicks.coerceAtLeast(0) * 50L)
            if (now - t.lastEventAtTick < debounceMs) continue
            t.lastEventAtTick = now
            val bodyTpl = t.templates["open"]
            val wantsItems = needsItems(bodyTpl, config.embedFooter)
            val includeItems = wantsItems
            val rendered = TemplateRenderer.render(
                bodyTpl,
                t,
                "open",
                TemplateRenderer.RenderOptions(
                    user = player.name,
                    uuid = player.uniqueId.toString(),
                    includeItems = includeItems,
                    inventory = if (includeItems) e.inventory else null,
                    testPrefix = null
                )
            )
            val itemsSummary = if (includeItems) TemplateRenderer.render(
                "<items>",
                t,
                "open",
                TemplateRenderer.RenderOptions(includeItems = true, inventory = e.inventory)
            ) else null
            webhook.enqueue(t, rendered, "open", itemsSummary)
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

        val matches = store.byLocationAndTrigger(world, x, y, z, Trigger.INVENTORY_OPEN)
        for (t in matches) {
            if (!t.options.enabled) continue
            val now = System.currentTimeMillis()
            val debounceMs = (t.options.debounceTicks.coerceAtLeast(0) * 50L)
            if (now - t.lastEventAtTick < debounceMs) continue
            t.lastEventAtTick = now
            val bodyTpl = t.templates["close"]
            val wantsItems = needsItems(bodyTpl, config.embedFooter)
            val includeItems = wantsItems
            val rendered = TemplateRenderer.render(
                bodyTpl,
                t,
                "close",
                TemplateRenderer.RenderOptions(
                    user = player.name,
                    uuid = player.uniqueId.toString(),
                    includeItems = includeItems,
                    inventory = if (includeItems) e.inventory else null,
                    testPrefix = null
                )
            )
            val itemsSummary = if (includeItems) TemplateRenderer.render(
                "<items>",
                t,
                "close",
                TemplateRenderer.RenderOptions(includeItems = true, inventory = e.inventory)
            ) else null
            webhook.enqueue(t, rendered, "close", itemsSummary)
        }
    }
}
