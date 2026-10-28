package dev.chandra.grabbit.sniffer

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Thread-safe list of media found on the page currently shown. Cleared on each top-level navigation. */
class MediaRegistry {
    private val items = LinkedHashMap<String, MediaItem>()
    /** Groups replaced by a better source (e.g. sniffed YouTube chunks once the extractor ran). */
    private val suppressed = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(List<MediaItem>) -> Unit>()
    @Volatile var pageUrl: String = ""
    @Volatile var pageTitle: String = ""

    fun addListener(l: (List<MediaItem>) -> Unit) { listeners.add(l) }
    fun removeListener(l: (List<MediaItem>) -> Unit) { listeners.remove(l) }

    fun clear() {
        synchronized(items) { items.clear(); suppressed.clear() }
        notifyChanged()
    }

    /** May be called from any thread. */
    fun offer(raw: MediaItem) {
        val item = raw.copy(
            pageUrl = raw.pageUrl.ifBlank { pageUrl },
            pageTitle = raw.pageTitle.ifBlank { pageTitle },
        )
        var changed = false
        synchronized(items) {
            if (item.groupKey != null && item.groupKey in suppressed) return
            val existing = items[item.key]
            if (existing == null) {
                items[item.key] = item; changed = true
            } else {
                // Keep the richer record (dimensions/duration/label from JS or mime from network).
                val merged = existing.copy(
                    width = maxOf(existing.width, item.width),
                    height = maxOf(existing.height, item.height),
                    durationSec = maxOf(existing.durationSec, item.durationSec),
                    mime = existing.mime ?: item.mime,
                    label = existing.label ?: item.label,
                    pageTitle = existing.pageTitle.ifBlank { item.pageTitle },
                    thumbUrl = existing.thumbUrl ?: item.thumbUrl,
                )
                if (merged != existing) { items[item.key] = merged; changed = true }
            }
        }
        if (changed) notifyChanged()
    }

    fun update(key: String, transform: (MediaItem) -> MediaItem) {
        synchronized(items) { items[key]?.let { items[key] = transform(it) } }
        notifyChanged()
    }

    fun removeGroup(groupKey: String) {
        synchronized(items) { items.values.removeAll { it.groupKey == groupKey }; suppressed.add(groupKey) }
        notifyChanged()
    }

    fun remove(key: String) {
        synchronized(items) { items.remove(key) }
        notifyChanged()
    }

    fun replace(key: String, with: List<MediaItem>) {
        synchronized(items) {
            items.remove(key)
            with.forEach { items[it.key] = it }
        }
        notifyChanged()
    }

    fun snapshot(): List<MediaItem> = synchronized(items) {
        items.values.sortedWith(compareBy<MediaItem> { it.type.ordinal }.thenByDescending { it.height }.thenByDescending { it.sizeBytes })
    }

    private fun notifyChanged() {
        val snap = snapshot()
        main.post { listeners.forEach { it(snap) } }
    }
}
