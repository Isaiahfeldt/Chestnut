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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class TrackersStore(private val plugin: JavaPlugin) {
    private val file: File = File(plugin.dataFolder, "trackers.yml")
    private val trackersByName = ConcurrentHashMap<String, Tracker>()
    // Fast lookup index: world|x|y|z|TRIGGER -> list of trackers
    private val index = ConcurrentHashMap<String, MutableList<Tracker>>()

    fun all(): Collection<Tracker> = trackersByName.values
    fun get(name: String): Tracker? = trackersByName[name]
    fun exists(name: String): Boolean = trackersByName.containsKey(name)

    private fun makeKey(world: String, x: Int, y: Int, z: Int, trigger: Trigger): String = "$world|$x|$y|$z|${'$'}{trigger.name}"

    private fun indexTracker(t: Tracker) {
        val key = makeKey(t.world, t.x, t.y, t.z, t.trigger)
        if (t.indexKey == key) return
        // Remove from previous bucket if present
        t.indexKey?.let { old -> index[old]?.remove(t) }
        val list = index.computeIfAbsent(key) { Collections.synchronizedList(mutableListOf()) }
        if (!list.contains(t)) list.add(t)
        t.indexKey = key
    }

    private fun deindexTracker(t: Tracker) {
        t.indexKey?.let { old -> index[old]?.remove(t) }
        t.indexKey = null
    }

    fun byLocationAndTrigger(world: String, x: Int, y: Int, z: Int, trigger: Trigger): List<Tracker> =
        index[makeKey(world, x, y, z, trigger)]?.toList() ?: emptyList()

    fun putAndSave(tracker: Tracker) {
        trackersByName[tracker.name] = tracker
        indexTracker(tracker)
        save()
    }

    fun removeAndSave(name: String) {
        trackersByName.remove(name)?.let { deindexTracker(it) }
        save()
    }

    fun rename(oldName: String, newName: String): Boolean {
        if (trackersByName.containsKey(newName)) return false
        val t = trackersByName.remove(oldName) ?: return false
        t.name = newName
        trackersByName[newName] = t
        save()
        return true
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
            val trigger = triggerStr?.let { xyz.bellbot.chestnut.triggers.TriggerRegistry.resolve(it) ?: Trigger.fromString(it) }
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
                templates[key.lowercase()] = templatesSec.getString(key, "") ?: ""
            }
            val optSec = tSec.getConfigurationSection("options")
            val options = TrackerOptions(
                enabled = optSec?.getBoolean("enabled", true) ?: true,
                debounceTicks = optSec?.getInt("debounceTicks", 4) ?: 4,
                ratelimitPerMinute = optSec?.getInt("ratelimitPerMinute", 0) ?: 0,
            )
            val owner = try { UUID.fromString(ownerStr) } catch (e: Exception) { null }
            if (owner == null) {
                plugin.logger.warning("Invalid owner UUID for tracker '$name' - dropping")
                continue
            }
            val tracker = Tracker(name, trigger, world!!, x, y, z, templates, options, owner)
            tracker.title = tSec.getString("title", null)
            tracker.description = tSec.getString("description", null)
            tracker.blockType = tSec.getString("blockType", null)
            // Load per-event embed colors (ints) and thumbnails (urls)
            val colorsSec = tSec.getConfigurationSection("embedColors")
            colorsSec?.getKeys(false)?.forEach { key ->
                val raw = colorsSec.getString(key, null)
                if (raw != null) {
                    val parsed = raw.toIntOrNull()
                    if (parsed != null) tracker.embedColors[key.lowercase()] = parsed
                } else {
                    val intVal = colorsSec.getInt(key, Int.MIN_VALUE)
                    if (intVal != Int.MIN_VALUE) tracker.embedColors[key.lowercase()] = intVal
                }
            }
            val thumbsSec = tSec.getConfigurationSection("embedThumbnails")
            thumbsSec?.getKeys(false)?.forEach { key ->
                val url = thumbsSec.getString(key, null)
                if (!url.isNullOrBlank()) tracker.embedThumbnails[key.lowercase()] = url
            }
            trackersByName[name] = tracker
            indexTracker(tracker)
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
            // Save canonical lowercase trigger id
            sec.set("trigger", xyz.bellbot.chestnut.triggers.TriggerRegistry.descriptor(t.trigger).id)
            sec.set("world", t.world)
            sec.set("x", t.x)
            sec.set("y", t.y)
            sec.set("z", t.z)
            if (!t.title.isNullOrBlank()) sec.set("title", t.title)
            if (!t.description.isNullOrBlank()) sec.set("description", t.description)
            if (!t.blockType.isNullOrBlank()) sec.set("blockType", t.blockType)
            val templatesSec = sec.createSection("templates")
            for ((k, v) in t.templates) templatesSec.set(k, v)
            val colorsSec = sec.createSection("embedColors")
            for ((k, v) in t.embedColors) colorsSec.set(k, v)
            val thumbsSec = sec.createSection("embedThumbnails")
            for ((k, v) in t.embedThumbnails) thumbsSec.set(k, v)
            val optSec = sec.createSection("options")
            optSec.set("enabled", t.options.enabled)
            optSec.set("debounceTicks", t.options.debounceTicks)
            optSec.set("ratelimitPerMinute", t.options.ratelimitPerMinute)
            sec.set("owner", t.owner.toString())
        }
        yml.save(file)
    }
}
