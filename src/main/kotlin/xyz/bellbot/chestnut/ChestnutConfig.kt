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

    // Embed customization
    var embedColor: Int = 0xFFCC00.toInt()
    var embedFooter: String = "<trigger> @ <world> <x>,<y>,<z>"

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

        // Parse embedColor from config supporting hex ("#RRGGBB", "0xRRGGBB", or plain hex) and decimal
        val colorRaw: Any? = cfg.get("embedColor")
        embedColor = parseColor(colorRaw) ?: 0xFFCC00.toInt()

        embedFooter = cfg.getString("embedFooter", "<trigger> @ <world> <x>,<y>,<z>") ?: "<trigger> @ <world> <x>,<y>,<z>"
    }

    private fun parseColor(value: Any?): Int? {
        try {
            when (value) {
                null -> return null
                is Number -> return value.toInt()
                is String -> {
                    val s0 = value.trim()
                    if (s0.isEmpty()) return null
                    var s = s0
                    if (s.startsWith("#")) s = s.substring(1)
                    if (s.startsWith("0x", ignoreCase = true)) s = s.substring(2)

                    // If it's purely hex digits, parse as hex
                    if (s.matches(Regex("^[0-9A-Fa-f]{1,8}$"))) {
                        // Limit to RGB if 6 digits; allow ARGB/RGB forms
                        val parsed = s.toLong(16)
                        return parsed.toInt()
                    }

                    // Otherwise, try decimal integer string
                    return s0.toInt()
                }
                else -> return null
            }
        } catch (e: Exception) {
            plugin.logger.warning("Invalid embedColor '$value' in config.yml. Using default #FFCC00.")
            return null
        }
    }
}
