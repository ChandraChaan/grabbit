package dev.chandra.grabbit.download

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArrayList

/** Persistent list of downloads (newest first) backed by SharedPreferences JSON. */
object DownloadStore {
    private lateinit var prefs: SharedPreferences
    private val records = ArrayList<DownloadRecord>()
    private val listeners = CopyOnWriteArrayList<(List<DownloadRecord>) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        synchronized(records) {
            records.clear()
            runCatching {
                val arr = JSONArray(prefs.getString("list", "[]"))
                for (i in 0 until arr.length()) records.add(DownloadRecord.fromJson(arr.getJSONObject(i)))
            }
        }
    }

    fun all(): List<DownloadRecord> = synchronized(records) { records.map { it.copy() } }
    fun get(id: Long): DownloadRecord? = synchronized(records) { records.firstOrNull { it.id == id }?.copy() }

    fun addListener(l: (List<DownloadRecord>) -> Unit) { listeners.add(l); l(all()) }
    fun removeListener(l: (List<DownloadRecord>) -> Unit) { listeners.remove(l) }

    fun add(r: DownloadRecord) { synchronized(records) { records.add(0, r) }; persist() }

    fun update(id: Long, mutate: (DownloadRecord) -> Unit) {
        synchronized(records) { records.firstOrNull { it.id == id }?.let(mutate) }
        persist()
    }

    fun remove(id: Long) { synchronized(records) { records.removeAll { it.id == id } }; persist() }

    fun clearFinished() {
        synchronized(records) { records.removeAll { it.status == DownloadStatus.DONE || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED } }
        persist()
    }

    private var lastPersist = 0L
    private fun persist(force: Boolean = false) {
        val snap = all()
        main.post { listeners.forEach { it(snap) } }
        val now = System.currentTimeMillis()
        if (!force && now - lastPersist < 500) return // progress updates come fast; throttle disk writes
        lastPersist = now
        val arr = JSONArray(); snap.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun flush() = persist(force = true)
}
