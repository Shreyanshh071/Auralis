package com.auralis.music.windows.dev

import com.auralis.music.util.Log
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import java.io.File

/**
 * Opt-in startup profiler: with a `trace-startup` file in the data folder, samples the AWT event
 * thread (where Compose composes and draws) every 20ms for the first [seconds] and logs where its
 * time went, so a slow first frame can be diagnosed from one launch of the installed app.
 */
internal object StartupSampler {
    private const val TAG = "StartupSampler"

    fun startIfRequested(dataDir: File, seconds: Int = 8) {
        if (!File(dataDir, "trace-startup").isFile) return
        Thread({ run(seconds * 1000L, "startup") }, "startup-sampler").apply { isDaemon = true; start() }
    }

    private val sampling = java.util.concurrent.atomic.AtomicBoolean(false)

    /** With a `trace-resize` file in the data folder, each maximize/restore/resize is sampled for 1.5s. */
    fun sampleResizeIfRequested(dataDir: File) {
        val frames = File(dataDir, "trace-frames").isFile
        if ((!File(dataDir, "trace-resize").isFile && !frames) || !sampling.compareAndSet(false, true)) return
        val resized = System.nanoTime()
        Thread({
            try {
                // trace-frames: only the frame times, without stack sampling (which pauses the app).
                if (frames) Thread.sleep(1500) else run(1500L, "resize")
                Log.i(TAG, "[resize] root draws after the resize: ${FrameLog.since(resized)}")
            } finally { sampling.set(false) }
        }, "resize-sampler")
            .apply { isDaemon = true; start() }
    }

    @Volatile private var minimisedAt = -1

    /**
     * With `trace-frames`, logs the root draws in the second after the window is minimised, restored,
     * hidden to the tray or shown, and how many it drew while minimised (a black flash on restore).
     */
    fun windowEventIfRequested(dataDir: File, label: String) {
        if (!File(dataDir, "trace-frames").isFile) return
        val at = System.nanoTime()
        val during = if (label == "restored" && minimisedAt >= 0) " (${FrameLog.total() - minimisedAt} drawn while minimised)" else ""
        if (label == "minimised") minimisedAt = FrameLog.total()
        Thread({
            Thread.sleep(1000)
            Log.i(TAG, "[$label]$during root draws after: ${FrameLog.since(at)}")
        }, "window-event-sampler").apply { isDaemon = true; start() }
    }

    private fun run(millis: Long, label: String) {
        val seconds = "%.1f".format(millis / 1000f)
        val started = System.currentTimeMillis()
        val leaf = HashMap<String, Int>()
        val app = HashMap<String, Int>()
        val inclusive = HashMap<String, Int>()
        var samples = 0
        while (System.currentTimeMillis() - started < millis) {
            val edt = Thread.getAllStackTraces().keys.firstOrNull { it.name.startsWith("AWT-EventQueue") }
            val stack = edt?.stackTrace
            // Idle means parked waiting for the next event; anything else (running, loading
            // classes, blocked on a lock or I/O) is time the first frame waits for.
            val idle = stack.isNullOrEmpty() || stack.take(8).any { it.methodName == "getNextEvent" }
            if (!idle) {
                samples++
                stack[0].let { leaf.merge("${it.className}.${it.methodName}", 1, Int::plus) }
                stack.firstOrNull { it.className.startsWith("com.auralis") }
                    ?.let { app.merge("${it.className}.${it.methodName}:${it.lineNumber}", 1, Int::plus) }
                stack.map { "${it.className}.${it.methodName}" }.filter { it.startsWith("com.auralis") }.toSet()
                    .forEach { inclusive.merge(it, 1, Int::plus) }
            }
            Thread.sleep(20)
        }
        fun top(map: Map<String, Int>, n: Int) = map.entries.sortedByDescending { it.value }.take(n)
            .joinToString("\n") { "  ${it.value * 20}ms ${it.key}" }
        Log.i(TAG, "[$label] UI thread busy in $samples samples (~${samples * 20}ms) over ${seconds}s\n" +
            "-- innermost frames:\n${top(leaf, 20)}\n-- innermost Auralis frames:\n${top(app, 25)}\n" +
            "-- Auralis frames on the stack (inclusive):\n${top(inclusive, 30)}")
    }
}

/**
 * Keeps this build's class-data archive (-Dauralis.cdsArchive, see build.gradle.kts). Every start
 * records the classes it loads; when the archive is missing, a background java.exe builds it from
 * that list, so the next start maps it instead of loading ~1.2s of classes from the jars. Archives
 * of earlier builds (~70MB each) are deleted: JDK 21 never reuses or rebuilds a stale one.
 */
internal object ClassArchive {
    private const val TAG = "ClassArchive"

    fun maintainIfPackaged() {
        val current = System.getProperty("auralis.cdsArchive") ?: return
        val appDir = System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile } ?: return
        Thread({ runCatching { maintain(appDir, current) }.onFailure { Log.w(TAG, "Class archive upkeep failed: ${it.message}") } },
            "class-archive").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun maintain(appDir: File, current: String) {
        // Well after startup, so this never competes with the first frames.
        Thread.sleep(45_000)
        appDir.listFiles { f -> f.name.startsWith("auralis-cds") && f.name != current && f.name != "auralis-cds.log" }
            ?.forEach { old -> old.setWritable(true); if (old.delete()) Log.i(TAG, "Deleted ${old.name} from an earlier build") }
        val archive = File(appDir, current)
        if (archive.isFile) return
        val recorded = File(appDir, "auralis-classes.lst")
        // A full start lists several thousand classes; a short second launcher overwrites it with a few.
        if (!recorded.isFile || recorded.length() < 200_000) return
        val launcher = File(System.getProperty("java.home"), "bin/java.exe").takeIf { it.isFile } ?: return
        val list = File(appDir, "auralis-classes.dump.lst").also { recorded.copyTo(it, overwrite = true) }
        val partial = File(appDir, "$current.partial")
        val started = System.currentTimeMillis()
        try {
            val process = ProcessBuilder(launcher.path, "-Xshare:dump", "-XX:ActiveProcessorCount=1",
                "-XX:SharedClassListFile=${list.path}", "-XX:SharedArchiveFile=${partial.path}",
                "-cp", System.getProperty("java.class.path"))
                .redirectErrorStream(true).redirectOutput(File(appDir, "auralis-cds.log")).start()
            val done = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)
            if (!done) process.destroyForcibly()
            if (done && process.exitValue() == 0 && partial.isFile) {
                java.nio.file.Files.move(partial.toPath(), archive.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                Log.i(TAG, "Built $current (${archive.length() / 1_048_576}MB) in ${System.currentTimeMillis() - started}ms")
            } else Log.w(TAG, "Building $current failed; see auralis-cds.log")
        } finally {
            list.delete()
            if (partial.exists()) { partial.setWritable(true); partial.delete() }
        }
    }
}

/**
 * When the app's root last drew, at which size, and where that frame's time went (opt-in resize
 * diagnosis, see StartupSampler): C = Compose's whole render call (animations, composition, layout,
 * drawing), L = measuring and placing, D = recording the drawing, named panels' layout, and
 * G = from the end of that render to the next one (Skia playing the frame to the GPU, the flush,
 * the buffer swap and the wait for the display), shown when 9ms or more.
 */
internal object FrameLog {
    private val times = LongArray(512)
    private val widths = IntArray(512)
    private val heights = IntArray(512)
    private val layouts = LongArray(512)
    private val draws = LongArray(512)
    private val composes = LongArray(512)
    private val presents = LongArray(512)
    private val parts = arrayOfNulls<String>(512)
    private var next = 0
    private var pendingLayout = 0L
    private val pendingParts = LinkedHashMap<String, Long>()
    private var drawStarted = 0L
    private var renderStarted = 0L
    private var renderEnded = 0L

    fun laidOut(nanos: Long) = synchronized(this) { pendingLayout += nanos }

    fun part(name: String, nanos: Long) = synchronized(this) { pendingParts.merge(name, nanos, Long::plus); Unit }

    fun drawn(width: Int, height: Int) = synchronized(this) {
        val i = next++ and 511
        drawStarted = System.nanoTime()
        times[i] = drawStarted; widths[i] = width; heights[i] = height
        layouts[i] = pendingLayout; draws[i] = 0; composes[i] = 0; presents[i] = 0; pendingLayout = 0
        parts[i] = pendingParts.filterValues { it >= 1_000_000 }.entries
            .joinToString(" ") { " ${it.key}=${it.value / 1_000_000}" }.ifEmpty { null }
        pendingParts.clear()
    }

    fun drawFinished() = synchronized(this) { if (next > 0) draws[(next - 1) and 511] = System.nanoTime() - drawStarted }

    /** Around Skia's render callback (installed with trace-frames, see [installRenderTiming]). */
    fun renderStarted() = synchronized(this) {
        val now = System.nanoTime()
        val last = (next - 1) and 511
        if (next > 0 && renderEnded != 0L && times[last] in renderStarted..renderEnded) presents[last] = now - renderEnded
        renderStarted = now
    }

    fun renderEnded() = synchronized(this) {
        val now = System.nanoTime()
        val last = (next - 1) and 511
        if (next > 0 && times[last] >= renderStarted) composes[last] = now - renderStarted
        renderEnded = now
    }

    /** Frames in [from, to): count, size, median gap between draws, mean C and mean G in ms. */
    fun stats(from: Long, to: Long): String = synchronized(this) {
        val frames = (maxOf(0, next - 512) until next).map { it and 511 }.filter { times[it] in from until to }
        if (frames.size < 2) return@synchronized "${frames.size} frames"
        val gaps = frames.zipWithNext { a, b -> (times[b] - times[a]) / 1e6 }.sorted()
        val size = frames.last().let { "${widths[it]}x${heights[it]}" }
        "%d frames at %s, median gap %.1fms, worst %.1fms, render C %.1fms, GPU/present G %.1fms".format(
            frames.size, size, gaps[gaps.size / 2], gaps.last(),
            frames.map { composes[it] / 1e6 }.average(), frames.dropLast(1).map { presents[it] / 1e6 }.average())
    }

    /** Root draws so far. */
    fun total(): Int = synchronized(this) { next }

    /** Each root draw since [since]: ms after it, size, and where its time went when 1ms or more. */
    fun since(since: Long): String = synchronized(this) {
        (maxOf(0, next - 512) until next).map { it and 511 }.filter { times[it] >= since }
            .joinToString(" ") {
                val compose = composes[it] / 1_000_000; val layout = layouts[it] / 1_000_000
                val draw = draws[it] / 1_000_000; val present = presents[it] / 1_000_000
                val detail = buildString {
                    if (compose > 0) append(" C$compose")
                    if (layout > 0) append(" L$layout")
                    if (draw > 0) append(" D$draw")
                    parts[it]?.let(::append)
                    if (present >= 9) append(" G$present")
                }.trim()
                "${(times[it] - since) / 1_000_000}ms:${widths[it]}x${heights[it]}" + if (detail.isNotEmpty()) "($detail)" else ""
            }
    }
}

/** With `trace-frames`, times Skia's render callback for [FrameLog] (C and G). */
internal fun installRenderTiming(window: java.awt.Window, dataDir: File) {
    if (!File(dataDir, "trace-frames").isFile) return
    fun find(container: java.awt.Container): org.jetbrains.skiko.SkiaLayer? {
        for (child in container.components) {
            if (child is org.jetbrains.skiko.SkiaLayer) return child
            if (child is java.awt.Container) find(child)?.let { return it }
        }
        return null
    }
    val layer = find(window) ?: return
    val original = layer.renderDelegate ?: return
    layer.renderDelegate = object : org.jetbrains.skiko.SkikoRenderDelegate {
        override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
            FrameLog.renderStarted()
            try { original.onRender(canvas, width, height, nanoTime) } finally { FrameLog.renderEnded() }
        }
    }
}

/** A panel's measure and place time in [FrameLog] (named in the frame log). */
internal fun timedLayout(name: String): androidx.compose.ui.Modifier =
    androidx.compose.ui.Modifier.layout { measurable, constraints ->
        val started = System.nanoTime()
        val placeable = measurable.measure(constraints)
        FrameLog.part(name, System.nanoTime() - started)
        layout(placeable.width, placeable.height) {
            val placing = System.nanoTime()
            placeable.place(0, 0)
            FrameLog.part(name, System.nanoTime() - placing)
        }
    }

/**
 * Diagnostic switches: a `perf-off` file in the data folder lists effects to leave out (dockglass,
 * aura, animatedcover), so their GPU cost after a resize can be measured on the user's machine.
 * Without the file nothing changes.
 */
internal object PerfFlags {
    private val off: Set<String> = runCatching {
        File(com.auralis.music.util.desktopAppDataDir(), "perf-off").takeIf { it.isFile }?.readText()
            ?.split(Regex("[ ,\t\r\n]+"))?.filter { it.isNotBlank() }?.map { it.lowercase() }?.toSet()
    }.getOrNull().orEmpty().also { if (it.isNotEmpty()) Log.i("PerfFlags", "Effects off for measurement: $it") }
    private val experimentOff = androidx.compose.runtime.mutableStateOf(emptySet<String>())

    fun off(effect: String) = effect in off || effect in experimentOff.value

    /** Set by [GpuExperiment] on the UI thread. */
    fun experiment(effects: String) { experimentOff.value = effects.split(',').filter { it.isNotBlank() }.toSet() }
}

/** Leaves out the drawing of what it wraps while [PerfFlags] has [name] off (layout stays). */
internal fun perfSkip(name: String): androidx.compose.ui.Modifier =
    androidx.compose.ui.Modifier.drawWithContent { if (!PerfFlags.off(name)) drawContent() }

/**
 * With `trace-gpu` and `trace-frames` files, each maximise runs one measurement: for ~0.8s per
 * switch it leaves out one part's drawing and logs the frame cadence and the GPU/present time (G),
 * so what makes full-size frames miss the display's refresh shows in one round.
 */
internal object GpuExperiment {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val switches = listOf("", "background", "left", "page", "right", "aura", "cover", "lyrics", "bar", "titlebar",
        "background,left,page,right,bar,titlebar")

    fun runIfRequested(dataDir: File, window: java.awt.Window) {
        if (!File(dataDir, "trace-gpu").isFile || !File(dataDir, "trace-frames").isFile) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                Thread.sleep(800)
                val frame = window as? java.awt.Frame
                if (frame == null || frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH != java.awt.Frame.MAXIMIZED_BOTH) return@Thread
                val lines = switches.map { effects ->
                    java.awt.EventQueue.invokeAndWait { PerfFlags.experiment(effects) }
                    Thread.sleep(250)
                    val from = System.nanoTime()
                    Thread.sleep(600)
                    "  ${effects.ifEmpty { "everything drawn" }.padEnd(42)} ${FrameLog.stats(from, System.nanoTime())}"
                }
                java.awt.EventQueue.invokeAndWait { PerfFlags.experiment("") }
                Log.i("GpuExperiment", "[gpu] without each part's drawing (maximised):\n" + lines.joinToString("\n"))
            } finally { running.set(false) }
        }, "gpu-experiment").apply { isDaemon = true; start() }
    }
}
