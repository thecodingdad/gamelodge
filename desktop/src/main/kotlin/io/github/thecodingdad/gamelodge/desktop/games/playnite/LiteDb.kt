package io.github.thecodingdad.gamelodge.desktop.games.playnite

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Read-only access to LiteDB 4 files (Playnite's library). Only what reading every document
 * needs: the data pages and the extend pages large documents continue in; indexes are ignored.
 *
 * Layout: 4096-byte pages with a 25-byte header (id, type, previous, next, item count, free bytes).
 * A data page holds blocks of (index u16, extend page u32, length u16, BSON bytes); a block whose
 * extend page is set keeps its whole document in that chain of extend pages, each carrying
 * "item count" bytes.
 */
object LiteDb {
    private const val PAGE = 4096
    private const val HEADER = 25
    private const val TYPE_HEADER = 1
    private const val TYPE_DATA = 4
    private const val NONE = 0xFFFFFFFFL
    private const val MAGIC = "** This is a LiteDB file **"

    /** Every document of every collection in [file]. */
    fun readAll(file: File): List<Map<String, Any?>> = readAll(file.readBytes())

    fun readAll(bytes: ByteArray): List<Map<String, Any?>> {
        require(bytes.size >= PAGE) { "Not a LiteDB file" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes[4].toInt() == TYPE_HEADER && String(bytes, HEADER, MAGIC.length, Charsets.US_ASCII) == MAGIC) { "Not a LiteDB file" }
        val pages = bytes.size / PAGE
        val docs = ArrayList<Map<String, Any?>>()
        for (p in 0 until pages) {
            val base = p * PAGE
            if (bytes[base + 4].toInt() != TYPE_DATA) continue
            val count = buf.getShort(base + 13).toInt() and 0xFFFF
            var off = base + HEADER
            repeat(count) {
                if (off + 8 > base + PAGE) return@repeat
                val extend = buf.getInt(off + 2).toLong() and 0xFFFFFFFFL
                val size = buf.getShort(off + 6).toInt() and 0xFFFF
                val data = if (extend != NONE) extendChain(bytes, buf, extend) else bytes.copyOfRange(off + 8, off + 8 + size)
                off += 8 + size
                runCatching { Bson.document(data) }.getOrNull()?.let(docs::add)
            }
        }
        return docs
    }

    private fun extendChain(bytes: ByteArray, buf: ByteBuffer, first: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var page = first
        val seen = HashSet<Long>()
        while (page != NONE && seen.add(page) && (page + 1) * PAGE <= bytes.size) {
            val base = (page * PAGE).toInt()
            val length = (buf.getShort(base + 13).toInt() and 0xFFFF).coerceAtMost(PAGE - HEADER)
            out.write(bytes, base + HEADER, length)
            page = buf.getInt(base + 9).toLong() and 0xFFFFFFFFL
        }
        return out.toByteArray()
    }
}

/** BSON as LiteDB writes it (GUIDs as binary subtype 4 in .NET byte order, dates in ms). */
internal object Bson {
    fun document(data: ByteArray): Map<String, Any?> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return readDocument(buf)
    }

    private fun readDocument(buf: ByteBuffer): Map<String, Any?> {
        val start = buf.position()
        val length = buf.getInt()
        val end = start + length
        val map = LinkedHashMap<String, Any?>()
        while (buf.position() < end - 1) {
            val type = buf.get().toInt() and 0xFF
            if (type == 0) break
            val name = cstring(buf)
            map[name] = value(buf, type)
        }
        buf.position(end)
        return map
    }

    private fun value(buf: ByteBuffer, type: Int): Any? = when (type) {
        0x01 -> buf.getDouble()
        0x02 -> {
            val len = buf.getInt()
            val bytes = ByteArray(len - 1).also { buf.get(it) }
            buf.get()
            String(bytes, Charsets.UTF_8)
        }
        0x03 -> readDocument(buf)
        0x04 -> readDocument(buf).values.toList()
        0x05 -> {
            val len = buf.getInt()
            val subtype = buf.get().toInt()
            val bytes = ByteArray(len).also { buf.get(it) }
            if (subtype == 4 && len == 16) guid(bytes) else bytes
        }
        0x07 -> ByteArray(12).also { buf.get(it) }
        0x08 -> buf.get().toInt() != 0
        0x09 -> java.time.Instant.ofEpochMilli(buf.getLong())
        0x0A -> null
        0x10 -> buf.getInt()
        0x12 -> buf.getLong()
        0x13 -> ByteArray(16).also { buf.get(it) }
        0xFF, 0x7F -> null
        else -> error("Unknown BSON type $type")
    }

    private fun cstring(buf: ByteBuffer): String {
        val start = buf.position()
        while (buf.get().toInt() != 0) Unit
        return String(buf.array(), start, buf.position() - start - 1, Charsets.UTF_8)
    }

    /** .NET's Guid.ToByteArray order: the first three groups little-endian. */
    private fun guid(b: ByteArray): UUID {
        val bb = ByteBuffer.wrap(b)
        val a = Integer.reverseBytes(bb.getInt()).toLong() and 0xFFFFFFFFL
        val c = java.lang.Short.reverseBytes(bb.getShort()).toLong() and 0xFFFF
        val d = java.lang.Short.reverseBytes(bb.getShort()).toLong() and 0xFFFF
        val high = (a shl 32) or (c shl 16) or d
        return UUID(high, bb.getLong())
    }
}
