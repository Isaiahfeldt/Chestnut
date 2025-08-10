package xyz.bellbot.chestnut.model

import java.util.UUID

/**
 * Represents a single block-bound tracker with a single trigger.
 */
data class Tracker(
    var name: String,
    val trigger: Trigger,
    var world: String,
    var x: Int,
    var y: Int,
    var z: Int,
    val templates: MutableMap<String, String> = mutableMapOf(),
    val options: TrackerOptions = TrackerOptions(),
    val owner: UUID,
    var title: String? = null,
    var description: String? = null,
    var blockType: String? = null,
    // Per-event embed customization
    val embedColors: MutableMap<String, Int> = mutableMapOf(),
    val embedThumbnails: MutableMap<String, String> = mutableMapOf(),
) {
    /** Last event tick for debounce. */
    @Transient var lastEventAtTick: Long = 0L

    /** Cached last torch lit state for TORCH_TOGGLE */
    @Transient var lastTorchLit: Boolean? = null
}
