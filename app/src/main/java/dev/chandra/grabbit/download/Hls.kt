package dev.chandra.grabbit.download

import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Minimal HLS (m3u8) parsing: master variants, media segments, AES-128 keys, fMP4 init maps. */
object Hls {
    data class Variant(val url: String, val bandwidth: Long, val resolution: String?, val codecs: String?) {
        val height: Int get() = resolution?.substringAfter('x', "")?.toIntOrNull() ?: 0
        val label: String get() = when {
            height > 0 -> "${height}p"
            bandwidth > 0 -> "${bandwidth / 1000} kbps"
            else -> "stream"
        }
    }

    data class Segment(val url: String, val keyUrl: String?, val iv: ByteArray?, val sequence: Long)

    data class MediaPlaylist(val segments: List<Segment>, val initUrl: String?, val durationSec: Double)

    fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    fun parseMaster(text: String, baseUrl: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = attributes(line.substringAfter(':'))
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    out.add(Variant(resolve(baseUrl, lines[j]), attrs["BANDWIDTH"]?.toLongOrNull() ?: 0, attrs["RESOLUTION"], attrs["CODECS"]))
                }
                i = j
            }
            i++
        }
        return out.sortedByDescending { it.bandwidth }
    }

    fun parseMedia(text: String, baseUrl: String): MediaPlaylist {
        val segs = ArrayList<Segment>()
        var keyUrl: String? = null
        var keyIv: ByteArray? = null
        var initUrl: String? = null
        var seq = 0L
        var total = 0.0
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-KEY") -> {
                    val a = attributes(line.substringAfter(':'))
                    if (a["METHOD"] == "NONE") { keyUrl = null; keyIv = null }
                    else { keyUrl = a["URI"]?.let { resolve(baseUrl, it) }; keyIv = a["IV"]?.let { hex(it) } }
                }
                line.startsWith("#EXT-X-MAP") -> initUrl = attributes(line.substringAfter(':'))["URI"]?.let { resolve(baseUrl, it) }
                line.startsWith("#EXTINF") -> total += line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                line.isNotBlank() && !line.startsWith("#") -> { segs.add(Segment(resolve(baseUrl, line), keyUrl, keyIv, seq)); seq++ }
            }
        }
        return MediaPlaylist(segs, initUrl, total)
    }

    private fun attributes(s: String): Map<String, String> {
        val map = HashMap<String, String>()
        val re = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")
        for (m in re.findAll(s)) map[m.groupValues[1]] = m.groupValues[3].ifEmpty { m.groupValues[2] }
        return map
    }

    private fun hex(s: String): ByteArray {
        val clean = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun decrypt(data: ByteArray, key: ByteArray, seg: Segment): ByteArray {
        val iv = seg.iv ?: ByteArray(16).also { b -> val s = seg.sequence; for (k in 0 until 8) b[15 - k] = (s shr (8 * k)).toByte() }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }
}
