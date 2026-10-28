package dev.chandra.grabbit.download

import java.util.ArrayDeque

/** Tracks throughput with a sliding 3-second window and derives speed and ETA. */
class Progress(private val onUpdate: (done: Long, total: Long, speedBps: Long, etaSec: Long) -> Unit) {
    private val samples = ArrayDeque<Pair<Long, Long>>() // (timeMs, bytesDone)
    @Volatile private var done = 0L
    @Volatile var total = -1L
    private var lastEmit = 0L
    private val lock = Any()

    /** Bytes received but not yet written in order (HLS segments still in flight). Counts for speed/display. */
    @Volatile private var inflight = 0L

    fun add(bytes: Int) = set(done + bytes)

    fun addInflight(bytes: Long) { synchronized(lock) { inflight = (inflight + bytes).coerceAtLeast(0) }; set(done) }

    /** A segment counted as in-flight is now written: move it to done. */
    fun commit(bytes: Int) { synchronized(lock) { inflight = (inflight - bytes).coerceAtLeast(0) }; set(done + bytes) }

    fun set(bytesDone: Long, force: Boolean = false) {
        synchronized(lock) {
            done = bytesDone
            val now = System.currentTimeMillis()
            samples.addLast(now to (done + inflight))
            while (samples.size > 2 && now - samples.first.first > 3000) samples.removeFirst()
            if (!force && now - lastEmit < 250) return
            lastEmit = now
            val first = samples.first
            val dt = now - first.first
            val shown = done + inflight
            val speed = if (dt > 200) ((shown - first.second) * 1000 / dt).coerceAtLeast(0) else 0L
            val eta = if (speed > 0 && total > 0) ((total - shown) / speed) else -1L
            onUpdate(shown, total, speed, eta)
        }
    }

    fun finish() { set(done, force = true) }
}
