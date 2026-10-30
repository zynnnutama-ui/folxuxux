package com.zynnn.axwarestream.core.tiktok

import java.io.ByteArrayOutputStream

/**
 * Pembaca/penulis Protocol Buffers minimal (wire format resmi) untuk pesan TikTok LIVE Webcast.
 * Hanya tipe wire yang dipakai: varint (0), 64-bit (1), length-delimited (2), 32-bit (5).
 */
class ProtoMsg(private val fields: Map<Int, List<Any>>) {
    fun long(n: Int): Long = (fields[n]?.firstOrNull() as? Long) ?: 0L
    fun int(n: Int): Int = long(n).toInt()
    fun bool(n: Int): Boolean = long(n) != 0L
    fun bytes(n: Int): ByteArray? = fields[n]?.firstOrNull() as? ByteArray
    fun string(n: Int): String = bytes(n)?.toString(Charsets.UTF_8) ?: ""
    fun msg(n: Int): ProtoMsg? = bytes(n)?.let { runCatching { parse(it) }.getOrNull() }
    fun msgs(n: Int): List<ProtoMsg> = fields[n].orEmpty().mapNotNull { (it as? ByteArray)?.let { b -> runCatching { parse(b) }.getOrNull() } }
    fun strings(n: Int): List<String> = fields[n].orEmpty().mapNotNull { (it as? ByteArray)?.toString(Charsets.UTF_8) }
    fun has(n: Int) = fields.containsKey(n)

    /** Entri map<string,string> (setiap entri adalah pesan {1:key, 2:value}). */
    fun stringMap(n: Int): Map<String, String> = msgs(n).associate { it.string(1) to it.string(2) }

    companion object {
        fun parse(b: ByteArray): ProtoMsg {
            val out = HashMap<Int, MutableList<Any>>()
            var p = 0
            fun varint(): Long {
                var shift = 0; var r = 0L
                while (true) {
                    if (p >= b.size) throw IllegalArgumentException("varint terpotong")
                    val x = b[p++].toInt() and 0xFF
                    r = r or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return r
                    shift += 7
                    if (shift > 63) throw IllegalArgumentException("varint terlalu panjang")
                }
            }
            while (p < b.size) {
                val key = varint()
                val field = (key ushr 3).toInt(); val wire = (key and 7).toInt()
                if (field <= 0) throw IllegalArgumentException("field tidak valid")
                val v: Any = when (wire) {
                    0 -> varint()
                    1 -> { if (p + 8 > b.size) throw IllegalArgumentException("fixed64 terpotong"); var r = 0L; for (i in 0 until 8) r = r or ((b[p + i].toLong() and 0xFF) shl (8 * i)); p += 8; r }
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || p + len > b.size) throw IllegalArgumentException("panjang tidak valid")
                        b.copyOfRange(p, p + len).also { p += len }
                    }
                    5 -> { if (p + 4 > b.size) throw IllegalArgumentException("fixed32 terpotong"); var r = 0L; for (i in 0 until 4) r = r or ((b[p + i].toLong() and 0xFF) shl (8 * i)); p += 4; r }
                    else -> throw IllegalArgumentException("wire type $wire tidak didukung")
                }
                out.getOrPut(field) { ArrayList() }.add(v)
            }
            return ProtoMsg(out)
        }
    }
}

class ProtoWriter {
    private val out = ByteArrayOutputStream()
    private fun varint(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
    fun long(field: Int, v: Long) = apply { if (v != 0L) { varint((field shl 3).toLong()); varint(v) } }
    fun bytes(field: Int, v: ByteArray) = apply { if (v.isNotEmpty()) { varint(((field shl 3) or 2).toLong()); varint(v.size.toLong()); out.write(v) } }
    fun string(field: Int, v: String) = bytes(field, v.toByteArray(Charsets.UTF_8))
    fun toByteArray(): ByteArray = out.toByteArray()
}
