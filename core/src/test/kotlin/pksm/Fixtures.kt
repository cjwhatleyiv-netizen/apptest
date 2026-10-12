package pksm

internal fun fixture(name: String): ByteArray =
    object {}.javaClass.getResourceAsStream("/pk4/$name")!!.use { it.readBytes() }

/** Build a minimal but structurally valid Platinum save (both partitions) for tests. */
internal fun syntheticSave(): ByteArray {
    val f = ByteArray(Gen4Save.SIZE)
    fun footer(off: Int, size: Int, major: Long, minor: Long) {
        val fo = off + size - Gen4Save.FOOTER
        f.putU32(fo, major); f.putU32(fo + 4, minor)
        f.putU32(fo + 8, size.toLong()); f.putU32(fo + 12, Gen4Save.MAGIC)
    }
    for (p in 0..1) {
        val base = p * Gen4Save.PARTITION
        // partition 1 is newer (counter 2) than partition 0 (counter 1)
        footer(base, Gen4Save.GENERAL_SIZE, if (p == 1) 2 else 1, 0)
        footer(base + Gen4Save.GENERAL_SIZE, Gen4Save.STORAGE_SIZE, if (p == 1) 2 else 1, 0)
        val g = base
        val s = base + Gen4Save.GENERAL_SIZE
        f.putU16(g + Gen4Save.GENERAL_SIZE - 2, Crypto.crc16(f, g, g + Gen4Save.GENERAL_SIZE - Gen4Save.FOOTER))
        f.putU16(s + Gen4Save.STORAGE_SIZE - 2, Crypto.crc16(f, s, s + Gen4Save.STORAGE_SIZE - Gen4Save.FOOTER))
    }
    return f
}
