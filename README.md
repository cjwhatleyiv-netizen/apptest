# PKSM Droid

A small Android save editor for **Pokémon Platinum (US/EU)**, modeled on PKSM. It edits the `.sav` file that
melonDS (Android) writes for the game. Built for the AYN Thor, works on any Android 8+ device.

## Features
- Party and all 18 boxes: species, nickname, level, nature, gender, shiny, ability, held item, IVs, EVs, moves, OT/TID/SID
- Import / export `.pk4` files (136 or 236 byte, PKHeX-compatible decrypted format)
- Bag: all 8 pouches
- Trainer: name, TID/SID, money, coins, BP, badges, play time
- Pokédex: seen / caught, complete / clear
- Mystery Gift: import `.pcd` / `.pgt` files into the Wonder Card slots and delivery queue, plus a built-in Azure Flute card
- Any Gen 4 ability (ID 1-123) on any Pokémon, and free nature / gender / shininess (the PID is re-rolled to match)

## Using it
1. **Close the game in melonDS first.** melonDS rewrites the save on exit and would overwrite your edits.
2. Open the app, tap **Open .sav file**, and pick the game's `.sav`.
3. Edit, tap **Save**. The app writes the file back and reads it back to verify.
4. Launch the game in melonDS.

A copy of the original file is stored in the app's private storage every time you open a save (last 5 kept),
and **Save copy** writes the edited save anywhere you choose. Keep your own backup too.

## Getting the APK
Every push runs the GitHub Actions workflow `Build APK`. Download the `pksm-droid-debug-apk` artifact from the run,
unzip it, and sideload `app-debug.apk`.

To build locally you need JDK 17 and the Android SDK (API 35): `./gradlew :app:assembleDebug`.

## Layout
- `core/` – pure Kotlin library: Gen 4 crypto, PK4 format, Platinum save blocks, checksums, name tables. No Android deps.
  `./gradlew :core:test -PcoreOnly` runs its tests without the Android SDK.
- `app/` – Jetpack Compose UI (Storage Access Framework for file access, so no storage permissions are needed).

## Mystery Gift notes
Queued gifts are handed out by the Mystery Gift delivery man in a Pokémon Center after you reload the save.
The `.pcd`/`.pgt` layout and slot offsets are from PKHeX. The built-in Azure Flute card is assembled from that
documented layout (gift type 10); no retail Platinum Azure Flute card file was available to compare against, so it
has not been verified in the game. The Azure Flute (item 455) can always be added directly in Bag > Key Items.

## Scope / limits
- Platinum only (international header). Diamond/Pearl/HeartGold/SoulSilver and Japanese/Korean saves are rejected.
- Species forms (Rotom, Giratina, Unown letters, etc.) are preserved but not editable; the Pokédex form/language
  sub-tables are not updated.
- Edits are not checked for legality. Bad combinations can make the game or online services reject a Pokémon.

## Credits
Offsets, checksums and data tables come from [PKHeX](https://github.com/kwsch/PKHeX) (GPLv3). This project is
therefore distributed under GPLv3.
