package com.zynnn.axwarestream.core.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Menyimpan proyek overlay (semua scene, template) secara lokal sebagai JSON.
 * Setiap perubahan menaikkan [OverlayProject.revision] dan memberi tahu listener (server SSE, renderer).
 */
class OverlayRepository(private val file: File, val mediaDir: File) {

    private val lock = Any()
    private val _project = MutableStateFlow(load())
    val project: StateFlow<OverlayProject> = _project.asStateFlow()
    private val listeners = CopyOnWriteArrayList<(OverlayProject) -> Unit>()

    init { mediaDir.mkdirs() }

    fun addListener(l: (OverlayProject) -> Unit) { listeners.add(l) }
    fun removeListener(l: (OverlayProject) -> Unit) { listeners.remove(l) }

    private fun load(): OverlayProject = try {
        if (file.exists()) OverlayProject.fromJson(JSONObject(file.readText())) else OverlayProject.default()
    } catch (_: Exception) {
        OverlayProject.default()
    }

    fun update(block: (OverlayProject) -> OverlayProject) {
        val next: OverlayProject
        synchronized(lock) {
            next = block(_project.value).let { it.copy(revision = _project.value.revision + 1) }
            _project.value = next
        }
        scheduleSave()
        listeners.forEach { runCatching { it(next) } }
    }

    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "overlay-save").apply { isDaemon = true } }
    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Simpan ke disk di background; perubahan beruntun digabung menjadi satu penulisan. */
    private fun scheduleSave() {
        if (savePending.getAndSet(true)) return
        saver.execute {
            savePending.set(false)
            saveNow()
        }
    }

    /** Tulis proyek saat ini ke file secara atomik (dipakai juga oleh test). */
    fun saveNow() = synchronized(file) {
        runCatching {
            val json = _project.value.toJson().toString()
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) { file.writeText(json); tmp.delete() }
        }
    }

    /** Tunggu penulisan tertunda selesai. */
    fun flush() { saver.submit {}.get() }

    // ---------- operasi scene ----------
    fun setActiveScene(id: String) = update { p -> if (p.scenes.any { it.id == id }) p.copy(activeSceneId = id) else p }

    fun addScene(scene: OverlayScene, activate: Boolean = true) =
        update { p -> p.copy(scenes = p.scenes + scene, activeSceneId = if (activate) scene.id else p.activeSceneId) }

    fun newScene(name: String, orientation: Orientation) = addScene(OverlayScene(name = name, orientation = orientation))

    fun duplicateScene(id: String) = update { p ->
        val src = p.scenes.firstOrNull { it.id == id } ?: return@update p
        val copy = src.copy(id = newId(), name = src.name + " (salinan)", elements = src.elements.map { it.copy(id = newId()) })
        p.copy(scenes = p.scenes + copy)
    }

    fun deleteScene(id: String) = update { p ->
        if (p.scenes.size <= 1) return@update p
        val rest = p.scenes.filterNot { it.id == id }
        p.copy(scenes = rest, activeSceneId = if (p.activeSceneId == id) rest.first().id else p.activeSceneId)
    }

    fun updateScene(id: String, block: (OverlayScene) -> OverlayScene) = update { p ->
        p.copy(scenes = p.scenes.map { if (it.id == id) block(it) else it })
    }

    fun saveAsTemplate(id: String) = update { p ->
        val src = p.scenes.firstOrNull { it.id == id } ?: return@update p
        p.copy(templates = p.templates + src.copy(id = newId(), name = src.name))
    }

    fun deleteTemplate(id: String) = update { p -> p.copy(templates = p.templates.filterNot { it.id == id }) }

    /** Tambah scene baru dari template; mengembalikan id scene baru. */
    fun applyTemplate(template: OverlayScene, activate: Boolean = true): String {
        val id = newId()
        addScene(template.copy(id = id, elements = template.elements.map { it.copy(id = newId()) }), activate)
        return id
    }

    // ---------- operasi elemen ----------
    fun addElement(sceneId: String, el: OverlayElement) = updateScene(sceneId) { it.copy(elements = it.elements + el) }

    fun updateElement(sceneId: String, el: OverlayElement) = updateScene(sceneId) { s ->
        s.copy(elements = s.elements.map { if (it.id == el.id) el else it })
    }

    fun removeElement(sceneId: String, elId: String) = updateScene(sceneId) { s -> s.copy(elements = s.elements.filterNot { it.id == elId }) }

    fun duplicateElement(sceneId: String, elId: String) = updateScene(sceneId) { s ->
        val src = s.elements.firstOrNull { it.id == elId } ?: return@updateScene s
        s.copy(elements = s.elements + src.copy(id = newId(), name = src.name + " 2", x = src.x + 40, y = src.y + 40))
    }

    /** dir = +1 naik satu lapis (lebih depan), -1 turun satu lapis. */
    fun moveLayer(sceneId: String, elId: String, dir: Int) = updateScene(sceneId) { s ->
        val list = s.elements.toMutableList()
        val i = list.indexOfFirst { it.id == elId }
        val j = i + dir
        if (i < 0 || j !in list.indices) return@updateScene s
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        s.copy(elements = list)
    }

    fun exportJson(): String = _project.value.toJson().toString(2)

    fun importJson(text: String) {
        val p = OverlayProject.fromJson(JSONObject(text))
        update { p }
    }

    fun mediaFile(name: String): File? {
        if (name.isBlank() || name.contains("/") || name.contains("..")) return null
        return File(mediaDir, name).takeIf { it.isFile }
    }

    companion object {
        fun newId() = UUID.randomUUID().toString().take(8)
    }
}
