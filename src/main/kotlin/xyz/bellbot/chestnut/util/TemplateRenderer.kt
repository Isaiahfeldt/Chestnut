package xyz.bellbot.chestnut.util

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import xyz.bellbot.chestnut.model.Tracker
import xyz.bellbot.chestnut.model.Trigger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object TemplateRenderer {
    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun defaultTemplate(trigger: Trigger, event: String): String {
        val d = xyz.bellbot.chestnut.triggers.TriggerRegistry.descriptor(trigger)
        val key = event.lowercase(Locale.ROOT)
        return d.defaultTemplates[key] ?: "<name> event: <event>"
    }

    fun render(template: String?, tracker: Tracker, event: String, opts: RenderOptions): String {
        val base = template ?: defaultTemplate(tracker.trigger, event)
        val map = mutableMapOf<String, String>()
        map["name"] = tracker.title?.takeIf { it.isNotBlank() } ?: tracker.name
        map["trigger"] = tracker.trigger.name
        map["event"] = event
        map["world"] = tracker.world
        map["x"] = tracker.x.toString()
        map["y"] = tracker.y.toString()
        map["z"] = tracker.z.toString()
        map["time"] = LocalDateTime.now().format(isoFormatter)
        map["state"] = opts.state ?: ""
        map["user"] = opts.user ?: ""
        map["uuid"] = opts.uuid ?: ""
        map["items"] = if (opts.includeItems && opts.inventory != null) summarizeInventory(opts.inventory) else ""
        map["page"] = opts.page?.toString() ?: ""
        map["book_title"] = opts.bookTitle ?: ""
        map["book_author"] = opts.bookAuthor ?: ""
        map["book_pages"] = opts.bookPages?.toString() ?: ""
        map["has_book"] = opts.hasBook?.toString() ?: ""

        var out = base
        for ((k, v) in map) {
            out = out.replace("<$k>", v)
        }
        if (opts.testPrefix != null && opts.testPrefix.isNotEmpty()) {
            out = opts.testPrefix + out
        }
        if (out.length > 1900) out = out.substring(0, 1900)
        return out
    }

    private fun summarizeInventory(inv: Inventory): String {
        val counts = LinkedHashMap<String, Int>()
        for (i in 0 until inv.size) {
            val item: ItemStack = inv.getItem(i) ?: continue
            if (item.type.isAir) continue
            val key = item.type.key.key.replace('_', ' ').lowercase()
            counts[key] = (counts[key] ?: 0) + item.amount
        }
        if (counts.isEmpty()) return "(empty)"
        val parts = counts.entries.take(5).map { (name, c) -> "$c x $name" }
        val suffix = if (counts.size > 5) "…" else ""
        return parts.joinToString(", ") + suffix
    }

    data class RenderOptions(
        val user: String? = null,
        val uuid: String? = null,
        val state: String? = null,
        val includeItems: Boolean = false,
        val inventory: Inventory? = null,
        val testPrefix: String? = null,
        // Lectern-specific
        val page: Int? = null,
        val bookTitle: String? = null,
        val bookAuthor: String? = null,
        val bookPages: Int? = null,
        val hasBook: Boolean? = null,
    )
}
