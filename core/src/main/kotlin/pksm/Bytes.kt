package pksm

internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
internal fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
internal fun ByteArray.u32(o: Int): Long = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
internal fun ByteArray.i32(o: Int): Int = u16(o) or (u16(o + 2) shl 16)

internal fun ByteArray.putU8(o: Int, v: Int) { this[o] = v.toByte() }
internal fun ByteArray.putU16(o: Int, v: Int) {
    this[o] = v.toByte()
    this[o + 1] = (v shr 8).toByte()
}
internal fun ByteArray.putU32(o: Int, v: Long) {
    putU16(o, (v and 0xFFFF).toInt())
    putU16(o + 2, ((v shr 16) and 0xFFFF).toInt())
}
