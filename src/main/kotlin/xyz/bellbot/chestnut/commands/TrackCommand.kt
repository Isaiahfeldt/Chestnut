package xyz.bellbot.chestnut.commands

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.ChestnutConfig
import xyz.bellbot.chestnut.bind.BindManager
import xyz.bellbot.chestnut.model.Tracker
import xyz.bellbot.chestnut.model.TrackerOptions
import xyz.bellbot.chestnut.model.Trigger
import xyz.bellbot.chestnut.store.TrackersStore
import xyz.bellbot.chestnut.util.TemplateRenderer
import xyz.bellbot.chestnut.webhook.WebhookSender
import java.util.*
import java.util.regex.Pattern

class TrackCommand(
    private val plugin: JavaPlugin,
    private val config: ChestnutConfig,
    private val store: TrackersStore,
    private val bind: BindManager,
    private val webhook: WebhookSender,
) : CommandExecutor, TabCompleter {

    private val namePattern = Pattern.compile("^[A-Za-z0-9 _.-]{1,32}$")
    private val placeholders = listOf(
        "<name>", "<trigger>", "<event>", "<world>", "<x>", "<y>", "<z>", "<time>", "<state>", "<user>", "<uuid>", "<items>"
    )

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args[0].equals("help", true)) {
            sendHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "add" -> {
                if (sender !is Player) { sender.sendMessage("§cOnly players can bind trackers."); return true }
                if (!sender.hasPermission("chestnut.use") && !sender.hasPermission("chestnut.admin")) { sender.sendMessage("§cNo permission."); return true }
                if (args.size < 3) { sender.sendMessage("§eUsage: /track add <name> <trigger>"); return true }
                val name = args[1]
                val trigger = Trigger.fromString(args[2]) ?: run { sender.sendMessage("§cUnknown trigger. Valid: ${Trigger.entries.joinToString(", ") { it.name }}"); return true }
                if (!namePattern.matcher(name).matches()) { sender.sendMessage("§cInvalid name. 1–32 chars: letters, digits, space, _ . -"); return true }
                if (store.exists(name)) { sender.sendMessage("§cTracker with that name already exists."); return true }
                bind.start(sender, name, trigger)
                return true
            }
            "msg" -> {
                if (args.size < 3) { sender.sendMessage("§eUsage: /track msg <name> <event> <template>"); return true }
                val name = args[1]
                val tracker = store.get(name) ?: run { sender.sendMessage("§cTracker not found."); return true }
                if (!canManage(sender, tracker)) { sender.sendMessage("§cYou don't own this tracker."); return true }
                val event = args[2].lowercase()
                if (!tracker.trigger.events.map { it.lowercase() }.contains(event)) { sender.sendMessage("§cInvalid event for ${tracker.trigger}: ${tracker.trigger.events.joinToString(", ")}"); return true }
                val template = if (args.size >= 4) joinTail(args, 3) else ""
                tracker.templates[event] = template
                store.putAndSave(tracker)
                sender.sendMessage("§aTemplate for '$event' updated for '${tracker.name}'.")
                return true
            }
            "list" -> {
                val list = store.all().sortedBy { it.name.lowercase() }
                if (list.isEmpty()) { sender.sendMessage("§7No trackers."); return true }
                var i = 1
                list.forEach { t ->
                    val status = if (t.options.enabled) "enabled" else "disabled"
                    sender.sendMessage("§7${i++}) §b${t.name} §7 ${t.trigger} §8${t.world} ${t.x} ${t.y} ${t.z} §7[$status]")
                }
                return true
            }
            "remove" -> {
                if (args.size < 2) { sender.sendMessage("§eUsage: /track remove <name>"); return true }
                val tracker = store.get(args[1]) ?: run { sender.sendMessage("§aTracker removed (not found)."); return true }
                if (!canManage(sender, tracker)) { sender.sendMessage("§cYou don't own this tracker."); return true }
                store.removeAndSave(tracker.name)
                sender.sendMessage("§aTracker '${tracker.name}' removed.")
                return true
            }
            "test" -> {
                if (!config.enableTestCommand) { sender.sendMessage("§cTest command is disabled."); return true }
                if (args.size < 3) { sender.sendMessage("§eUsage: /track test <name> <event>"); return true }
                val tracker = store.get(args[1]) ?: run { sender.sendMessage("§cTracker not found."); return true }
                if (!canManage(sender, tracker)) { sender.sendMessage("§cYou don't own this tracker."); return true }
                val event = args[2].lowercase()
                val template = tracker.templates[event]
                val player = sender as? Player
                val rendered = TemplateRenderer.render(
                    template,
                    tracker,
                    event,
                    TemplateRenderer.RenderOptions(
                        user = player?.name,
                        uuid = player?.uniqueId?.toString(),
                        includeItems = false,
                        inventory = null,
                        testPrefix = config.testPrefix
                    )
                )
                webhook.enqueue(tracker, rendered)
                sender.sendMessage("§aTest message enqueued.")
                return true
            }
            "set" -> {
                if (args.size < 4) { sender.sendMessage("§eUsage: /track set <name> <key> <value>"); return true }
                val tracker = store.get(args[1]) ?: run { sender.sendMessage("§cTracker not found."); return true }
                if (!canManage(sender, tracker)) { sender.sendMessage("§cYou don't own this tracker."); return true }
                when (args[2].lowercase()) {
                    "enabled" -> tracker.options.enabled = args[3].toBooleanStrictOrNull() ?: return badValue(sender)
                    "debounceticks" -> tracker.options.debounceTicks = args[3].toIntOrNull() ?: return badValue(sender)
                    "includeitems" -> tracker.options.includeItems = args[3].toBooleanStrictOrNull() ?: return badValue(sender)
                    "ratelimitperminute" -> tracker.options.ratelimitPerMinute = args[3].toIntOrNull() ?: return badValue(sender)
                    else -> { sender.sendMessage("§cUnknown key. Keys: enabled, debounceTicks, includeItems, ratelimitPerMinute"); return true }
                }
                store.putAndSave(tracker)
                sender.sendMessage("§aUpdated options for '${tracker.name}'.")
                return true
            }
            else -> sendHelp(sender)
        }
        return true
    }

    private fun badValue(sender: CommandSender): Boolean {
        sender.sendMessage("§cInvalid value.")
        return true
    }

    private fun joinTail(args: Array<out String>, start: Int): String {
        val raw = args.copyOfRange(start, args.size).joinToString(" ")
        return if ((raw.startsWith('"') && raw.endsWith('"')) || (raw.startsWith('\'') && raw.endsWith('\''))) raw.substring(1, raw.length - 1) else raw
    }

    private fun canManage(sender: CommandSender, tracker: Tracker): Boolean {
        if (sender.hasPermission("chestnut.admin")) return true
        if (sender !is Player) return false
        return sender.hasPermission("chestnut.use") && tracker.owner == sender.uniqueId
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§6Chestnut commands:")
        sender.sendMessage("§e/track add <name> <trigger> §7- Start bind session")
        sender.sendMessage("§e/track msg <name> <event> <template> §7- Set message template")
        sender.sendMessage("§e/track list §7- List trackers")
        sender.sendMessage("§e/track remove <name> §7- Remove tracker")
        if (config.enableTestCommand) sender.sendMessage("§e/track test <name> <event> §7- Send test message")
        sender.sendMessage("§e/track set <name> <key> <value> §7- Set options (enabled, debounceTicks, includeItems, ratelimitPerMinute)")
        sender.sendMessage("§7Placeholders: <name> <trigger> <event> <world> <x> <y> <z> <time> <state> <user> <uuid> <items>")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        fun filter(options: List<String>, prefix: String): MutableList<String> = options.filter { it.startsWith(prefix, ignoreCase = true) }.toMutableList()
        if (args.isEmpty()) return mutableListOf("add", "msg", "list", "remove", "test", "set", "help")
        return when (args.size) {
            1 -> filter(listOf("add", "msg", "list", "remove", "test", "set", "help"), args[0])
            2 -> when (args[0].lowercase()) {
                "msg", "remove", "test", "set" -> filter(store.all().map { it.name }, args[1])
                else -> mutableListOf()
            }
            3 -> when (args[0].lowercase()) {
                "add" -> filter(Trigger.entries.map { it.name }, args[2])
                "msg" -> {
                    val t = store.get(args[1]) ?: return mutableListOf()
                    filter(t.trigger.events, args[2])
                }
                "set" -> filter(listOf("enabled", "debounceTicks", "includeItems", "ratelimitPerMinute"), args[2])
                else -> mutableListOf()
            }
            4 -> when (args[0].lowercase()) {
                "msg" -> {
                    val token = args[3]
                    if (token.startsWith("<")) filter(placeholders, token) else mutableListOf()
                }
                "set" -> when (args[2].lowercase()) {
                    "enabled", "includeitems" -> filter(listOf("true", "false"), args[3])
                    else -> mutableListOf()
                }
                else -> mutableListOf()
            }
            else -> mutableListOf()
        }
    }
}
