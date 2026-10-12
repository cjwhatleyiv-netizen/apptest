package pksm

import java.io.ByteArrayOutputStream

/** Static game data: names, personal (base stat) data, EXP curves, item pouch contents. */
object Tables {
    const val MAX_SPECIES = 493
    const val MAX_MOVE = 467
    const val MAX_ITEM = 467
    const val MAX_ABILITY = 123

    private fun readBytes(name: String): ByteArray {
        val s = Tables::class.java.getResourceAsStream("/pksm/$name") ?: error("missing resource $name")
        return s.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
    }

    private fun readLines(name: String): List<String> =
        String(readBytes(name), Charsets.UTF_8).removePrefix("﻿").lines().map { it.trimEnd('\r') }

    val species: List<String> by lazy { readLines("species.txt").take(MAX_SPECIES + 1) }
    val moves: List<String> by lazy { readLines("moves.txt").take(MAX_MOVE + 1) }
    val abilities: List<String> by lazy { readLines("abilities.txt").take(MAX_ABILITY + 1) }
    val items: List<String> by lazy { readLines("items.txt").take(MAX_ITEM + 1) }
    val natures: List<String> by lazy { readLines("natures.txt").filter { it.isNotEmpty() }.take(25) }
    val types: List<String> by lazy { readLines("types.txt").filter { it.isNotEmpty() } }

    /** Gen 4 default nicknames are the species name in upper case. */
    fun defaultNickname(species: Int): String = this.species.getOrElse(species) { "" }.uppercase()

    fun speciesName(id: Int) = species.getOrElse(id) { "???" }
    fun moveName(id: Int) = moves.getOrElse(id) { "???" }
    fun itemName(id: Int) = items.getOrElse(id) { "???" }
    fun abilityName(id: Int) = abilities.getOrElse(id) { "???" }

    fun movePP(id: Int): Int = GameData.movePP.getOrElse(id) { 0 }

    // ---- personal ----
    private const val PERSONAL_SIZE = 0x2C
    private val personalRaw: ByteArray by lazy { readBytes("personal_pt.bin") }

    class Personal(private val raw: ByteArray, private val o: Int) {
        val baseStats: IntArray get() = IntArray(6) { raw.u8(o + it) } // HP ATK DEF SPE SPA SPD
        val type1 get() = raw.u8(o + 6)
        val type2 get() = raw.u8(o + 7)
        val genderRatio get() = raw.u8(o + 0x10)
        val growth get() = raw.u8(o + 0x13)
        val ability1 get() = raw.u8(o + 0x16)
        val ability2 get() = raw.u8(o + 0x17)
        val formCount get() = raw.u8(o + 0x29)
        val formStatsIndex get() = raw.u16(o + 0x2A)
    }

    fun personal(species: Int, form: Int = 0): Personal {
        val sp = species.coerceIn(0, MAX_SPECIES)
        val base = Personal(personalRaw, sp * PERSONAL_SIZE)
        if (form > 0 && base.formCount > 1 && base.formStatsIndex > 0) {
            val idx = base.formStatsIndex + form - 1
            if ((idx + 1) * PERSONAL_SIZE <= personalRaw.size) return Personal(personalRaw, idx * PERSONAL_SIZE)
        }
        return base
    }

    // ---- experience ----
    fun expForLevel(growth: Int, level: Int): Long {
        val t = GameData.expTables[growth.coerceIn(0, 5)]
        return t[(level.coerceIn(1, 100)) - 1].toLong()
    }

    fun levelForExp(growth: Int, exp: Long): Int {
        val t = GameData.expTables[growth.coerceIn(0, 5)]
        var lv = 1
        while (lv < 100 && exp >= t[lv]) lv++
        return lv
    }

    // ---- bag ----
    enum class Pouch(val title: String, val offset: Int, val slots: Int, val maxCount: Int) {
        ITEMS("Items", 0x000, 162, 999),
        KEY_ITEMS("Key Items", 0x294, 40, 1),
        TMS("TMs & HMs", 0x35C, 100, 99),
        MAIL("Mail", 0x4EC, 12, 999),
        MEDICINE("Medicine", 0x51C, 38, 999),
        BERRIES("Berries", 0x5BC, 64, 999),
        BALLS("Poké Balls", 0x6BC, 15, 999),
        BATTLE("Battle Items", 0x6F8, 13, 999);

        /** Item IDs legal for this pouch in Platinum. */
        val allowed: List<Int> by lazy { allowedItems(this) }
    }

    private fun r(a: Int, b: Int) = (a..b).toList()

    private fun allowedItems(p: Pouch): List<Int> = when (p) {
        Pouch.ITEMS -> r(68, 112) + listOf(135, 136) + r(213, 327)
        Pouch.KEY_ITEMS -> r(428, 467)
        Pouch.TMS -> r(328, 427)
        Pouch.MAIL -> r(137, 148)
        Pouch.MEDICINE -> r(17, 54)
        Pouch.BERRIES -> r(149, 212)
        Pouch.BALLS -> listOf(1, 2, 3, 4) + r(6, 16)
        Pouch.BATTLE -> r(55, 67)
    }

    /** Items that may be held by a Pokémon. */
    val heldItemChoices: List<Int> by lazy {
        (Pouch.ITEMS.allowed + Pouch.MAIL.allowed + Pouch.MEDICINE.allowed + Pouch.BERRIES.allowed +
            Pouch.BALLS.allowed + Pouch.BATTLE.allowed + Pouch.TMS.allowed.take(92)).distinct().sorted()
    }
}
