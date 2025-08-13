package xyz.bellbot.chestnut

import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.bind.BindManager
import xyz.bellbot.chestnut.commands.TrackCommand
import xyz.bellbot.chestnut.listeners.InventoryOpenListener
import xyz.bellbot.chestnut.listeners.LecternListener
import xyz.bellbot.chestnut.listeners.TorchToggleListener
import xyz.bellbot.chestnut.store.TrackersStore
import xyz.bellbot.chestnut.webhook.WebhookSender

/**
 * Main plugin entry point for Chestnut.
 *
 * Responsibilities:
 * - Load and expose configuration (ChestnutConfig).
 * - Manage the persistent trackers store.
 * - Start/stop the webhook sender.
 * - Register listeners and commands.
 * - Schedule periodic autosaves.
 */
class Chestnut : JavaPlugin() {

    // Core components initialized during onEnable
    lateinit var configManager: ChestnutConfig
    lateinit var store: TrackersStore
    lateinit var webhook: WebhookSender
    lateinit var bindManager: BindManager

    private var autosaveTaskId: Int = -1

    /**
     * Called when the plugin is enabled.
     * Sets up configuration, storage, webhook, listeners, commands, and autosave.
     */
    override fun onEnable() {
        // Ensure default configuration file exists and load configuration
        saveDefaultConfig()
        configManager = ChestnutConfig(this)
        configManager.load()

        // Initialize a persistent store and load existing trackers
        store = TrackersStore(this)
        val loaded = store.load()

        // Start webhook sender
        webhook = WebhookSender(this, configManager)
        webhook.start()

        // Create a bind manager (also a listener)
        bindManager = BindManager(this, store, configManager)

        // Register listeners
        val pm = server.pluginManager
        pm.registerEvents(bindManager, this)
        pm.registerEvents(InventoryOpenListener(this, store, configManager, webhook), this)
        pm.registerEvents(TorchToggleListener(this, store, configManager, webhook), this)
        pm.registerEvents(LecternListener(this, store, configManager, webhook), this)

        // Register commands
        val track = TrackCommand(this, configManager, store, bindManager, webhook)

        getCommand("settracker")?.let {
            it.setExecutor(track)
            it.tabCompleter = track
        }
        getCommand("deltracker")?.let {
            it.setExecutor(track)
            it.tabCompleter = track
        }
        getCommand("edittracker")?.let {
            it.setExecutor(track)
            it.tabCompleter = track
        }
        getCommand("trackerlist")?.let {
            it.setExecutor(track)
            it.tabCompleter = track
        }
        getCommand("chestnut")?.let {
            it.setExecutor(track)
            it.tabCompleter = track
        }

        // Autosave every 30 seconds (async)
        autosaveTaskId = server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable {
                try {
                    store.save()
                } catch (e: Exception) {
                    logger.warning("Autosave failed: ${e.message}")
                }
            },
            20L * 30,
            20L * 30
        ).taskId

        // Warn if the webhook is not configured
        if (configManager.webhookUrl.isBlank()) {
            logger.warning("No webhookUrl configured. Set 'webhookUrl' in config.yml to enable Discord notifications.")
        }

        // Startup summary
        logger.info("Chestnut enabled. Loaded $loaded trackers. Webhook URL configured: ${configManager.webhookUrl.isNotBlank()}")
    }

    /**
     * Called when the plugin is disabled.
     * Saves state, stops services, and cancels an autosave task.
     */
    override fun onDisable() {
        // Attempt to persist state
        try {
            store.save()
        } catch (_: Exception) {
            // Ignore save errors during shutdown
        }

        // Attempt to stop webhook sender gracefully
        try {
            webhook.stopAndDrain(2500)
        } catch (_: Exception) {
            // Ignore shutdown errors during disabling
        }

        // Cancel an autosave task if scheduled
        if (autosaveTaskId != -1) {
            server.scheduler.cancelTask(autosaveTaskId)
        }

        logger.info("Chestnut disabled.")
    }
}
