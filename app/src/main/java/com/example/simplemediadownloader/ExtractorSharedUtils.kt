package com.example.simplemediadownloader

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.regex.Pattern

const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
const val CRAWLER_USER_AGENT =
    "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
const val WHATSAPP_USER_AGENT =
    "WhatsApp/2.21.12.21 A"
const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"

val FACEBOOK_NAV_HEADERS = mapOf(
    "User-Agent" to USER_AGENT,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "none",
    "Sec-Fetch-User" to "?1",
)

val defaultGatewayClient: OkHttpClient = OkHttpClient.Builder()
    .cookieJar(CookieJar.NO_COOKIES)
    .dns(SafeDns())
    .addInterceptor(SecurityInterceptor(allowCleartextHttp = true))
    .build()

var gatewayClient: OkHttpClient = defaultGatewayClient

fun followRedirects(client: OkHttpClient, initialUrl: String): String {
    var currentUrl = initialUrl
    val redirectClient = client.newBuilder()
        .dns(SafeDns())
        .addInterceptor(SecurityInterceptor(allowCleartextHttp = true))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val reqBuilder = Request.Builder().url(currentUrl)
    if (initialUrl.contains("facebook.com") || initialUrl.contains("fb.watch")) {
        FACEBOOK_NAV_HEADERS.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
    } else {
        reqBuilder.addHeader("User-Agent", USER_AGENT)
    }

    try {
        redirectClient.newCall(reqBuilder.build()).execute().use { response ->
            currentUrl = response.request.url.toString()
            if (currentUrl.contains("/share/")) {
                val body = response.body?.readBoundedString().orEmpty()
                val canonical = extractPattern(body, """<link rel="canonical" href="([^"]+)"""")
                    ?: extractPattern(body, """<meta property="og:url" content="([^"]+)"""")
                if (!canonical.isNullOrBlank() && NetworkSecurityPolicy.isAllowedShareUrl(canonical)) {
                    currentUrl = canonical
                }
            }
        }
    } catch (_: Exception) {}
    return currentUrl
}

fun probeStreamSize(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>? = null,
): Long? {
    if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return null
    return try {
        val probeClient = client.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(4))
            .readTimeout(java.time.Duration.ofSeconds(4))
            .build()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
        headers?.forEach { (k, v) -> reqBuilder.header(k, v) }
        probeClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 206) {
                resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
            } else if (resp.isSuccessful) {
                val cl = resp.body?.contentLength()?.takeIf { it > 0 }
                cl ?: resp.header("Content-Length")?.toLongOrNull()
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }
}

fun cleanTitle(raw: String): String {
    var decoded = raw
    try {
        val hexPattern = Pattern.compile("&#x([0-9a-fA-F]+);")
        val hexMatcher = hexPattern.matcher(decoded)
        val sb = StringBuffer()
        while (hexMatcher.find()) {
            val hexStr = hexMatcher.group(1)
            val cp = hexStr?.toIntOrNull(16)
            if (cp != null) {
                hexMatcher.appendReplacement(sb, String(Character.toChars(cp)))
            }
        }
        hexMatcher.appendTail(sb)
        decoded = sb.toString()
    } catch (_: Exception) {}

    try {
        val decPattern = Pattern.compile("&#([0-9]+);")
        val decMatcher = decPattern.matcher(decoded)
        val sb2 = StringBuffer()
        while (decMatcher.find()) {
            val decStr = decMatcher.group(1)
            val cp = decStr?.toIntOrNull(10)
            if (cp != null) {
                decMatcher.appendReplacement(sb2, String(Character.toChars(cp)))
            }
        }
        decMatcher.appendTail(sb2)
        decoded = sb2.toString()
    } catch (_: Exception) {}

    decoded = decoded
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&middot;", "·")
        .replace("&#xb7;", "·")
        .replace("\r", " ")
        .replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    if (decoded.contains(" on Instagram: \"") && decoded.endsWith("\"")) {
        decoded = decoded.substringAfter(" on Instagram: \"").removeSuffix("\"")
    }
    if (decoded.endsWith(" | Facebook")) {
        decoded = decoded.removeSuffix(" | Facebook")
    }
    if (decoded.endsWith(" - Facebook")) {
        decoded = decoded.removeSuffix(" - Facebook")
    }

    val strippedPrefix = decoded.replace(
        Regex("""^\d+[\d.,]*[KkMmBb]?\s+(?:views|plays|reactions)\s*(?:[·|•]\s*\d+[\d.,]*[KkMmBb]?\s+(?:views|plays|reactions))*\s*[|•·]\s*""", RegexOption.IGNORE_CASE),
        "",
    )
    val withoutHashtags = strippedPrefix.replace(Regex("""\s*#\S+.*$"""), "").trim()
    val candidate = if (withoutHashtags.length >= 4) withoutHashtags else strippedPrefix
    return candidate.take(120).trim().ifBlank { "Media Video" }
}

fun findMetaProperty(html: String, property: String): String? {
    val pattern1 = Pattern.compile("""<meta\s+property=["']$property["']\s+content=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
    val matcher1 = pattern1.matcher(html)
    if (matcher1.find()) return unescapeJsonUrl(matcher1.group(1).orEmpty())

    val pattern2 = Pattern.compile("""<meta\s+content=["']([^"']+)["']\s+property=["']$property["']""", Pattern.CASE_INSENSITIVE)
    val matcher2 = pattern2.matcher(html)
    if (matcher2.find()) return unescapeJsonUrl(matcher2.group(1).orEmpty())

    return null
}

fun extractPattern(html: String, regex: String): String? {
    val pattern = Pattern.compile(regex)
    val matcher = pattern.matcher(html)
    return if (matcher.find()) matcher.group(1) else null
}

fun extractJsonFromHtmlTag(html: String, tagIdentifier: String): String? {
    val idx = html.indexOf(tagIdentifier)
    if (idx == -1) return null
    val tagEnd = html.indexOf('>', idx)
    if (tagEnd == -1) return null
    val scriptEnd = html.indexOf("</script>", tagEnd)
    if (scriptEnd == -1) return null
    return html.substring(tagEnd + 1, scriptEnd).trim()
}

fun unescapeJsonUrl(url: String): String {
    return url.replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u00252F", "/")
        .replace("\\u0025", "%")
        .replace("\\u0026", "&")
        .replace("\\u003D", "=")
        .replace("\\u003F", "?")
        .replace("&amp;", "&")
}

object ExtractorSharedUtils {
    const val USER_AGENT = com.example.simplemediadownloader.USER_AGENT
    const val CRAWLER_USER_AGENT = com.example.simplemediadownloader.CRAWLER_USER_AGENT
    const val WHATSAPP_USER_AGENT = com.example.simplemediadownloader.WHATSAPP_USER_AGENT
    const val MOBILE_USER_AGENT = com.example.simplemediadownloader.MOBILE_USER_AGENT
    val FACEBOOK_NAV_HEADERS = com.example.simplemediadownloader.FACEBOOK_NAV_HEADERS

    var gatewayClient: OkHttpClient
        get() = com.example.simplemediadownloader.gatewayClient
        set(value) {
            com.example.simplemediadownloader.gatewayClient = value
        }

    fun followRedirects(client: OkHttpClient, initialUrl: String): String =
        com.example.simplemediadownloader.followRedirects(client, initialUrl)

    fun probeStreamSize(client: OkHttpClient, url: String, headers: Map<String, String>? = null): Long? =
        com.example.simplemediadownloader.probeStreamSize(client, url, headers)

    fun cleanTitle(raw: String): String =
        com.example.simplemediadownloader.cleanTitle(raw)

    fun findMetaProperty(html: String, property: String): String? =
        com.example.simplemediadownloader.findMetaProperty(html, property)

    fun extractPattern(html: String, regex: String): String? =
        com.example.simplemediadownloader.extractPattern(html, regex)

    fun extractJsonFromHtmlTag(html: String, tagIdentifier: String): String? =
        com.example.simplemediadownloader.extractJsonFromHtmlTag(html, tagIdentifier)

    fun unescapeJsonUrl(url: String): String =
        com.example.simplemediadownloader.unescapeJsonUrl(url)
}
