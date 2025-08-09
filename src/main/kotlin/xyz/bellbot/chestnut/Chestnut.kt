package xyz.bellbot.chestnut

import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.bind.BindManager
import xyz.bellbot.chestnut.commands.TrackCommand
import xyz.bellbot.chestnut.listeners.InventoryOpenListener
import xyz.bellbot.chestnut.listeners.TorchToggleListener
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

        // Commands
        val track = TrackCommand(this, configManager, store, bindManager, webhook)
        getCommand("track")?.setExecutor(track)
        getCommand("track")?.tabCompleter = track

        // Autosave every 30s async
        autosaveTaskId = server.scheduler.runTaskTimerAsynchronously(this, Runnable {
            try { store.save() } catch (e: Exception) { logger.warning("Autosave failed: ${e.message}") }
        }, 20L * 30, 20L * 30).taskId

        logger.info("Chestnut enabled. Loaded $loaded trackers. Webhook URL configured: ${configManager.webhookUrl.isNotBlank()}")
    }

    override fun onDisable() {
        try { store.save() } catch (_: Exception) {}
        try { webhook.stopAndDrain(2500) } catch (_: Exception) {}
        if (autosaveTaskId != -1) server.scheduler.cancelTask(autosaveTaskId)
        logger.info("Chestnut disabled.")
    }
}
