package com.softpos.emv.flow

import com.softpos.emv.apdu.CommandApdu
import com.softpos.emv.apdu.ResponseApdu
import com.softpos.emv.apdu.StatusWord
import com.softpos.emv.tlv.BerTlvParser
import com.softpos.emv.tlv.ConstructedTlv
import com.softpos.emv.tlv.EmvTags
import com.softpos.emv.tlv.PrimitiveTlv
import com.softpos.emv.tlv.TlvNode
import com.softpos.emv.util.Hex

/**
 * APDU log for debugging a card exchange.
 *
 * A raw contactless trace contains tag 57 and tag 5A, which means a plain hex dump of a READ RECORD
 * response is a full PAN. Redaction is therefore on by default and works by re-parsing the response
 * and blanking the value of every element [EmvTags] marks sensitive. When the response cannot be
 * parsed the whole data field is blanked rather than passed through, because an unparseable
 * response is exactly the case where we cannot prove it holds nothing sensitive.
 */
class ApduTrace(
    private val enabled: Boolean = true,
    private val redact: Boolean = true,
) {

    data class Exchange(
        val step: String,
        val command: String,
        val response: String,
        val statusWord: StatusWord,
        val elapsedMicros: Long,
    )

    private val entries = ArrayList<Exchange>()

    val exchanges: List<Exchange> get() = entries.toList()

    fun record(step: String, command: CommandApdu, response: ResponseApdu, elapsedNanos: Long) {
        if (!enabled) return
        entries += Exchange(
            step = step,
            command = redactCommand(command),
            response = redactResponse(response.data),
            statusWord = response.statusWord,
            elapsedMicros = elapsedNanos / 1_000,
        )
    }

    fun recordFailure(step: String, command: CommandApdu, error: String) {
        if (!enabled) return
        entries += Exchange(
            step = step,
            command = redactCommand(command),
            response = "<transport error: $error>",
            statusWord = StatusWord(0),
            elapsedMicros = 0,
        )
    }

    /**
     * A command APDU is safe to show except for GPO, whose data field carries the PDOL response.
     * That contains only terminal-supplied values, so it is kept.
     */
    private fun redactCommand(command: CommandApdu): String = Hex.spaced(command.toBytes())

    private fun redactResponse(data: ByteArray): String {
        if (data.isEmpty()) return ""
        if (!redact) return Hex.spaced(data)
        val nodes = BerTlvParser.parseOrEmpty(data)
        if (nodes.isEmpty()) return "<${data.size} bytes, unparsed and withheld>"
        return nodes.joinToString(" ") { renderRedacted(it) }
    }

    private fun renderRedacted(node: TlvNode): String = when (node) {
        is ConstructedTlv -> "${node.tag}[${node.children.joinToString(" ") { renderRedacted(it) }}]"
        is PrimitiveTlv -> if (EmvTags.isSensitive(node.tag)) {
            "${node.tag}=<${node.value.size}B>"
        } else {
            "${node.tag}=${Hex.encode(node.value)}"
        }
    }

    fun format(): String = buildString {
        appendLine("APDU trace (${entries.size} exchanges${if (redact) ", sensitive values withheld" else ""})")
        entries.forEachIndexed { index, e ->
            appendLine("${index + 1}. ${e.step}  [${e.elapsedMicros} us]")
            appendLine("   -> ${e.command}")
            appendLine("   <- ${e.response} ${e.statusWord} (${e.statusWord.describe()})")
        }
    }

    override fun toString(): String = format()
}
