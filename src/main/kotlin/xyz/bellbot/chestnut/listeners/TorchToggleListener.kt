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

/**
 * Listens for redstone updates and emits events when a redstone torch toggles.
 *
 * The handler runs on MONITOR priority and ignores canceled events, waiting one
 * tick before reading the final block state so we observe the torch's settled
 * on/off value. Matching trackers at the block location are then notified via
 * the webhook queue, respecting debounce and enable flags.
 */
class TorchToggleListener(
    private val plugin: JavaPlugin,
    private val store: TrackersStore,
    private val config: ChestnutConfig,
    private val webhook: WebhookSender,
) : Listener {

    /**
     * Handles redstone updates for redstone torches only.
     *
     * - Filters for REDSTONE_TORCH or REDSTONE_WALL_TORCH.
     * - Delays one tick to read the final Lightable state.
     * - Notifies all trackers bound to the block location using Trigger.TORCH_TOGGLE.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRedstone(event: BlockRedstoneEvent) {
        val block = event.block
        val type = block.type

        // Only interested in redstone torches (including wall variant)
        if (type != Material.REDSTONE_TORCH && type != Material.REDSTONE_WALL_TORCH) {
            return
        }

        // Capture immutable coordinates now; we'll read the final lit state next tick
        val loc = block.location
        val world = loc.world?.name ?: return

        val x = loc.blockX
        val y = loc.blockY
        val z = loc.blockZ

        // Delay 1 tick to read the final state after all redstone updates settle
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                val data = block.blockData as? Lightable ?: return@Runnable
                val lit = data.isLit

                val matches = store.byLocationAndTrigger(
                    world,
                    x, y, z,
                    Trigger.TORCH_TOGGLE,
                )

                for (tracker in matches) {
                    // Respect tracker enabled flag
                    if (!tracker.options.enabled) continue

                    // Skip duplicate consecutive states
                    val last = tracker.lastTorchLit
                    if (last != null && last == lit) continue

                    tracker.lastTorchLit = lit

                    // Debounce (ticks -> milliseconds). If debounced, abort the entire Runnable
                    val now = System.currentTimeMillis()
                    val debounceMs = tracker.options.debounceTicks
                        .coerceAtLeast(0)
                        .times(50L)

                    if (now - tracker.lastEventAtTick < debounceMs) return@Runnable

                    tracker.lastEventAtTick = now

                    // Render and enqueue webhook payload
                    val eventName = if (lit) "on" else "off"
                    val rendered = TemplateRenderer.render(
                        tracker.templates[eventName],
                        tracker,
                        eventName,
                        TemplateRenderer.RenderOptions(
                            state = if (lit) "lit" else "unlit",
                            testPrefix = null,
                        ),
                    )

                    webhook.enqueue(tracker, rendered, eventName)
                }
            },
        )
    }
}
