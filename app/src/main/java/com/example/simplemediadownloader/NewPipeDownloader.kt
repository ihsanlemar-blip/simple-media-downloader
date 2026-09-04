package com.example.simplemediadownloader

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class OkHttpNewPipeDownloader(
    val client: OkHttpClient = OkHttpClient.Builder().build(),
) : Downloader() {

    private val mCookies = ConcurrentHashMap<String, String>()

    fun setCookie(key: String, cookie: String) {
        mCookies[key] = cookie
    }

    fun getCookies(url: String): String {
        return mCookies.values.joinToString("; ")
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = if (httpMethod.equals("POST", ignoreCase = true) ||
            httpMethod.equals("PUT", ignoreCase = true)
        ) {
            dataToSend?.toRequestBody(null) ?: ByteArray(0).toRequestBody(null)
        } else {
            null
        }

        val okHttpRequestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept-Language", "en-US,en;q=0.9")

        val cookies = getCookies(url)
        if (cookies.isNotEmpty()) {
            okHttpRequestBuilder.addHeader("Cookie", cookies)
        }

        headers?.forEach { (name, values) ->
            okHttpRequestBuilder.removeHeader(name)
            values.forEach { value ->
                okHttpRequestBuilder.addHeader(name, value)
            }
        }

        val okHttpResponse = client.newCall(okHttpRequestBuilder.build()).execute()
        if (okHttpResponse.code == 429) {
            okHttpResponse.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        // Keep session / consent cookies for subsequent calls
        okHttpResponse.headers("Set-Cookie").forEach { cookieHeader ->
            val cookiePart = cookieHeader.substringBefore(';')
            val key = cookiePart.substringBefore('=', "")
            if (key.isNotBlank()) {
                mCookies[key] = cookiePart
            }
        }

        val responseBody = okHttpResponse.body?.string().orEmpty()

        return Response(
            okHttpResponse.code,
            okHttpResponse.message,
            okHttpResponse.headers.toMultimap(),
            responseBody,
            okHttpResponse.request.url.toString(),
        )
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
