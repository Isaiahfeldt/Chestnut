package xyz.bellbot.chestnut.model

import java.util.UUID

/**
 * Represents a single block-bound tracker with a single trigger.
 */
data class Tracker(
    val name: String,
    val trigger: Trigger,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val templates: MutableMap<String, String> = mutableMapOf(),
    val options: TrackerOptions = TrackerOptions(),
    val owner: UUID,
) {
    /** Last event tick for debounce. */
    @Transient var lastEventAtTick: Long = 0L

    /** Cached last torch lit state for TORCH_TOGGLE */
    @Transient var lastTorchLit: Boolean? = null
}
