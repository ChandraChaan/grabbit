package dev.chandra.grabbit.extract

import dev.chandra.grabbit.download.Http
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** OkHttp-backed network layer for NewPipeExtractor. */
class NewPipeDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val b = okhttp3.Request.Builder().url(request.url())
        val body = request.dataToSend()?.toRequestBody()
        b.method(request.httpMethod(), body)
        b.header("User-Agent", UA)
        for ((k, v) in request.headers()) {
            b.removeHeader(k)
            v.forEach { b.addHeader(k, it) }
        }
        val resp = Http.client.newCall(b.build()).execute()
        if (resp.code == 429) { resp.close(); throw ReCaptchaException("reCaptcha Challenge requested", request.url()) }
        val text = resp.body?.string()
        val latest = resp.request.url.toString()
        return Response(resp.code, resp.message, resp.headers.toMultimap(), text, latest)
    }

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
