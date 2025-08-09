package xyz.bellbot.chestnut.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import xyz.bellbot.chestnut.ChestnutConfig
import xyz.bellbot.chestnut.bind.BindManager
import xyz.bellbot.chestnut.model.Tracker
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
    private val corePlaceholders = listOf(
        "<name>", "<trigger>", "<event>", "<world>", "<x>", "<y>", "<z>", "<time>"
    )
    private fun availablePlaceholders(trigger: Trigger): List<String> {
        val extras = when (trigger) {
            Trigger.INVENTORY_OPEN -> listOf("<user>", "<uuid>", "<items>")
            Trigger.TORCH_TOGGLE -> listOf("<state>")
        }
        return corePlaceholders + extras
    }

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
                // If no template provided, show helper with placeholders and examples
                if (args.size == 3) {
                    sendPlaceholderHelper(sender, tracker, event)
                    return true
                }
                val template = joinTail(args, 3)
                tracker.templates[event] = template
                store.putAndSave(tracker)
                sender.sendMessage("§aTemplate for '$event' updated for '${tracker.name}'.")
                // Local preview after save (do not send to Discord)
                val player = sender as? Player
                val preview = TemplateRenderer.render(
                    template,
                    tracker,
                    event,
                    TemplateRenderer.RenderOptions(
                        user = player?.name,
                        uuid = player?.uniqueId?.toString(),
                        includeItems = false,
                        inventory = null,
                        testPrefix = ""
                    )
                )
                sender.sendMessage("§7Preview: §f$preview")
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
            "reload" -> {
                if (!sender.hasPermission("chestnut.admin")) { sender.sendMessage("§cNo permission."); return true }
                config.load()
                webhook.onConfigReload()
                val urlOk = if (config.webhookUrl.isNotBlank()) "§a✓" else "§c✗"
                sender.sendMessage("§aChestnut config reloaded. Webhook URL set: $urlOk§a. Embed color: ${config.embedColor}.")
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

    private fun sendPlaceholderHelper(sender: CommandSender, tracker: Tracker, event: String) {
        val ph = availablePlaceholders(tracker.trigger)
        val isPlayer = sender is Player
        if (isPlayer) {
            val p = sender as Player
            // Header
            p.sendMessage(Component.text("Placeholders for ${tracker.trigger} › ${event}", NamedTextColor.GOLD))
            // Chips line
            var line = Component.text("Click to insert: ", NamedTextColor.YELLOW)
            for (token in ph) {
                val suggest = "/track msg ${tracker.name} ${event} \"${token}\""
                val chip = Component.text("[ $token ]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand(suggest))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to prefill with $token", NamedTextColor.GRAY)))
                line = line.append(Component.text(" ", NamedTextColor.DARK_GRAY)).append(chip)
            }
            p.sendMessage(line)
            // Example templates
            val examples = buildExamples(tracker.trigger, event)
            for ((i, tpl) in examples.withIndex()) {
                val suggest = "/track msg ${tracker.name} ${event} \"${tpl}\""
                val btn = Component.text("[ Insert example ${i + 1} ]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.suggestCommand(suggest))
                    .hoverEvent(HoverEvent.showText(Component.text(tpl, NamedTextColor.GRAY)))
                p.sendMessage(btn)
            }
        } else {
            sender.sendMessage("§ePlaceholders: ${ph.joinToString(" ")}")
            val examples = buildExamples(tracker.trigger, event)
            examples.forEach { tpl -> sender.sendMessage("§7Example: /track msg ${tracker.name} ${event} \"$tpl\"") }
        }
    }

    private fun buildExamples(trigger: Trigger, event: String): List<String> {
        return when (trigger) {
            Trigger.INVENTORY_OPEN -> {
                if (event.equals("open", true)) listOf(
                    "<user> opened <name> at <x>,<y>,<z>.",
                    "<user> opened <name> — <items>"
                ) else listOf(
                    "<user> closed <name> at <x>,<y>,<z>.",
                    "<name> closed by <user> at <time>"
                )
            }
            Trigger.TORCH_TOGGLE -> listOf(
                "<name> has been <state>.",
                "<name> <event> at <time>"
            )
        }
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§6Chestnut commands:")
        sender.sendMessage("§e/track add <name> <trigger> §7- Start bind session")
        sender.sendMessage("§e/track msg <name> <event> <template> §7- Set message template")
        sender.sendMessage("§e/track list §7- List trackers")
        sender.sendMessage("§e/track remove <name> §7- Remove tracker")
        if (config.enableTestCommand) sender.sendMessage("§e/track test <name> <event> §7- Send test message")
        sender.sendMessage("§e/track set <name> <key> <value> §7- Set options (enabled, debounceTicks, includeItems, ratelimitPerMinute)")
        if (sender.hasPermission("chestnut.admin")) sender.sendMessage("§e/track reload §7- Reload config")
        sender.sendMessage("§7Inventory events: open, close · Torch events: on, off")
        sender.sendMessage("§7Placeholders: <name> <trigger> <event> <world> <x> <y> <z> <time> <state> <user> <uuid> <items>")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        fun filter(options: List<String>, prefix: String): MutableList<String> = options.filter { it.startsWith(prefix, ignoreCase = true) }.toMutableList()
        if (args.isEmpty()) return mutableListOf("add", "msg", "list", "remove", "test", "set", "reload", "help")
        if (args.size == 1) return filter(listOf("add", "msg", "list", "remove", "test", "set", "reload", "help"), args[0])
        when (args[0].lowercase()) {
            "add" -> {
                return when (args.size) {
                    2 -> mutableListOf() // free-form name
                    3 -> filter(Trigger.entries.map { it.name }, args[2])
                    else -> mutableListOf()
                }
            }
            "msg" -> {
                return when (args.size) {
                    2 -> filter(store.all().map { it.name }, args[1])
                    3 -> {
                        val t = store.get(args[1]) ?: return mutableListOf()
                        filter(t.trigger.events, args[2])
                    }
                    else -> {
                        val t = store.get(args[1]) ?: return mutableListOf()
                        val token = args.last()
                        val raw = token.trimStart('"', '\'')
                        if (raw.startsWith("<")) filter(availablePlaceholders(t.trigger), raw) else mutableListOf()
                    }
                }
            }
            "set" -> {
                return when (args.size) {
                    2 -> filter(store.all().map { it.name }, args[1])
                    3 -> filter(listOf("enabled", "debounceTicks", "includeItems", "ratelimitPerMinute"), args[2])
                    4 -> when (args[2].lowercase()) {
                        "enabled", "includeitems" -> filter(listOf("true", "false"), args[3])
                        else -> mutableListOf()
                    }
                    else -> mutableListOf()
                }
            }
            "remove", "test" -> {
                return if (args.size == 2) filter(store.all().map { it.name }, args[1]) else mutableListOf()
            }
            else -> return mutableListOf()
        }
    }
}
