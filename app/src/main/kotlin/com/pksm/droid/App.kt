package com.pksm.droid

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pksm.Gen4Save
import pksm.PK4

private enum class Tab(val title: String) { PARTY("Party"), BOXES("Boxes"), BAG("Bag"), TRAINER("Trainer"), DEX("Pokédex"), GIFTS("Gifts") }

/** What the open editor will write back to. */
private sealed interface Slot {
    data class Party(val slot: Int) : Slot
    data class Box(val box: Int, val slot: Int) : Slot
}

private class EditSession(val slot: Slot, val pk: PK4, val isNew: Boolean)

@Composable
fun App(vm: EditorViewModel) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) { AppContent(vm) }
    }
}

@Composable
private fun AppContent(vm: EditorViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision // recompose on every save mutation

    var tab by remember { mutableStateOf(Tab.PARTY) }
    var box by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<EditSession?>(null) }
    var importTarget by remember { mutableStateOf<Slot?>(null) }
    var exportPk by remember { mutableStateOf<PK4?>(null) }
    var showBackup by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(vm.status) {
        vm.status?.let {
            snackbar.showSnackbar(it)
            vm.status = null
        }
    }

    val openSave = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.open(uri)
    }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) vm.writeCopy(uri)
    }
    val openPk4 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val target = importTarget
        importTarget = null
        if (uri != null && target != null) {
            vm.readPk4(uri)?.let { pk -> session = EditSession(target, pk, isNew = true) }
        }
    }
    val openGift = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            vm.readBytes(uri)?.let { bytes -> vm.edit { s -> vm.status = s.deliverGift(bytes) } }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.setBackupFolder(uri)
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) confirmRestore = uri
    }
    val savePk4 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val pk = exportPk
        exportPk = null
        if (uri != null && pk != null) vm.writePk4(uri, pk)
    }

    val save = vm.save
    if (save == null) {
        StartScreen(
            lastUri = vm.lastUri,
            onOpen = { openSave.launch(arrayOf("*/*")) },
            onReopen = { u -> vm.open(u) },
            snackbar = snackbar,
        )
        return
    }

    val current = session
    if (current != null) {
        PokemonEditor(
            source = current.pk,
            isNew = current.isNew,
            onApply = { pk ->
                vm.edit { s ->
                    when (val t = current.slot) {
                        is Slot.Party -> s.setParty(t.slot, pk)
                        is Slot.Box -> s.setBox(t.box, t.slot, pk)
                    }
                }
                session = null
            },
            onExport = { pk ->
                exportPk = pk
                savePk4.launch("${pksm.Tables.speciesName(pk.species)}.pk4")
            },
            onDelete = if (current.isNew) null else ({
                vm.edit { s ->
                    when (val t = current.slot) {
                        is Slot.Party -> s.deleteParty(t.slot)
                        is Slot.Box -> s.deleteBox(t.box, t.slot)
                    }
                }
                session = null
            }),
            onCancel = { session = null },
        )
        return
    }

    fun newMon(target: Slot) {
        session = EditSession(
            target,
            PK4.create(save.tid, save.sid, save.trainerName, save.trainerGender, species = 1, level = 5),
            isNew = true,
        )
    }

    if (showBackup) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBackup = false },
            title = { Text("Backups") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Backup folder: " + (vm.backupFolder?.lastPathSegment ?: "not set"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Once a folder is set, every Save first writes a timestamped copy of the existing file there " +
                            "and cancels the save if that copy fails.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickFolder.launch(null) }) { Text(if (vm.backupFolder == null) "Choose folder" else "Change") }
                        if (vm.backupFolder != null) OutlinedButton(onClick = { vm.setBackupFolder(null) }) { Text("Clear") }
                    }
                    OutlinedButton(onClick = { vm.backupNow() }, enabled = vm.backupFolder != null) { Text("Back up current file now") }
                    OutlinedButton(onClick = { pickBackup.launch(arrayOf("*/*")) }) { Text("Restore from backup file…") }
                }
            },
            confirmButton = { TextButton(onClick = { showBackup = false }) { Text("Close") } },
        )
    }
    confirmRestore?.let { uri ->
        ConfirmDialog(
            title = "Restore this backup?",
            text = "The open save file will be overwritten with the chosen backup (the current file is backed up first). " +
                "Unsaved edits in the app are discarded.",
            confirmLabel = "Restore",
            onConfirm = { confirmRestore = null; showBackup = false; vm.restore(uri) },
            onDismiss = { confirmRestore = null },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(save.trainerName.ifEmpty { "Trainer" }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (vm.dirty) "Unsaved changes" else "No changes",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = { showBackup = true }) { Text("Backup") }
                    TextButton(onClick = { saveCopy.launch("platinum-edited.sav") }) { Text("Save copy") }
                    Button(onClick = { vm.writeBack() }, enabled = vm.dirty) { Text("Save") }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.title.take(1), style = MaterialTheme.typography.titleMedium) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.PARTY -> PartyScreen(
                    save,
                    onEdit = { slot -> session = EditSession(Slot.Party(slot), save.getParty(slot), isNew = false) },
                    onImport = { slot -> importTarget = Slot.Party(slot); openPk4.launch(arrayOf("*/*")) },
                    onCreate = { slot -> newMon(Slot.Party(slot)) },
                )

                Tab.BOXES -> {
                    var chooser by remember { mutableStateOf<Pair<Int, Int>?>(null) }
                    BoxScreen(
                        save, box,
                        onBoxChange = { box = it },
                        onSlot = { b, slot, pk ->
                            if (pk != null) session = EditSession(Slot.Box(b, slot), pk, isNew = false)
                            else chooser = b to slot
                        },
                        onRename = { b, name -> vm.edit { it.setBoxName(b, name) } },
                    )
                    chooser?.let { (b, slot) ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { chooser = null },
                            title = { Text("Empty slot") },
                            text = { Text("Box ${b + 1}, slot ${slot + 1}") },
                            confirmButton = {
                                TextButton(onClick = { chooser = null; newMon(Slot.Box(b, slot)) }) { Text("Create new") }
                            },
                            dismissButton = {
                                Row {
                                    TextButton(onClick = {
                                        chooser = null
                                        importTarget = Slot.Box(b, slot)
                                        openPk4.launch(arrayOf("*/*"))
                                    }) { Text("Import .pk4") }
                                    TextButton(onClick = { chooser = null }) { Text("Cancel") }
                                }
                            },
                        )
                    }
                }

                Tab.BAG -> BagScreen(vm, save)
                Tab.TRAINER -> TrainerScreen(vm, save)
                Tab.DEX -> DexScreen(vm, save)
                Tab.GIFTS -> GiftsScreen(vm, save, onImport = { openGift.launch(arrayOf("*/*")) })
            }
        }
    }
}

@Composable
private fun StartScreen(
    lastUri: Uri?,
    onOpen: () -> Unit,
    onReopen: (Uri) -> Unit,
    snackbar: SnackbarHostState,
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PKSM Droid", style = MaterialTheme.typography.headlineLarge)
            Text("Pokémon Platinum save editor", style = MaterialTheme.typography.titleMedium)
            Text(
                "\nClose the game in melonDS first (so it can't overwrite your edits), then open the game's .sav file. " +
                    "A backup of the original is kept in app storage each time you open a save.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Button(onClick = onOpen) { Text("Open .sav file") }
            if (lastUri != null) {
                OutlinedButton(onClick = { onReopen(lastUri) }, modifier = Modifier.padding(top = 8.dp)) { Text("Reopen last save") }
            }
        }
    }
}
