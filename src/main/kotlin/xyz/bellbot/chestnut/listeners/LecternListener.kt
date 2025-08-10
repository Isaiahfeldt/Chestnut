package xyz.bellbot.chestnut.listeners

import io.papermc.paper.event.player.PlayerLecternPageChangeEvent
import org.bukkit.Material
import org.bukkit.block.Lectern
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.ChestnutConfig
import xyz.bellbot.chestnut.model.Trigger
import xyz.bellbot.chestnut.store.TrackersStore
import xyz.bellbot.chestnut.util.TemplateRenderer
import xyz.bellbot.chestnut.webhook.WebhookSender

class LecternListener(
    private val plugin: JavaPlugin,
    private val store: TrackersStore,
    private val config: ChestnutConfig,
    private val webhook: WebhookSender,
) : Listener {

    private fun matchAndSend(
        world: String,
        x: Int,
        y: Int,
        z: Int,
        eventName: String,
        optsBuilder: (TemplateRenderer.RenderOptions) -> TemplateRenderer.RenderOptions
    ) {
        for (t in store.all()) {
            if (t.trigger != Trigger.LECTERN) continue
            if (!t.options.enabled) continue
            if (t.world == world && t.x == x && t.y == y && t.z == z) {
                val now = System.currentTimeMillis()
                val debounceMs = (t.options.debounceTicks.coerceAtLeast(0) * 50L)
                if (now - t.lastEventAtTick < debounceMs) continue
                t.lastEventAtTick = now
                val rendered = TemplateRenderer.render(
                    t.templates[eventName],
                    t,
                    eventName,
                    optsBuilder(TemplateRenderer.RenderOptions(testPrefix = null))
                )
                webhook.enqueue(t, rendered, eventName)
            }
        }
    }

    // Page change (Paper API)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPageChange(e: PlayerLecternPageChangeEvent) {
        val block = e.lectern.block
        val loc = block.location ?: return
        val world = loc.world?.name ?: return
        val x = loc.blockX
        val y = loc.blockY
        val z = loc.blockZ
        val newPage = e.newPage
        val lecternState = block.state as? Lectern
        val invItem = lecternState?.inventory?.getItem(0)
        val hasBook = invItem != null && !invItem.type.isAir
        val meta = invItem?.itemMeta as? org.bukkit.inventory.meta.BookMeta
        val title = meta?.title ?: "Book"
        val author = meta?.author ?: ""
        val pages = meta?.pageCount ?: 0
        matchAndSend(world, x, y, z, "page_change") { opts ->
            opts.copy(
                user = e.player.name,
                uuid = e.player.uniqueId.toString(),
                page = (newPage + 1), // 1-based for users
                bookTitle = title,
                bookAuthor = author,
                bookPages = pages,
                hasBook = hasBook
            )
        }
    }

    // Insert/remove detection via interact + state delta
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteractLectern(e: PlayerInteractEvent) {
        val block = e.clickedBlock ?: return
        if (block.type != Material.LECTERN) return
        val loc = block.location ?: return
        val world = loc.world?.name ?: return
        val x = loc.blockX
        val y = loc.blockY
        val z = loc.blockZ
        val beforeLectern = block.state as? Lectern ?: return
        val beforeItem = beforeLectern.inventory.getItem(0)
        val beforeHasBook = beforeItem != null && !beforeItem.type.isAir
        // read after one tick to allow state change
        plugin.server.scheduler.runTask(plugin, Runnable {
            val afterLectern = block.state as? Lectern ?: return@Runnable
            val afterItem = afterLectern.inventory.getItem(0)
            val afterHasBook = afterItem != null && !afterItem.type.isAir
            if (beforeHasBook == afterHasBook) {
                // Heuristic "open": interaction with lectern did not change book presence, but a book is present
                if (afterHasBook) {
                    val meta = afterItem?.itemMeta as? org.bukkit.inventory.meta.BookMeta
                    val title = meta?.title ?: "Book"
                    val author = meta?.author ?: ""
                    val pages = meta?.pageCount ?: 0
                    matchAndSend(world, x, y, z, "open") { opts ->
                        opts.copy(
                            user = (e.player?.name ?: ""),
                            uuid = (e.player?.uniqueId?.toString() ?: ""),
                            page = 1,
                            bookTitle = title,
                            bookAuthor = author,
                            bookPages = pages,
                            hasBook = true
                        )
                    }
                }
                return@Runnable
            }
            if (afterHasBook) {
                val meta = afterItem?.itemMeta as? org.bukkit.inventory.meta.BookMeta
                val title = meta?.title ?: "Book"
                val author = meta?.author ?: ""
                val pages = meta?.pageCount ?: 0
                matchAndSend(world, x, y, z, "insert_book") { opts ->
                    opts.copy(
                        user = (e.player?.name ?: ""),
                        uuid = (e.player?.uniqueId?.toString() ?: ""),
                        page = 1,
                        bookTitle = title,
                        bookAuthor = author,
                        bookPages = pages,
                        hasBook = true
                    )
                }
            } else {
                val meta = beforeItem?.itemMeta as? org.bukkit.inventory.meta.BookMeta
                val title = meta?.title ?: "Book"
                val author = meta?.author ?: ""
                val pages = meta?.pageCount ?: 0
                matchAndSend(world, x, y, z, "remove_book") { opts ->
                    opts.copy(
                        user = (e.player?.name ?: ""),
                        uuid = (e.player?.uniqueId?.toString() ?: ""),
                        page = 1,
                        bookTitle = title,
                        bookAuthor = author,
                        bookPages = pages,
                        hasBook = false
                    )
                }
            }
        })
    }
}
