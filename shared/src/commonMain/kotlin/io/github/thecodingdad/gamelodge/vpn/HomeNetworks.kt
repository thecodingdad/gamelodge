package io.github.thecodingdad.gamelodge.vpn

/**
 * The user's home networks as IPv4 entries: a single address (`192.168.1.20`), a subnet
 * (`192.168.1.0/24`) or a range (`192.168.1.10-192.168.1.50`). The device is at home when one of
 * its own addresses lies in one of them.
 */
object HomeNetworks {
    private class Span(val first: Long, val last: Long)

    /** The entry in its canonical spelling, or null when it is no valid address, subnet or range. */
    fun normalize(entry: String): String? {
        val text = entry.trim().replace(" ", "")
        return when {
            '/' in text -> {
                val address = parse(text.substringBefore('/')) ?: return null
                val bits = text.substringAfter('/').toIntOrNull()?.takeIf { it in 0..32 } ?: return null
                "${format(address and mask(bits))}/$bits"
            }
            '-' in text -> {
                val first = parse(text.substringBefore('-')) ?: return null
                val last = parse(text.substringAfter('-')) ?: return null
                if (last < first) return null
                "${format(first)}-${format(last)}"
            }
            else -> parse(text)?.let(::format)
        }
    }

    /** Whether one of [addresses] (the device's own) lies in one of [entries]. */
    fun contains(entries: Collection<String>, addresses: Collection<String>): Boolean {
        val spans = entries.mapNotNull(::span)
        return addresses.mapNotNull(::parse).any { ip -> spans.any { ip in it.first..it.last } }
    }

    /** The subnet `address/bits` lies in, e.g. `192.168.1.0/24` for `192.168.1.20` and 24. */
    fun subnetOf(address: String, bits: Int): String? =
        parse(address)?.takeIf { bits in 0..32 }?.let { "${format(it and mask(bits))}/$bits" }

    private fun span(entry: String): Span? {
        val text = normalize(entry) ?: return null
        return when {
            '/' in text -> {
                val base = parse(text.substringBefore('/'))!!
                val bits = text.substringAfter('/').toInt()
                Span(base, base or (mask(bits).inv() and 0xFFFFFFFFL))
            }
            '-' in text -> Span(parse(text.substringBefore('-'))!!, parse(text.substringAfter('-'))!!)
            else -> parse(text)!!.let { Span(it, it) }
        }
    }

    private fun mask(bits: Int): Long = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL

    private fun parse(address: String): Long? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val n = part.toIntOrNull()?.takeIf { it in 0..255 && part.length in 1..3 } ?: return null
            value = (value shl 8) or n.toLong()
        }
        return value
    }

    private fun format(value: Long) = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 0xFF).toString() }
}
