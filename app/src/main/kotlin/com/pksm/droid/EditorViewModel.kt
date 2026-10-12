package com.pksm.droid

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import pksm.Gen4Save
import pksm.PK4
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** User-chosen folder that receives a timestamped copy of the file before every save. */
    var backupFolder by mutableStateOf<Uri?>(null)
        private set

    /** Exact bytes currently on disk (as loaded, or as last written by us). */
    private var diskBytes: ByteArray? = null

    private val prefs = app.getSharedPreferences("pksm", Context.MODE_PRIVATE)

    init {
        backupFolder = prefs.getString("backup_folder", null)?.let(Uri::parse)
    }
    private val resolver get() = getApplication<Application>().contentResolver

    val lastUri: Uri? get() = prefs.getString("last_uri", null)?.let(Uri::parse)

    fun open(uri: Uri) {
        try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
            val s = Gen4Save.load(bytes)
            backup(bytes)
            diskBytes = bytes
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

    /**
     * Write the edited save back over the file it was opened from.
     * Order: validate the new bytes -> back up what is on disk now -> write -> read back and compare.
     * Any failure before the write leaves the original file untouched.
     */
    fun writeBack() {
        val s = save ?: return
        val uri = saveUri ?: return
        val before = diskBytes ?: return
        try {
            val out = s.export()
            Gen4Save.load(out) // refuse to write anything that would not load again
            val where = backupExternal(before)
            backup(before)
            resolver.openOutputStream(uri, "wt")?.use { it.write(out) } ?: error("Cannot open file for writing")
            val check = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (check == null || !check.contentEquals(out)) {
                error("Verification failed: file on disk differs from what was written. Restore from your backup.")
            }
            diskBytes = out
            dirty = false
            revision++
            status = "Saved." + (where?.let { " Backup: $it." } ?: " (No backup folder set; app-private backup only.)")
        } catch (e: Exception) {
            status = "Save failed: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ backups

    fun setBackupFolder(uri: Uri?) {
        if (uri != null) {
            try {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                status = "Cannot keep access to that folder: ${e.message}"
                return
            }
        }
        backupFolder = uri
        prefs.edit().putString("backup_folder", uri?.toString()).apply()
        status = if (uri == null) "Backup folder cleared" else "Backup folder set"
    }

    /** Copy the file as it is on disk right now into the backup folder. */
    fun backupNow() {
        val bytes = diskBytes ?: return
        try {
            val where = backupExternal(bytes) ?: error("Choose a backup folder first")
            status = "Backed up: $where"
        } catch (e: Exception) {
            status = "Backup failed: ${e.message}"
        }
    }

    /**
     * Replace the open save file with a backup. The current on-disk file is backed up first,
     * and the chosen file must load as a valid Platinum save.
     */
    fun restore(uri: Uri) {
        val target = saveUri ?: return
        val before = diskBytes ?: return
        try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read backup file")
            val restored = Gen4Save.load(bytes)
            backupExternal(before)
            backup(before)
            resolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: error("Cannot open file for writing")
            val check = resolver.openInputStream(target)?.use { it.readBytes() }
            if (check == null || !check.contentEquals(bytes)) error("Verification failed after restore")
            diskBytes = bytes
            save = restored
            dirty = false
            revision++
            status = "Restored ${restored.trainerName.ifEmpty { "save" }} from backup"
        } catch (e: Exception) {
            status = "Restore failed: ${e.message}"
        }
    }

    /** Writes [bytes] into the backup folder (if set) and verifies it. Returns the file name, or null if no folder is set. */
    private fun backupExternal(bytes: ByteArray): String? {
        val folder = backupFolder ?: return null
        val name = "platinum-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".sav"
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: error("Backup folder is not writable (choose it again)")
        resolver.openOutputStream(doc, "wt")?.use { it.write(bytes) } ?: error("Cannot write backup file")
        val check = resolver.openInputStream(doc)?.use { it.readBytes() }
        if (check == null || !check.contentEquals(bytes)) error("Backup verification failed")
        return name
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
