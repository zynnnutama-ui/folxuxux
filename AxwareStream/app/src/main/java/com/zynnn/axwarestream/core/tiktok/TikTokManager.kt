package com.zynnn.axwarestream.core.tiktok

import com.zynnn.axwarestream.core.events.EventHub
import kotlinx.coroutines.flow.StateFlow

/** Satu koneksi event TikTok LIVE untuk seluruh aplikasi. */
class TikTokManager(hub: EventHub, signApiKey: () -> String) {
    private val client = TikTokLiveClient(hub, signApiKey = signApiKey)
    val status: StateFlow<TikTokStatus> = client.status
    val active: Boolean get() = status.value.state != TikTokState.IDLE && status.value.state != TikTokState.ERROR
    fun connect(username: String) = client.connect(username)
    fun disconnect() = client.disconnect()
}
