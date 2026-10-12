package pksm

/** Gen 4 (international) string encoding: u16 per char, 0xFFFF terminated. */
object Gen4Text {
    private const val TERMINATOR = 0xFFFF
    private const val INVALID = 0x1AC // '?'
    private const val HALF_M = '⑭'
    private const val HALF_F = '⑮'

    private val table = GameData.charTable

    fun decode(data: ByteArray, off: Int, byteLen: Int): String {
        val sb = StringBuilder()
        var i = off
        while (i + 1 < off + byteLen) {
            val v = data.u16(i)
            if (v == TERMINATOR || v == 0) break
            var c = if (v < table.length) table[v] else '?'
            if (c == HALF_M) c = '♂' else if (c == HALF_F) c = '♀'
            sb.append(c)
            i += 2
        }
        return sb.toString()
    }

    fun encode(text: String, dest: ByteArray, off: Int, byteLen: Int, maxChars: Int) {
        java.util.Arrays.fill(dest, off, off + byteLen, 0)
        val s = if (text.length > maxChars) text.substring(0, maxChars) else text
        var o = off
        for (ch in s) {
            val c = when (ch) {
                '♂' -> HALF_M
                '♀' -> HALF_F
                '’' -> '\''
                else -> ch
            }
            val idx = table.indexOf(c)
            dest.putU16(o, if (idx >= 0 && c != '￿') idx else INVALID)
            o += 2
        }
        if (o + 1 < off + byteLen) dest.putU16(o, TERMINATOR)
    }

    /** True if every char can be represented. */
    fun canEncode(text: String): Boolean = text.all { it == '♂' || it == '♀' || (table.indexOf(it) >= 0 && it != '￿') }
}
