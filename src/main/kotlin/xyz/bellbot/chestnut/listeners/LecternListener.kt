package xyz.bellbot.chestnut.listeners

import io.papermc.paper.event.player.PlayerLecternPageChangeEvent
import org.bukkit.Material
import org.bukkit.block.Lectern
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.ChestnutConfig
import xyz.bellbot.chestnut.model.Trigger
import xyz.bellbot.chestnut.store.TrackersStore
import xyz.bellbot.chestnut.util.TemplateRenderer
import xyz.bellbot.chestnut.webhook.WebhookSender

/**
 * Listens for lectern interactions and page changes, and sends webhooks for matching trackers.
 *
 * Responsibilities:
 * - Find trackers bound to the lectern's block location
 * - Apply per-tracker debounce (in ticks converted to milliseconds)
 * - Render templates with contextual data (user, page, book metadata)
 * - Enqueue the webhook payload
 */
class LecternListener(
    private val plugin: JavaPlugin,
    private val store: TrackersStore,
    private val config: ChestnutConfig,
    private val webhook: WebhookSender,
) : Listener {

    private companion object {
        private const val TICK_MILLIS = 50L
    }

    /**
     * Finds trackers at the given location for Trigger.LECTERN, applies debounce,
     * renders the corresponding template, and enqueues a webhook.
     *
     * optsBuilder allows callers to customize RenderOptions per event.
     */
    private fun matchAndSend(
        world: String,
        x: Int,
        y: Int,
        z: Int,
        eventName: String,
        optsBuilder: (TemplateRenderer.RenderOptions) -> TemplateRenderer.RenderOptions,
    ) {
        val matches = store.byLocationAndTrigger(world, x, y, z, Trigger.LECTERN)

        for (tracker in matches) {
            if (!tracker.options.enabled) continue

            val now = System.currentTimeMillis()
            val debounceTicks = tracker.options.debounceTicks.coerceAtLeast(0)
            val debounceMs = debounceTicks * TICK_MILLIS
            if (now - tracker.lastEventAtTick < debounceMs) continue

            tracker.lastEventAtTick = now

            val renderOptions = optsBuilder(
                TemplateRenderer.RenderOptions(testPrefix = null)
            )

            val rendered = TemplateRenderer.render(
                tracker.templates[eventName],
                tracker,
                eventName,
                renderOptions,
            )

            webhook.enqueue(tracker, rendered, eventName)
        }
    }

    /**
     * Handles page change events from Paper API and emits a "page_change" notification.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPageChange(event: PlayerLecternPageChangeEvent) {
        val block = event.lectern.block

        val location = block.location ?: return
        val world = location.world?.name ?: return

        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ

        val newPage = event.newPage

        val lecternState = block.state as? Lectern
        val inventoryItem = lecternState?.inventory?.getItem(0)
        val hasBook = inventoryItem != null && !inventoryItem.type.isAir

        val meta = inventoryItem?.itemMeta as? BookMeta
        val title = meta?.title ?: "Book"
        val author = meta?.author ?: ""
        val pages = meta?.pageCount ?: 0

        matchAndSend(world, x, y, z, "page_change") { opts ->
            opts.copy(
                user = event.player.name,
                uuid = event.player.uniqueId.toString(),
                page = (newPage + 1), // 1-based for users
                bookTitle = title,
                bookAuthor = author,
                bookPages = pages,
                hasBook = hasBook,
            )
        }
    }

    /**
     * Detects book insert/remove or "open" interactions via a one-tick-delayed state comparison.
     *
     * We capture the lectern state before the click, then read it again next tick to see if a
     * book was inserted or removed. If the presence didn't change but a book exists, we treat it
     * as an "open" event.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteractLectern(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (block.type != Material.LECTERN) return

        val location = block.location ?: return
        val world = location.world?.name ?: return

        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ

        val beforeLectern = block.state as? Lectern ?: return
        val beforeItem = beforeLectern.inventory.getItem(0)
        val beforeHasBook = beforeItem != null && !beforeItem.type.isAir

        val userName = event.player?.name ?: ""
        val userUuid = event.player?.uniqueId?.toString() ?: ""

        // Read after one tick to allow the state to update
        plugin.server.scheduler.runTask(plugin, Runnable {
            val afterLectern = block.state as? Lectern ?: return@Runnable
            val afterItem = afterLectern.inventory.getItem(0)
            val afterHasBook = afterItem != null && !afterItem.type.isAir

            if (beforeHasBook == afterHasBook) {
                // Heuristic "open": interaction did not change book presence, but a book is present
                if (afterHasBook) {
                    val meta = afterItem?.itemMeta as? BookMeta
                    val title = meta?.title ?: "Book"
                    val author = meta?.author ?: ""
                    val pages = meta?.pageCount ?: 0

                    matchAndSend(world, x, y, z, "open") { opts ->
                        opts.copy(
                            user = userName,
                            uuid = userUuid,
                            page = 1,
                            bookTitle = title,
                            bookAuthor = author,
                            bookPages = pages,
                            hasBook = true,
                        )
                    }
                }
                return@Runnable
            }

            if (afterHasBook) {
                val meta = afterItem?.itemMeta as? BookMeta
                val title = meta?.title ?: "Book"
                val author = meta?.author ?: ""
                val pages = meta?.pageCount ?: 0

                matchAndSend(world, x, y, z, "insert_book") { opts ->
                    opts.copy(
                        user = userName,
                        uuid = userUuid,
                        page = 1,
                        bookTitle = title,
                        bookAuthor = author,
                        bookPages = pages,
                        hasBook = true,
                    )
                }
            } else {
                val meta = beforeItem?.itemMeta as? BookMeta
                val title = meta?.title ?: "Book"
                val author = meta?.author ?: ""
                val pages = meta?.pageCount ?: 0

                matchAndSend(world, x, y, z, "remove_book") { opts ->
                    opts.copy(
                        user = userName,
                        uuid = userUuid,
                        page = 1,
                        bookTitle = title,
                        bookAuthor = author,
                        bookPages = pages,
                        hasBook = false,
                    )
                }
            }
        })
    }
}
