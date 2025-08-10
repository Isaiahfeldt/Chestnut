package xyz.bellbot.chestnut.bind

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.ChestnutConfig
import xyz.bellbot.chestnut.model.Tracker
import xyz.bellbot.chestnut.model.TrackerOptions
import xyz.bellbot.chestnut.model.Trigger
import xyz.bellbot.chestnut.store.TrackersStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BindManager(
    private val plugin: JavaPlugin,
    private val store: TrackersStore,
    private val config: ChestnutConfig,
) : Listener {

    data class Session(val name: String, val trigger: Trigger, val owner: UUID, val started: Long, val rebind: Boolean)

    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun start(player: Player, name: String, trigger: Trigger) {
        val s = Session(name, trigger, player.uniqueId, System.currentTimeMillis(), rebind = false)
        val replaced = sessions.put(player.uniqueId, s)
        if (replaced != null) player.sendMessage("§eReplacing previous bind session.")
        player.sendMessage("§aRight-click a block to bind '$name'… (60s)")
        // Schedule timeout message
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (sessions[player.uniqueId] == s) {
                sessions.remove(player.uniqueId)
                player.sendMessage("§cBind session for '$name' timed out.")
            }
        }, 20L * 60)
    }

    fun startRebind(player: Player, name: String) {
        val existing = store.get(name) ?: run {
            player.sendMessage("§cTracker '$name' not found.")
            return
        }
        val s = Session(name, existing.trigger, player.uniqueId, System.currentTimeMillis(), rebind = true)
        val replaced = sessions.put(player.uniqueId, s)
        if (replaced != null) player.sendMessage("§eReplacing previous bind/rebind session.")
        player.sendMessage("§aRight-click the new block location for '$name'… (60s)")
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (sessions[player.uniqueId] == s) {
                sessions.remove(player.uniqueId)
                player.sendMessage("§cRebind session for '$name' timed out.")
            }
        }, 20L * 60)
    }

    fun cancel(player: Player) {
        sessions.remove(player.uniqueId)
    }

    fun hasSession(player: Player): Boolean = sessions.containsKey(player.uniqueId)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(e: PlayerInteractEvent) {
        val s = sessions[e.player.uniqueId] ?: return
        val block = e.clickedBlock
        if (block == null || e.action.isRightClick == false) return
        e.isCancelled = true // prevent opening chest etc.

        val loc = block.location ?: return
        val world = loc.world?.name ?: return
        val x = loc.blockX
        val y = loc.blockY
        val z = loc.blockZ

        if (s.rebind) {
            val t = store.get(s.name)
            if (t == null) {
                e.player.sendMessage("§cTracker '${s.name}' no longer exists.")
            } else {
                t.world = world
                t.x = x
                t.y = y
                t.z = z
                t.blockType = block.type.name
                store.putAndSave(t)
                e.player.sendMessage("§aRebound '${s.name}' to ${block.type.name} at `$x $y $z`.")
            }
            sessions.remove(e.player.uniqueId)
            return
        }

        val templates = when (s.trigger) {
            Trigger.INVENTORY_OPEN -> mutableMapOf(
                "open" to "<user> opened <name> at <x>,<y>,<z>.",
                "close" to "<user> closed <name> at <x>,<y>,<z>."
            )
            Trigger.TORCH_TOGGLE -> mutableMapOf(
                "on" to "<name> has been lit!",
                "off" to "<name> has turned off."
            )
            Trigger.LECTERN -> mutableMapOf(
                "insert_book" to "<user> placed '<book_title>' on <name>.",
                "remove_book" to "<user> removed '<book_title>' from <name>.",
                "page_change" to "<user> turned to page <page> of '<book_title>' on <name>.",
                "open" to "<user> opened '<book_title>' on <name>."
            )
        }
        val options = TrackerOptions(
            enabled = true,
            debounceTicks = config.defaultDebounceTicks,
            includeItems = config.includeItemsByDefault,
            ratelimitPerMinute = 0
        )
        val tracker = Tracker(s.name, s.trigger, world, x, y, z, templates, options, s.owner)
        tracker.blockType = block.type.name
        store.putAndSave(tracker)
        sessions.remove(e.player.uniqueId)
        e.player.sendMessage("§aTracker '${s.name}' bound to ${block.type.name} at `$x $y $z`.")
    }
}
