package com.example.simplemediadownloader

import java.net.URI

object PlatformResolver {
    fun fromUrl(url: String): String {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") -> "YouTube"
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> "TikTok"
            host == "instagram.com" || host.endsWith(".instagram.com") -> "Instagram"
            host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com") -> "X"
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" ->
                "Facebook"
            host == "reddit.com" || host.endsWith(".reddit.com") || host == "redd.it" -> "Reddit"
            host.isNotBlank() -> host.removePrefix("www.")
            else -> "Web"
        }
    }
}
