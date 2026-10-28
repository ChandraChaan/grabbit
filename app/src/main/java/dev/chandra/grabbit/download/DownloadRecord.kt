package dev.chandra.grabbit.download

import org.json.JSONObject

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class DownloadRecord(
    val id: Long,
    val url: String,
    var fileName: String,
    val mime: String,
    val referer: String,
    val userAgent: String,
    val isHls: Boolean,
    val createdAt: Long,
    var status: DownloadStatus = DownloadStatus.QUEUED,
    var bytesDone: Long = 0,
    var bytesTotal: Long = -1,
    /** content:// or file:// URI of the saved file once DONE. */
    var savedUri: String? = null,
    var error: String? = null,
    var pageTitle: String = "",
) {
    val progressPercent: Int get() = if (bytesTotal > 0) ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100) else -1

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("fileName", fileName); put("mime", mime)
        put("referer", referer); put("userAgent", userAgent); put("isHls", isHls)
        put("createdAt", createdAt); put("status", status.name); put("bytesDone", bytesDone)
        put("bytesTotal", bytesTotal); put("savedUri", savedUri ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL); put("pageTitle", pageTitle)
    }

    companion object {
        fun fromJson(o: JSONObject) = DownloadRecord(
            id = o.getLong("id"), url = o.getString("url"), fileName = o.getString("fileName"),
            mime = o.optString("mime", "*/*"), referer = o.optString("referer"), userAgent = o.optString("userAgent"),
            isHls = o.optBoolean("isHls"), createdAt = o.optLong("createdAt"),
            status = runCatching { DownloadStatus.valueOf(o.optString("status")) }.getOrDefault(DownloadStatus.FAILED),
            bytesDone = o.optLong("bytesDone"), bytesTotal = o.optLong("bytesTotal", -1),
            savedUri = o.optString("savedUri").takeIf { it.isNotBlank() && it != "null" },
            error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
            pageTitle = o.optString("pageTitle"),
        ).also { if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) { it.status = DownloadStatus.FAILED; it.error = "Interrupted" } }
    }
}
