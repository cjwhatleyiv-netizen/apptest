package pksm

class SaveFormatException(message: String) : Exception(message)

/**
 * Pokémon Platinum (international) 512 KB save. Offsets and checksum rules follow PKHeX's SAV4Pt.
 *
 * The file holds two copies of two blocks (General + Storage). Each block has a 0x14 byte footer
 * (major counter, minor counter, size, date magic, u16 pad, u16 CRC). The copy with the higher counter is live;
 * we edit the live copies in place and fix their CRCs.
 */
class Gen4Save private constructor(val raw: ByteArray, private val trailer: ByteArray) {

    private val generalOff: Int
    private val storageOff: Int

    init {
        val g = activeBlock(raw, 0, GENERAL_SIZE)
        val s = activeBlock(raw, GENERAL_SIZE, STORAGE_SIZE)
        generalOff = if (g == 0) 0 else PARTITION
        storageOff = (if (s == 0) 0 else PARTITION) + GENERAL_SIZE
    }

    // ---------------------------------------------------------------- trainer

    private val tr get() = generalOff + TRAINER

    var trainerName: String
        get() = Gen4Text.decode(raw, tr, 16)
        set(v) = Gen4Text.encode(v, raw, tr, 16, 7)
    var tid: Int
        get() = raw.u16(tr + 0x10)
        set(v) = raw.putU16(tr + 0x10, v)
    var sid: Int
        get() = raw.u16(tr + 0x12)
        set(v) = raw.putU16(tr + 0x12, v)
    var money: Long
        get() = raw.u32(tr + 0x14)
        set(v) = raw.putU32(tr + 0x14, v.coerceIn(0, 999_999))
    var trainerGender: Int
        get() = raw.u8(tr + 0x18)
        set(v) = raw.putU8(tr + 0x18, v)
    /** Bitmask of the 8 Sinnoh badges (bit 0 = Coal Badge). */
    var badges: Int
        get() = raw.u8(tr + 0x1A)
        set(v) = raw.putU8(tr + 0x1A, v)
    var gameClear: Boolean
        get() = raw.u8(tr + 0x1D) and 1 != 0
        set(v) = raw.putU8(tr + 0x1D, (raw.u8(tr + 0x1D) and 1.inv()) or (if (v) 1 else 0))
    var nationalDex: Boolean
        get() = raw.u8(tr + 0x1D) and 2 != 0
        set(v) = raw.putU8(tr + 0x1D, (raw.u8(tr + 0x1D) and 2.inv()) or (if (v) 2 else 0))
    var coins: Int
        get() = raw.u16(tr + 0x20)
        set(v) = raw.putU16(tr + 0x20, v.coerceIn(0, 50_000))
    var playHours: Int
        get() = raw.u16(tr + 0x22)
        set(v) = raw.putU16(tr + 0x22, v.coerceIn(0, 999))
    var playMinutes: Int
        get() = raw.u8(tr + 0x24)
        set(v) = raw.putU8(tr + 0x24, v.coerceIn(0, 59))
    var playSeconds: Int
        get() = raw.u8(tr + 0x25)
        set(v) = raw.putU8(tr + 0x25, v.coerceIn(0, 59))
    /** Battle Points. */
    var battlePoints: Int
        get() = raw.u16(generalOff + 0x7234)
        set(v) = raw.putU16(generalOff + 0x7234, v.coerceIn(0, 9999))

    // ---------------------------------------------------------------- party

    var partyCount: Int
        get() = raw.u8(generalOff + PARTY - 4).coerceIn(0, 6)
        private set(v) = raw.putU8(generalOff + PARTY - 4, v)

    private fun partyOffset(slot: Int) = generalOff + PARTY + slot * Crypto.SIZE_PARTY

    fun getParty(slot: Int): PK4 {
        require(slot in 0 until 6)
        return PK4.fromEncrypted(raw.copyOfRange(partyOffset(slot), partyOffset(slot) + Crypto.SIZE_PARTY))
    }

    fun setParty(slot: Int, pk: PK4) {
        require(slot in 0 until 6)
        pk.refreshPartyStats(fullHeal = false)
        val enc = pk.encryptedParty()
        System.arraycopy(enc, 0, raw, partyOffset(slot), enc.size)
        if (slot >= partyCount) partyCount = slot + 1
        markDex(pk)
    }

    /** Removes a party member, shifting the rest up. The last remaining member cannot be removed. */
    fun deleteParty(slot: Int) {
        val n = partyCount
        require(slot in 0 until n)
        if (n <= 1) throw SaveFormatException("Party must keep at least one Pokémon")
        for (i in slot until n - 1) {
            System.arraycopy(raw, partyOffset(i + 1), raw, partyOffset(i), Crypto.SIZE_PARTY)
        }
        java.util.Arrays.fill(raw, partyOffset(n - 1), partyOffset(n - 1) + Crypto.SIZE_PARTY, 0)
        partyCount = n - 1
    }

    // ---------------------------------------------------------------- boxes

    private fun boxOffset(box: Int, slot: Int) = storageOff + 4 + box * BOX_DATA_LEN + slot * Crypto.SIZE_STORED

    fun getBox(box: Int, slot: Int): PK4 {
        require(box in 0 until BOX_COUNT && slot in 0 until BOX_SLOTS)
        val o = boxOffset(box, slot)
        return PK4.fromEncrypted(raw.copyOfRange(o, o + Crypto.SIZE_STORED))
    }

    fun setBox(box: Int, slot: Int, pk: PK4) {
        require(box in 0 until BOX_COUNT && slot in 0 until BOX_SLOTS)
        val enc = pk.encryptedStored()
        System.arraycopy(enc, 0, raw, boxOffset(box, slot), enc.size)
        markDex(pk)
    }

    fun deleteBox(box: Int, slot: Int) {
        val o = boxOffset(box, slot)
        java.util.Arrays.fill(raw, o, o + Crypto.SIZE_STORED, 0)
    }

    fun boxName(box: Int): String = Gen4Text.decode(raw, storageOff + BOX_NAME + box * 40, 40)

    fun setBoxName(box: Int, name: String) = Gen4Text.encode(name, raw, storageOff + BOX_NAME + box * 40, 40, 8)

    // ---------------------------------------------------------------- bag

    class BagItem(var id: Int, var count: Int)

    fun getPouch(p: Tables.Pouch): MutableList<BagItem> {
        val base = generalOff + BAG + p.offset
        return MutableList(p.slots) { BagItem(raw.u16(base + it * 4), raw.u16(base + it * 4 + 2)) }
    }

    /** Writes the non-empty items packed to the front, clearing the remaining slots. */
    fun setPouch(p: Tables.Pouch, items: List<BagItem>) {
        val used = items.filter { it.id != 0 && it.count > 0 }
        if (used.size > p.slots) throw SaveFormatException("${p.title} pouch is full")
        val base = generalOff + BAG + p.offset
        for (i in 0 until p.slots) {
            val it = used.getOrNull(i)
            raw.putU16(base + i * 4, it?.id ?: 0)
            raw.putU16(base + i * 4 + 2, it?.count?.coerceIn(0, p.maxCount) ?: 0)
        }
    }

    // ---------------------------------------------------------------- pokédex

    private val dex get() = generalOff + DEX

    private fun dexFlag(region: Int, species: Int): Boolean {
        val i = species - 1
        return raw.u8(dex + 4 + region * 0x40 + (i shr 3)) and (1 shl (i and 7)) != 0
    }

    private fun setDexFlag(region: Int, species: Int, v: Boolean) {
        val i = species - 1
        val o = dex + 4 + region * 0x40 + (i shr 3)
        val m = 1 shl (i and 7)
        raw.putU8(o, if (v) raw.u8(o) or m else raw.u8(o) and m.inv())
    }

    fun isCaught(species: Int) = dexFlag(0, species)
    fun isSeen(species: Int) = dexFlag(1, species)

    fun setDex(species: Int, seen: Boolean, caught: Boolean) {
        if (species !in 1..Tables.MAX_SPECIES) return
        val nowSeen = seen || caught
        if (!nowSeen || !isSeen(species)) {
            // fresh entry: gender flags default to "male only"
            setDexFlag(2, species, false)
            setDexFlag(3, species, false)
        }
        setDexFlag(1, species, nowSeen)
        setDexFlag(0, species, caught)
    }

    /** Registers a Pokémon as seen+caught (as the game does when you obtain one). */
    private fun markDex(pk: PK4) {
        val sp = pk.species
        if (sp !in 1..Tables.MAX_SPECIES || pk.isEgg) return
        val gender = if (pk.gender == 1) 1 else 0
        if (!isSeen(sp)) {
            setDexFlag(2, sp, gender == 1)
            setDexFlag(3, sp, gender == 1)
        } else if (dexFlag(2, sp) == dexFlag(3, sp)) {
            setDexFlag(3, sp, gender == 1)
        }
        setDexFlag(1, sp, true)
        setDexFlag(0, sp, true)
    }

    fun caughtCount() = (1..Tables.MAX_SPECIES).count { isCaught(it) }
    fun seenCount() = (1..Tables.MAX_SPECIES).count { isSeen(it) }

    // ---------------------------------------------------------------- output

    /** Recalculates the CRCs of the live blocks and returns the full file. */
    fun export(): ByteArray {
        fixCrc(generalOff, GENERAL_SIZE)
        fixCrc(storageOff, STORAGE_SIZE)
        return if (trailer.isEmpty()) raw.copyOf() else raw + trailer
    }

    private fun fixCrc(off: Int, size: Int) {
        raw.putU16(off + size - 2, Crypto.crc16(raw, off, off + size - FOOTER))
    }

    companion object {
        const val SIZE = 0x80000
        const val PARTITION = 0x40000
        const val GENERAL_SIZE = 0xCF2C
        const val STORAGE_SIZE = 0x121E4
        const val FOOTER = 0x14
        const val MAGIC = 0x20060623L
        const val MAGIC_KOREAN = 0x20070903L

        private const val TRAINER = 0x68
        private const val PARTY = 0xA0
        private const val BAG = 0x630
        private const val DEX = 0x1328

        const val BOX_COUNT = 18
        const val BOX_SLOTS = 30
        private const val BOX_DATA_LEN = BOX_SLOTS * Crypto.SIZE_STORED
        private const val BOX_NAME = 4 + BOX_COUNT * BOX_DATA_LEN

        /** Parse and validate a Platinum save. Throws [SaveFormatException] with a readable reason. */
        fun load(file: ByteArray): Gen4Save {
            if (file.size < SIZE) throw SaveFormatException("Save is ${file.size} bytes; Platinum saves are 524288 (512 KB).")
            val body = file.copyOfRange(0, SIZE)
            val trailer = file.copyOfRange(SIZE, file.size) // e.g. DeSmuME .dsv footer; preserved on export
            // Platinum's General block is 0xCF2C; DP (0xC0EC) and HGSS (0xF628) fail this footer test.
            val ok = (0..1).any { p -> isValidFooter(body, p * PARTITION, GENERAL_SIZE) }
            if (!ok) throw SaveFormatException("Not a Pokémon Platinum (international) save: block footer mismatch.")
            val s = Gen4Save(body, trailer)
            val gOk = isCrcValid(s.raw, s.generalOff, GENERAL_SIZE)
            val sOk = isCrcValid(s.raw, s.storageOff, STORAGE_SIZE)
            if (!gOk || !sOk) {
                throw SaveFormatException(
                    "Checksum mismatch in live ${if (!gOk) "General" else "Storage"} block. " +
                        "The save is corrupt or not Platinum (US/EU).",
                )
            }
            return s
        }

        private fun isValidFooter(data: ByteArray, off: Int, size: Int): Boolean {
            val f = off + size - FOOTER
            return data.u32(f + 8) == size.toLong() && (data.u32(f + 12) == MAGIC || data.u32(f + 12) == MAGIC_KOREAN)
        }

        private fun isCrcValid(data: ByteArray, off: Int, size: Int): Boolean =
            data.u16(off + size - 2) == Crypto.crc16(data, off, off + size - FOOTER)

        /** 0 = first partition holds the newest copy, 1 = second. Mirrors PKHeX SAV4BlockDetection. */
        private fun activeBlock(data: ByteArray, begin: Int, length: Int): Int {
            val o1 = begin + length - FOOTER
            val o2 = o1 + PARTITION
            val major = compare(data.u32(o1), data.u32(o2))
            if (major != SAME) return major
            val minor = compare(data.u32(o1 + 4), data.u32(o2 + 4))
            return if (minor == 1) 1 else 0
        }

        private const val SAME = 2
        private fun compare(a: Long, b: Long): Int {
            val max = 0xFFFFFFFFL
            if (a == max && b != max - 1) return 1
            if (b == max && a != max - 1) return 0
            if (a > b) return 0
            if (a < b) return 1
            return SAME
        }
    }
}
