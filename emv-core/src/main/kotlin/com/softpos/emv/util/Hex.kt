package com.softpos.emv.util

/**
 * Hex helpers. EMV data is habitually discussed in hex, so keeping conversion in one place stops
 * ad-hoc `String.format("%02X")` loops from spreading through the card-handling code.
 */
object Hex {

    private val DIGITS = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "range $offset..${offset + length} outside array of size ${bytes.size}"
        }
        val out = CharArray(length * 2)
        for (i in 0 until length) {
            val v = bytes[offset + i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        val cleaned = hex.filterNot { it.isWhitespace() }
        require(cleaned.length % 2 == 0) { "hex string must have an even length, got ${cleaned.length}" }
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex character at index ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * Hex into a caller-owned [CharArray].
     *
     * [encode] returns a `String`, which can never be overwritten. A decoder working on sensitive
     * bytes - Track 2 Equivalent Data reproduces a full PAN - needs a buffer it can wipe once it
     * has copied out the fields it wants, and that is what this returns.
     */
    fun encodeToChars(bytes: ByteArray): CharArray {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return out
    }

    /** Renders bytes as `A0 00 00 00 03 10 10`, which is far easier to eyeball in an APDU log. */
    fun spaced(bytes: ByteArray): String = encode(bytes).chunked(2).joinToString(" ")
}

fun ByteArray.toHex(): String = Hex.encode(this)

fun String.hexToBytes(): ByteArray = Hex.decode(this)

/**
 * Reads a BCD-encoded unsigned number, e.g. tag 9F02 (Amount, Authorised) which is `n12` packed
 * into six bytes. Returns null when a nibble is not a decimal digit.
 */
fun ByteArray.bcdToLongOrNull(): Long? {
    var acc = 0L
    for (b in this) {
        val hi = (b.toInt() ushr 4) and 0x0F
        val lo = b.toInt() and 0x0F
        if (hi > 9 || lo > 9) return null
        acc = acc * 100 + hi * 10 + lo
    }
    return acc
}

/** Encodes [value] as BCD across exactly [length] bytes (`n` format, right justified). */
fun longToBcd(value: Long, length: Int): ByteArray {
    require(value >= 0) { "BCD cannot represent a negative value" }
    val out = ByteArray(length)
    var remaining = value
    for (i in length - 1 downTo 0) {
        val lo = (remaining % 10).toInt()
        remaining /= 10
        val hi = (remaining % 10).toInt()
        remaining /= 10
        out[i] = ((hi shl 4) or lo).toByte()
    }
    require(remaining == 0L) { "value $value does not fit in $length BCD bytes" }
    return out
}

/**
 * Expands packed BCD into its digit characters, dropping the trailing 'F' nibbles EMV uses as
 * padding (see EMV 4.4 Book 3, Annex A, format `cn`). Returns null on a non-digit nibble.
 *
 * Returns a caller-owned [CharArray] rather than a `String` so that a decoder handling a sensitive
 * element - tag 5A is a full PAN - can overwrite the digits when it is done. A `String` cannot be
 * overwritten and would leave them in the heap until garbage collection, which is exactly the
 * lifetime problem [com.softpos.emv.model.Pan] exists to avoid.
 */
fun ByteArray.cnToCharsOrNull(): CharArray? {
    val out = CharArray(size * 2)
    var i = 0
    for (b in this) {
        val hi = nibbleChar((b.toInt() ushr 4) and 0x0F)
        val lo = nibbleChar(b.toInt() and 0x0F)
        if (hi == null || lo == null) {
            out.fill('\u0000')
            return null
        }
        out[i++] = hi
        out[i++] = lo
    }

    var end = out.size
    while (end > 0 && out[end - 1] == 'F') end--
    if (end == out.size) return out

    val trimmed = out.copyOf(end)
    out.fill('\u0000')
    return trimmed
}

/**
 * String form of [cnToCharsOrNull], for elements that are not sensitive - tag 5F24 (expiry), for
 * instance. Never call this on a PAN: use [cnToCharsOrNull] and wipe the result.
 */
fun ByteArray.cnToDigitsOrNull(): String? =
    cnToCharsOrNull()?.let { chars -> String(chars).also { chars.fill('\u0000') } }

private fun nibbleChar(n: Int): Char? = when {
    n in 0..9 -> '0' + n
    n == 0x0F -> 'F'
    else -> null
}
