package com.example.simplemediadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object PlatformDesignTokens {
    // Platform Brand Primary Colors
    val YouTubeRed = Color(0xFFFF0000)
    val TikTokCyan = Color(0xFF00F2FE)
    val TikTokPink = Color(0xFFFE2C55)
    val TikTokBlack = Color(0xFF010101)
    val InstagramPurple = Color(0xFF833AB4)
    val InstagramPink = Color(0xFFE1306C)
    val InstagramOrange = Color(0xFFF77737)
    val FacebookBlue = Color(0xFF1877F2)
    val XBlack = Color(0xFF14171A)
    val RedditOrange = Color(0xFFFF4500)
    val GenericWeb = Color(0xFF4B5563)

    fun getBrandColor(platform: String): Color {
        return when (platform.lowercase()) {
            "youtube" -> YouTubeRed
            "tiktok" -> TikTokPink
            "instagram" -> InstagramPink
            "facebook" -> FacebookBlue
            "x", "twitter" -> Color(0xFF1DA1F2)
            "reddit" -> RedditOrange
            else -> GenericWeb
        }
    }

    fun getBrandGradient(platform: String): List<Color> {
        return when (platform.lowercase()) {
            "instagram" -> listOf(InstagramPurple, InstagramPink, InstagramOrange)
            "tiktok" -> listOf(Color(0xFF00F2FE), Color(0xFFFE2C55))
            "youtube" -> listOf(Color(0xFFFF0000), Color(0xFFCC0000))
            "facebook" -> listOf(Color(0xFF1877F2), Color(0xFF0C5ECC))
            "reddit" -> listOf(Color(0xFFFF4500), Color(0xFFFF5722))
            "x", "twitter" -> listOf(Color(0xFF2C3440), Color(0xFF14171A))
            else -> listOf(Color(0xFF4B5563), Color(0xFF374151))
        }
    }

    fun getPlatformIcon(platform: String): ImageVector {
        return when (platform.lowercase()) {
            "youtube" -> Icons.Rounded.PlayArrow
            "tiktok" -> Icons.Rounded.MusicNote
            "instagram" -> Icons.Rounded.CameraAlt
            "facebook" -> Icons.Rounded.Share
            "x", "twitter" -> Icons.Rounded.Tag
            "reddit" -> Icons.Rounded.Share
            else -> Icons.Rounded.Language
        }
    }
}

@Composable
fun PlatformBadge(
    platform: String,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
    size: Dp = 10.dp,
) {
    val brandColor = PlatformDesignTokens.getBrandColor(platform)
    val isDark = MaterialTheme.colorScheme.surface.isDark()

    val containerColor = if (isDark) {
        brandColor.copy(alpha = 0.16f)
    } else {
        brandColor.copy(alpha = 0.10f)
    }
    val contentColor = if (isDark) {
        brandColor.copy(alpha = 0.95f)
    } else {
        brandColor
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(size),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(
            width = 0.5.dp,
            color = brandColor.copy(alpha = if (isDark) 0.35f else 0.20f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                Icon(
                    imageVector = PlatformDesignTokens.getPlatformIcon(platform),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = platform,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                ),
                color = contentColor,
            )
        }
    }
}

@Composable
fun PlatformHeroGlow(
    platform: String,
    modifier: Modifier = Modifier,
) {
    val gradientColors = PlatformDesignTokens.getBrandGradient(platform)
    Box(
        modifier = modifier
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        gradientColors.first().copy(alpha = 0.25f),
                        gradientColors.last().copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

private fun Color.isDark(): Boolean {
    // Standard luminance formula
    val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
    return luminance < 0.5
}

enum class DateGroup(val stringRes: Int) {
    TODAY(R.string.date_group_today),
    YESTERDAY(R.string.date_group_yesterday),
    THIS_WEEK(R.string.date_group_this_week),
    OLDER(R.string.date_group_older),
}
