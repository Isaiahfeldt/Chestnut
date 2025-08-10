package xyz.bellbot.chestnut.listeners

import org.bukkit.Material
import org.bukkit.block.data.Lightable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.ChestnutConfig
import xyz.bellbot.chestnut.model.Trigger
import xyz.bellbot.chestnut.store.TrackersStore
import xyz.bellbot.chestnut.util.TemplateRenderer
import xyz.bellbot.chestnut.webhook.WebhookSender

class TorchToggleListener(
    private val plugin: JavaPlugin,
    private val store: TrackersStore,
    private val config: ChestnutConfig,
    private val webhook: WebhookSender,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRedstone(e: BlockRedstoneEvent) {
        val b = e.block
        if (b.type != Material.REDSTONE_TORCH && b.type != Material.REDSTONE_WALL_TORCH) return
        val loc = b.location
        val world = loc.world?.name ?: return
        val x = loc.blockX
        val y = loc.blockY
        val z = loc.blockZ

        // Delay 1 tick to read final state
        plugin.server.scheduler.runTask(plugin, Runnable {
            val data = b.blockData as? Lightable ?: return@Runnable
            val lit = data.isLit
            for (t in store.all()) {
                if (t.trigger != Trigger.TORCH_TOGGLE) continue
                if (!t.options.enabled) continue
                if (t.world == world && t.x == x && t.y == y && t.z == z) {
                    val last = t.lastTorchLit
                    if (last != null && last == lit) continue
                    t.lastTorchLit = lit

                    val now = System.currentTimeMillis()
                    val debounceMs = (t.options.debounceTicks.coerceAtLeast(0) * 50L)
                    if (now - t.lastEventAtTick < debounceMs) return@Runnable
                    t.lastEventAtTick = now

                    val eventName = if (lit) "on" else "off"
                    val rendered = TemplateRenderer.render(
                        t.templates[eventName],
                        t,
                        eventName,
                        TemplateRenderer.RenderOptions(
                            state = if (lit) "lit" else "unlit",
                            testPrefix = null
                        )
                    )
                    webhook.enqueue(t, rendered, eventName)
                }
            }
        })
    }
}
