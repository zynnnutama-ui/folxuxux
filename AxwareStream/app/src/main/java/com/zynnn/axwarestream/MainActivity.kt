package com.zynnn.axwarestream

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.zynnn.axwarestream.core.stream.Destination
import com.zynnn.axwarestream.service.FloatingOverlayService
import com.zynnn.axwarestream.service.StreamService
import com.zynnn.axwarestream.ui.AppActions
import com.zynnn.axwarestream.ui.AppRoot
import com.zynnn.axwarestream.ui.theme.AxwareTheme

class MainActivity : ComponentActivity(), AppActions {

    private val service = mutableStateOf<StreamService?>(null)
    private var pendingScreenDests: List<Destination> = emptyList()
    private var pendingAfterPermission: (() -> Unit)? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service.value = (binder as StreamService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) { service.value = null }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val denied = res.filter { !it.value && it.key != Manifest.permission.POST_NOTIFICATIONS }.keys
        if (denied.isEmpty()) pendingAfterPermission?.invoke()
        else toast("Izin dibutuhkan: " + denied.joinToString { it.substringAfterLast('.') })
        pendingAfterPermission = null
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        val svc = service.value
        if (r.resultCode == RESULT_OK && data != null && svc != null) {
            svc.startScreenLive(r.resultCode, data, pendingScreenDests).onFailure { toast("Gagal mulai live: ${it.message}") }
        } else toast("Izin rekam layar dibatalkan")
    }

    private val audioTestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        val svc = service.value
        if (r.resultCode == RESULT_OK && data != null && svc != null) {
            svc.startAudioTest(r.resultCode, data).onFailure { toast("Gagal tes audio: ${it.message}") }
        } else toast("Izin rekam layar dibatalkan")
    }

    override fun startAudioTest() {
        withPermissions(listOf(Manifest.permission.RECORD_AUDIO)) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            audioTestLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    override fun stopAudioTest() { service.value?.stopAudioTest() ?: com.zynnn.axwarestream.core.audio.AudioTester.stop() }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = imageCallback; imageCallback = null
        if (uri != null && cb != null) cb(copyToMedia(uri))
    }
    private var imageCallback: ((String?) -> Unit)? = null

    private val importJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            (application as AxwareApp).overlays.importJson(text)
        }.onSuccess { toast("Overlay berhasil diimpor") }.onFailure { toast("File tidak valid: ${it.message}") }
    }

    private val exportJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)!!.use { it.write((application as AxwareApp).overlays.exportJson().toByteArray()) }
        }.onSuccess { toast("Overlay diekspor") }.onFailure { toast("Gagal ekspor: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AxwareTheme { AppRoot(service = service.value, actions = this) } }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, StreamService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        runCatching { unbindService(conn) }
        service.value = null
        super.onStop()
    }

    // ----------------------------------------------------------------- AppActions
    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun withPermissions(perms: List<String>, then: () -> Unit) {
        val all = perms.toMutableList()
        if (Build.VERSION.SDK_INT >= 33) all += Manifest.permission.POST_NOTIFICATIONS
        val missing = all.filterNot { has(it) }
        if (missing.isEmpty()) then() else { pendingAfterPermission = then; permLauncher.launch(missing.toTypedArray()) }
    }

    override fun hasCameraPermission(): Boolean = has(Manifest.permission.CAMERA) && has(Manifest.permission.RECORD_AUDIO)

    override fun startScreenLive(dests: List<Destination>) {
        withPermissions(listOf(Manifest.permission.RECORD_AUDIO)) {
            pendingScreenDests = dests
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    override fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun setFloatingOverlay(on: Boolean) {
        if (on) {
            if (!canDrawOverlays()) { requestOverlayPermission(); toast("Izinkan \"Tampilkan di atas aplikasi lain\" lalu aktifkan lagi"); return }
            withPermissions(emptyList()) { ContextCompat.startForegroundService(this, Intent(this, FloatingOverlayService::class.java)) }
        } else {
            startService(Intent(this, FloatingOverlayService::class.java).setAction(FloatingOverlayService.ACTION_STOP))
        }
    }

    override fun setStreamerChat(on: Boolean) {
        if (on) {
            if (!canDrawOverlays()) { requestOverlayPermission(); toast("Izinkan \"Tampilkan di atas aplikasi lain\" lalu aktifkan lagi"); return }
            withPermissions(emptyList()) { ContextCompat.startForegroundService(this, Intent(this, com.zynnn.axwarestream.service.StreamerChatService::class.java)) }
        } else {
            startService(Intent(this, com.zynnn.axwarestream.service.StreamerChatService::class.java).setAction(com.zynnn.axwarestream.service.StreamerChatService.ACTION_STOP))
        }
    }

    override fun pickImage(onResult: (String?) -> Unit) { imageCallback = onResult; pickImage.launch("image/*") }
    override fun importOverlay() = importJson.launch(arrayOf("application/json", "text/plain", "*/*"))
    override fun exportOverlay() = exportJson.launch("axware-overlay.json")

    override fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("Tidak ada aplikasi untuk membuka tautan") }
    }

    override fun copy(label: String, text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < 33) toast("Disalin")
    }

    override fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Bagikan"))
    }

    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun copyToMedia(uri: Uri): String? = runCatching {
        val app = application as AxwareApp
        val ext = when (contentResolver.getType(uri)) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg" }
        val name = "img_" + System.currentTimeMillis() + "." + ext
        val out = java.io.File(app.overlays.mediaDir, name)
        contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        name
    }.getOrNull()
}
