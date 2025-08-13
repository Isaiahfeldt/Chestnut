package xyz.bellbot.chestnut.triggers

import xyz.bellbot.chestnut.model.Trigger

/**
 * Central registry describing each trigger's human-facing metadata to avoid duplication
 * across commands, renderer defaults, and bind defaults.
 */
 data class TriggerDescriptor(
    val id: String,
    val displayName: String,
    val trigger: Trigger,
    val events: List<String>,
    val extraPlaceholders: List<String>,
    val defaultTemplates: Map<String, String>,
    val examples: Map<String, List<String>>
 )

object TriggerRegistry {
    private val descriptors: List<TriggerDescriptor>
    private val byId: Map<String, TriggerDescriptor>

    init {
        val inventory = TriggerDescriptor(
            id = "storage",
            displayName = "🧰 Storage",
            trigger = Trigger.INVENTORY_OPEN,
            events = Trigger.INVENTORY_OPEN.events,
            extraPlaceholders = listOf("<user>", "<uuid>", "<items>"),
            defaultTemplates = mapOf(
                "open" to "<user> opened <name> at <x>,<y>,<z>.",
                "close" to "<user> closed <name> at <x>,<y>,<z>."
            ),
            examples = mapOf(
                "open" to listOf(
                    "<user> opened <name> at <x>,<y>,<z>.",
                    "<user> opened <name> — <items>"
                ),
                "close" to listOf(
                    "<user> closed <name> at <x>,<y>,<z>.",
                    "<name> closed by <user> at <time>"
                )
            )
        )
        val torch = TriggerDescriptor(
            id = "redstone_torch",
            displayName = "🔴 Redstone Torch",
            trigger = Trigger.TORCH_TOGGLE,
            events = Trigger.TORCH_TOGGLE.events,
            extraPlaceholders = listOf("<state>"),
            defaultTemplates = mapOf(
                "on" to "<name> has been lit!",
                "off" to "<name> has turned off."
            ),
            examples = mapOf(
                "on" to listOf("<name> has been <state>.", "<name> <event> at <time>"),
                "off" to listOf("<name> has been <state>.", "<name> <event> at <time>")
            )
        )
        val lectern = TriggerDescriptor(
            id = "lectern",
            displayName = "📖 Lectern",
            trigger = Trigger.LECTERN,
            events = Trigger.LECTERN.events,
            extraPlaceholders = listOf("<user>", "<uuid>", "<page>", "<book_title>", "<book_author>", "<book_pages>", "<has_book>"),
            defaultTemplates = mapOf(
                "insert_book" to "<user> placed '<book_title>' on <name>.",
                "remove_book" to "<user> removed '<book_title>' from <name>.",
                "page_change" to "<user> turned to page <page> of '<book_title>' on <name>.",
                "open" to "<user> opened '<book_title>' on <name>.",
                "close" to "<user> closed '<book_title>' on <name>."
            ),
            examples = mapOf(
                "insert_book" to listOf(
                    "<user> placed '<book_title>' on <name>.",
                    "<user> set a book by <book_author> on <name>."
                ),
                "remove_book" to listOf(
                    "<user> removed '<book_title>' from <name>.",
                    "Book removed from <name> at <time>."
                ),
                "open" to listOf(
                    "<user> opened '<book_title>' on <name>.",
                    "<user> started reading '<book_title>' at <time>."
                ),
                "page_change" to listOf(
                    "<user> turned to page <page> of '<book_title>' on <name>.",
                    "<user> is reading '<book_title>' (<book_pages> pages) at <name>."
                )
            )
        )
        descriptors = listOf(inventory, torch, lectern)
        val map = HashMap<String, TriggerDescriptor>()
        for (d in descriptors) {
            map[d.id.lowercase()] = d
        }
        byId = map
    }

    fun all(): List<TriggerDescriptor> = descriptors

    fun descriptor(trigger: Trigger): TriggerDescriptor = descriptors.first { it.trigger == trigger }

    fun resolve(input: String): Trigger? = byId[input.trim().lowercase()]?.trigger

    fun allTriggerInputs(): List<String> = descriptors
        .map { it.id }
        .sorted()
}
