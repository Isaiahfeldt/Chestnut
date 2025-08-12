package xyz.bellbot.chestnut.webhook

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import xyz.bellbot.chestnut.ChestnutConfig
import xyz.bellbot.chestnut.model.Tracker
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class WebhookSender(private val plugin: JavaPlugin, private val config: ChestnutConfig) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    data class Job(val tracker: Tracker?, val content: String, val event: String?, val itemsSummary: String?)

    private val queue = LinkedBlockingQueue<Job>()
    private val perTrackerCounts = ConcurrentHashMap<String, AtomicInteger>()
    private var globalCount = AtomicInteger(0)
    @Volatile private var windowResetAt = System.currentTimeMillis() + 60_000
    @Volatile private var warnNoUrl = false

    @Volatile private var running = false
    private var workerThread: Thread? = null

    fun onConfigReload() {
        // Reset warnings and rate limit window to reflect new configuration cleanly
        warnNoUrl = false
        windowResetAt = System.currentTimeMillis() + 60_000
        globalCount.set(0)
        perTrackerCounts.values.forEach { it.set(0) }
    }

    fun start() {
        running = true
        workerThread = Thread({
            while (running) {
                try {
                    val job = queue.take()
                    enforceRateLimits(job.tracker)
                    send(job)
                } catch (ie: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    plugin.logger.warning("Webhook worker error: ${t.message}")
                }
            }
        }, "Chestnut-Webhook")
        workerThread!!.isDaemon = true
        workerThread!!.start()
    }

    fun stopAndDrain(timeoutMs: Long = 2500) {
        running = false
        workerThread?.interrupt()
        val end = System.currentTimeMillis() + timeoutMs
        while (queue.isNotEmpty() && System.currentTimeMillis() < end) {
            try {
                val job = queue.poll() ?: break
                // Best effort send without rate limiting on shutdown
                send(job)
            } catch (_: Exception) {
                break
            }
        }
    }

    fun enqueue(tracker: Tracker?, content: String) {
        // Backward-compatible enqueue without event context
        queue.offer(Job(tracker, content, null, null))
    }

    fun enqueue(tracker: Tracker?, content: String, event: String?) {
        queue.offer(Job(tracker, content, event?.lowercase(), null))
    }

    fun enqueue(tracker: Tracker?, content: String, event: String?, itemsSummary: String?) {
        queue.offer(Job(tracker, content, event?.lowercase(), itemsSummary))
    }

    private fun enforceRateLimits(tracker: Tracker?) {
        // Reset window if needed
        val now = System.currentTimeMillis()
        if (now >= windowResetAt) {
            windowResetAt = now + 60_000
            globalCount.set(0)
            perTrackerCounts.values.forEach { it.set(0) }
        }
        // Global rate limit
        val globalLimit = config.globalRateLimitPerMinute.coerceAtLeast(1)
        while (globalCount.get() >= globalLimit && running) {
            Thread.sleep(100)
            if (System.currentTimeMillis() >= windowResetAt) break
        }
        // Per tracker
        if (tracker != null && tracker.options.ratelimitPerMinute > 0) {
            val key = tracker.name
            val counter = perTrackerCounts.computeIfAbsent(key) { AtomicInteger(0) }
            val limit = tracker.options.ratelimitPerMinute
            while (counter.get() >= limit && running) {
                Thread.sleep(100)
                if (System.currentTimeMillis() >= windowResetAt) break
            }
        }
    }

    private fun send(job: Job) {
        val url = config.webhookUrl
        if (url.isBlank()) {
            if (!warnNoUrl) {
                warnNoUrl = true
                plugin.logger.warning("Chestnut webhookUrl is empty. Messages will be dropped.")
            }
            return
        }
        val body = buildEmbedBody(job)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        var attempt = 0
        var backoff = 500L
        while (attempt < 3) {
            attempt++
            try {
                val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() in 200..299) {
                    globalCount.incrementAndGet()
                    job.tracker?.let {
                        if (it.options.ratelimitPerMinute > 0) {
                            perTrackerCounts.computeIfAbsent(it.name) { AtomicInteger(0) }.incrementAndGet()
                        }
                    }
                    return
                }
                if (resp.statusCode() == 429) {
                    val retryAfter = resp.headers().firstValue("Retry-After").orElse("1").toLongOrNull() ?: 1L
                    Thread.sleep(retryAfter * 1000)
                } else {
                    Thread.sleep(backoff)
                    backoff = (backoff * 2).coerceAtMost(5000)
                }
            } catch (e: Exception) {
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(5000)
            }
        }
        if (config.debug) Bukkit.getLogger().warning("Failed to send webhook after retries")
    }

    private fun jsonString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }

    private fun buildEmbedBody(job: Job): String {
        val title = job.tracker?.let { it.title?.takeIf { s -> s.isNotBlank() } ?: it.name } ?: "Chestnut"
        val description = job.content
        val eventKey = job.event?.lowercase()
        val color = job.tracker?.let { t ->
            if (eventKey != null) t.embedColors[eventKey] ?: config.embedColor else config.embedColor
        } ?: config.embedColor
        val ts = java.time.Instant.now().toString()
        val footerText = job.tracker?.let { t ->
            var f = config.embedFooter
            if (f.isNotBlank()) {
                f = f.replace("<name>", t.name)
                    .replace("<trigger>", xyz.bellbot.chestnut.triggers.TriggerRegistry.descriptor(t.trigger).id)
                    .replace("<world>", t.world)
                    .replace("<x>", t.x.toString())
                    .replace("<y>", t.y.toString())
                    .replace("<z>", t.z.toString())
                    .replace("<time>", ts)
                    .replace("<event>", (job.event ?: ""))
                    .replace("<items>", (job.itemsSummary ?: ""))
                f
            } else null
        }
        val footerJson = if (!footerText.isNullOrBlank()) ",\"footer\":{\"text\":${jsonString(footerText)}}" else ""
        val thumbUrl = job.tracker?.let { t ->
            if (eventKey != null) t.embedThumbnails[eventKey] else null
        }
        val thumbJson = if (!thumbUrl.isNullOrBlank()) ",\"thumbnail\":{\"url\":" + jsonString(thumbUrl) + "}" else ""
        val embed = "{" +
                "\"title\":" + jsonString(title) + "," +
                "\"description\":" + jsonString(description) + "," +
                "\"color\":" + color + "," +
                "\"timestamp\":" + jsonString(ts) +
                footerJson +
                thumbJson +
            "}"
        return "{\"embeds\":[" + embed + "]}"
    }
}
