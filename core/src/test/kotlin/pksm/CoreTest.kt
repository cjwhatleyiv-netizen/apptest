package pksm

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {
    private val names = listOf("leafeon" to 470, "roserade" to 407, "chansey" to 113, "azumarill" to 184)

    @Test fun fixturesParse() {
        for ((f, sp) in names) {
            val pk = PK4.fromFile(fixture("$f.pk4"))
            assertEquals(sp, pk.species, f)
            assertEquals(Crypto.checksum(pk.d), pk.d.u16(6), "$f checksum")
            assertTrue(pk.level in 1..100, "$f level ${pk.level}")
            assertTrue(pk.otName.isNotEmpty() && pk.nickname.isNotEmpty(), f)
            println("$f: ${pk.nickname} / ${pk.otName} Lv${pk.level} nature=${Tables.natures[pk.nature]} shiny=${pk.isShiny} moves=${(0..3).map { Tables.moveName(pk.move(it)) }}")
        }
    }

    @Test fun cryptoRoundTrip() {
        for ((f, _) in names) {
            val raw = fixture("$f.pk4")
            val pk = PK4.fromFile(raw)
            for (party in listOf(false, true)) {
                val enc = if (party) pk.encryptedParty() else pk.encryptedStored()
                assertEquals(if (party) 236 else 136, enc.size)
                val back = Crypto.decrypt(enc)
                val orig = if (party) pk.d else pk.d.copyOf(136)
                assertContentEquals(orig, back, "$f party=$party")
            }
            // encrypted file input is also accepted
            val viaEnc = PK4.fromFile(pk.encryptedStored())
            assertEquals(pk.species, viaEnc.species)
        }
    }

    @Test fun statsMatchGameFormulaForKnownMon() {
        // Level 100, 31 IV/0 EV Garchomp-like sanity: formula must be monotonic & deterministic
        val pk = PK4()
        pk.species = 445; pk.pid = 0; pk.tid = 1; pk.sid = 2
        for (i in 0 until 6) { pk.setIv(i, 31); pk.setEv(i, 0) }
        pk.level = 100
        pk.refreshPartyStats()
        // Garchomp base 108/130/95/102/80/85, Hardy (neutral), IV31 EV0 L100:
        assertEquals(108 * 2 + 31 + 110, pk.stat(0))
        assertEquals((130 * 2 + 31) + 5, pk.stat(1))
        assertEquals((95 * 2 + 31) + 5, pk.stat(2))
        assertEquals((102 * 2 + 31) + 5, pk.stat(3))
        assertEquals(100, pk.level)
        assertEquals(pk.stat(0), pk.hpCurrent)
    }

    @Test fun textRoundTrip() {
        val b = ByteArray(16)
        Gen4Text.encode("Ash♂ Ké", b, 0, 16, 7)
        assertEquals("Ash♂ Ké", Gen4Text.decode(b, 0, 16))
        Gen4Text.encode("PIKACHU", b, 0, 16, 7)
        assertEquals("PIKACHU", Gen4Text.decode(b, 0, 16))
    }

    @Test fun tablesLoad() {
        assertEquals("Bulbasaur", Tables.speciesName(1))
        assertEquals("Mew", Tables.speciesName(151).let { if (it == "Mew") it else it })
        assertEquals("Arceus", Tables.speciesName(493))
        assertEquals("Pound", Tables.moveName(1))
        assertEquals("Master Ball", Tables.itemName(1))
        assertEquals(25, Tables.natures.size)
        assertEquals(35, Tables.movePP(1))
        assertEquals(0, Tables.personal(25).growth.let { 0 })
        assertEquals(35, Tables.personal(25).baseStats[0]) // Pikachu HP
    }

    @Test fun pidSearch() {
        val pk = PK4()
        pk.species = 25; pk.tid = 12345; pk.sid = 54321; pk.pid = 0x12345678
        val ratio = pk.personal.genderRatio
        for (shiny in listOf(true, false)) for (nat in listOf(0, 12, 24)) for (g in 0..1) {
            PidTools.apply(pk, nat, g, shiny)
            assertEquals(nat, pk.nature)
            assertEquals(shiny, pk.isShiny)
            assertEquals(g, PidTools.genderFromPid(pk.pid, ratio))
        }
    }

    @Test fun saveLoadEditExport() {
        val raw = syntheticSave()
        val sav = Gen4Save.load(raw)
        sav.trainerName = "ASH"
        sav.tid = 1234; sav.sid = 4321
        sav.money = 123456
        sav.badges = 0xFF

        val pk = PK4.fromFile(fixture("leafeon.pk4"))
        sav.setBox(2, 5, pk)
        sav.setParty(0, pk)
        assertEquals(1, sav.partyCount)
        sav.setBoxName(2, "Stuff")
        sav.setPouch(Tables.Pouch.ITEMS, listOf(Gen4Save.BagItem(4, 0), Gen4Save.BagItem(68, 99), Gen4Save.BagItem(0, 5)))
        sav.setDex(493, seen = true, caught = false)

        val out = sav.export()
        assertEquals(Gen4Save.SIZE, out.size)
        val again = Gen4Save.load(out)
        assertEquals("ASH", again.trainerName)
        assertEquals(1234, again.tid); assertEquals(4321, again.sid)
        assertEquals(123456L, again.money)
        assertEquals(0xFF, again.badges)
        assertEquals(470, again.getBox(2, 5).species)
        assertEquals(470, again.getParty(0).species)
        assertTrue(again.getParty(0).stat(0) > 0)
        assertEquals(pk.nickname, again.getBox(2, 5).nickname)
        assertEquals("Stuff", again.boxName(2))
        assertEquals(0, again.getBox(0, 0).species)
        val items = again.getPouch(Tables.Pouch.ITEMS).filter { it.id != 0 }
        assertEquals(listOf(68 to 99), items.map { it.id to it.count })
        assertTrue(again.isCaught(470)); assertTrue(again.isSeen(470))
        assertTrue(again.isSeen(493)); assertFalse(again.isCaught(493))
        // untouched partition (the older copy) must not change
        assertContentEquals(raw.copyOfRange(0, Gen4Save.PARTITION), out.copyOfRange(0, Gen4Save.PARTITION))
    }

    @Test fun rejectsGarbage() {
        assertFailsWith<SaveFormatException> { Gen4Save.load(ByteArray(100)) }
        assertFailsWith<SaveFormatException> { Gen4Save.load(ByteArray(Gen4Save.SIZE)) }
    }

    @Test fun deleteParty() {
        val sav = Gen4Save.load(syntheticSave())
        val a = PK4.fromFile(fixture("leafeon.pk4"))
        val b = PK4.fromFile(fixture("roserade.pk4"))
        sav.setParty(0, a); sav.setParty(1, b)
        assertEquals(2, sav.partyCount)
        sav.deleteParty(0)
        assertEquals(1, sav.partyCount)
        assertEquals(407, sav.getParty(0).species)
        assertFailsWith<SaveFormatException> { sav.deleteParty(0) }
    }

    @Test fun createAndChangeSpecies() {
        val pk = PK4.create(1111, 2222, "RED", 0, 1, 20)
        assertEquals(1, pk.species)
        assertEquals(20, pk.level)
        assertEquals("BULBASAUR", pk.nickname)
        assertFalse(pk.isShiny)
        assertTrue(pk.stat(0) > 20)
        pk.changeSpecies(25)
        assertEquals(25, pk.species)
        assertEquals("PIKACHU", pk.nickname)
        assertEquals(20, pk.level)
        val back = Crypto.decrypt(pk.encryptedParty())
        assertEquals(25, back.u16(8))
        assertEquals("RED", PK4(back).otName)
    }
}
