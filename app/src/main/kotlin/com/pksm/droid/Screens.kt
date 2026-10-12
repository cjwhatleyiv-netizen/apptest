package com.pksm.droid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pksm.Gen4Save
import pksm.PK4
import pksm.Tables

// ---------------------------------------------------------------------------------------------
// Party
// ---------------------------------------------------------------------------------------------

@Composable
fun PartyScreen(
    save: Gen4Save,
    onEdit: (slot: Int) -> Unit,
    onImport: (slot: Int) -> Unit,
    onCreate: (slot: Int) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer8() }
        items(6) { slot ->
            if (slot < save.partyCount) {
                val pk = save.getParty(slot)
                PokemonCard(pk, Modifier.clickable { onEdit(slot) })
            } else if (slot == save.partyCount) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Empty slot ${slot + 1}", Modifier.weight(1f))
                        OutlinedButton(onClick = { onCreate(slot) }) { Text("New") }
                        OutlinedButton(onClick = { onImport(slot) }) { Text("Import .pk4") }
                    }
                }
            }
        }
        item { Spacer8() }
    }
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))

@Composable
fun PokemonCard(pk: PK4, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(pk.nickname.ifEmpty { Tables.speciesName(pk.species) })
                        if (pk.isShiny) append("  ★")
                        if (pk.isEgg) append("  (Egg)")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Lv ${pk.level}")
            }
            Text(
                "${Tables.speciesName(pk.species)} · ${Tables.natures.getOrElse(pk.nature) { "?" }} · " +
                    (if (pk.heldItem != 0) Tables.itemName(pk.heldItem) else "no item"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                (0 until 4).map { pk.move(it) }.filter { it != 0 }.joinToString(" / ") { Tables.moveName(it) },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Boxes
// ---------------------------------------------------------------------------------------------

@Composable
fun BoxScreen(
    save: Gen4Save,
    box: Int,
    onBoxChange: (Int) -> Unit,
    onSlot: (box: Int, slot: Int, pk: PK4?) -> Unit,
    onRename: (box: Int, name: String) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onBoxChange((box + Gen4Save.BOX_COUNT - 1) % Gen4Save.BOX_COUNT) }) { Text("◀") }
            Text(
                "Box ${box + 1}: ${save.boxName(box).ifEmpty { "-" }}",
                modifier = Modifier.weight(1f).clickable { renaming = true },
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { onBoxChange((box + 1) % Gen4Save.BOX_COUNT) }) { Text("▶") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            for (row in 0 until 5) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 6) {
                        val slot = row * 6 + col
                        val pk = save.getBox(box, slot).takeIf { !it.isEmpty }
                        BoxCell(pk, Modifier.weight(1f).padding(2.dp)) { onSlot(box, slot, pk) }
                    }
                }
            }
            Text(
                "Tap a slot to edit. Tap an empty slot to create or import. Tap the box name to rename.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(save.boxName(box)) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename box ${box + 1}") },
            text = {
                OutlinedTextField(name, { name = it.take(8) }, singleLine = true, label = { Text("Max 8 characters") })
            },
            confirmButton = {
                TextButton(onClick = { renaming = false; onRename(box, name) }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BoxCell(pk: PK4?, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.height(56.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxSize().padding(2.dp), contentAlignment = Alignment.Center) {
            if (pk != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        Tables.speciesName(pk.species).take(8) + if (pk.isShiny) "★" else "",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    Text("Lv${pk.level}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Bag
// ---------------------------------------------------------------------------------------------

@Composable
fun BagScreen(vm: EditorViewModel, save: Gen4Save) {
    var pouch by remember { mutableStateOf(Tables.Pouch.ITEMS) }
    var adding by remember { mutableStateOf(false) }
    val entries = save.getPouch(pouch).filter { it.id != 0 }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tables.Pouch.entries.forEach { p ->
                if (p == pouch) Button(onClick = {}) { Text(p.title) }
                else OutlinedButton(onClick = { pouch = p }) { Text(p.title) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${entries.size} / ${pouch.slots} slots", Modifier.weight(1f))
            OutlinedButton(onClick = { adding = true }) { Text("Add item") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(entries) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Tables.itemName(item.id), Modifier.weight(1f))
                    NumberField(
                        "Qty", item.count, 1..pouch.maxCount,
                        { n -> vm.edit { s -> s.setPouch(pouch, s.getPouch(pouch).map { if (it.id == item.id) Gen4Save.BagItem(it.id, n) else it }) } },
                        Modifier.weight(0.6f),
                    )
                    TextButton(onClick = { vm.edit { s -> s.setPouch(pouch, s.getPouch(pouch).filter { it.id != item.id }) } }) { Text("✕") }
                }
                HorizontalDivider()
            }
        }
    }
    if (adding) {
        val have = entries.map { it.id }.toSet()
        PickerDialog(
            title = "Add to ${pouch.title}",
            options = pouch.allowed.filter { it !in have }.map { it to Tables.itemName(it) },
            onPick = { id ->
                adding = false
                vm.edit { s ->
                    val cur = s.getPouch(pouch).filter { it.id != 0 }
                    s.setPouch(pouch, cur + Gen4Save.BagItem(id, if (pouch == Tables.Pouch.KEY_ITEMS) 1 else minOf(99, pouch.maxCount)))
                }
            },
            onDismiss = { adding = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Trainer
// ---------------------------------------------------------------------------------------------

private val BADGES = listOf("Coal", "Forest", "Cobble", "Fen", "Relic", "Mine", "Icicle", "Beacon")

@Composable
fun TrainerScreen(vm: EditorViewModel, save: Gen4Save) {
    var name by remember { mutableStateOf(save.trainerName) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(7); vm.edit { s -> s.trainerName = name } },
            label = { Text("Name (max 7)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LabeledRow {
            NumberField("TID", save.tid, 0..65535, { n -> vm.edit { it.tid = n } }, Modifier.weight(1f))
            NumberField("SID", save.sid, 0..65535, { n -> vm.edit { it.sid = n } }, Modifier.weight(1f))
        }
        LabeledRow {
            NumberField("Money", save.money.toInt(), 0..999_999, { n -> vm.edit { it.money = n.toLong() } }, Modifier.weight(1f))
            NumberField("Coins", save.coins, 0..50_000, { n -> vm.edit { it.coins = n } }, Modifier.weight(1f))
            NumberField("BP", save.battlePoints, 0..9999, { n -> vm.edit { it.battlePoints = n } }, Modifier.weight(1f))
        }
        SectionTitle("Play time")
        LabeledRow {
            NumberField("Hours", save.playHours, 0..999, { n -> vm.edit { it.playHours = n } }, Modifier.weight(1f))
            NumberField("Min", save.playMinutes, 0..59, { n -> vm.edit { it.playMinutes = n } }, Modifier.weight(1f))
            NumberField("Sec", save.playSeconds, 0..59, { n -> vm.edit { it.playSeconds = n } }, Modifier.weight(1f))
        }
        SectionTitle("Gender")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Male", 1 to "Female").forEach { (g, label) ->
                if (save.trainerGender == g) Button(onClick = {}) { Text(label) }
                else OutlinedButton(onClick = { vm.edit { it.trainerGender = g } }) { Text(label) }
            }
        }
        SectionTitle("Sinnoh badges")
        for (i in BADGES.indices) {
            val on = (save.badges shr i) and 1 == 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = { c ->
                    vm.edit { it.badges = if (c) it.badges or (1 shl i) else it.badges and (1 shl i).inv() }
                })
                Text("${BADGES[i]} Badge")
            }
        }
        LabeledRow {
            Text("Game cleared (Hall of Fame)", Modifier.weight(1f))
            Switch(checked = save.gameClear, onCheckedChange = { c -> vm.edit { it.gameClear = c } })
        }
        LabeledRow {
            Text("National Pokédex", Modifier.weight(1f))
            Switch(checked = save.nationalDex, onCheckedChange = { c -> vm.edit { it.nationalDex = c } })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pokédex
// ---------------------------------------------------------------------------------------------

@Composable
fun DexScreen(vm: EditorViewModel, save: Gen4Save) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Seen ${save.seenCount()} · Caught ${save.caughtCount()}", Modifier.weight(1f))
            OutlinedButton(onClick = {
                vm.edit { s -> for (sp in 1..Tables.MAX_SPECIES) s.setDex(sp, seen = true, caught = true) }
            }) { Text("Complete") }
            OutlinedButton(onClick = {
                vm.edit { s -> for (sp in 1..Tables.MAX_SPECIES) s.setDex(sp, seen = false, caught = false) }
            }) { Text("Clear") }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Species", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("Seen", style = MaterialTheme.typography.labelMedium)
            Text("   Caught", style = MaterialTheme.typography.labelMedium)
        }
        LazyColumn(Modifier.weight(1f)) {
            items((1..Tables.MAX_SPECIES).toList(), key = { it }) { sp ->
                val seen = save.isSeen(sp)
                val caught = save.isCaught(sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("%03d  %s".format(sp, Tables.speciesName(sp)), Modifier.weight(1f))
                    Checkbox(checked = seen, onCheckedChange = { c -> vm.edit { it.setDex(sp, seen = c, caught = caught && c) } })
                    Checkbox(checked = caught, onCheckedChange = { c -> vm.edit { it.setDex(sp, seen = seen || c, caught = c) } })
                }
            }
        }
    }
}
