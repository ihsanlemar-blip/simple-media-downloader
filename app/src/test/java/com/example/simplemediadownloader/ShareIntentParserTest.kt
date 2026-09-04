package com.example.simplemediadownloader

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareIntentParserTest {
    @Test
    fun `parses first valid URL and reports additional URLs`() {
        val result = ShareIntentParser.parse(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(
                    Intent.EXTRA_TEXT,
                    "First https://example.test/watch?v=1 then https://second.test/video",
                ),
        ) as SharedUrlResult.Valid

        assertEquals("https://example.test/watch?v=1", result.url)
        assertTrue(result.additionalUrlDetected)
    }

    @Test
    fun `accepts a single link shared as styled text content`() {
        val result = ShareIntentParser.parse(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/example"),
        ) as SharedUrlResult.Valid

        assertEquals("https://youtu.be/example", result.url)
        assertFalse(result.additionalUrlDetected)
    }

    @Test
    fun `rejects missing malformed wrong-action and extremely long shares`() {
        val missing = ShareIntentParser.parse(Intent(Intent.ACTION_SEND).setType("text/plain"))
        val malformed = ShareIntentParser.parseText("Open https://%%%")
        val wrongAction = ShareIntentParser.parse(Intent(Intent.ACTION_VIEW))
        val wrongMime = ShareIntentParser.parse(
            Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_TEXT, "https://example.test/video"),
        )
        val missingMime = ShareIntentParser.parse(
            Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_TEXT, "https://example.test/video"),
        )
        val tooLong = ShareIntentParser.parseText(
            "https://example.test/" + "a".repeat(ShareIntentParser.MAX_SHARED_TEXT_LENGTH),
        )

        assertTrue(missing is SharedUrlResult.Invalid)
        assertTrue(malformed is SharedUrlResult.Invalid)
        assertTrue(wrongAction is SharedUrlResult.Invalid)
        assertTrue(wrongMime is SharedUrlResult.Invalid)
        assertTrue(missingMime is SharedUrlResult.Invalid)
        assertTrue(tooLong is SharedUrlResult.Invalid)
    }

    @Test
    fun `short display URL preserves both identifying ends`() {
        val url = "https://example.test/a/very/long/path/to/media?video=important-id"
        val shortened = shortenedSourceUrl(url, maximumLength = 32)

        assertTrue(shortened.length <= 32)
        assertTrue(shortened.startsWith("https://example"))
        assertTrue(shortened.endsWith("important-id"))
    }
}
