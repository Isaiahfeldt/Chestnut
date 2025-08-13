package xyz.bellbot.chestnut

import org.bukkit.plugin.java.JavaPlugin

/**
 * Loads and exposes user-configurable settings from config.yml.
 *
 * All fields are public vars to allow read-only access by other components
 * after load() is called. Defaults are provided and used if values are missing.
 */
class ChestnutConfig(private val plugin: JavaPlugin) {

    // Core configuration
    var webhookUrl: String = ""
    var testPrefix: String = DEFAULT_TEST_PREFIX
    var globalRateLimitPerMinute: Int = DEFAULT_GLOBAL_RATELIMIT
    var defaultDebounceTicks: Int = DEFAULT_DEBOUNCE_TICKS
    var enableTestCommand: Boolean = DEFAULT_ENABLE_TEST_COMMAND
    var debug: Boolean = DEFAULT_DEBUG

    // Embed customization
    var embedColor: Int = DEFAULT_EMBED_COLOR
    var embedFooter: String = DEFAULT_EMBED_FOOTER

    /**
     * Loads the configuration from the disk and populates fields with validated values.
     * The default config file will be created on the first run.
     */
    fun load() {
        plugin.saveDefaultConfig()
        plugin.reloadConfig()

        val cfg = plugin.config

        webhookUrl = cfg.getString("webhookUrl", "") ?: ""
        testPrefix = cfg.getString("testPrefix", DEFAULT_TEST_PREFIX) ?: DEFAULT_TEST_PREFIX

        globalRateLimitPerMinute = cfg.getInt("globalRateLimitPerMinute", DEFAULT_GLOBAL_RATELIMIT)
        defaultDebounceTicks = cfg.getInt("defaultDebounceTicks", DEFAULT_DEBOUNCE_TICKS)

        enableTestCommand = cfg.getBoolean("enableTestCommand", DEFAULT_ENABLE_TEST_COMMAND)
        debug = cfg.getBoolean("debug", DEFAULT_DEBUG)

        // Parse embedColor supporting hex ("#RRGGBB", "0xRRGGBB", or plain hex) and decimal strings
        val colorRaw: Any? = cfg.get("embedColor")
        embedColor = parseColor(colorRaw) ?: DEFAULT_EMBED_COLOR

        embedFooter = cfg.getString("embedFooter", DEFAULT_EMBED_FOOTER) ?: DEFAULT_EMBED_FOOTER
    }

    /**
     * Parses a color value from config into an ARGB/RGB integer.
     *
     * Accepts:
     * - Number: returned as Int
     * - String: "#RRGGBB", "0xRRGGBB", "RRGGBB", or decimal integer
     * - null/other: returns null
     *
     * Returns null on invalid format and logs a warning, allowing the caller to apply default.
     */
    private fun parseColor(value: Any?): Int? {
        try {
            when (value) {
                null -> return null

                is Number -> return value.toInt()

                is String -> {
                    val s0 = value.trim()
                    if (s0.isEmpty()) {
                        return null
                    }

                    var s = s0
                    if (s.startsWith("#")) s = s.substring(1)
                    if (s.startsWith("0x", ignoreCase = true)) s = s.substring(2)

                    // If it's purely hex digits, parse as hex
                    if (s.matches(Regex("^[0-9A-Fa-f]{1,8}$"))) {
                        // Allow ARGB (8) or RGB (6) forms
                        val parsed = s.toLong(16)
                        return parsed.toInt()
                    }

                    // Otherwise, try decimal integer string
                    return s0.toInt()
                }

                else -> return null
            }
        } catch (_: Exception) {
            plugin.logger.warning("Invalid embedColor '$value' in config.yml. Using default #FFCC00.")
            return null
        }
    }

    private companion object {
        const val DEFAULT_TEST_PREFIX: String = "[TEST] "
        const val DEFAULT_GLOBAL_RATELIMIT: Int = 120
        const val DEFAULT_DEBOUNCE_TICKS: Int = 4
        const val DEFAULT_ENABLE_TEST_COMMAND: Boolean = true
        const val DEFAULT_DEBUG: Boolean = false

        // Embed defaults
        const val DEFAULT_EMBED_COLOR: Int = 0xFFCC00
        const val DEFAULT_EMBED_FOOTER: String = "<trigger> @ <world> <x>,<y>,<z>"
    }
}
