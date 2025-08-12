package xyz.bellbot.chestnut

import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.bind.BindManager
import xyz.bellbot.chestnut.commands.TrackCommand
import xyz.bellbot.chestnut.listeners.InventoryOpenListener
import xyz.bellbot.chestnut.listeners.TorchToggleListener
import xyz.bellbot.chestnut.listeners.LecternListener
import xyz.bellbot.chestnut.store.TrackersStore
import xyz.bellbot.chestnut.webhook.WebhookSender

class Chestnut : JavaPlugin() {
    lateinit var configManager: ChestnutConfig
    lateinit var store: TrackersStore
    lateinit var webhook: WebhookSender
    lateinit var bindManager: BindManager

    private var autosaveTaskId: Int = -1

    override fun onEnable() {
        saveDefaultConfig()
        configManager = ChestnutConfig(this).also { it.load() }
        store = TrackersStore(this)
        val loaded = store.load()

        webhook = WebhookSender(this, configManager)
        webhook.start()

        bindManager = BindManager(this, store, configManager)

        // Register listeners
        server.pluginManager.registerEvents(bindManager, this)
        server.pluginManager.registerEvents(InventoryOpenListener(this, store, configManager, webhook), this)
        server.pluginManager.registerEvents(TorchToggleListener(this, store, configManager, webhook), this)
        server.pluginManager.registerEvents(LecternListener(this, store, configManager, webhook), this)

        // Commands
        val track = TrackCommand(this, configManager, store, bindManager, webhook)
        getCommand("settracker")?.setExecutor(track)
        getCommand("settracker")?.tabCompleter = track
        getCommand("deltracker")?.setExecutor(track)
        getCommand("deltracker")?.tabCompleter = track
        getCommand("edittracker")?.setExecutor(track)
        getCommand("edittracker")?.tabCompleter = track
        getCommand("trackerlist")?.setExecutor(track)
        getCommand("trackerlist")?.tabCompleter = track
        getCommand("chestnut")?.setExecutor(track)
        getCommand("chestnut")?.tabCompleter = track

        // Autosave every 30s async
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

        if (configManager.webhookUrl.isBlank()) {
            logger.warning("No webhookUrl configured. Set 'webhookUrl' in config.yml to enable Discord notifications.")
        }

        logger.info("Chestnut enabled. Loaded $loaded trackers. Webhook URL configured: ${configManager.webhookUrl.isNotBlank()}")
    }

    override fun onDisable() {
        try {
            store.save()
        } catch (_: Exception) {
            // ignored on shutdown
        }

        try {
            webhook.stopAndDrain(2500)
        } catch (_: Exception) {
            // ignored on shutdown
        }

        if (autosaveTaskId != -1) {
            server.scheduler.cancelTask(autosaveTaskId)
        }

        logger.info("Chestnut disabled.")
    }
}
