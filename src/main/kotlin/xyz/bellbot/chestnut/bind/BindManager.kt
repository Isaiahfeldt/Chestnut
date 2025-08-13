package xyz.bellbot.chestnut.bind

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.ChestnutConfig
import xyz.bellbot.chestnut.model.Tracker
import xyz.bellbot.chestnut.model.TrackerOptions
import xyz.bellbot.chestnut.model.Trigger
import xyz.bellbot.chestnut.store.TrackersStore
import xyz.bellbot.chestnut.triggers.TriggerRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages bind and rebind sessions for players.
 *
 * Players can start a session to bind a Tracker to a block by right-clicking in the world.
 * This class also listens for PlayerInteractEvent to capture the click and then persists
 * the tracker's location and related data.
 */
class BindManager(
    private val plugin: JavaPlugin,
    private val store: TrackersStore,
    private val config: ChestnutConfig,
) : Listener {

    /**
     * Starts a new bind session for the given player and tracker name/trigger.
     */
    data class Session(
        val name: String,
        val trigger: Trigger,
        val owner: UUID,
        val started: Long,
        val rebind: Boolean
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()

    private val disconnectNotices = ConcurrentHashMap<UUID, Session>()

    private companion object {
        // 15 seconds in server ticks
        private const val TIMEOUT_TICKS = 20L * 15

        // 2 seconds in server ticks
        private const val REJOIN_NOTICE_TICKS = 20L * 3
    }

    /**
     * Starts a new bind session for the given player and tracker name/trigger.
     */
    fun start(player: Player, name: String, trigger: Trigger) {
        val session = Session(
            name,
            trigger,
            player.uniqueId,
            System.currentTimeMillis(),
            rebind = false
        )

        val replaced = sessions.put(player.uniqueId, session)
        if (replaced != null) player.sendMessage("§eReplacing previous bind session.")

        player.sendMessage("§aRight-click a block to bind '$name'… (15s)")

        // Schedule timeout message
        scheduleTimeout(player, session)
    }

    /**
     * Starts a 'rebind session' (moving an existing tracker to a new block location).
     */
    fun startRebind(player: Player, name: String) {
        val existing = store.get(name) ?: run {
            player.sendMessage("§cTracker '$name' not found.")
            return
        }

        val session = Session(
            name,
            existing.trigger,
            player.uniqueId,
            System.currentTimeMillis(),
            rebind = true
        )

        val replaced = sessions.put(player.uniqueId, session)
        if (replaced != null) player.sendMessage("§eReplacing previous bind/rebind session.")

        player.sendMessage("§aRight-click the new block location for '$name'… (15s)")

        // Schedule timeout message
        scheduleTimeout(player, session)
    }

    /**
     * Cancels any in-progress bind/rebind session for the given player.
     */
    fun cancel(player: Player) {
        sessions.remove(player.uniqueId)
    }

    /**
     * Returns true if the player currently has an active session.
     */
    fun hasSession(player: Player): Boolean = sessions.containsKey(player.uniqueId)

    /**
     * Captures the player's right-click on a block to either bind a new tracker
     * or rebind an existing one to a new location.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        val session = sessions[event.player.uniqueId] ?: return

        val block = event.clickedBlock
        if (block == null || !event.action.isRightClick) return

        // Prevent opening chests, lecterns, etc., while binding.
        event.isCancelled = true

        val loc = block.location ?: return
        val world = loc.world?.name ?: return

        val x = loc.blockX
        val y = loc.blockY
        val z = loc.blockZ

        if (session.rebind) {
            val tracker = store.get(session.name)
            if (tracker == null) {
                event.player.sendMessage("§cTracker '${session.name}' no longer exists.")
            } else {
                tracker.world = world
                tracker.x = x
                tracker.y = y
                tracker.z = z
                tracker.blockType = block.type.name

                store.putAndSave(tracker)
                event.player.sendMessage("§aRebound '${session.name}' to ${block.type.name} at `$x $y $z`.")
            }

            sessions.remove(event.player.uniqueId)
            return
        }

        // Create a new tracker bound to the clicked block
        val templates = TriggerRegistry
            .descriptor(session.trigger)
            .defaultTemplates
            .toMutableMap()

        val options = TrackerOptions(
            enabled = true,
            debounceTicks = config.defaultDebounceTicks,
            ratelimitPerMinute = 0
        )
        val tracker = Tracker(
            session.name,
            session.trigger,
            world,
            x, y, z,
            templates,
            options,
            session.owner
        )

        tracker.blockType = block.type.name

        store.putAndSave(tracker)
        sessions.remove(event.player.uniqueId)

        event.player.sendMessage("§aTracker '${session.name}' bound to ${block.type.name} at `$x $y $z`.")
    }

    /**
     * Schedules a timeout for the provided session. If the session is still active after
     * TIMEOUT_TICKS, it is removed and the player is informed.
     */
    private fun scheduleTimeout(player: Player, session: Session) {
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (sessions[player.uniqueId] == session) {
                    sessions.remove(player.uniqueId)

                    val label = if (session.rebind) "Rebind" else "Bind"
                    player.sendMessage("§c$label session for '${session.name}' timed out.")
                }
            },
            TIMEOUT_TICKS,
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) {
        val session = sessions.remove(event.player.uniqueId) ?: return

        disconnectNotices[event.player.uniqueId] = session
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onJoin(event: PlayerJoinEvent) {
        val session = disconnectNotices.remove(event.player.uniqueId) ?: return

        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                val player = plugin.server.getPlayer(event.player.uniqueId) ?: return@Runnable

                if (session.rebind) {
                    player.sendMessage("§eWhoops — you poofed! Rebind for '${session.name}' cancelled, but your tracker’s safe and sound.")
                } else {
                    player.sendMessage("§eBind for '${session.name}' was cancelled because you disconnected.")
                }
            },
            REJOIN_NOTICE_TICKS,
        )
    }
}
