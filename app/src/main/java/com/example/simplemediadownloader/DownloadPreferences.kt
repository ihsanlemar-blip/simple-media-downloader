package com.example.simplemediadownloader

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class DefaultDownloadChoice(
    val label: String,
    val actionLabel: String,
) {
    ALWAYS_ASK("Always ask for quality", "Choose quality"),
    BEST_VIDEO("Best video", "Download best video"),
    VIDEO_1080("1080p", "Download 1080p"),
    VIDEO_720("720p", "Download 720p"),
    VIDEO_480("480p", "Download 480p"),
    ORIGINAL_AUDIO("Audio (platform default)", "Download audio"),
    MP3_AUDIO("Convert to MP3", "Download MP3"),
    ;

    val targetHeight: Int?
        get() = when (this) {
            VIDEO_1080 -> 1080
            VIDEO_720 -> 720
            VIDEO_480 -> 480
            else -> null
        }

    companion object {
        fun fromStored(value: String?): DefaultDownloadChoice =
            when (value) {
                "MP3" -> MP3_AUDIO
                else -> entries.firstOrNull { it.name == value } ?: BEST_VIDEO
            }
    }
}

enum class AppThemeMode(
    val label: String,
    val description: String,
) {
    SYSTEM("System Default", "Follow OS light or dark theme"),
    DYNAMIC("Dynamic Monet", "Material You accent matching your wallpaper"),
    AMOLED_DARK("Pure AMOLED Black", "Deep #000000 contrast with OLED power saving"),
    LIGHT("Clean Light", "Crisp white surface with refined contrast"),
    ;

    companion object {
        fun fromStored(value: String?): AppThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

interface DownloadPreferenceStore {
    val youtubeMp3BitrateKbps: StateFlow<Int> get() = DefaultAudioPreferences.bitrate
    suspend fun setYoutubeMp3BitrateKbps(bitrateKbps: Int) { require(bitrateKbps in PlatformAudioPolicy.MP3_BITRATES) }
    val defaultChoice: StateFlow<DefaultDownloadChoice>
    suspend fun setDefaultChoice(choice: DefaultDownloadChoice)
    val themeMode: StateFlow<AppThemeMode>
    suspend fun setThemeMode(mode: AppThemeMode)
    val wifiOnly: StateFlow<Boolean>
    suspend fun setWifiOnly(enabled: Boolean)
    val maxConcurrentDownloads: StateFlow<Int>
    suspend fun setMaxConcurrentDownloads(limit: Int)
    val vaultViewMode: StateFlow<String>
    suspend fun setVaultViewMode(mode: String)
    val allowThirdPartyGateways: StateFlow<Boolean>
    suspend fun setAllowThirdPartyGateways(enabled: Boolean)
}

private object DefaultAudioPreferences {
    val bitrate: StateFlow<Int> = MutableStateFlow(PlatformAudioPolicy.DEFAULT_MP3_BITRATE_KBPS).asStateFlow()
}

class SharedPreferencesDownloadPreferenceStore(
    context: Context,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) : DownloadPreferenceStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _youtubeMp3BitrateKbps = MutableStateFlow(
        preferences.getInt(KEY_YOUTUBE_MP3_BITRATE, PlatformAudioPolicy.DEFAULT_MP3_BITRATE_KBPS)
            .takeIf { it in PlatformAudioPolicy.MP3_BITRATES } ?: PlatformAudioPolicy.DEFAULT_MP3_BITRATE_KBPS,
    )
    override val youtubeMp3BitrateKbps: StateFlow<Int> = _youtubeMp3BitrateKbps.asStateFlow()

    @SuppressLint("UseKtx")
    override suspend fun setYoutubeMp3BitrateKbps(bitrateKbps: Int) = withContext(dispatchers.io) {
        require(bitrateKbps in PlatformAudioPolicy.MP3_BITRATES)
        check(preferences.edit().putInt(KEY_YOUTUBE_MP3_BITRATE, bitrateKbps).commit()) { "Could not save YouTube MP3 quality" }
        _youtubeMp3BitrateKbps.value = bitrateKbps
    }

    private val _defaultChoice = MutableStateFlow(
        DefaultDownloadChoice.fromStored(preferences.getString(KEY_DEFAULT_CHOICE, null)),
    )
    override val defaultChoice: StateFlow<DefaultDownloadChoice> = _defaultChoice.asStateFlow()

    private val _themeMode = MutableStateFlow(
        AppThemeMode.fromStored(preferences.getString(KEY_THEME_MODE, null)),
    )
    override val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _wifiOnly = MutableStateFlow(
        preferences.getBoolean(KEY_WIFI_ONLY, false),
    )
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _maxConcurrentDownloads = MutableStateFlow(
        preferences.getInt(KEY_MAX_CONCURRENT, 3),
    )
    override val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads.asStateFlow()

    private val _vaultViewMode = MutableStateFlow(
        preferences.getString(KEY_VAULT_VIEW_MODE, "grid") ?: "grid",
    )
    override val vaultViewMode: StateFlow<String> = _vaultViewMode.asStateFlow()

    private val _allowThirdPartyGateways = MutableStateFlow(
        preferences.getBoolean(KEY_ALLOW_THIRD_PARTY_GATEWAYS, false),
    )
    override val allowThirdPartyGateways: StateFlow<Boolean> = _allowThirdPartyGateways.asStateFlow()

    @SuppressLint("UseKtx") // commit() reports persistence failures; edit(commit = true) does not.
    override suspend fun setDefaultChoice(choice: DefaultDownloadChoice) {
        withContext(dispatchers.io) {
            check(
                preferences.edit()
                    .putString(KEY_DEFAULT_CHOICE, choice.name)
                    .commit(),
            ) { "Could not save the default download choice." }
            _defaultChoice.value = choice
        }
    }

    @SuppressLint("UseKtx")
    override suspend fun setThemeMode(mode: AppThemeMode) {
        withContext(dispatchers.io) {
            check(
                preferences.edit()
                    .putString(KEY_THEME_MODE, mode.name)
                    .commit(),
            ) { "Could not save the theme mode preference." }
            _themeMode.value = mode
        }
    }

    @SuppressLint("UseKtx")
    override suspend fun setWifiOnly(enabled: Boolean) {
        withContext(dispatchers.io) {
            check(
                preferences.edit()
                    .putBoolean(KEY_WIFI_ONLY, enabled)
                    .commit(),
            ) { "Could not save wifi only preference." }
            _wifiOnly.value = enabled
        }
    }

    @SuppressLint("UseKtx")
    override suspend fun setMaxConcurrentDownloads(limit: Int) {
        withContext(dispatchers.io) {
            val valid = limit.coerceIn(1, 5)
            check(
                preferences.edit()
                    .putInt(KEY_MAX_CONCURRENT, valid)
                    .commit(),
            ) { "Could not save concurrency preference." }
            _maxConcurrentDownloads.value = valid
        }
    }

    @SuppressLint("UseKtx")
    override suspend fun setVaultViewMode(mode: String) {
        withContext(dispatchers.io) {
            check(
                preferences.edit()
                    .putString(KEY_VAULT_VIEW_MODE, mode)
                    .commit(),
            ) { "Could not save vault view mode preference." }
            _vaultViewMode.value = mode
        }
    }

    @SuppressLint("UseKtx")
    override suspend fun setAllowThirdPartyGateways(enabled: Boolean) {
        withContext(dispatchers.io) {
            check(
                preferences.edit()
                    .putBoolean(KEY_ALLOW_THIRD_PARTY_GATEWAYS, enabled)
                    .commit(),
            ) { "Could not save third-party gateways preference." }
            _allowThirdPartyGateways.value = enabled
        }
    }

    companion object {
        internal const val PREFERENCES_NAME = "download_preferences"
        private const val KEY_YOUTUBE_MP3_BITRATE = "youtube_mp3_bitrate_kbps"
        private const val KEY_DEFAULT_CHOICE = "default_download_choice"
        private const val KEY_THEME_MODE = "app_theme_mode"
        private const val KEY_WIFI_ONLY = "pref_wifi_only"
        private const val KEY_MAX_CONCURRENT = "pref_max_concurrent"
        private const val KEY_VAULT_VIEW_MODE = "pref_vault_view_mode"
        private const val KEY_ALLOW_THIRD_PARTY_GATEWAYS = "pref_allow_third_party_gateways"
    }
}

internal object DefaultDownloadChoiceMapper {
    fun select(
        choice: DefaultDownloadChoice,
        catalog: MediaFormatCatalog,
        bestVideoFallback: AvailableFormat,
        youtubeMp3BitrateKbps: Int = PlatformAudioPolicy.DEFAULT_MP3_BITRATE_KBPS,
    ): AvailableFormat? = when (choice) {
        DefaultDownloadChoice.ALWAYS_ASK -> null
        DefaultDownloadChoice.BEST_VIDEO -> bestVideo(catalog) ?: bestVideoFallback
        DefaultDownloadChoice.VIDEO_1080,
        DefaultDownloadChoice.VIDEO_720,
        DefaultDownloadChoice.VIDEO_480 -> videoAtOrBelow(
            catalog,
            requireNotNull(choice.targetHeight),
        )
        DefaultDownloadChoice.ORIGINAL_AUDIO -> PlatformAudioPolicy.selectAudio(catalog, youtubeMp3BitrateKbps)
        DefaultDownloadChoice.MP3_AUDIO -> AudioFormatOptions.augment(catalog).audioFormats.firstOrNull {
            it.mode == DownloadMode.AUDIO_MP3 && it.targetAudioBitrateKbps == youtubeMp3BitrateKbps
        }

    }

    private fun bestVideo(catalog: MediaFormatCatalog): AvailableFormat? =
        catalog.videoFormats
            .asSequence()
            .maxWithOrNull(videoQualityComparator)

    private fun videoAtOrBelow(catalog: MediaFormatCatalog, targetHeight: Int): AvailableFormat? =
        catalog.videoFormats
            .asSequence()
            .filter { it.height in 1..targetHeight }
            .maxWithOrNull(videoQualityComparator)
            ?: catalog.videoFormats.firstOrNull {
                it.isQuickPreset && it.height == targetHeight
            }

    private val videoQualityComparator = compareBy<AvailableFormat> { it.height }
        .thenBy { it.width }
        .thenBy { it.fps }
        .thenBy { it.bitrateKbps }
}
