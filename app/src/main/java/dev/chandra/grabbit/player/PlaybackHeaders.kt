package dev.chandra.grabbit.player

import android.net.Uri
import android.webkit.CookieManager
import java.util.concurrent.ConcurrentHashMap

/** Per-host request headers (referer, cookies, UA) so the player can stream what the browser could. */
object PlaybackHeaders {
    private val byHost = ConcurrentHashMap<String, Map<String, String>>()

    fun register(url: String, referer: String?, userAgent: String?) {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
        val map = HashMap<String, String>()
        if (!userAgent.isNullOrBlank()) map["User-Agent"] = userAgent
        if (!referer.isNullOrBlank()) {
            map["Referer"] = referer
            runCatching { val u = java.net.URL(referer); map["Origin"] = "${u.protocol}://${u.host}" }
        }
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { map["Cookie"] = it }
        byHost[host] = map
    }

    fun forUrl(url: String): Map<String, String> {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return emptyMap()
        return byHost[host] ?: emptyMap()
    }
}
