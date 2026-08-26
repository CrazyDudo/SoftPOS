package com.softpos.emv.tlv

import com.softpos.emv.util.Hex

/**
 * A BER-TLV tag, stored as canonical uppercase hex so equality and map lookups are trivial.
 *
 * Tag encoding is defined in EMV 4.4 Book 3, Annex B1 (and ISO/IEC 8825-1):
 *  - b8-b7 of the leading byte carry the class,
 *  - b6 distinguishes primitive (0) from constructed (1),
 *  - b5-b1 hold the tag number; when all five bits are set the number continues into subsequent
 *    bytes, each of which sets b8 while more bytes follow.
 */
class Tag private constructor(val hex: String) : Comparable<Tag> {

    val bytes: ByteArray get() = Hex.decode(hex)

    val size: Int get() = hex.length / 2

    private val leadingByte: Int get() = Integer.parseInt(hex.substring(0, 2), 16)

    /** Constructed tags carry nested TLV objects in their value; primitives carry raw data. */
    val isConstructed: Boolean get() = (leadingByte and 0x20) != 0

    val isPrimitive: Boolean get() = !isConstructed

    val tagClass: TagClass
        get() = when ((leadingByte and 0xC0) ushr 6) {
            0 -> TagClass.UNIVERSAL
            1 -> TagClass.APPLICATION
            2 -> TagClass.CONTEXT_SPECIFIC
            else -> TagClass.PRIVATE
        }

    override fun equals(other: Any?): Boolean = other is Tag && other.hex == hex

    override fun hashCode(): Int = hex.hashCode()

    override fun compareTo(other: Tag): Int = hex.compareTo(other.hex)

    override fun toString(): String = hex

    companion object {

        /** Longest tag EMV uses in practice is three bytes (proprietary `DFxxxx`). */
        const val MAX_TAG_BYTES: Int = 4

        fun of(hex: String): Tag {
            val cleaned = hex.filterNot { it.isWhitespace() }.uppercase()
            require(cleaned.isNotEmpty()) { "tag must not be empty" }
            require(cleaned.length % 2 == 0) { "tag hex must have an even length: '$hex'" }
            require(cleaned.length <= MAX_TAG_BYTES * 2) { "tag longer than $MAX_TAG_BYTES bytes: '$hex'" }
            require(cleaned.all { it in "0123456789ABCDEF" }) { "invalid hex in tag: '$hex'" }
            return Tag(cleaned)
        }

        fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Tag =
            of(Hex.encode(bytes, offset, length))
    }
}

enum class TagClass { UNIVERSAL, APPLICATION, CONTEXT_SPECIFIC, PRIVATE }
