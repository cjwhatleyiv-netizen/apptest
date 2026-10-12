package com.pksm.droid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import pksm.Gen4Text
import pksm.PK4
import pksm.PidTools
import pksm.Tables

private val STAT_NAMES = listOf("HP", "Atk", "Def", "SpA", "SpD", "Spe")

/** Display order -> index in the PK4 data order (HP, Atk, Def, Spe, SpA, SpD). */
private val STAT_DATA_INDEX = intArrayOf(0, 1, 2, 4, 5, 3)

private val speciesOptions: List<Pair<Int, String>> by lazy {
    (1..Tables.MAX_SPECIES).map { it to "%03d  %s".format(it, Tables.speciesName(it)) }
}
private val moveOptions: List<Pair<Int, String>> by lazy {
    (0..Tables.MAX_MOVE).map { it to (if (it == 0) "(none)" else Tables.moveName(it)) }
}
private val itemOptions: List<Pair<Int, String>> by lazy {
    listOf(0 to "(none)") + Tables.heldItemChoices.map { it to Tables.itemName(it) }
}
private val abilityOptions: List<Pair<Int, String>> by lazy {
    (1..Tables.MAX_ABILITY).map { it to Tables.abilityName(it) }
}
private val natureOptions: List<Pair<Int, String>> by lazy { Tables.natures.mapIndexed { i, n -> i to n } }

/**
 * Full-screen editor for one Pokémon. Works on a private copy; [onApply] receives the edited copy.
 */
@Composable
fun PokemonEditor(
    source: PK4,
    isNew: Boolean,
    onApply: (PK4) -> Unit,
    onExport: (PK4) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    val pk = remember { source.copy() }
    var tick by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val recomposeKey = tick // reading it subscribes this scope to changes
    fun touch() { tick++ }

    BackHandler(onBack = onCancel)

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                if (isNew) "New Pokémon" else "Edit Pokémon",
                style = MaterialTheme.typography.titleLarge,
            )

            SectionTitle("Basics")
            PickerButton(
                "Species", "%03d %s".format(pk.species, Tables.speciesName(pk.species)),
                speciesOptions, { pk.changeSpecies(it); touch() }, Modifier.fillMaxWidth(),
            )

            var nick by remember(pk.species) { mutableStateOf(pk.nickname) }
            OutlinedTextField(
                value = nick,
                onValueChange = {
                    nick = it.take(10)
                    pk.nickname = nick
                    pk.isNicknamed = nick != Tables.defaultNickname(pk.species)
                },
                label = { Text("Nickname (max 10)") },
                supportingText = {
                    if (!Gen4Text.canEncode(nick)) Text("Unsupported characters become '?'")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            LabeledRow {
                NumberField("Level", pk.level, 1..100, { pk.level = it; touch() }, Modifier.weight(1f))
                NumberField("Friendship", pk.friendship, 0..255, { pk.friendship = it }, Modifier.weight(1f))
            }

            PickerButton(
                "Held item", if (pk.heldItem == 0) "(none)" else Tables.itemName(pk.heldItem),
                itemOptions, { pk.heldItem = it; touch() }, Modifier.fillMaxWidth(),
            )

            val p = pk.personal
            PickerButton(
                "Ability", Tables.abilityName(pk.ability), abilityOptions,
                { pk.ability = it; touch() }, Modifier.fillMaxWidth(),
            )
            val natural = listOf(p.ability1, p.ability2).distinct().filter { it != 0 }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Natural:", Modifier.padding(top = 10.dp))
                natural.forEach { id ->
                    OutlinedButton(onClick = { pk.ability = id; touch() }) { Text(Tables.abilityName(id)) }
                }
            }
            Text(
                "Any Gen 4 ability (ID 1-123) works on any species; the game reads this byte directly.",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionTitle("Nature, gender, shininess")
            val ratio = p.genderRatio
            val fixedGender = ratio == 0 || ratio == 254 || ratio == 255
            PickerButton(
                "Nature", Tables.natures.getOrElse(pk.nature) { "?" }, natureOptions,
                { PidTools.apply(pk, it, pk.gender, pk.isShiny); touch() }, Modifier.fillMaxWidth(),
            )
            LabeledRow {
                Text("Gender:")
                listOf(0 to "Male", 1 to "Female").forEach { (g, label) ->
                    val selected = pk.gender == g
                    val enabled = !fixedGender
                    if (selected) Button(onClick = {}, enabled = enabled) { Text(label) }
                    else OutlinedButton(
                        onClick = { PidTools.apply(pk, pk.nature, g, pk.isShiny); touch() },
                        enabled = enabled,
                    ) { Text(label) }
                }
                if (ratio == 255) Text("Genderless")
            }
            LabeledRow {
                Text("Shiny", Modifier.weight(1f))
                Switch(checked = pk.isShiny, onCheckedChange = { PidTools.apply(pk, pk.nature, pk.gender, it); touch() })
            }
            Text("PID %08X".format(pk.pid), style = MaterialTheme.typography.bodySmall)

            SectionTitle("IVs / EVs   (EV total ${(0 until 6).sumOf { pk.ev(it) }} / 510)")
            for (row in 0 until 6) {
                val di = STAT_DATA_INDEX[row]
                LabeledRow {
                    Text(STAT_NAMES[row], Modifier.weight(0.6f))
                    NumberField("IV", pk.iv(di), 0..31, { pk.setIv(di, it) }, Modifier.weight(1f))
                    NumberField("EV", pk.ev(di), 0..255, { pk.setEv(di, it); touch() }, Modifier.weight(1f))
                }
            }

            SectionTitle("Moves")
            for (i in 0 until 4) {
                val m = pk.move(i)
                PickerButton(
                    "Move ${i + 1}", if (m == 0) "(none)" else Tables.moveName(m), moveOptions,
                    { id ->
                        pk.setMove(i, id)
                        pk.setPpUps(i, 0)
                        pk.setPp(i, Tables.movePP(id))
                        touch()
                    },
                    Modifier.fillMaxWidth(),
                )
            }

            SectionTitle("Original trainer")
            var ot by remember { mutableStateOf(pk.otName) }
            OutlinedTextField(
                value = ot,
                onValueChange = { ot = it.take(7); pk.otName = ot },
                label = { Text("OT name (max 7)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledRow {
                NumberField("TID", pk.tid, 0..65535, { pk.tid = it; touch() }, Modifier.weight(1f))
                NumberField("SID", pk.sid, 0..65535, { pk.sid = it; touch() }, Modifier.weight(1f))
            }
            Text(
                "Changing TID/SID can flip shininess; re-toggle Shiny afterwards if needed.",
                style = MaterialTheme.typography.bodySmall,
            )

            LabeledRow {
                Text("Egg", Modifier.weight(1f))
                Switch(checked = pk.isEgg, onCheckedChange = { pk.isEgg = it; touch() })
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { onApply(pk) }) { Text("Apply") }
                OutlinedButton(onClick = { onExport(pk) }) { Text("Export .pk4") }
                TextButton(onClick = onCancel) { Text("Cancel") }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
