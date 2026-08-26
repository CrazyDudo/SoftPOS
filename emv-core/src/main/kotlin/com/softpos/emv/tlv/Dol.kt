package com.softpos.emv.tlv

import java.io.ByteArrayOutputStream

/** One `(tag, length)` pair inside a Data Object List. */
data class DolEntry(val tag: Tag, val length: Int)

/**
 * Data Object Lists - PDOL (9F38), CDOL1 (8C), CDOL2 (8D), TDOL (97).
 *
 * A DOL is a concatenation of tags each followed by a BER length, with no values. The terminal
 * answers with the values alone, concatenated in the same order and padded to the requested
 * lengths. See EMV 4.4 Book 3, section 5.4.
 */
object DolParser {

    fun parse(data: ByteArray): List<DolEntry> {
        val entries = ArrayList<DolEntry>()
        var i = 0
        while (i < data.size) {
            // Padding bytes may separate entries, same rule as ordinary TLV.
            if (data[i] == 0x00.toByte()) {
                i++
                continue
            }
            val tagStart = i
            val first = data[i].toInt() and 0xFF
            i++
            if (first and 0x1F == 0x1F) {
                while (true) {
                    if (i >= data.size) throw TlvParseException("truncated DOL tag", tagStart)
                    val b = data[i].toInt() and 0xFF
                    i++
                    if (b and 0x80 == 0) break
                }
            }
            val tag = Tag.of(data, tagStart, i - tagStart)

            if (i >= data.size) throw TlvParseException("DOL entry $tag has no length", tagStart)
            val lengthByte = data[i].toInt() and 0xFF
            i++
            val length = if (lengthByte and 0x80 == 0) {
                lengthByte
            } else {
                val n = lengthByte and 0x7F
                if (n == 0 || n > 2) throw TlvParseException("unsupported DOL length form", tagStart)
                if (i + n > data.size) throw TlvParseException("truncated DOL length", tagStart)
                var acc = 0
                repeat(n) {
                    acc = (acc shl 8) or (data[i].toInt() and 0xFF)
                    i++
                }
                acc
            }
            entries += DolEntry(tag, length)
        }
        return entries
    }

    fun totalLength(entries: List<DolEntry>): Int = entries.sumOf { it.length }
}

/** Supplies terminal-side values while a DOL is being filled in. */
fun interface TagValueSource {
    fun valueOf(tag: Tag): ByteArray?
}

object DolBuilder {

    /**
     * Concatenates the values for [entries]. Any tag the terminal cannot supply is filled with
     * zeroes of the requested length, which is what EMV 4.4 Book 3 section 5.4 mandates - the card
     * relies on the field being present and correctly sized, not on it being meaningful.
     */
    fun build(entries: List<DolEntry>, source: TagValueSource): ByteArray {
        val out = ByteArrayOutputStream(DolParser.totalLength(entries))
        for (entry in entries) {
            out.write(fit(source.valueOf(entry.tag), entry.length, EmvTags.format(entry.tag)))
        }
        return out.toByteArray()
    }

    /**
     * Pads or truncates [value] to exactly [length] bytes following the justification rules for
     * [format] (EMV 4.4 Book 3, section 5.4):
     *  - `n`  : right justified, padded on the left with hex zeroes, truncated from the left.
     *  - `cn` : left justified, padded on the right with 'F' nibbles, truncated from the right.
     *  - other: left justified, padded on the right with hex zeroes, truncated from the right.
     */
    fun fit(value: ByteArray?, length: Int, format: TagFormat): ByteArray {
        require(length >= 0) { "negative DOL length" }
        if (value == null) return ByteArray(length)
        if (value.size == length) return value.copyOf()

        return when (format) {
            TagFormat.NUMERIC -> if (value.size < length) {
                ByteArray(length).also { value.copyInto(it, destinationOffset = length - value.size) }
            } else {
                value.copyOfRange(value.size - length, value.size)
            }

            TagFormat.COMPRESSED_NUMERIC -> if (value.size < length) {
                ByteArray(length) { 0xFF.toByte() }.also { value.copyInto(it) }
            } else {
                value.copyOfRange(0, length)
            }

            else -> if (value.size < length) {
                ByteArray(length).also { value.copyInto(it) }
            } else {
                value.copyOfRange(0, length)
            }
        }
    }
}
