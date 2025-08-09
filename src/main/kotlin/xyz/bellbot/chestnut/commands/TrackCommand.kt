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

    private fun displayTitle(t: Tracker): String = t.title?.takeIf { it.isNotBlank() } ?: t.name

    private fun triggerLabel(t: Tracker): String = when (t.trigger) {
        Trigger.INVENTORY_OPEN -> "⚙ Inventory Open"
        Trigger.TORCH_TOGGLE -> "🔦 Torch Toggle"
    }

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
                .clickEvent(ClickEvent.runCommand("/track info ${t.name}"))
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
            .append(Component.text(t.trigger.name.lowercase(), TextColor.fromHexString("#a6a6a6")))
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
            .append(makeButton("[⏩ Teleport…]", NamedTextColor.DARK_GREEN, "/track tp ${t.name}", "Teleport to this tracker"))
            .append(Component.text(" "))
            .append(makeButton("[🧪 Trigger Test Event…]", NamedTextColor.GREEN, "/track test ${t.name} ${t.trigger.events.first()}", "Send a test event"))
        p.sendMessage(line)

        // Manage
        val toggleBtn = if (t.options.enabled)
            makeButton("[✖ Disable…]", NamedTextColor.YELLOW, "/track set ${t.name} enabled false", "Disable tracker")
        else
            makeButton("[✔ Enable…]", NamedTextColor.GREEN, "/track set ${t.name} enabled true", "Enable tracker")
        line = Component.text("Manage: ", NamedTextColor.GRAY)
            .append(makeButton("[❌ Delete…]", NamedTextColor.DARK_RED, "/track remove ${t.name}", "Delete this tracker"))
            .append(Component.text(" "))
            .append(toggleBtn)
            .append(Component.text(" "))
            .append(makeButton("[↻ Rebind Block]", NamedTextColor.YELLOW, "/track rebind ${t.name}", "Rebind to another block"))
        p.sendMessage(line)

        // Edit
        line = Component.text("Edit: ", NamedTextColor.GRAY)
            .append(makeButton("[✎ Rename…]", NamedTextColor.LIGHT_PURPLE, "/track rename ${t.name} ", "Type a new internal name", run = false))
            .append(Component.text(" "))
            .append(makeButton("[✎ Set Title…]", NamedTextColor.LIGHT_PURPLE, "/track title ${t.name} ", "Type a new display title", run = false))
            .append(Component.text(" "))
            .append(makeButton("[✎ Set Description…]", NamedTextColor.LIGHT_PURPLE, "/track description ${t.name} ", "Type a short description", run = false))
            .append(Component.text(" "))
            .append(makeButton("[✎ Edit Templates…]", NamedTextColor.LIGHT_PURPLE, "/track msg ${t.name} ", "Set a template for an event", run = false))
        p.sendMessage(line)
    }

    private fun sendInfoConsole(sender: CommandSender, t: Tracker) {
        val title = displayTitle(t)
        sender.sendMessage("§fViewing details for tracker: §b$title")
        if (title != t.name) sender.sendMessage("§7Internal name: §f${t.name}")
        sender.sendMessage("§7Owner: §f${t.owner}")
        sender.sendMessage("§7Trigger: §e${t.trigger}")
        sender.sendMessage("§7Block: §f${t.blockType ?: "Block"}")
        sender.sendMessage("§6📍 ${t.world}, ${t.x}, ${t.y}, ${t.z}")
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§6Chestnut commands:")
        sender.sendMessage("§e/track add <name> <trigger> §7- Start bind session")
        sender.sendMessage("§e/track list [page] §7- Interactive list of your trackers")
        sender.sendMessage("§e/track info <name> §7- View tracker details & actions")
        sender.sendMessage("§e/track tp <name> §7- Teleport to the tracker")
        sender.sendMessage("§e/track rebind <name> §7- Rebind tracker to a new block")
        sender.sendMessage("§e/track rename <oldName> <newName> §7- Change the internal name")
        sender.sendMessage("§e/track title <name> <title> §7- Set a display title")
        sender.sendMessage("§e/track description <name> <text> §7- Set a short description")
        sender.sendMessage("§e/track msg <name> <event> <template> §7- Set message template")
        if (config.enableTestCommand) sender.sendMessage("§e/track test <name> <event> §7- Send test message")
        sender.sendMessage("§e/track set <name> <key> <value> §7- Options (enabled, debounceTicks, includeItems, ratelimitPerMinute)")
        sender.sendMessage("§e/track remove <name> §7- Remove tracker")
        if (sender.hasPermission("chestnut.admin")) sender.sendMessage("§e/track reload §7- Reload config")
        sender.sendMessage("§7Inventory events: open, close · Torch events: on, off")
        sender.sendMessage("§7Placeholders: <name> <trigger> <event> <world> <x> <y> <z> <time> <state> <user> <uuid> <items>")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        fun filter(options: List<String>, prefix: String): MutableList<String> = options.filter { it.startsWith(prefix, ignoreCase = true) }.toMutableList()
        if (args.isEmpty()) return mutableListOf("add", "msg", "list", "info", "tp", "rebind", "rename", "title", "description", "remove", "test", "set", "reload", "help")
        if (args.size == 1) return filter(listOf("add", "msg", "list", "info", "tp", "rebind", "rename", "title", "description", "remove", "test", "set", "reload", "help"), args[0])
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
