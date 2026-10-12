package pksm

/** Gen 4 Pokémon encryption (PRNG xor + block shuffle). Ported from PKHeX's PokeCrypto. */
object Crypto {
    const val SIZE_STORED = 136
    const val SIZE_PARTY = 236

    private val BLOCK_POSITION = intArrayOf(
        0, 1, 2, 3, 0, 1, 3, 2, 0, 2, 1, 3, 0, 3, 1, 2,
        0, 2, 3, 1, 0, 3, 2, 1, 1, 0, 2, 3, 1, 0, 3, 2,
        2, 0, 1, 3, 3, 0, 1, 2, 2, 0, 3, 1, 3, 0, 2, 1,
        1, 2, 0, 3, 1, 3, 0, 2, 2, 1, 0, 3, 3, 1, 0, 2,
        2, 3, 0, 1, 3, 2, 0, 1, 1, 2, 3, 0, 1, 3, 2, 0,
        2, 1, 3, 0, 3, 1, 2, 0, 2, 3, 1, 0, 3, 2, 1, 0,
        // duplicates of 0-7 so shift values 24..31 need no modulus
        0, 1, 2, 3, 0, 1, 3, 2, 0, 2, 1, 3, 0, 3, 1, 2,
        0, 2, 3, 1, 0, 3, 2, 1, 1, 0, 2, 3, 1, 0, 3, 2,
    )

    private val BLOCK_POSITION_INVERT = intArrayOf(
        0, 1, 2, 4, 3, 5, 6, 7,
        12, 18, 13, 19, 8, 10, 14, 20,
        16, 22, 9, 11, 15, 21, 17, 23,
        0, 1, 2, 4, 3, 5, 6, 7,
    )

    private fun cryptArray(d: ByteArray, from: Int, to: Int, seed0: Int) {
        var seed = seed0
        var i = from
        while (i + 1 < to) {
            seed = 0x41C64E6D * seed + 0x6073
            val xor = (seed ushr 16) and 0xFFFF
            d.putU16(i, d.u16(i) xor xor)
            i += 2
        }
    }

    private fun shuffle(d: ByteArray, sv: Int) {
        if (sv == 0) return
        val blocks = Array(4) { d.copyOfRange(8 + it * 32, 8 + (it + 1) * 32) }
        for (i in 0 until 4) {
            System.arraycopy(blocks[BLOCK_POSITION[sv * 4 + i]], 0, d, 8 + i * 32, 32)
        }
    }

    /** @param data 136 (stored) or 236 (party) encrypted bytes. Returns a new decrypted array. */
    fun decrypt(data: ByteArray): ByteArray {
        require(data.size == SIZE_STORED || data.size == SIZE_PARTY) { "bad pk4 size ${data.size}" }
        val d = data.copyOf()
        val pv = d.i32(0)
        val chk = d.u16(6)
        val sv = (pv ushr 13) and 31
        cryptArray(d, 8, SIZE_STORED, chk)
        if (d.size > SIZE_STORED) cryptArray(d, SIZE_STORED, d.size, pv)
        shuffle(d, sv)
        return d
    }

    /** @param data 136 or 236 decrypted bytes with a valid checksum. Returns a new encrypted array. */
    fun encrypt(data: ByteArray): ByteArray {
        require(data.size == SIZE_STORED || data.size == SIZE_PARTY) { "bad pk4 size ${data.size}" }
        val d = data.copyOf()
        val pv = d.i32(0)
        val chk = d.u16(6)
        val sv = BLOCK_POSITION_INVERT[(pv ushr 13) and 31]
        shuffle(d, sv)
        cryptArray(d, 8, SIZE_STORED, chk)
        if (d.size > SIZE_STORED) cryptArray(d, SIZE_STORED, d.size, pv)
        return d
    }

    /** Sum of the u16 words in [8, 136). */
    fun checksum(decrypted: ByteArray): Int {
        var sum = 0
        var i = 8
        while (i < SIZE_STORED) {
            sum += decrypted.u16(i)
            i += 2
        }
        return sum and 0xFFFF
    }

    /** CRC16-CCITT variant used by Gen 4 save blocks. */
    fun crc16(data: ByteArray, from: Int, to: Int): Int {
        var top = 0xFF
        var bot = 0xFF
        for (i in from until to) {
            var x = (data.u8(i) xor top)
            x = x xor (x ushr 4)
            top = (bot xor (x ushr 3) xor (x shl 4)) and 0xFF
            bot = (x xor (x shl 5)) and 0xFF
        }
        return (top shl 8) or bot
    }
}
