package xyz.bellbot.chestnut.model

/**
 * Options that control how a tracker behaves at runtime.
 */
data class TrackerOptions(
    var enabled: Boolean = true,
    var debounceTicks: Int = 4,
    var includeItems: Boolean = false,
    /**
     * Per-tracker rate limit in messages per minute. 0 disables per-tracker limiting.
     */
    var ratelimitPerMinute: Int = 0
)
