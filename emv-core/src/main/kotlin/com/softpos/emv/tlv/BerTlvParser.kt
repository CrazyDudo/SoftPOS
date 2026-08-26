package com.softpos.emv.tlv

import com.softpos.emv.util.Hex

sealed interface TlvNode {
    val tag: Tag
    val value: ByteArray
}

class PrimitiveTlv(override val tag: Tag, override val value: ByteArray) : TlvNode {
    override fun toString(): String = "$tag[${value.size}]"
}

class ConstructedTlv(
    override val tag: Tag,
    override val value: ByteArray,
    val children: List<TlvNode>,
) : TlvNode {
    override fun toString(): String = "$tag{${children.joinToString(",")}}"
}

class TlvParseException(message: String, val offset: Int) : IllegalArgumentException("$message (at offset $offset)")

/**
 * BER-TLV decoder for the EMV subset, per EMV 4.4 Book 3 Annex B.
 *
 * Deliberate deviations from full ASN.1 BER, all of which Annex B either requires or permits:
 *  - Indefinite length (`80`) is rejected; EMV forbids it.
 *  - `00` bytes appearing where a tag is expected are skipped. Annex B1 allows them before,
 *    between and after data objects.
 *  - A run of `FF` extending to the end of the buffer is treated as padding rather than the start
 *    of a private-class tag. Cards commonly pad records this way.
 */
object BerTlvParser {

    private const val MAX_DEPTH = 16

    fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<TlvNode> {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range $offset..${offset + length} outside array of size ${data.size}"
        }
        return parseRange(data, offset, offset + length, depth = 0)
    }

    /** Returns an empty list instead of throwing; useful when probing possibly-malformed records. */
    fun parseOrEmpty(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<TlvNode> =
        runCatching { parse(data, offset, length) }.getOrDefault(emptyList())

    private fun parseRange(data: ByteArray, start: Int, end: Int, depth: Int): List<TlvNode> {
        if (depth > MAX_DEPTH) throw TlvParseException("nesting deeper than $MAX_DEPTH levels", start)

        val nodes = ArrayList<TlvNode>()
        var i = start
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            if (b == 0x00) {
                i++
                continue
            }
            if (b == 0xFF && isAllPadding(data, i, end)) break

            val tagStart = i
            val tagEnd = scanTag(data, i, end)
            val tag = Tag.of(data, tagStart, tagEnd - tagStart)

            val (valueLength, valueStart) = readLength(data, tagEnd, end)
            val valueEnd = valueStart + valueLength
            if (valueEnd > end) {
                throw TlvParseException(
                    "value of $tag claims $valueLength bytes but only ${end - valueStart} remain",
                    tagStart,
                )
            }

            val value = data.copyOfRange(valueStart, valueEnd)
            nodes += if (tag.isConstructed) {
                ConstructedTlv(tag, value, parseRange(value, 0, value.size, depth + 1))
            } else {
                PrimitiveTlv(tag, value)
            }
            i = valueEnd
        }
        return nodes
    }

    private fun isAllPadding(data: ByteArray, from: Int, end: Int): Boolean {
        for (i in from until end) if (data[i] != 0xFF.toByte()) return false
        return true
    }

    /** Returns the index one past the last tag byte. */
    private fun scanTag(data: ByteArray, from: Int, end: Int): Int {
        if (from >= end) throw TlvParseException("truncated tag", from)
        var i = from
        val first = data[i].toInt() and 0xFF
        i++
        // b5-b1 all set means the tag number continues into following bytes.
        if (first and 0x1F == 0x1F) {
            while (true) {
                if (i >= end) throw TlvParseException("truncated multi-byte tag", from)
                if (i - from >= Tag.MAX_TAG_BYTES) {
                    throw TlvParseException("tag longer than ${Tag.MAX_TAG_BYTES} bytes", from)
                }
                val b = data[i].toInt() and 0xFF
                i++
                if (b and 0x80 == 0) break
            }
        }
        return i
    }

    /** Returns `length to indexOfFirstValueByte`. */
    private fun readLength(data: ByteArray, from: Int, end: Int): Pair<Int, Int> {
        if (from >= end) throw TlvParseException("truncated length", from)
        val first = data[from].toInt() and 0xFF
        if (first and 0x80 == 0) return first to (from + 1)

        val byteCount = first and 0x7F
        if (byteCount == 0) throw TlvParseException("indefinite length is not permitted in EMV", from)
        if (byteCount > 4) throw TlvParseException("length field of $byteCount bytes is unsupported", from)
        if (from + 1 + byteCount > end) throw TlvParseException("truncated long-form length", from)

        var length = 0
        for (k in 1..byteCount) {
            length = (length shl 8) or (data[from + k].toInt() and 0xFF)
        }
        if (length < 0) throw TlvParseException("length overflow", from)
        return length to (from + 1 + byteCount)
    }

    /** Re-encodes a node tree back to bytes. Used to rebuild templates and in round-trip tests. */
    fun encode(nodes: List<TlvNode>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (node in nodes) {
            out.write(node.tag.bytes)
            out.write(encodeLength(node.value.size))
            out.write(node.value)
        }
        return out.toByteArray()
    }

    fun encodeLength(length: Int): ByteArray = when {
        length < 0 -> throw IllegalArgumentException("negative length")
        length <= 0x7F -> byteArrayOf(length.toByte())
        length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
        length <= 0xFFFF -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
        else -> byteArrayOf(
            0x83.toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        )
    }
}

/** Depth-first walk over a node and everything nested inside it. */
fun TlvNode.walk(): Sequence<TlvNode> = sequence {
    yield(this@walk)
    if (this@walk is ConstructedTlv) {
        for (child in children) yieldAll(child.walk())
    }
}

fun List<TlvNode>.walk(): Sequence<TlvNode> = asSequence().flatMap { it.walk() }

fun TlvNode.render(indent: Int = 0, redactSensitive: Boolean = true): String {
    val pad = "  ".repeat(indent)
    val label = EmvTags.name(tag)
    return when (this) {
        is ConstructedTlv -> buildString {
            append("$pad$tag ($label)\n")
            children.forEach { append(it.render(indent + 1, redactSensitive)) }
        }

        is PrimitiveTlv -> {
            val shown = if (redactSensitive && EmvTags.isSensitive(tag)) {
                "<redacted ${value.size} bytes>"
            } else {
                Hex.encode(value)
            }
            "$pad$tag ($label) = $shown\n"
        }
    }
}
