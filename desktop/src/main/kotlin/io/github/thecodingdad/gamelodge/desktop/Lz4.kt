package io.github.thecodingdad.gamelodge.desktop

/**
 * LZ4 block compression (the standard block format): very fast to unpack, which is what frames
 * kept in memory need. Greedy matching over a hash of 4-byte sequences.
 */
internal object Lz4 {
    private const val MIN_MATCH = 4
    private const val LAST_LITERALS = 5
    private const val MF_LIMIT = 12
    private const val HASH_LOG = 14
    private const val MAX_OFFSET = 65535

    fun maxCompressedLength(length: Int) = length + length / 255 + 16

    fun compress(src: ByteArray, length: Int = src.size): ByteArray {
        val out = ByteArray(maxCompressedLength(length))
        var op = 0
        var anchor = 0
        if (length >= MF_LIMIT + 1) {
            val table = IntArray(1 shl HASH_LOG) // position + 1, 0 = empty
            val limit = length - MF_LIMIT
            val matchEnd = length - LAST_LITERALS
            var ip = 0
            while (ip < limit) {
                val sequence = readInt(src, ip)
                val h = hash(sequence)
                val ref = table[h] - 1
                table[h] = ip + 1
                if (ref < 0 || ip - ref > MAX_OFFSET || readInt(src, ref) != sequence) {
                    ip++
                    continue
                }
                var start = ip
                var from = ref
                while (start > anchor && from > 0 && src[start - 1] == src[from - 1]) { start--; from-- }
                var len = MIN_MATCH
                while (start + len < matchEnd && src[start + len] == src[from + len]) len++
                op = writeSequence(out, op, src, anchor, start - anchor, start - from, len)
                ip = start + len
                anchor = ip
                if (ip - 2 in 0 until limit) table[hash(readInt(src, ip - 2))] = ip - 2 + 1
            }
        }
        op = writeLiterals(out, op, src, anchor, length - anchor)
        return out.copyOf(op)
    }

    /** Unpacks [src] into [dst]; returns the number of bytes written. */
    fun decompress(src: ByteArray, dst: ByteArray): Int {
        var ip = 0
        var op = 0
        while (ip < src.size) {
            val token = src[ip++].toInt() and 0xFF
            var literals = token ushr 4
            if (literals == 15) {
                do { val b = src[ip++].toInt() and 0xFF; literals += b } while (b == 255)
            }
            System.arraycopy(src, ip, dst, op, literals)
            ip += literals
            op += literals
            if (ip >= src.size) break
            val offset = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
            ip += 2
            var len = token and 15
            if (len == 15) {
                do { val b = src[ip++].toInt() and 0xFF; len += b } while (b == 255)
            }
            len += MIN_MATCH
            val from = op - offset
            if (offset >= len) {
                System.arraycopy(dst, from, dst, op, len)
            } else {
                // Overlapping (a repeated pattern): byte by byte.
                for (i in 0 until len) dst[op + i] = dst[from + i]
            }
            op += len
        }
        return op
    }

    private fun writeSequence(out: ByteArray, at: Int, src: ByteArray, literalsAt: Int, literals: Int, offset: Int, matchLength: Int): Int {
        var op = at
        val token = op++
        val ml = matchLength - MIN_MATCH
        out[token] = ((minOf(literals, 15) shl 4) or minOf(ml, 15)).toByte()
        op = writeLength(out, op, literals)
        System.arraycopy(src, literalsAt, out, op, literals)
        op += literals
        out[op++] = offset.toByte()
        out[op++] = (offset ushr 8).toByte()
        return writeLength(out, op, ml)
    }

    private fun writeLiterals(out: ByteArray, at: Int, src: ByteArray, literalsAt: Int, literals: Int): Int {
        var op = at
        out[op++] = (minOf(literals, 15) shl 4).toByte()
        op = writeLength(out, op, literals)
        System.arraycopy(src, literalsAt, out, op, literals)
        return op + literals
    }

    /** The rest of a length of 15 or more: bytes of 255, then the remainder. */
    private fun writeLength(out: ByteArray, at: Int, length: Int): Int {
        if (length < 15) return at
        var op = at
        var rest = length - 15
        while (rest >= 255) { out[op++] = 255.toByte(); rest -= 255 }
        out[op++] = rest.toByte()
        return op
    }

    private fun readInt(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24)

    private fun hash(sequence: Int) = (sequence * -1640531535) ushr (32 - HASH_LOG)
}
