package xyz.bellbot.chestnut.store

import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.model.Tracker
import xyz.bellbot.chestnut.model.TrackerOptions
import xyz.bellbot.chestnut.model.Trigger
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TrackersStore(private val plugin: JavaPlugin) {
    private val file: File = File(plugin.dataFolder, "trackers.yml")
    private val trackersByName = ConcurrentHashMap<String, Tracker>()

    fun all(): Collection<Tracker> = trackersByName.values
    fun get(name: String): Tracker? = trackersByName[name]
    fun exists(name: String): Boolean = trackersByName.containsKey(name)

    fun putAndSave(tracker: Tracker) {
        trackersByName[tracker.name] = tracker
        save()
    }

    fun removeAndSave(name: String) {
        trackersByName.remove(name)
        save()
    }

    fun load(): Int {
        if (!file.exists()) {
            plugin.dataFolder.mkdirs()
            file.writeText("trackers: {}\n")
        }
        val cfg: FileConfiguration = YamlConfiguration.loadConfiguration(file)
        val section = cfg.getConfigurationSection("trackers") ?: return 0
        var loaded = 0
        for (name in section.getKeys(false)) {
            val tSec = section.getConfigurationSection(name)
            if (tSec == null) {
                plugin.logger.warning("Malformed tracker entry for '$name' - skipping")
                continue
            }
            val triggerStr = tSec.getString("trigger")
            val trigger = triggerStr?.let { Trigger.fromString(it) }
            val world = tSec.getString("world")
            val x = tSec.getInt("x", Int.MIN_VALUE)
            val y = tSec.getInt("y", Int.MIN_VALUE)
            val z = tSec.getInt("z", Int.MIN_VALUE)
            val ownerStr = tSec.getString("owner")
            if (trigger == null || world.isNullOrBlank() || x == Int.MIN_VALUE || y == Int.MIN_VALUE || z == Int.MIN_VALUE || ownerStr.isNullOrBlank()) {
                plugin.logger.warning("Invalid tracker '$name' in trackers.yml - dropping")
                continue
            }
            val templates = mutableMapOf<String, String>()
            val templatesSec = tSec.getConfigurationSection("templates")
            templatesSec?.getKeys(false)?.forEach { key ->
                templates[key] = templatesSec.getString(key, "") ?: ""
            }
            val optSec = tSec.getConfigurationSection("options")
            val options = TrackerOptions(
                enabled = optSec?.getBoolean("enabled", true) ?: true,
                debounceTicks = optSec?.getInt("debounceTicks", 4) ?: 4,
                includeItems = optSec?.getBoolean("includeItems", false) ?: false,
                ratelimitPerMinute = optSec?.getInt("ratelimitPerMinute", 0) ?: 0,
            )
            val owner = try { UUID.fromString(ownerStr) } catch (e: Exception) { null }
            if (owner == null) {
                plugin.logger.warning("Invalid owner UUID for tracker '$name' - dropping")
                continue
            }
            val tracker = Tracker(name, trigger, world!!, x, y, z, templates, options, owner)
            trackersByName[name] = tracker
            loaded++
        }
        plugin.logger.info("Loaded $loaded trackers. Worlds loaded: ${Bukkit.getWorlds().size}")
        return loaded
    }

    fun save() {
        val yml = YamlConfiguration()
        val root = yml.createSection("trackers")
        trackersByName.values.sortedBy { it.name.lowercase() }.forEach { t ->
            val sec = root.createSection(t.name)
            sec.set("trigger", t.trigger.name)
            sec.set("world", t.world)
            sec.set("x", t.x)
            sec.set("y", t.y)
            sec.set("z", t.z)
            val templatesSec = sec.createSection("templates")
            for ((k, v) in t.templates) templatesSec.set(k, v)
            val optSec = sec.createSection("options")
            optSec.set("enabled", t.options.enabled)
            optSec.set("debounceTicks", t.options.debounceTicks)
            optSec.set("includeItems", t.options.includeItems)
            optSec.set("ratelimitPerMinute", t.options.ratelimitPerMinute)
            sec.set("owner", t.owner.toString())
        }
        yml.save(file)
    }
}
