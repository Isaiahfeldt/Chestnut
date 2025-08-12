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
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import xyz.bellbot.chestnut.ChestnutConfig
import xyz.bellbot.chestnut.bind.BindManager
import xyz.bellbot.chestnut.model.Tracker
import xyz.bellbot.chestnut.model.Trigger
import xyz.bellbot.chestnut.store.TrackersStore
import xyz.bellbot.chestnut.util.TemplateRenderer
import xyz.bellbot.chestnut.triggers.TriggerRegistry
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
        val extras = TriggerRegistry.descriptor(trigger).extraPlaceholders
        return corePlaceholders + extras
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val cmd = command.name.lowercase(Locale.getDefault())
        val used = label.lowercase(Locale.getDefault())
        fun isCmd(vararg names: String) = names.any { n -> n.equals(cmd, true) || n.equals(used, true) }

        // New HuskHomes-style commands
        if (isCmd("settracker", "settrack")) {
            if (sender !is Player) { sender.sendMessage("§cOnly players can bind trackers."); return true }
            if (!sender.hasPermission("chestnut.use") && !sender.hasPermission("chestnut.admin")) { sender.sendMessage("§cNo permission."); return true }
            if (args.size < 2) { sender.sendMessage("§eUsage: /settracker <name> <trigger>"); return true }
            val name = args[0]
            val trigger = TriggerRegistry.resolve(args.getOrNull(1) ?: "") ?: run { sender.sendMessage("§cUnknown trigger. Valid: ${TriggerRegistry.allTriggerInputs().joinToString(", ")}"); return true }
            if (!namePattern.matcher(name).matches()) { sender.sendMessage("§cInvalid name. 1–32 chars: letters, digits, space, _ . -"); return true }
            if (store.exists(name)) { sender.sendMessage("§cTracker with that name already exists."); return true }
            bind.start(sender, name, trigger)
            return true
        }
        if (isCmd("deltracker", "delt")) {
            if (args.isEmpty()) { sender.sendMessage("§eUsage: /deltracker <name|all> [--confirm]"); return true }
            val confirm = args.any { it.equals("--confirm", true) || it.equals("confirm", true) || it.equals("-y", true) }
            if (!confirm) { sender.sendMessage("§ePlease confirm with --confirm"); return true }
            val target = args[0]
            if (target.equals("all", true)) {
                if (!sender.hasPermission("chestnut.admin")) { sender.sendMessage("§cNo permission to delete all."); return true }
                val names = store.all().map { it.name }.toList()
                var count = 0
                for (n in names) { store.removeAndSave(n); count++ }
                sender.sendMessage("§aDeleted $count trackers.")
                return true
            }
            val t = store.get(target)
            if (t == null) { sender.sendMessage("§aTracker removed (not found)."); return true }
            if (!canManage(sender, t)) { sender.sendMessage("§cYou don't own this tracker."); return true }
            store.removeAndSave(t.name)
            sender.sendMessage("§aTracker '${t.name}' removed.")
            return true
        }
        if (isCmd("edittracker", "edittrack")) {
            if (args.size < 2) {
                sender.sendMessage("§eUsage: /edittracker <name> <rename|title|description|msg|rebind|enable|disable|test|tp|info|color|thumbnail> [args]")
                return true
            }
            val name = args[0]
            val sub = args[1].lowercase(Locale.getDefault())
            val tracker = store.get(name) ?: run { sender.sendMessage("§cTracker not found."); return true }
            if (!canManage(sender, tracker)) { sender.sendMessage("§cYou don't own this tracker."); return true }
            when (sub) {
                "rename" -> {
                    if (args.size < 3) { sender.sendMessage("§eUsage: /edittracker $name rename <new_name>"); return true }
                    val newName = args[2]
                    if (!namePattern.matcher(newName).matches()) { sender.sendMessage("§cInvalid name. 1–32 chars: letters, digits, space, _ . -"); return true }
                    if (store.exists(newName)) { sender.sendMessage("§cName already in use."); return true }
                    val ok = store.rename(tracker.name, newName)
                    if (ok) sender.sendMessage("§aRenamed '${tracker.name}' to '$newName'.") else sender.sendMessage("§cRename failed.")
                }
                "title" -> {
                    if (args.size < 3) { sender.sendMessage("§eUsage: /edittracker $name title <title> (use \"\" to clear)"); return true }
                    val title = joinTail(args, 2)
                    tracker.title = if (title.isBlank()) null else title
                    store.putAndSave(tracker)
                    sender.sendMessage("§aTitle updated.")
                }
                "description" -> {
                    if (args.size < 3) { sender.sendMessage("§eUsage: /edittracker $name description <text>"); return true }
                    val desc = joinTail(args, 2)
                    tracker.description = if (desc.isBlank()) null else desc
                    store.putAndSave(tracker)
                    sender.sendMessage("§aDescription updated.")
                }
                "msg" -> {
                    if (args.size < 3) { sender.sendMessage("§eUsage: /edittracker $name msg <event> <template>"); return true }
                    val event = args[2].lowercase()
                    if (!tracker.trigger.events.map { it.lowercase() }.contains(event)) { sender.sendMessage("§cInvalid event for ${tracker.trigger}: ${tracker.trigger.events.joinToString(", ")}"); return true }
                    if (args.size == 3) { sendPlaceholderHelper(sender, tracker, event); return true }
                    val template = joinTail(args, 3)
                    tracker.templates[event] = template
                    store.putAndSave(tracker)
                    sender.sendMessage("§aTemplate for '$event' updated for '${tracker.name}'.")
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
                }
                "rebind" -> {
                    if (sender !is Player) { sender.sendMessage("§cOnly players can rebind trackers."); return true }
                    bind.startRebind(sender, tracker.name)
                }
                "enable" -> {
                    tracker.options.enabled = true
                    store.putAndSave(tracker)
                    sender.sendMessage("§aTracker enabled.")
                }
                "disable" -> {
                    tracker.options.enabled = false
                    store.putAndSave(tracker)
                    sender.sendMessage("§eTracker disabled.")
                }
                "tp" -> {
                    if (sender !is Player) { sender.sendMessage("§cOnly players can teleport."); return true }
                    val world = plugin.server.getWorld(tracker.world)
                    if (world == null) { sender.sendMessage("§cWorld '${tracker.world}' is not loaded."); return true }
                    val loc = org.bukkit.Location(world, tracker.x + 0.5, tracker.y + 1.0, tracker.z + 0.5)
                    sender.teleport(loc)
                    sender.sendMessage("§aTeleported to '${tracker.name}'.")
                }
                "info" -> {
                    if (sender is Player) sendInfoPanel(sender, tracker) else sendInfoConsole(sender, tracker)
                }
                "test" -> {
                    if (!config.enableTestCommand) { sender.sendMessage("§cTest command is disabled."); return true }
                    val event = if (args.size >= 3) args[2].lowercase(Locale.getDefault()) else tracker.trigger.events.first().lowercase(Locale.getDefault())
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
                    webhook.enqueue(tracker, rendered, event)
                    sender.sendMessage("§aTest message enqueued.")
                }
                "color" -> {
                    if (args.size < 4) { sender.sendMessage("§eUsage: /edittracker $name color <event|all> <#RRGGBB|0xRRGGBB|16711680|reset>"); return true }
                    val event = args[2].lowercase(Locale.getDefault())
                    val applyAll = event.equals("all", true)
                    if (!applyAll && !tracker.trigger.events.map { it.lowercase() }.contains(event)) { sender.sendMessage("§cInvalid event for ${tracker.trigger}: ${tracker.trigger.events.joinToString(", ")}"); return true }
                    val value = args[3]
                    if (value.equals("reset", true) || value.equals("clear", true)) {
                        if (applyAll) {
                            for (ev in tracker.trigger.events) tracker.embedColors.remove(ev.lowercase())
                            store.putAndSave(tracker)
                            sender.sendMessage("§aEmbed color reset to default for all events.")
                        } else {
                            tracker.embedColors.remove(event)
                            store.putAndSave(tracker)
                            sender.sendMessage("§aEmbed color for '$event' reset to default.")
                        }
                        return true
                    }
                    fun parseColor(input: String): Int? {
                        val s = input.trim()
                        return when {
                            s.startsWith("#") && s.length == 7 -> s.substring(1).toIntOrNull(16)
                            s.startsWith("0x", true) && s.length == 8 -> s.substring(2).toIntOrNull(16)
                            else -> s.toIntOrNull()
                        }
                    }
                    val parsed = parseColor(value)
                    if (parsed == null || parsed !in 0..0xFFFFFF) { sender.sendMessage("§cInvalid color. Use #RRGGBB, 0xRRGGBB, or decimal."); return true }
                    if (applyAll) {
                        for (ev in tracker.trigger.events) tracker.embedColors[ev.lowercase()] = parsed
                        store.putAndSave(tracker)
                        sender.sendMessage("§aEmbed color set for all events to ${String.format("#%06X", parsed)}.")
                    } else {
                        tracker.embedColors[event] = parsed
                        store.putAndSave(tracker)
                        sender.sendMessage("§aEmbed color for '$event' set to ${String.format("#%06X", parsed)}.")
                    }
                }
                "thumbnail" -> {
                    if (args.size < 4) { sender.sendMessage("§eUsage: /edittracker $name thumbnail <event|all> <url|reset>"); return true }
                    val event = args[2].lowercase(Locale.getDefault())
                    val applyAll = event.equals("all", true)
                    if (!applyAll && !tracker.trigger.events.map { it.lowercase() }.contains(event)) { sender.sendMessage("§cInvalid event for ${tracker.trigger}: ${tracker.trigger.events.joinToString(", ")}"); return true }
                    val url = args[3]
                    if (url.equals("reset", true) || url.equals("clear", true)) {
                        if (applyAll) {
                            for (ev in tracker.trigger.events) tracker.embedThumbnails.remove(ev.lowercase())
                            store.putAndSave(tracker)
                            sender.sendMessage("§aThumbnail cleared for all events.")
                        } else {
                            tracker.embedThumbnails.remove(event)
                            store.putAndSave(tracker)
                            sender.sendMessage("§aThumbnail for '$event' cleared.")
                        }
                        return true
                    }
                    val ok = url.startsWith("http://", true) || url.startsWith("https://", true)
                    if (!ok) { sender.sendMessage("§cInvalid URL. Must start with http:// or https://"); return true }
                    if (applyAll) {
                        for (ev in tracker.trigger.events) tracker.embedThumbnails[ev.lowercase()] = url
                        store.putAndSave(tracker)
                        sender.sendMessage("§aThumbnail set for all events.")
                    } else {
                        tracker.embedThumbnails[event] = url
                        store.putAndSave(tracker)
                        sender.sendMessage("§aThumbnail for '$event' set.")
                    }
                }
                else -> sender.sendMessage("§cUnknown subcommand: $sub")
            }
            return true
        }
        if (isCmd("trackerlist", "trackers")) {
            val all = store.all().sortedBy { it.name.lowercase() }
            if (all.isEmpty()) { sender.sendMessage("§7No trackers."); return true }
            val pageSize = 5
            val total = all.size
            val maxPage = ((total - 1) / pageSize) + 1
            val reqPage = args.getOrNull(0)?.toIntOrNull()?.coerceIn(1, maxPage) ?: 1
            val from = (reqPage - 1) * pageSize
            val to = (from + pageSize).coerceAtMost(total)
            if (sender is Player) {
                sendCompactList(sender, all.subList(from, to), reqPage, maxPage, from + 1, to, total)
            } else {
                var i = from + 1
                all.subList(from, to).forEach { t ->
                    val status = if (t.options.enabled) "enabled" else "disabled"
                    sender.sendMessage("§7${i++}) §b${t.name} §7 ${TriggerRegistry.descriptor(t.trigger).id} §8${t.world} ${t.x} ${t.y} ${t.z} §7[${status}]")
                }
                sender.sendMessage("§bPage ${reqPage}/${maxPage}")
            }
            return true
        }
        if (isCmd("chestnut", "trackeradmin", "tadmin")) {
            if (!sender.hasPermission("chestnut.admin")) { sender.sendMessage("§cNo permission."); return true }
            val sub = args.getOrNull(0)?.lowercase(Locale.getDefault()) ?: "help"
            when (sub) {
                "reload" -> {
                    config.load()
                    webhook.onConfigReload()
                    val urlOk = if (config.webhookUrl.isNotBlank()) "§a✓" else "§c✗"
                    sender.sendMessage("§aChestnut config reloaded. Webhook URL set: $urlOk§a. Embed color: ${config.embedColor}.")
                }
                "status" -> {
                    val total = store.all().size
                    val urlOk = if (config.webhookUrl.isNotBlank()) "set" else "unset"
                    sender.sendMessage("§aChestnut status: trackers=$total, webhook=$urlOk")
                }
                else -> {
                    sender.sendMessage("§6Chestnut Admin:")
                    sender.sendMessage("§e/chestnut reload §7- Reload config")
                    sender.sendMessage("§e/chestnut status §7- Show basic status")
                }
            }
            return true
        }

        // Legacy /track behavior (read-only/help + edits kept for migration)
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
                val trigger = TriggerRegistry.resolve(args[2]) ?: run { sender.sendMessage("§cUnknown trigger. Valid: ${TriggerRegistry.allTriggerInputs().joinToString(", ")}"); return true }
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
                val all = store.all().sortedBy { it.name.lowercase() }
                if (all.isEmpty()) { sender.sendMessage("§7No trackers."); return true }
                val pageSize = 5
                val total = all.size
                val maxPage = ((total - 1) / pageSize) + 1
                val reqPage = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, maxPage) ?: 1
                val from = (reqPage - 1) * pageSize
                val to = (from + pageSize).coerceAtMost(total)
                if (sender is Player) {
                    sendCompactList(sender, all.subList(from, to), reqPage, maxPage, from + 1, to, total)
                } else {
                    var i = from + 1
                    all.subList(from, to).forEach { t ->
                        val status = if (t.options.enabled) "enabled" else "disabled"
                        sender.sendMessage("§7${i++}) §b${t.name} §7 ${t.trigger} §8${t.world} ${t.x} ${t.y} ${t.z} §7[$status]")
                    }
                    sender.sendMessage("§bPage $reqPage/$maxPage")
                }
                return true
            }
            "info" -> {
                if (args.size < 2) { sender.sendMessage("§eUsage: /track info <name>"); return true }
                val t = store.get(args[1]) ?: run { sender.sendMessage("§cTracker not found."); return true }
                if (sender is Player) sendInfoPanel(sender, t) else sendInfoConsole(sender, t)
                return true
            }
            "tp" -> {
                if (sender !is Player) { sender.sendMessage("§cOnly players can teleport."); return true }
                if (args.size < 2) { sender.sendMessage("§eUsage: /track tp <name>"); return true }
                val t = store.get(args[1]) ?: run { sender.sendMessage("§cTracker not found."); return true }
                if (!canManage(sender, t)) { sender.sendMessage("§cYou can't use this tracker."); return true }
                val world = plugin.server.getWorld(t.world)
                if (world == null) { sender.sendMessage("§cWorld '${t.world}' is not loaded."); return true }
                val loc = org.bukkit.Location(world, t.x + 0.5, t.y + 1.0, t.z + 0.5)
                sender.teleport(loc)
                sender.sendMessage("§aTeleported to '${t.name}'.")
                return true
            }
            "rebind" -> {
                if (sender !is Player) { sender.sendMessage("§cOnly players can rebind trackers."); return true }
                if (args.size < 2) { sender.sendMessage("§eUsage: /track rebind <name>"); return true }
                val t = store.get(args[1]) ?: run { sender.sendMessage("§cTracker not found."); return true }
                if (!canManage(sender, t)) { sender.sendMessage("§cYou don't own this tracker."); return true }
                bind.startRebind(sender, t.name)
                return true
            }
            "rename" -> {
                if (args.size < 3) { sender.sendMessage("§eUsage: /track rename <oldName> <newName>"); return true }
                val t = store.get(args[1]) ?: run { sender.sendMessage("§cTracker not found."); return true }
                if (!canManage(sender, t)) { sender.sendMessage("§cYou don't own this tracker."); return true }
                val newName = args[2]
                if (!namePattern.matcher(newName).matches()) { sender.sendMessage("§cInvalid name. 1–32 chars: letters, digits, space, _ . -"); return true }
                if (store.exists(newName)) { sender.sendMessage("§cName already in use."); return true }
                val ok = store.rename(t.name, newName)
                if (ok) sender.sendMessage("§aRenamed '${t.name}' to '$newName'.") else sender.sendMessage("§cRename failed.")
                return true
            }
            "title" -> {
                if (args.size < 3) { sender.sendMessage("§eUsage: /track title <name> <title> (use \"\" to clear)"); return true }
                val t = store.get(args[1]) ?: run { sender.sendMessage("§cTracker not found."); return true }
                if (!canManage(sender, t)) { sender.sendMessage("§cYou don't own this tracker."); return true }
                val title = joinTail(args, 2)
                t.title = if (title.isBlank()) null else title
                store.putAndSave(t)
                sender.sendMessage("§aTitle updated.")
                return true
            }
            "description" -> {
                if (args.size < 3) { sender.sendMessage("§eUsage: /track description <name> <text> (use \"\" to clear)"); return true }
                val t = store.get(args[1]) ?: run { sender.sendMessage("§cTracker not found."); return true }
                if (!canManage(sender, t)) { sender.sendMessage("§cYou don't own this tracker."); return true }
                val desc = joinTail(args, 2)
                t.description = if (desc.isBlank()) null else desc
                store.putAndSave(t)
                sender.sendMessage("§aDescription updated.")
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
                webhook.enqueue(tracker, rendered, event)
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
                    "ratelimitperminute" -> tracker.options.ratelimitPerMinute = args[3].toIntOrNull() ?: return badValue(sender)
                    else -> { sender.sendMessage("§cUnknown key. Keys: enabled, debounceTicks, ratelimitPerMinute"); return true }
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
                val suggest = "/edittracker ${tracker.name} msg ${event} \"${token}\""
                val chip = Component.text("[ $token ]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand(suggest))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to prefill with $token", NamedTextColor.GRAY)))
                line = line.append(Component.text(" ", NamedTextColor.DARK_GRAY)).append(chip)
            }
            p.sendMessage(line)
            // Example templates
            val examples = buildExamples(tracker.trigger, event)
            for ((i, tpl) in examples.withIndex()) {
                val suggest = "/edittracker ${tracker.name} msg ${event} \"${tpl}\""
                val btn = Component.text("[ Insert example ${i + 1} ]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.suggestCommand(suggest))
                    .hoverEvent(HoverEvent.showText(Component.text(tpl, NamedTextColor.GRAY)))
                p.sendMessage(btn)
            }
        } else {
            sender.sendMessage("§ePlaceholders: ${ph.joinToString(" ")}")
            val examples = buildExamples(tracker.trigger, event)
            examples.forEach { tpl -> sender.sendMessage("§7Example: /edittracker ${tracker.name} msg ${event} \"$tpl\"") }
        }
    }

    private fun buildExamples(trigger: Trigger, event: String): List<String> {
        val d = TriggerRegistry.descriptor(trigger)
        val key = event.lowercase()
        return d.examples[key] ?: listOf("<name> event: <event>")
    }

    private fun displayTitle(t: Tracker): String = t.title?.takeIf { it.isNotBlank() } ?: t.name

    private fun triggerLabel(t: Tracker): String = xyz.bellbot.chestnut.triggers.TriggerRegistry.descriptor(t.trigger).displayName

    private fun stateText(t: Tracker): Pair<String, NamedTextColor> = when (t.trigger) {
        Trigger.TORCH_TOGGLE -> when (t.lastTorchLit) {
            true -> "Lit" to NamedTextColor.GOLD
            false -> "Unlit" to NamedTextColor.GRAY
            null -> "Idle" to NamedTextColor.GRAY
        }
        else -> "Idle" to NamedTextColor.GRAY
    }

    private fun buildHover(t: Tracker): Component {
        val titleRaw = t.title?.takeIf { it.isNotBlank() }
        val (state, stateColor) = stateText(t)
        val titleLine = if (titleRaw != null) Component.text(titleRaw, NamedTextColor.GOLD) else Component.text(t.name, NamedTextColor.GREEN)
        val triggerLine = Component.text(triggerLabel(t), NamedTextColor.YELLOW)
        val block = t.blockType ?: "Block"
        val stateLine = Component.text("$block ", NamedTextColor.GRAY)
            .append(Component.text("– ", NamedTextColor.GRAY))
            .append(Component.text(state, stateColor))
        val coordsLine = Component.text("📍 ${t.world}, ${t.x}, ${t.y}, ${t.z}", NamedTextColor.GOLD)
        val descLine = t.description?.takeIf { it.isNotBlank() }?.let {
            Component.text(it, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true)
        }
        val hint = Component.text("Click to view & manage this tracker", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, true)
        var hover = Component.empty()
            .append(titleLine).append(Component.newline())
            .append(triggerLine).append(Component.newline())
            .append(stateLine).append(Component.newline())
            .append(coordsLine)
        if (descLine != null) {
            hover = hover.append(Component.newline()).append(descLine)
        }
        hover = hover.append(Component.newline()).append(hint)
        return hover
    }

    private fun sendCompactList(player: Player, pageItems: List<Tracker>, page: Int, maxPage: Int, fromIndex: Int, toIndex: Int, total: Int) {
        val header = Component.empty()
            .append(Component.text(player.name, TextColor.fromHexString("#24fb9c")))
            .append(Component.text("'s Trackers: ", TextColor.fromHexString("#24fb9c")))
            .append(Component.text("(", TextColor.fromHexString("#24fb9c")))
            .append(Component.text("$fromIndex-$toIndex", TextColor.fromHexString("#24fb9c")))
            .append(Component.text(" of $total)", TextColor.fromHexString("#24fb9c")))
        player.sendMessage(header)
        var first = true
        var line = Component.empty()
        for (t in pageItems) {
            if (!first) {
                line = line.append(Component.text(" ", NamedTextColor.GRAY))
                    .append(Component.text("•", NamedTextColor.GRAY))
                    .append(Component.text(" ", NamedTextColor.GRAY))
            }
            first = false
            val chip = Component.text("[${t.name}]", NamedTextColor.WHITE)
                .clickEvent(ClickEvent.runCommand("/edittracker ${t.name} info"))
                .hoverEvent(HoverEvent.showText(buildHover(t)))
            line = line.append(chip)
        }
        player.sendMessage(line)
        val footer = Component.text("Page $page/$maxPage", TextColor.fromHexString("#24fb9c"))
        player.sendMessage(footer)
    }

    private fun makeButton(label: String, color: NamedTextColor, command: String, hover: String, run: Boolean = true): Component {
        val comp = Component.text(label, color)
        return if (run) comp.clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)))
        else comp.clickEvent(ClickEvent.suggestCommand(command)).hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)))
    }

    private fun sendInfoPanel(p: Player, t: Tracker) {
        val internalName = t.name
        // Header (dark aqua line; internal name bold aqua)
        val header = Component.text("Viewing details for tracker: ", TextColor.fromHexString("#24fb9c"))
            .append(Component.text(internalName, TextColor.fromHexString("#24fb9c")).decorate(TextDecoration.BOLD))
        p.sendMessage(header)

        // Metadata line: Owner • Trigger • Webhook
        val ownerName = p.server.getOfflinePlayer(t.owner).name ?: t.owner.toString()
        val webhookEnabled = config.webhookUrl.isNotBlank()
        val metaLine = Component.empty()
            .append(Component.text("👤 ", TextColor.fromHexString("#a6a6a6")))
            .append(Component.text(ownerName, TextColor.fromHexString("#a6a6a6")))
            .append(Component.text("  ", TextColor.fromHexString("#a6a6a6")))
            .append(Component.text("⚙ ", TextColor.fromHexString("#a6a6a6")))
            .append(Component.text(TriggerRegistry.descriptor(t.trigger).id, TextColor.fromHexString("#a6a6a6")))
            .append(Component.text("  ", TextColor.fromHexString("#a6a6a6")))
            .append(
                Component.text(
                    if (webhookEnabled) "⏺ Enabled" else "⭘ Disabled",TextColor.fromHexString("#ffab13")
                )
            )
        p.sendMessage(metaLine)

        // Description (light purple, italic), prefixed with ⓘ
        t.description?.takeIf { it.isNotBlank() }?.let {
            val desc = Component.text("ⓘ $it", TextColor.fromHexString("#c44bff"))
            p.sendMessage(desc)
        }

        // Spacer
        p.sendMessage(Component.text(""))

        // Title and location
        val titleDisplay = displayTitle(t)
        p.sendMessage(Component.text("🏷 ", NamedTextColor.GOLD).append(Component.text(titleDisplay, NamedTextColor.GOLD)))
        p.sendMessage(Component.text("○ ${t.world}", TextColor.fromHexString("#5990ff")))
        p.sendMessage(Component.text("⚑ x: ${t.x}, y: ${t.y}, z: ${t.z}", TextColor.fromHexString("#ffd152")))

        // Spacer
        p.sendMessage(Component.text(""))

        // Sections
        // Use
        var line = Component.text("Use: ", NamedTextColor.GRAY)
            .append(makeButton("[⏩ Teleport…]", NamedTextColor.DARK_GREEN, "/edittracker ${t.name} tp", "Teleport to this tracker"))
            .append(Component.text(" "))
            .append(makeButton("[🧪 Trigger Test Event…]", NamedTextColor.GREEN, "/edittracker ${t.name} test ${t.trigger.events.first()}", "Send a test event"))
        p.sendMessage(line)

        // Manage
        val toggleBtn = if (t.options.enabled)
            makeButton("[✖ Disable…]", NamedTextColor.YELLOW, "/edittracker ${t.name} disable", "Disable tracker")
        else
            makeButton("[✔ Enable…]", NamedTextColor.GREEN, "/edittracker ${t.name} enable", "Enable tracker")
        line = Component.text("Manage: ", NamedTextColor.GRAY)
            .append(makeButton("[❌ Delete…]", NamedTextColor.DARK_RED, "/deltracker ${t.name} --confirm", "Delete this tracker"))
            .append(Component.text(" "))
            .append(toggleBtn)
            .append(Component.text(" "))
            .append(makeButton("[↻ Rebind Block]", NamedTextColor.YELLOW, "/edittracker ${t.name} rebind", "Rebind to another block"))
        p.sendMessage(line)

        // Edit
        line = Component.text("Edit: ", NamedTextColor.GRAY)
            .append(makeButton("[✎ Rename…]", NamedTextColor.LIGHT_PURPLE, "/edittracker ${t.name} rename ", "Type a new internal name", run = false))
            .append(Component.text(" "))
            .append(makeButton("[✎ Set Title…]", NamedTextColor.LIGHT_PURPLE, "/edittracker ${t.name} title ", "Type a new display title", run = false))
            .append(Component.text(" "))
            .append(makeButton("[✎ Set Description…]", NamedTextColor.LIGHT_PURPLE, "/edittracker ${t.name} description ", "Type a short description", run = false))
            .append(Component.text(" "))
            .append(makeButton("[✎ Edit Templates…]", NamedTextColor.LIGHT_PURPLE, "/edittracker ${t.name} msg ", "Set a template for an event", run = false))
        p.sendMessage(line)
    }

    private fun sendInfoConsole(sender: CommandSender, t: Tracker) {
        val title = displayTitle(t)
        sender.sendMessage("§fViewing details for tracker: §b$title")
        if (title != t.name) sender.sendMessage("§7Internal name: §f${t.name}")
        sender.sendMessage("§7Owner: §f${t.owner}")
        sender.sendMessage("§7Trigger: §e${TriggerRegistry.descriptor(t.trigger).id}")
        sender.sendMessage("§7Block: §f${t.blockType ?: "Block"}")
        sender.sendMessage("§6📍 ${t.world}, ${t.x}, ${t.y}, ${t.z}")
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§6Chestnut commands:")
        sender.sendMessage("§e/trackerlist [page] §7- List your trackers (alias: /trackers)")
        sender.sendMessage("§e/settracker <name> <trigger> §7- Create & bind a new tracker")
        sender.sendMessage("§e/deltracker <name|all> [--confirm] §7- Delete tracker (all = admin only)")
        sender.sendMessage("§e/edittracker <name> <rename|title|description|msg|rebind|enable|disable|test|tp|info|color|thumbnail> §7- Edit hub")
        sender.sendMessage("§7Examples: §f/edittracker mailbox rename mailbox_v2 §7· §f/edittracker mailbox msg open \"<name> opened\"")
        if (sender.hasPermission("chestnut.admin")) sender.sendMessage("§e/chestnut <help|reload|status> §7- Admin tools")
        sender.sendMessage("§7Storage events: open, close · Redstone Torch events: on, off · Lectern events: insert_book, remove_book, page_change, open")
        sender.sendMessage("§7Placeholders: <name> <trigger> <event> <world> <x> <y> <z> <time> <state> <user> <uuid> <items> <page> <book_title> <book_author> <book_pages> <has_book>")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        fun filter(options: List<String>, prefix: String): MutableList<String> = options.filter { it.startsWith(prefix, ignoreCase = true) }.toMutableList()
        val cmd = command.name.lowercase(Locale.getDefault())
        val used = alias.lowercase(Locale.getDefault())
        fun isCmd(vararg names: String) = names.any { n -> n.equals(cmd, true) || n.equals(used, true) }

        // New commands completions
        if (isCmd("settracker", "settrack")) {
            return when (args.size) {
                0 -> mutableListOf("<name>") // show tip for first argument
                1 -> if (args[0].isBlank()) mutableListOf("<name>") else mutableListOf() // name is free-form
                2 -> {
                    val inputs = TriggerRegistry.allTriggerInputs()
                    if (args[1].isBlank()) (mutableListOf("<trigger>") + inputs).toMutableList() else filter(inputs, args[1])
                }
                else -> mutableListOf()
            }
        }
        if (isCmd("deltracker", "delt")) {
            return when (args.size) {
                0, 1 -> (store.all().map { it.name } + "all").sorted().toMutableList()
                else -> filter(listOf("--confirm"), args.last())
            }
        }
        if (isCmd("edittracker", "edittrack")) {
            return when (args.size) {
                0, 1 -> store.all().map { it.name }.toMutableList()
                2 -> filter(listOf("rename", "title", "description", "msg", "rebind", "enable", "disable", "test", "tp", "info", "color", "thumbnail"), args[1])
                3 -> when (args[1].lowercase(Locale.getDefault())) {
                    "test", "msg", "color", "thumbnail" -> {
                        val t = store.get(args[0]) ?: return mutableListOf()
                        val sub = args[1].lowercase(Locale.getDefault())
                        val events = t.trigger.events + if (sub == "color" || sub == "thumbnail") listOf("all") else emptyList()
                        filter(events, args[2])
                    }
                    else -> mutableListOf()
                }
                4 -> when (args[1].lowercase(Locale.getDefault())) {
                    "color" -> filter(listOf("reset", "#FFCC00", "0x00FF00", "16711680"), args[3])
                    "thumbnail" -> filter(listOf("reset", "https://"), args[3])
                    "msg" -> {
                        val t = store.get(args[0]) ?: return mutableListOf()
                        val token = args.last()
                        val raw = token.trimStart('"', '\'')
                        if (raw.startsWith("<")) filter(availablePlaceholders(t.trigger), raw) else mutableListOf()
                    }
                    else -> mutableListOf()
                }
                else -> {
                    // When editing a message template, allow placeholder suggestions beyond the 4th argument
                    if (args[1].equals("msg", ignoreCase = true)) {
                        val t = store.get(args[0]) ?: return mutableListOf()
                        val token = args.last()
                        val raw = token.trimStart('"', '\'')
                        return if (raw.startsWith("<")) filter(availablePlaceholders(t.trigger), raw) else mutableListOf()
                    }
                    mutableListOf()
                }
            }
        }
        if (isCmd("chestnut", "trackeradmin", "tadmin")) {
            return when (args.size) {
                0, 1 -> filter(listOf("help", "reload", "status"), args.getOrNull(0) ?: "")
                else -> mutableListOf()
            }
        }
        if (isCmd("trackerlist", "trackers")) {
            val total = store.all().size
            val pageSize = 5
            val maxPage = if (total == 0) 1 else ((total - 1) / pageSize) + 1
            val pages = (1..maxPage).map { it.toString() }
            return when (args.size) {
                0, 1 -> pages.toMutableList()
                else -> filter(pages, args.last())
            }
        }

        // Legacy /track tab-complete
        if (args.isEmpty()) return mutableListOf("add", "msg", "list", "info", "tp", "rebind", "rename", "title", "description", "remove", "test", "set", "reload", "help")
        if (args.size == 1) return filter(listOf("add", "msg", "list", "info", "tp", "rebind", "rename", "title", "description", "remove", "test", "set", "reload", "help"), args[0])
        when (args[0].lowercase()) {
            "add" -> {
                return when (args.size) {
                    2 -> mutableListOf() // free-form name
                    3 -> filter(TriggerRegistry.allTriggerInputs(), args[2])
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
                    3 -> filter(listOf("enabled", "debounceTicks", "ratelimitPerMinute"), args[2])
                    4 -> when (args[2].lowercase()) {
                        "enabled" -> filter(listOf("true", "false"), args[3])
                        else -> mutableListOf()
                    }
                    else -> mutableListOf()
                }
            }
            "info", "tp", "rebind", "remove", "test", "title", "description" -> {
                return when (args.size) {
                    2 -> filter(store.all().map { it.name }, args[1])
                    else -> mutableListOf()
                }
            }
            "rename" -> {
                return when (args.size) {
                    2 -> filter(store.all().map { it.name }, args[1])
                    else -> mutableListOf()
                }
            }
            else -> return mutableListOf()
        }
    }
}
