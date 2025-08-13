package xyz.bellbot.chestnut.listeners

import org.bukkit.Material
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

    /**
     * Listens for container inventory open/close events and sends formatted webhooks
     * for trackers bound to that block location.
     *
     * Responsibilities:
     * - Locate trackers bound to the container's block location
     * - Apply per-tracker debounce
     * - Render templates (optionally including the <items> placeholder)
     * - Enqueue a webhook payload
     */

    private companion object {
        private const val TICK_MILLIS = 50L
    }

    /**
     * Returns true if either the body or footer template references the <items> placeholder.
     */
    private fun needsItems(bodyTemplate: String?, footerTemplate: String): Boolean {
        fun hasItems(s: String?): Boolean {
            return s?.contains("<items>", ignoreCase = true) == true
        }

        return hasItems(bodyTemplate) || hasItems(footerTemplate)
    }

    /**
     * Handles InventoryOpenEvent and emits an "open" notification for matching trackers.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val location = event.inventory.location ?: return
        val block = location.block
        if (block.type == Material.LECTERN) return // Don't notify for lecterns', use @onLecternInventoryOpen instead.
        val world = location.world?.name ?: return

        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ

        val player = event.player

        val matches = store.byLocationAndTrigger(world, x, y, z, Trigger.INVENTORY_OPEN)
        for (tracker in matches) {
            if (!tracker.options.enabled) continue
            if (tracker.options.disabledEvents.contains("open")) continue

            val now = System.currentTimeMillis()

            val debounceTicks = tracker.options.debounceTicks.coerceAtLeast(0)
            val debounceMs = debounceTicks * TICK_MILLIS
            if (now - tracker.lastEventAtTick < debounceMs) continue

            tracker.lastEventAtTick = now

            val bodyTemplate = tracker.templates["open"]
            val includeItems = needsItems(bodyTemplate, config.embedFooter)

            val renderOptions = TemplateRenderer.RenderOptions(
                user = player.name,
                uuid = player.uniqueId.toString(),
                includeItems = includeItems,
                inventory = if (includeItems) event.inventory else null,
                testPrefix = null,
            )

            val rendered = TemplateRenderer.render(
                bodyTemplate,
                tracker,
                "open",
                renderOptions,
            )

            val itemsSummary = if (includeItems) {
                TemplateRenderer.render(
                    "<items>",
                    tracker,
                    "open",
                    TemplateRenderer.RenderOptions(
                        includeItems = true,
                        inventory = event.inventory,
                    ),
                )
            } else {
                null
            }

            webhook.enqueue(tracker, rendered, "open", itemsSummary)
        }
    }

    /**
     * Handles InventoryCloseEvent and emits a "close" notification for matching trackers.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val location = event.inventory.location ?: return
        val block = location.block
        if (block.type == Material.LECTERN) return
        val world = location.world?.name ?: return

        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ

        val player = event.player

        val matches = store.byLocationAndTrigger(world, x, y, z, Trigger.INVENTORY_OPEN)
        for (tracker in matches) {
            if (!tracker.options.enabled) continue
            if (tracker.options.disabledEvents.contains("close")) continue

            val now = System.currentTimeMillis()

            val debounceTicks = tracker.options.debounceTicks.coerceAtLeast(0)
            val debounceMs = debounceTicks * TICK_MILLIS
            if (now - tracker.lastEventAtTick < debounceMs) continue

            tracker.lastEventAtTick = now

            val bodyTemplate = tracker.templates["close"]
            val includeItems = needsItems(bodyTemplate, config.embedFooter)

            val renderOptions = TemplateRenderer.RenderOptions(
                user = player.name,
                uuid = player.uniqueId.toString(),
                includeItems = includeItems,
                inventory = if (includeItems) event.inventory else null,
                testPrefix = null,
            )

            val rendered = TemplateRenderer.render(
                bodyTemplate,
                tracker,
                "close",
                renderOptions,
            )

            val itemsSummary = if (includeItems) {
                TemplateRenderer.render(
                    "<items>",
                    tracker,
                    "close",
                    TemplateRenderer.RenderOptions(
                        includeItems = true,
                        inventory = event.inventory,
                    ),
                )
            } else {
                null
            }

            webhook.enqueue(tracker, rendered, "close", itemsSummary)
        }
    }
}
