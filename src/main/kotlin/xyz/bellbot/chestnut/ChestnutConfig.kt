package xyz.bellbot.chestnut

import org.bukkit.plugin.java.JavaPlugin

class ChestnutConfig(private val plugin: JavaPlugin) {
    var webhookUrl: String = ""
    var testPrefix: String = "[TEST] "
    var globalRateLimitPerMinute: Int = 120
    var defaultDebounceTicks: Int = 4
    var includeItemsByDefault: Boolean = false
    var enableTestCommand: Boolean = true
    var debug: Boolean = false

    fun load() {
        plugin.saveDefaultConfig()
        plugin.reloadConfig()
        val cfg = plugin.config
        webhookUrl = cfg.getString("webhookUrl", "") ?: ""
        testPrefix = cfg.getString("testPrefix", "[TEST] ") ?: "[TEST] "
        globalRateLimitPerMinute = cfg.getInt("globalRateLimitPerMinute", 120)
        defaultDebounceTicks = cfg.getInt("defaultDebounceTicks", 4)
        includeItemsByDefault = cfg.getBoolean("includeItemsByDefault", false)
        enableTestCommand = cfg.getBoolean("enableTestCommand", true)
        debug = cfg.getBoolean("debug", false)
    }
}
