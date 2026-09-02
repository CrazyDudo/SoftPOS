package com.softpos.emv.tlv

import com.softpos.emv.util.Hex
import com.softpos.emv.util.bcdToLongOrNull

/**
 * Flattened view of everything a card returned across SELECT, GPO and READ RECORD.
 *
 * EMV keeps data elements addressable by tag regardless of which template they arrived in, so the
 * read flow accumulates them here. Constructed templates are stored alongside their children,
 * which lets callers ask for `61` (Application Template) or for `4F` inside it.
 *
 * Duplicates are kept in arrival order. Per EMV 4.4 Book 3 section 10.2 a card should not return
 * the same element twice, but real cards sometimes do, and silently discarding the second copy
 * hides the anomaly during debugging.
 */
class TlvDatabase private constructor(
    private val entries: LinkedHashMap<Tag, MutableList<ByteArray>>,
) {

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    operator fun get(tag: Tag): ByteArray? = entries[tag]?.firstOrNull()

    fun getAll(tag: Tag): List<ByteArray> = entries[tag]?.toList() ?: emptyList()

    operator fun contains(tag: Tag): Boolean = entries.containsKey(tag)

    fun tags(): Set<Tag> = entries.keys.toSet()

    fun hex(tag: Tag): String? = get(tag)?.let { Hex.encode(it) }

    /** Reads a tag whose value is a big-endian unsigned integer of at most four bytes. */
    fun int(tag: Tag): Int? {
        val v = get(tag) ?: return null
        if (v.isEmpty() || v.size > 4) return null
        var acc = 0
        for (b in v) acc = (acc shl 8) or (b.toInt() and 0xFF)
        return acc
    }

    /** Reads a BCD (`n` format) value such as 9F02 Amount, Authorised. */
    fun bcd(tag: Tag): Long? = get(tag)?.bcdToLongOrNull()

    /**
     * Card-supplied text, e.g. tag 50 Application Label or tag 9F12 Application Preferred Name.
     *
     * Control characters are dropped rather than merely trimmed. The card chooses these bytes, and
     * the resulting string travels a long way - into a log line, a CSV cell, a JSON string and a
     * label on screen. A label carrying an embedded newline or a NUL corrupts every one of those,
     * and no legitimate label needs one. C0 (`00`-`1F`), DEL and C1 (`80`-`9F`) go; printable
     * ASCII and printable Latin-1 stay.
     */
    fun text(tag: Tag): String? = get(tag)
        ?.toString(Charsets.ISO_8859_1)
        ?.filter { it.code in 0x20..0x7E || it.code >= 0xA0 }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /**
     * Overwrites the values of every element flagged sensitive in [EmvTags], then removes them.
     *
     * Zeroing the backing arrays matters because the same arrays may still be referenced by an
     * in-flight [com.softpos.emv.model.RawCardData]; dropping the map entry alone would leave the
     * track data sitting in the heap until GC happens to reclaim it.
     */
    fun wipeSensitive() {
        val sensitive = entries.keys.filter { EmvTags.isSensitive(it) }
        for (tag in sensitive) {
            entries.remove(tag)?.forEach { it.fill(0) }
        }
    }

    operator fun plus(other: TlvDatabase): TlvDatabase {
        val merged = LinkedHashMap<Tag, MutableList<ByteArray>>(entries.size + other.entries.size)
        for ((tag, values) in entries) merged[tag] = ArrayList(values)
        for ((tag, values) in other.entries) merged.getOrPut(tag) { ArrayList() }.addAll(values)
        return TlvDatabase(merged)
    }

    fun describe(redactSensitive: Boolean = true): String = buildString {
        for ((tag, values) in entries) {
            for (value in values) {
                val shown = when {
                    tag.isConstructed -> "<template>"
                    redactSensitive && EmvTags.isSensitive(tag) -> "<redacted ${value.size} bytes>"
                    else -> Hex.encode(value)
                }
                append(tag).append(" (").append(EmvTags.name(tag)).append(") = ").append(shown).append('\n')
            }
        }
    }

    override fun toString(): String = "TlvDatabase(${entries.size} tags)"

    class Builder {
        private val entries = LinkedHashMap<Tag, MutableList<ByteArray>>()

        fun put(tag: Tag, value: ByteArray) = apply {
            entries.getOrPut(tag) { ArrayList(1) }.add(value)
        }

        fun putAll(nodes: List<TlvNode>) = apply {
            for (node in nodes.walk()) {
                // A constructed template's value is the concatenation of its children, so storing
                // it would keep a second copy of every sensitive element nested inside - one that
                // wipeSensitive() could never reach, because the template's own tag is not
                // sensitive. The same walk stores each child individually, so the template only
                // needs to record that it was present.
                put(node.tag, if (node is ConstructedTlv) EMPTY_VALUE else node.value)
            }
        }

        fun putAll(other: TlvDatabase) = apply {
            for ((tag, values) in other.entries) entries.getOrPut(tag) { ArrayList() }.addAll(values)
        }

        fun build(): TlvDatabase = TlvDatabase(entries)
    }

    companion object {
        private val EMPTY_VALUE = ByteArray(0)

        fun empty(): TlvDatabase = TlvDatabase(LinkedHashMap())

        fun from(nodes: List<TlvNode>): TlvDatabase = Builder().putAll(nodes).build()

        fun parse(data: ByteArray): TlvDatabase = from(BerTlvParser.parse(data))

        fun builder(): Builder = Builder()
    }
}
