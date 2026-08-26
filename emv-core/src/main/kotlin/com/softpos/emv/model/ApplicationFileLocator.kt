package com.softpos.emv.model

/**
 * One entry of the Application File Locator (tag 94).
 *
 * @param odaRecordCount how many records starting at [firstRecord] take part in offline data
 *   authentication. Parsed and surfaced, but never acted on - this prototype performs no ODA.
 */
data class AflEntry(
    val sfi: Int,
    val firstRecord: Int,
    val lastRecord: Int,
    val odaRecordCount: Int,
) {
    val records: IntRange get() = firstRecord..lastRecord
    val recordCount: Int get() = lastRecord - firstRecord + 1
}

/**
 * The AFL is a sequence of four-byte entries (EMV 4.4 Book 3, section 10.2):
 *  - byte 1: SFI in bits b8-b4, bits b3-b1 are RFU and must be zero,
 *  - byte 2: first record number,
 *  - byte 3: last record number,
 *  - byte 4: number of records from the first that are covered by offline data authentication.
 */
object AflParser {

    fun parse(data: ByteArray): List<AflEntry> {
        require(data.size % 4 == 0) { "AFL length must be a multiple of 4, got ${data.size}" }
        require(data.isNotEmpty()) { "AFL must contain at least one entry" }

        val entries = ArrayList<AflEntry>(data.size / 4)
        for (i in data.indices step 4) {
            val sfi = (data[i].toInt() and 0xFF) ushr 3
            val first = data[i + 1].toInt() and 0xFF
            val last = data[i + 2].toInt() and 0xFF
            val oda = data[i + 3].toInt() and 0xFF

            // SFI 0 addresses the currently selected file and 31 is reserved; neither is legal here.
            require(sfi in 1..30) { "AFL entry ${i / 4} has invalid SFI $sfi" }
            require(first in 1..255) { "AFL entry ${i / 4} has invalid first record $first" }
            require(last >= first) { "AFL entry ${i / 4} has last record $last before first record $first" }
            require(oda <= last - first + 1) {
                "AFL entry ${i / 4} claims $oda ODA records but only covers ${last - first + 1}"
            }
            entries += AflEntry(sfi, first, last, oda)
        }
        return entries
    }

    fun parseOrNull(data: ByteArray): List<AflEntry>? = runCatching { parse(data) }.getOrNull()
}
