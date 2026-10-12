package pksm

/**
 * A decrypted Gen 4 Pokémon. [d] is always 236 bytes (party layout); box Pokémon only use the first 136
 * and carry zeroed battle stats until [refreshPartyStats] is called.
 */
class PK4(val d: ByteArray = ByteArray(Crypto.SIZE_PARTY)) {
    init { require(d.size == Crypto.SIZE_PARTY) }

    var pid: Long
        get() = d.u32(0)
        set(v) = d.putU32(0, v)
    var species: Int
        get() = d.u16(0x08)
        set(v) = d.putU16(0x08, v)
    var heldItem: Int
        get() = d.u16(0x0A)
        set(v) = d.putU16(0x0A, v)
    var tid: Int
        get() = d.u16(0x0C)
        set(v) = d.putU16(0x0C, v)
    var sid: Int
        get() = d.u16(0x0E)
        set(v) = d.putU16(0x0E, v)
    var exp: Long
        get() = d.u32(0x10)
        set(v) = d.putU32(0x10, v)
    var friendship: Int
        get() = d.u8(0x14)
        set(v) = d.putU8(0x14, v)
    var ability: Int
        get() = d.u8(0x15)
        set(v) = d.putU8(0x15, v)
    var language: Int
        get() = d.u8(0x17)
        set(v) = d.putU8(0x17, v)

    /** Order: HP, Atk, Def, Spe, SpA, SpD (data order). */
    fun ev(i: Int): Int = d.u8(0x18 + i)
    fun setEv(i: Int, v: Int) = d.putU8(0x18 + i, v.coerceIn(0, 255))

    fun move(i: Int): Int = d.u16(0x28 + i * 2)
    fun setMove(i: Int, v: Int) = d.putU16(0x28 + i * 2, v)
    fun pp(i: Int): Int = d.u8(0x30 + i)
    fun setPp(i: Int, v: Int) = d.putU8(0x30 + i, v)
    fun ppUps(i: Int): Int = d.u8(0x34 + i)
    fun setPpUps(i: Int, v: Int) = d.putU8(0x34 + i, v)

    private var iv32: Long
        get() = d.u32(0x38)
        set(v) = d.putU32(0x38, v)

    /** Order: HP, Atk, Def, Spe, SpA, SpD (data order). */
    fun iv(i: Int): Int = ((iv32 shr (i * 5)) and 0x1F).toInt()
    fun setIv(i: Int, v: Int) {
        val shift = i * 5
        iv32 = (iv32 and (0x1FL shl shift).inv() and 0xFFFFFFFFL) or (v.coerceIn(0, 31).toLong() shl shift)
    }

    var isEgg: Boolean
        get() = (iv32 shr 30) and 1L == 1L
        set(v) { iv32 = if (v) iv32 or (1L shl 30) else iv32 and (1L shl 30).inv() }
    var isNicknamed: Boolean
        get() = (iv32 shr 31) and 1L == 1L
        set(v) { iv32 = if (v) iv32 or (1L shl 31) else iv32 and (1L shl 31).inv() and 0xFFFFFFFFL }

    var gender: Int // 0 male, 1 female, 2 genderless
        get() = (d.u8(0x40) shr 1) and 3
        set(v) = d.putU8(0x40, (d.u8(0x40) and 0x06.inv()) or ((v and 3) shl 1))
    var form: Int
        get() = d.u8(0x40) shr 3
        set(v) = d.putU8(0x40, (d.u8(0x40) and 0x07) or (v shl 3))

    var nickname: String
        get() = Gen4Text.decode(d, 0x48, 22)
        set(v) = Gen4Text.encode(v, d, 0x48, 22, 10)
    var otName: String
        get() = Gen4Text.decode(d, 0x68, 16)
        set(v) = Gen4Text.encode(v, d, 0x68, 16, 7)

    var otGender: Int
        get() = d.u8(0x84) shr 7
        set(v) = d.putU8(0x84, (d.u8(0x84) and 0x7F) or (v shl 7))

    // ---- party-only block ----
    var statLevel: Int
        get() = d.u8(0x8C)
        set(v) = d.putU8(0x8C, v)
    var hpCurrent: Int
        get() = d.u16(0x8E)
        set(v) = d.putU16(0x8E, v)
    /** index 0..5 = HP, Atk, Def, Spe, SpA, SpD */
    fun stat(i: Int): Int = d.u16(0x90 + i * 2)
    fun setStat(i: Int, v: Int) = d.putU16(0x90 + i * 2, v)

    // ---- derived ----
    val nature: Int get() = (pid % 25L).toInt()
    val isEmpty: Boolean get() = species == 0
    val personal: Tables.Personal get() = Tables.personal(species, form)
    val isShiny: Boolean
        get() = ((tid xor sid xor (pid and 0xFFFF).toInt() xor ((pid shr 16) and 0xFFFF).toInt()) and 0xFFFF) < 8

    var level: Int
        get() = Tables.levelForExp(personal.growth, exp)
        set(v) { exp = Tables.expForLevel(personal.growth, v) }

    /** Recompute level, HP and the six stats from species/IV/EV/nature/EXP. Keeps the current HP ratio-agnostic: full HP. */
    fun refreshPartyStats(fullHeal: Boolean = true) {
        val lv = level
        val base = personal.baseStats
        val oldMax = stat(0)
        val oldCur = hpCurrent
        statLevel = lv
        val up = nature / 5
        val down = nature % 5
        for (i in 0 until 6) {
            val inner = (2 * base[i] + iv(i) + ev(i) / 4) * lv / 100
            val v = if (i == 0) {
                if (species == 292) 1 else inner + lv + 10 // Shedinja
            } else {
                // natures index stats in order Atk, Def, Spe, SpA, SpD (data order 1..5)
                var s = inner + 5
                if (up != down) {
                    if (i - 1 == up) s = s * 110 / 100
                    if (i - 1 == down) s = s * 90 / 100
                }
                s
            }
            setStat(i, v)
        }
        hpCurrent = if (fullHeal || oldMax == 0 || oldCur >= oldMax) stat(0) else minOf(oldCur, stat(0))
    }

    fun refreshChecksum() = d.putU16(6, Crypto.checksum(d))

    fun encryptedStored(): ByteArray {
        refreshChecksum()
        return Crypto.encrypt(d.copyOf(Crypto.SIZE_STORED))
    }

    fun encryptedParty(): ByteArray {
        refreshChecksum()
        return Crypto.encrypt(d)
    }

    /** Decrypted export in .pk4 format (136 bytes, like PKHeX box export). */
    fun exportPk4(): ByteArray {
        refreshChecksum()
        return d.copyOf(Crypto.SIZE_STORED)
    }

    fun copy(): PK4 = PK4(d.copyOf())

    /** Switch species, keeping level, nature, shininess, and PID. Gender/ability/nickname follow the new species. */
    fun changeSpecies(newSpecies: Int) {
        require(newSpecies in 1..Tables.MAX_SPECIES) { "bad species $newSpecies" }
        val lv = level
        val wasNicknamed = isNicknamed
        species = newSpecies
        form = 0
        val p = personal
        ability = p.ability1
        gender = PidTools.genderFromPid(pid, p.genderRatio)
        if (!wasNicknamed) nickname = Tables.defaultNickname(newSpecies)
        exp = Tables.expForLevel(p.growth, lv)
    }

    companion object {
        /** A fresh, non-shiny Pokémon owned by the given trainer, as if caught in Platinum. */
        fun create(
            tid: Int, sid: Int, otName: String, otGender: Int,
            species: Int, level: Int, rng: java.util.Random = java.util.Random(),
        ): PK4 {
            val pk = PK4()
            pk.species = species
            pk.tid = tid
            pk.sid = sid
            var pid: Long
            do {
                pid = rng.nextInt().toLong() and 0xFFFFFFFFL
            } while (PidTools.isShiny(tid, sid, pid))
            pk.pid = pid
            val p = pk.personal
            pk.gender = PidTools.genderFromPid(pid, p.genderRatio)
            pk.exp = Tables.expForLevel(p.growth, level)
            pk.friendship = 70
            pk.ability = p.ability1
            pk.language = 2 // English
            for (i in 0 until 6) pk.setIv(i, rng.nextInt(32))
            pk.nickname = Tables.defaultNickname(species)
            pk.otName = otName
            pk.otGender = otGender
            pk.d[0x5F] = 12 // game version: Platinum
            pk.d[0x83] = 4 // Poké Ball
            pk.d[0x84] = ((pk.d[0x84].toInt() and 0x80) or (level and 0x7F)).toByte() // met level
            pk.setMove(0, 33) // Tackle
            pk.setPp(0, Tables.movePP(33))
            pk.refreshPartyStats()
            return pk
        }

        fun fromEncrypted(raw: ByteArray): PK4 {
            val dec = Crypto.decrypt(raw)
            val full = ByteArray(Crypto.SIZE_PARTY)
            System.arraycopy(dec, 0, full, 0, dec.size)
            return PK4(full)
        }

        /** Import a decrypted or encrypted .pk4 file (136 or 236 bytes). */
        fun fromFile(raw: ByteArray): PK4 {
            require(raw.size == Crypto.SIZE_STORED || raw.size == Crypto.SIZE_PARTY) { "Not a .pk4 file (${raw.size} bytes)" }
            val full = ByteArray(Crypto.SIZE_PARTY)
            System.arraycopy(raw, 0, full, 0, raw.size)
            val pk = PK4(full)
            // Encrypted dumps fail the checksum and decode to nonsense; try decrypting if so.
            if (Crypto.checksum(full) != pk.d.u16(6) || pk.species > Tables.MAX_SPECIES) {
                val dec = fromEncrypted(raw)
                if (Crypto.checksum(dec.d) == dec.d.u16(6) && dec.species in 1..Tables.MAX_SPECIES) return dec
                throw IllegalArgumentException("Corrupt .pk4 (checksum mismatch)")
            }
            return pk
        }
    }
}
