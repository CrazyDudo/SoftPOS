package com.softpos.emv.apdu

import com.softpos.emv.tlv.Tag
import java.io.ByteArrayOutputStream

/**
 * The four commands this prototype issues. Coding follows EMV 4.4 Book 1 section 11 and
 * Book 3 section 6.5.
 *
 * Not implemented on purpose: GENERATE AC (Book 3, 6.5.5), INTERNAL AUTHENTICATE (6.5.9),
 * VERIFY (6.5.12) and EXTERNAL AUTHENTICATE. Those belong to the authorisation half of a
 * transaction, which this project does not perform.
 */
object EmvCommands {

    /** "2PAY.SYS.DDF01" - the contactless Proximity Payment System Environment (EMV Book B, 3.3.2). */
    val PPSE_NAME: ByteArray = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)

    /** "1PAY.SYS.DDF01" - the contact PSE, kept for reference. */
    val PSE_NAME: ByteArray = "1PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)

    private const val CLA_ISO = 0x00
    private const val CLA_PROPRIETARY = 0x80

    private const val INS_SELECT = 0xA4
    private const val INS_READ_RECORD = 0xB2
    private const val INS_GET_PROCESSING_OPTIONS = 0xA8
    private const val INS_GET_RESPONSE = 0xC0
    private const val INS_GET_DATA = 0xCA

    /**
     * SELECT by DF name. P1 = 04 (select by name), P2 = 00 (first occurrence) or 02 (next).
     * EMV 4.4 Book 1, section 11.3.
     */
    fun selectByName(name: ByteArray, first: Boolean = true): CommandApdu = CommandApdu(
        cla = CLA_ISO,
        ins = INS_SELECT,
        p1 = 0x04,
        p2 = if (first) 0x00 else 0x02,
        data = name,
        le = 0x00,
    )

    /**
     * GET PROCESSING OPTIONS. The command data is always a `83` template wrapping the PDOL-related
     * data; when the application has no PDOL the template is empty (`83 00`).
     * EMV 4.4 Book 3, section 6.5.8.
     */
    fun getProcessingOptions(pdolData: ByteArray): CommandApdu {
        require(pdolData.size <= 252) { "PDOL data too long for a short APDU: ${pdolData.size}" }
        val body = ByteArrayOutputStream(pdolData.size + 2)
        body.write(0x83)
        body.write(pdolData.size)
        body.write(pdolData)
        return CommandApdu(
            cla = CLA_PROPRIETARY,
            ins = INS_GET_PROCESSING_OPTIONS,
            p1 = 0x00,
            p2 = 0x00,
            data = body.toByteArray(),
            le = 0x00,
        )
    }

    /**
     * READ RECORD. P1 carries the record number, P2 carries the SFI in its top five bits with the
     * low three bits set to 100b, meaning "P1 is a record number".
     * EMV 4.4 Book 3, section 6.5.11.
     */
    fun readRecord(recordNumber: Int, sfi: Int): CommandApdu {
        require(recordNumber in 1..255) { "record number out of range: $recordNumber" }
        require(sfi in 1..30) { "SFI out of range: $sfi" }
        return CommandApdu(
            cla = CLA_ISO,
            ins = INS_READ_RECORD,
            p1 = recordNumber,
            p2 = (sfi shl 3) or 0x04,
            le = 0x00,
        )
    }

    /** GET RESPONSE, issued after a 61xx status word. */
    fun getResponse(length: Int): CommandApdu = CommandApdu(
        cla = CLA_ISO,
        ins = INS_GET_RESPONSE,
        p1 = 0x00,
        p2 = 0x00,
        le = if (length == 0) 256 else length,
    )

    /**
     * GET DATA for a primitive element held outside any file, such as 9F36 (ATC) or 9F13 (Last
     * Online ATC). Only two-byte tags are addressable. EMV 4.4 Book 3, section 6.5.7.
     */
    fun getData(tag: Tag): CommandApdu {
        val bytes = tag.bytes
        require(bytes.size == 2) { "GET DATA requires a two-byte tag, got $tag" }
        return CommandApdu(
            cla = CLA_PROPRIETARY,
            ins = INS_GET_DATA,
            p1 = bytes[0].toInt() and 0xFF,
            p2 = bytes[1].toInt() and 0xFF,
            le = 0x00,
        )
    }
}
