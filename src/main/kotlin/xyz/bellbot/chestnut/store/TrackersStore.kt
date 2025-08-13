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

/**
 * Persists Tracker objects to trackers.yml and provides fast lookup and mutation operations.
 *
 * Also maintains an in-memory index keyed by world|x|y|z|TRIGGER for quick event dispatch.
 */
class TrackersStore(private val plugin: JavaPlugin) {
    // Backing file for persisted trackers
    private val file: File = File(plugin.dataFolder, "trackers.yml")
    // Primary map: tracker name -> Tracker
    private val trackersByName = ConcurrentHashMap<String, Tracker>()
    // Fast lookup index: world|x|y|z|TRIGGER -> list of trackers
    private val index = ConcurrentHashMap<String, MutableList<Tracker>>()

    fun getAllTrackers(): Collection<Tracker> {
        return trackersByName.values
    }

    fun getTrackerByName(name: String): Tracker? {
        return trackersByName[name]
    }

    fun isTrackerPresent(name: String): Boolean {
        return trackersByName.containsKey(name)
    }

    // Builds the composite index key used for fast lookup: world|x|y|z|TRIGGER
    private fun makeKey(world: String, x: Int, y: Int, z: Int, trigger: Trigger): String = "$world|$x|$y|$z|${'$'}{trigger.name}"

    /**
     * Ensures the tracker is present in the correct index bucket based on its world/coords/trigger.
     *
     * Moves it between buckets if its key is changed.
     */
    private fun indexTracker(t: Tracker) {
        val key = makeKey(t.world, t.x, t.y, t.z, t.trigger)

        if (t.indexKey == key) {
            return
        }

        // Remove from the previous bucket if present
        t.indexKey?.let { oldKey ->
            index[oldKey]?.remove(t)
        }

        val list = index.computeIfAbsent(key) {
            Collections.synchronizedList(mutableListOf())
        }

        if (!list.contains(t)) {
            list.add(t)
        }

        t.indexKey = key
    }

    /**
     * Removes the tracker from its current index bucket, if any.
     */
    private fun deindexTracker(t: Tracker) {
        t.indexKey?.let { oldKey ->
            index[oldKey]?.remove(t)
        }

        t.indexKey = null
    }

    /**
     * Returns trackers bound to the exact world/x/y/z and trigger.
     */
    fun byLocationAndTrigger(
        world: String,
        x: Int,
        y: Int,
        z: Int,
        trigger: Trigger
    ): List<Tracker> {
        val key = makeKey(world, x, y, z, trigger)
        val bucket = index[key]

        return bucket?.toList() ?: emptyList()
    }

    /**
     * Inserts or replaces the tracker, re-indexes it, and persists to disk.
     */
    fun putAndSave(tracker: Tracker) {
        trackersByName[tracker.name] = tracker

        indexTracker(tracker)

        save()
    }

    /**
     * Removes a tracker by name, de-indexes it if present, and persists to disk.
     */
    fun removeAndSave(name: String) {
        val removed = trackersByName.remove(name)

        if (removed != null) {
            deindexTracker(removed)
        }

        save()
    }

    /**
     * Renames a tracker if the new name is available; persists the change.
     */
    fun rename(oldName: String, newName: String): Boolean {
        if (trackersByName.containsKey(newName)) {
            return false
        }

        val tracker = trackersByName.remove(oldName) ?: return false

        tracker.name = newName
        trackersByName[newName] = tracker

        save()
        return true
    }

    /**
     * Loads trackers from trackers.yml into memory and rebuilds the index.
     *
     * Returns the number of trackers successfully loaded.
     */
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
                disabledEvents = (optSec?.getStringList("disabledEvents")?.map { it.lowercase() }?.toMutableSet()) ?: mutableSetOf(),
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

    /**
     * Saves all trackers to trackers.yml in a stable, readable order.
     */
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
            optSec.set("disabledEvents", t.options.disabledEvents.sorted())
            sec.set("owner", t.owner.toString())
        }
        yml.save(file)
    }
}
