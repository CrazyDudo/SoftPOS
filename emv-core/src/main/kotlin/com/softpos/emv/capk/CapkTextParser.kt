package com.softpos.emv.capk

import com.softpos.emv.util.Hex
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class CapkParseError(val lineNumber: Int, val line: String, val reason: String) {
    override fun toString(): String = "line $lineNumber: $reason"
}

data class CapkParseResult(
    val entries: List<CapkEntry>,
    val errors: List<CapkParseError>,
) {
    val registry: CapkRegistry get() = CapkRegistry(entries)
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

/**
 * Reads a CA public key table from the whitespace-delimited text format that scheme bulletins and
 * the common open-source CAPK lists are usually transcribed into.
 *
 * One key per line. Blank lines and lines beginning with `#` are ignored.
 *
 * ```
 * # RID        IDX EXP    EXPIRY  H S MODULUS            CHECKSUM
 * A000000003   92  03     251231  1 1 996AF56F...        429C954A...
 * ```
 *
 * | Field    | Meaning |
 * |----------|---------|
 * | RID      | 5 bytes hex |
 * | IDX      | CA Public Key Index, 1 byte hex |
 * | EXP      | RSA public exponent, hex (`03` or `010001`) |
 * | EXPIRY   | `YYMMDD` or `YYYYMMDD`; `-` if the bulletin gives none |
 * | H        | hash algorithm, `1` = SHA-1 |
 * | S        | signature algorithm, `1` = RSA |
 * | MODULUS  | hex, 1024 to 1984 bits |
 * | CHECKSUM | 20-byte SHA-1 published with the key; `-` if absent |
 *
 * Parsing never throws: a malformed line becomes a [CapkParseError] and the rest of the file still
 * loads. A partly-loaded table is more useful than none, provided the caller looks at the errors -
 * and a key that parses can still be rejected by [CapkRegistry.verify].
 */
object CapkTextParser {

    fun parse(text: String): CapkParseResult {
        val entries = mutableListOf<CapkEntry>()
        val errors = mutableListOf<CapkParseError>()

        text.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            when (val parsed = parseLine(line)) {
                is Parsed.Ok -> entries += parsed.entry
                is Parsed.Err -> errors += CapkParseError(lineNumber, rawLine, parsed.reason)
            }
        }
        return CapkParseResult(entries, errors)
    }

    private sealed interface Parsed {
        class Ok(val entry: CapkEntry) : Parsed
        class Err(val reason: String) : Parsed
    }

    private fun parseLine(line: String): Parsed {
        val fields = line.split(Regex("\\s+"))
        if (fields.size < 7) {
            return Parsed.Err("expected at least 7 fields, found ${fields.size}")
        }

        val rid = fields[0].uppercase()
        if (rid.length != 10 || !rid.isHex()) return Parsed.Err("RID must be 10 hex characters, got '$rid'")

        val index = fields[1].toIntOrNull(16)
            ?: return Parsed.Err("CA public key index is not hex: '${fields[1]}'")
        if (index !in 0..0xFF) return Parsed.Err("CA public key index out of range: $index")

        val exponentHex = fields[2].let { if (it.length % 2 == 1) "0$it" else it }
        if (!exponentHex.isHex()) return Parsed.Err("exponent is not hex: '${fields[2]}'")

        val expiry = when (val raw = fields[3]) {
            "-", "" -> null
            else -> parseExpiry(raw) ?: return Parsed.Err("cannot read expiry '$raw'")
        }

        val hash = fields[4].toIntOrNull(16)?.let { CapkHashAlgorithm.fromCode(it) }
            ?: return Parsed.Err("unsupported hash algorithm '${fields[4]}'")
        val signature = fields[5].toIntOrNull(16)?.let { CapkSignatureAlgorithm.fromCode(it) }
            ?: return Parsed.Err("unsupported signature algorithm '${fields[5]}'")

        val modulusHex = fields[6].uppercase()
        if (!modulusHex.isHex() || modulusHex.length % 2 != 0) {
            return Parsed.Err("modulus is not an even-length hex string")
        }

        val checksumHex = fields.getOrNull(7)?.takeUnless { it == "-" }
        if (checksumHex != null && (!checksumHex.isHex() || checksumHex.length != 40)) {
            return Parsed.Err("checksum must be 40 hex characters, got '${checksumHex}'")
        }

        val label = if (fields.size > 8) fields.drop(8).joinToString(" ") else null

        return try {
            Parsed.Ok(
                CapkEntry(
                    rid = rid,
                    index = index,
                    modulus = Hex.decode(modulusHex),
                    exponent = Hex.decode(exponentHex),
                    hashAlgorithm = hash,
                    signatureAlgorithm = signature,
                    checksum = checksumHex?.let { Hex.decode(it) },
                    expiry = expiry,
                    label = label,
                ),
            )
        } catch (e: IllegalArgumentException) {
            Parsed.Err(e.message ?: "invalid key")
        }
    }

    /** Accepts `YYMMDD` and `YYYYMMDD`. Two-digit years map into 2000-2099, as elsewhere in EMV. */
    private fun parseExpiry(raw: String): LocalDate? = try {
        when (raw.length) {
            6 -> LocalDate.of(
                2000 + raw.substring(0, 2).toInt(),
                raw.substring(2, 4).toInt(),
                raw.substring(4, 6).toInt(),
            )

            8 -> LocalDate.of(
                raw.substring(0, 4).toInt(),
                raw.substring(4, 6).toInt(),
                raw.substring(6, 8).toInt(),
            )

            else -> null
        }
    } catch (e: NumberFormatException) {
        null
    } catch (e: DateTimeParseException) {
        null
    } catch (e: java.time.DateTimeException) {
        null
    }

    private fun String.isHex(): Boolean = isNotEmpty() && all {
        it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
    }

    /** Renders a registry back to the same format, for round-tripping an edited table. */
    fun format(registry: CapkRegistry): String = buildString {
        appendLine("# RID IDX EXP EXPIRY HASH SIG MODULUS CHECKSUM [LABEL]")
        for (entry in registry.entries.sortedWith(compareBy({ it.rid }, { it.index }))) {
            append(entry.rid).append(' ')
            append("%02X".format(entry.index)).append(' ')
            append(Hex.encode(entry.exponent)).append(' ')
            append(entry.expiry?.let { "%04d%02d%02d".format(it.year, it.monthValue, it.dayOfMonth) } ?: "-").append(' ')
            append("%02X".format(entry.hashAlgorithm.code)).append(' ')
            append("%02X".format(entry.signatureAlgorithm.code)).append(' ')
            append(Hex.encode(entry.modulus)).append(' ')
            append(entry.checksum?.let { Hex.encode(it) } ?: "-")
            entry.label?.let { append(' ').append(it) }
            appendLine()
        }
    }
}
