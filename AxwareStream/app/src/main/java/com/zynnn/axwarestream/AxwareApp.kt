package com.zynnn.axwarestream

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.zynnn.axwarestream.core.tiktok.TikTokManager
import com.zynnn.axwarestream.core.events.EventHub
import com.zynnn.axwarestream.core.overlay.OverlayRenderer
import com.zynnn.axwarestream.core.overlay.OverlayRepository
import com.zynnn.axwarestream.core.server.OverlayServer
import com.zynnn.axwarestream.core.stream.SettingsStore
import java.io.File

/** Wadah dependensi aplikasi (satu instance per proses). */
class AxwareApp : Application() {

    lateinit var hub: EventHub; private set
    lateinit var overlays: OverlayRepository; private set
    lateinit var settings: SettingsStore; private set
    lateinit var tiktok: TikTokManager; private set
    lateinit var server: OverlayServer; private set

    fun newRenderer() = OverlayRenderer(overlays.mediaDir)

    override fun onCreate() {
        super.onCreate()
        instance = this
        hub = EventHub()
        overlays = OverlayRepository(File(filesDir, "overlay/project.json"), File(filesDir, "overlay/media"))
        settings = SettingsStore(this)
        tiktok = TikTokManager(hub) { settings.tiktok.value.signApiKey }
        settings.tiktok.value.let { if (it.autoConnect && it.username.isNotBlank()) tiktok.connect(it.username) }
        server = OverlayServer(hub, overlays, webhookToken = settings.settings.value.webhookToken)
        runCatching { server.start(settings.settings.value.serverPort) }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STREAM, "Siaran live", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_OVERLAY, "Overlay mengambang", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val CHANNEL_STREAM = "axware_stream"
        const val CHANNEL_OVERLAY = "axware_overlay"
        lateinit var instance: AxwareApp
            private set
    }
}
