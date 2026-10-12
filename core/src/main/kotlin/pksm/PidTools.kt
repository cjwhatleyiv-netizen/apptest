package pksm

import java.util.Random

/** PID manipulation: nature, gender, and shininess all derive from the PID in Gen 4. */
object PidTools {
    /** 0 male, 1 female, 2 genderless for [pid] and the species gender [ratio]. */
    fun genderFromPid(pid: Long, ratio: Int): Int = when (ratio) {
        255 -> 2
        254 -> 1
        0 -> 0
        else -> if ((pid and 0xFF).toInt() >= ratio) 0 else 1
    }

    fun isShiny(tid: Int, sid: Int, pid: Long): Boolean =
        ((tid xor sid xor (pid and 0xFFFF).toInt() xor ((pid shr 16) and 0xFFFF).toInt()) and 0xFFFF) < 8

    /**
     * Find a PID with the requested [nature], [gender] (ignored if the species is fixed-gender) and shininess.
     * Also keeps the low bit of the current PID so ability-slot style parity is preserved.
     */
    fun findPid(
        tid: Int, sid: Int, ratio: Int, nature: Int, gender: Int, shiny: Boolean,
        keepParityOf: Long, rng: Random = Random(),
    ): Long {
        val fixedGender = ratio == 0 || ratio == 254 || ratio == 255
        repeat(5_000_000) {
            val lo = rng.nextInt(0x10000)
            val hi = if (shiny) (lo xor tid xor sid xor rng.nextInt(8)) and 0xFFFF else rng.nextInt(0x10000)
            val pid = (hi.toLong() shl 16) or lo.toLong()
            if (pid % 25L != nature.toLong()) return@repeat
            if (isShiny(tid, sid, pid) != shiny) return@repeat
            if (!fixedGender && genderFromPid(pid, ratio) != gender) return@repeat
            if ((pid and 1L) != (keepParityOf and 1L)) return@repeat
            return pid
        }
        error("No matching PID found")
    }

    /** Re-roll PID so nature/gender/shiny match; updates PK4 fields. */
    fun apply(pk: PK4, nature: Int, gender: Int, shiny: Boolean) {
        val ratio = pk.personal.genderRatio
        pk.pid = findPid(pk.tid, pk.sid, ratio, nature, gender, shiny, pk.pid)
        pk.gender = genderFromPid(pk.pid, ratio)
    }
}
