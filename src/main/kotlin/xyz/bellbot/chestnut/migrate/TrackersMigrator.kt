package xyz.bellbot.chestnut.migrate

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Upgrades trackers.yml to newer canonical trigger ids and fixes common typos.
 *
 * Rewrites only the "trigger" field of each tracker entry:
 *  - inventory_open, INVENTORY_OPEN, inventory, container, inv -> storage
 *  - torch, torch_toggle, TORCH_TOGGLE -> redstone_torch
 *  - lecturn -> lectern
 */
object TrackersMigrator {
    fun migrateIfNeeded(plugin: JavaPlugin): Int {
        val file = File(plugin.dataFolder, "trackers.yml")
        if (!file.exists()) return 0

        val yml = YamlConfiguration.loadConfiguration(file)
        val section = yml.getConfigurationSection("trackers") ?: return 0
        var changes = 0
        for (name in section.getKeys(false)) {
            val tSec = section.getConfigurationSection(name) ?: continue
            val triggerRaw = (tSec.getString("trigger") ?: "").trim()
            val lowered = triggerRaw.lowercase()
            val mapped = mapTrigger(lowered)
            if (mapped != null && mapped != triggerRaw) {
                tSec.set("trigger", mapped)
                changes++
            }
            // Fix common typo in id field itself if users named a tracker "lecturn" we do not change the name.
        }
        if (changes > 0) yml.save(file)
        return changes
    }

    private fun mapTrigger(lowered: String): String? = when (lowered) {
        "inventory_open", "inventory", "container", "inv", "inventory-open", "inventoryopen", "inventoryopen_event", "inventory_open_event" -> "storage"
        "torch", "torch_toggle", "torch-toggle", "torchtoggle" -> "redstone_torch"
        "lecturn" -> "lectern"
        else -> null
    }
}
