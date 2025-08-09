package xyz.bellbot.chestnut.model

enum class Trigger(val events: List<String>) {
    INVENTORY_OPEN(listOf("open", "close")),
    TORCH_TOGGLE(listOf("on", "off"));

    companion object {
        fun fromString(s: String): Trigger? = entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }
    }
}
