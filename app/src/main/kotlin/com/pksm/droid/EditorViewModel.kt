package com.pksm.droid

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import pksm.Gen4Save
import pksm.PK4
import java.io.File

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    var save by mutableStateOf<Gen4Save?>(null)
        private set
    var saveUri by mutableStateOf<Uri?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set

    /** Bumped on every change so composables reading mutable save data recompose. */
    var revision by mutableIntStateOf(0)
        private set

    /** One-shot message shown in a snackbar. */
    var status by mutableStateOf<String?>(null)

    private val prefs = app.getSharedPreferences("pksm", Context.MODE_PRIVATE)
    private val resolver get() = getApplication<Application>().contentResolver

    val lastUri: Uri? get() = prefs.getString("last_uri", null)?.let(Uri::parse)

    fun open(uri: Uri) {
        try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
            val s = Gen4Save.load(bytes)
            backup(bytes)
            save = s
            saveUri = uri
            dirty = false
            revision++
            try {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // provider doesn't support persistable grants; fine for this session
            }
            prefs.edit().putString("last_uri", uri.toString()).apply()
            status = "Loaded ${s.trainerName.ifEmpty { "save" }}"
        } catch (e: Exception) {
            status = "Open failed: ${e.message}"
        }
    }

    /** Apply an edit to the loaded save; surfaces errors as a message instead of crashing. */
    fun edit(block: (Gen4Save) -> Unit) {
        val s = save ?: return
        try {
            block(s)
            dirty = true
            revision++
        } catch (e: Exception) {
            status = e.message ?: e.toString()
        }
    }

    /** Write the edited save back over the file it was opened from, then verify by reading it back. */
    fun writeBack() {
        val s = save ?: return
        val uri = saveUri ?: return
        try {
            val out = s.export()
            resolver.openOutputStream(uri, "wt")?.use { it.write(out) } ?: error("Cannot open file for writing")
            val check = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (check == null || !check.contentEquals(out)) error("Verification failed: file on disk differs from what was written")
            dirty = false
            revision++
            status = "Saved. Reload the game in melonDS."
        } catch (e: Exception) {
            status = "Save failed: ${e.message}"
        }
    }

    fun writeCopy(uri: Uri) {
        val s = save ?: return
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(s.export()) } ?: error("Cannot open file for writing")
            status = "Copy written"
        } catch (e: Exception) {
            status = "Copy failed: ${e.message}"
        }
    }

    fun readBytes(uri: Uri): ByteArray? = try {
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
    } catch (e: Exception) {
        status = "Read failed: ${e.message}"
        null
    }

    fun readPk4(uri: Uri): PK4? = try {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
        PK4.fromFile(bytes)
    } catch (e: Exception) {
        status = "Import failed: ${e.message}"
        null
    }

    fun writePk4(uri: Uri, pk: PK4) {
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(pk.exportPk4()) } ?: error("Cannot open file for writing")
            status = "Exported .pk4"
        } catch (e: Exception) {
            status = "Export failed: ${e.message}"
        }
    }

    /** Keep the five most recent copies of the original file in app-private storage. */
    private fun backup(bytes: ByteArray) {
        try {
            val dir = File(getApplication<Application>().filesDir, "backups").apply { mkdirs() }
            File(dir, "backup-${System.currentTimeMillis()}.sav").writeBytes(bytes)
            dir.listFiles()?.sortedBy { it.name }?.dropLast(5)?.forEach { it.delete() }
        } catch (_: Exception) {
            // best effort
        }
    }
}
