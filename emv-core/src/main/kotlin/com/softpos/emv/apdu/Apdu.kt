package com.softpos.emv.apdu

import com.softpos.emv.util.Hex

/**
 * Short-form command APDU (ISO/IEC 7816-4). EMV contactless never needs extended length, so the
 * encoding here is limited to cases 1-4 short.
 */
class CommandApdu(
    val cla: Int,
    val ins: Int,
    val p1: Int,
    val p2: Int,
    val data: ByteArray = EMPTY,
    /** `null` means "no Le byte"; `0` means "up to 256 bytes". */
    val le: Int? = null,
) {

    init {
        require(cla in 0..0xFF && ins in 0..0xFF && p1 in 0..0xFF && p2 in 0..0xFF) {
            "APDU header bytes must fit in one byte each"
        }
        require(data.size <= 255) { "short-form Lc cannot exceed 255, got ${data.size}" }
        require(le == null || le in 0..256) { "Le out of range: $le" }
    }

    fun toBytes(): ByteArray {
        val lcLen = if (data.isNotEmpty()) 1 else 0
        val leLen = if (le != null) 1 else 0
        val out = ByteArray(4 + lcLen + data.size + leLen)
        out[0] = cla.toByte()
        out[1] = ins.toByte()
        out[2] = p1.toByte()
        out[3] = p2.toByte()
        var i = 4
        if (data.isNotEmpty()) {
            out[i++] = data.size.toByte()
            data.copyInto(out, i)
            i += data.size
        }
        if (le != null) {
            // 256 and 0 are both encoded as 0x00.
            out[i] = if (le == 256) 0 else le.toByte()
        }
        return out
    }

    fun withLe(newLe: Int?): CommandApdu = CommandApdu(cla, ins, p1, p2, data, newLe)

    override fun toString(): String = Hex.spaced(toBytes())

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

/** Response APDU: optional data followed by the two status bytes. */
class ResponseApdu(val bytes: ByteArray) {

    init {
        require(bytes.size >= 2) { "response APDU must contain at least SW1 SW2, got ${bytes.size} bytes" }
    }

    val data: ByteArray get() = bytes.copyOfRange(0, bytes.size - 2)

    val sw1: Int get() = bytes[bytes.size - 2].toInt() and 0xFF

    val sw2: Int get() = bytes[bytes.size - 1].toInt() and 0xFF

    val statusWord: StatusWord get() = StatusWord((sw1 shl 8) or sw2)

    val isSuccess: Boolean get() = statusWord.isSuccess

    override fun toString(): String = "${Hex.encode(data)} ${statusWord}"
}

@JvmInline
value class StatusWord(val value: Int) {

    val sw1: Int get() = (value ushr 8) and 0xFF
    val sw2: Int get() = value and 0xFF

    val isSuccess: Boolean get() = value == SUCCESS

    /** SW1 = 61 means the card has more data waiting for a GET RESPONSE. */
    val hasMoreData: Boolean get() = sw1 == 0x61

    /** SW1 = 6C means the Le we sent was wrong and SW2 carries the correct value. */
    val wrongLength: Boolean get() = sw1 == 0x6C

    fun describe(): String = when {
        value == SUCCESS -> "Success"
        sw1 == 0x61 -> "More data available (${sw2} bytes)"
        sw1 == 0x6C -> "Wrong Le, expected ${sw2}"
        value == 0x6283 -> "Selected file invalidated (card or application blocked)"
        value == 0x6300 -> "Authentication failed"
        value == 0x6700 -> "Wrong length"
        value == 0x6982 -> "Security status not satisfied"
        value == 0x6983 -> "Authentication method blocked"
        value == 0x6984 -> "Referenced data invalidated"
        value == 0x6985 -> "Conditions of use not satisfied"
        value == 0x6A80 -> "Incorrect parameters in command data"
        value == 0x6A81 -> "Function not supported"
        value == 0x6A82 -> "File or application not found"
        value == 0x6A83 -> "Record not found"
        value == 0x6A86 -> "Incorrect P1 P2"
        value == 0x6A88 -> "Referenced data not found"
        value == 0x6D00 -> "Instruction not supported"
        value == 0x6E00 -> "Class not supported"
        else -> "Unknown status word"
    }

    override fun toString(): String = String.format("%04X", value)

    companion object {
        const val SUCCESS = 0x9000
        const val CONDITIONS_NOT_SATISFIED = 0x6985
        const val FILE_NOT_FOUND = 0x6A82
        const val RECORD_NOT_FOUND = 0x6A83
        const val FILE_INVALIDATED = 0x6283
        const val FUNCTION_NOT_SUPPORTED = 0x6A81
    }
}

/**
 * Transport abstraction over whatever carries APDUs to the card.
 *
 * Keeping this an interface is what lets the entire EMV read flow run as a plain JVM unit test
 * against a simulated card; the Android module supplies the `IsoDep`-backed implementation.
 */
fun interface ApduTransceiver {
    /** Sends a full command APDU and returns the full response including SW1 SW2. */
    @Throws(ApduTransportException::class)
    fun transceive(command: ByteArray): ByteArray
}

class ApduTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
