package com.zynnn.axwarestream.core.stream

import android.content.Context
import com.zynnn.axwarestream.core.security.KeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Resolution(val label: String, val long: Int, val short: Int, val defaultKbps: Int) {
    SD540("540p", 960, 540, 1800), HD720("720p", 1280, 720, 2500), FHD1080("1080p", 1920, 1080, 4000);
    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: HD720 }
}

enum class VideoSourceKind(val label: String) { CAMERA("Kamera"), SCREEN("Layar / Game") }

enum class AudioSourceKind(val label: String) {
    MIC("Mikrofon"), INTERNAL("Audio internal (game)"), MIX("Mikrofon + internal");
    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: MIX }
}

/** Akun TikTok: username untuk membaca event live + kredensial RTMP dari LIVE Producer. */
data class TikTokAccount(
    val username: String = "",
    val server: String = "",
    val streamKey: String = "",
    /** API key Euler Stream (opsional) untuk menaikkan batas sign server event. */
    val signApiKey: String = "",
    /** Sambungkan event otomatis saat aplikasi dibuka / saat mulai live. */
    val autoConnect: Boolean = true,
) {
    val destination get() = Destination(server = server, streamKey = streamKey)
    val hasStreamKey get() = destination.validate() == null
}

data class StreamSettings(
    val resolution: Resolution = Resolution.HD720,
    val portrait: Boolean = true,
    val fps: Int = 30,
    val videoKbps: Int = 2500,
    val audioKbps: Int = 128,
    val source: VideoSourceKind = VideoSourceKind.CAMERA,
    val audio: AudioSourceKind = AudioSourceKind.MIX,
    val recordLocal: Boolean = false,
    val overlayInStream: Boolean = true,
    val frontCamera: Boolean = true,
    val serverPort: Int = 8787,
    val webhookToken: String = "",
    val measuredUplinkKbps: Int = 0,
    /** Tata letak layar game di frame siaran (mode Layar / Game). */
    val layout: ScreenLayout = ScreenLayout(),
    /** Tampilkan komentar live di layar Axware (hanya untuk streamer, tidak ikut siaran). */
    val showLiveComments: Boolean = true,
    /** Volume mikrofon & audio internal (1 = 100%, maks 2 = 200%) untuk semua mode siaran. */
    val micVolume: Float = 1f,
    val internalVolume: Float = 1f,
) {
    /** Lebar/tinggi output encoder (sebelum rotasi). */
    val width get() = resolution.long
    val height get() = resolution.short
    val outW get() = if (portrait) resolution.short else resolution.long
    val outH get() = if (portrait) resolution.long else resolution.short
}

/** Penyimpanan pengaturan + akun TikTok (stream key & API key terenkripsi Android Keystore). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("axware_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<StreamSettings> = _settings.asStateFlow()

    private val _tiktok = MutableStateFlow(
        TikTokAccount(
            username = prefs.getString("tt_username", "") ?: "",
            server = prefs.getString("tt_server", "") ?: "",
            streamKey = KeyCipher.decrypt(prefs.getString("tt_key", "") ?: ""),
            signApiKey = KeyCipher.decrypt(prefs.getString("tt_sign_key", "") ?: ""),
            autoConnect = prefs.getBoolean("tt_auto", true),
        )
    )
    val tiktok: StateFlow<TikTokAccount> = _tiktok.asStateFlow()

    private fun load() = StreamSettings(
        resolution = Resolution.parse(prefs.getString("resolution", null)),
        portrait = prefs.getBoolean("portrait", true),
        fps = prefs.getInt("fps", 30),
        videoKbps = prefs.getInt("videoKbps", 2500),
        audioKbps = prefs.getInt("audioKbps", 128),
        source = if (prefs.getString("source", "CAMERA") == "SCREEN") VideoSourceKind.SCREEN else VideoSourceKind.CAMERA,
        audio = AudioSourceKind.parse(prefs.getString("audio", null)),
        recordLocal = prefs.getBoolean("recordLocal", false),
        overlayInStream = prefs.getBoolean("overlayInStream", true),
        frontCamera = prefs.getBoolean("frontCamera", true),
        serverPort = prefs.getInt("serverPort", 8787),
        webhookToken = prefs.getString("webhookToken", "") ?: "",
        measuredUplinkKbps = prefs.getInt("measuredUplinkKbps", 0),
        layout = ScreenLayout(
            orientation = GameOrientation.parse(prefs.getString("sl_orientation", null)),
            fit = FitMode.parse(prefs.getString("sl_fit", null)),
            zoom = prefs.getFloat("sl_zoom", 1f),
            centerX = prefs.getFloat("sl_cx", 0.5f),
            centerY = prefs.getFloat("sl_cy", 0.5f),
            background = LayoutBackground.parse(prefs.getString("sl_bg", null)),
            bgColor = prefs.getLong("sl_bgColor", 0xFF101018),
        ),
        showLiveComments = prefs.getBoolean("showLiveComments", true),
        micVolume = prefs.getFloat("micVolume", 1f).coerceIn(0f, 2f),
        internalVolume = prefs.getFloat("internalVolume", 1f).coerceIn(0f, 2f),
    )

    fun update(block: (StreamSettings) -> StreamSettings) {
        val s = block(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString("resolution", s.resolution.name).putBoolean("portrait", s.portrait).putInt("fps", s.fps)
            .putInt("videoKbps", s.videoKbps).putInt("audioKbps", s.audioKbps)
            .putString("source", s.source.name).putString("audio", s.audio.name)
            .putBoolean("recordLocal", s.recordLocal).putBoolean("overlayInStream", s.overlayInStream)
            .putBoolean("frontCamera", s.frontCamera).putInt("serverPort", s.serverPort)
            .putString("webhookToken", s.webhookToken).putInt("measuredUplinkKbps", s.measuredUplinkKbps)
            .putString("sl_orientation", s.layout.orientation.name).putString("sl_fit", s.layout.fit.name)
            .putFloat("sl_zoom", s.layout.zoom).putFloat("sl_cx", s.layout.centerX).putFloat("sl_cy", s.layout.centerY)
            .putString("sl_bg", s.layout.background.name).putLong("sl_bgColor", s.layout.bgColor)
            .putBoolean("showLiveComments", s.showLiveComments)
            .putFloat("micVolume", s.micVolume).putFloat("internalVolume", s.internalVolume)
            .apply()
    }

    fun updateTikTok(block: (TikTokAccount) -> TikTokAccount) {
        val t = block(_tiktok.value)
        _tiktok.value = t
        prefs.edit()
            .putString("tt_username", t.username).putString("tt_server", t.server)
            .putString("tt_key", KeyCipher.encrypt(t.streamKey)).putString("tt_sign_key", KeyCipher.encrypt(t.signApiKey))
            .putBoolean("tt_auto", t.autoConnect)
            .apply()
    }
}
