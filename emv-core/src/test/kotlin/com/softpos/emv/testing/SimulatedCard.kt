package com.softpos.emv.testing

import com.softpos.emv.apdu.ApduTransceiver
import com.softpos.emv.apdu.ApduTransportException
import com.softpos.emv.util.Hex
import com.softpos.emv.util.toHex

/**
 * One application on a simulated card.
 *
 * @param records keyed by `(sfi, recordNumber)`. A missing key produces 6A83 (record not found).
 */
class SimulatedApplication(
    val fciHex: String,
    val gpoResponseHex: String,
    val records: Map<Pair<Int, Int>, String> = emptyMap(),
    val selectStatusWord: Int = 0x9000,
    val gpoStatusWord: Int = 0x9000,
)

/**
 * An [ApduTransceiver] that answers like a contactless card.
 *
 * This exists so the whole read flow - PPSE, selection, GPO, READ RECORD - can be exercised as an
 * ordinary JVM unit test. Every response below is hand-built from the encoding rules in EMV 4.4
 * Book 3, not captured from a real card.
 */
class SimulatedCard(
    private val ppseFciHex: String? = null,
    private val applications: Map<String, SimulatedApplication> = emptyMap(),
    /** Raise a transport error on the nth command (1-based); 0 disables. */
    private val failAtCommand: Int = 0,
) : ApduTransceiver {

    private val _commands = mutableListOf<String>()

    /** Every command APDU received, as uppercase hex, in order. */
    val commands: List<String> get() = _commands.toList()

    private var selectedApplication: SimulatedApplication? = null

    override fun transceive(command: ByteArray): ByteArray {
        _commands += command.toHex()
        if (failAtCommand > 0 && _commands.size == failAtCommand) {
            throw ApduTransportException("simulated tag loss at command ${_commands.size}")
        }

        require(command.size >= 4) { "command shorter than an APDU header" }
        val ins = command[1].toInt() and 0xFF
        val p1 = command[2].toInt() and 0xFF
        val p2 = command[3].toInt() and 0xFF

        return when (ins) {
            0xA4 -> handleSelect(command)
            0xA8 -> handleGpo()
            0xB2 -> handleReadRecord(recordNumber = p1, sfi = (p2 and 0xF8) ushr 3)
            else -> statusOnly(0x6D00)
        }
    }

    private fun handleSelect(command: ByteArray): ByteArray {
        val lc = command[4].toInt() and 0xFF
        val name = command.copyOfRange(5, 5 + lc)

        if (name.contentEquals(PPSE_NAME)) {
            val fci = ppseFciHex ?: return statusOnly(0x6A82)
            return respond(fci, 0x9000)
        }

        val nameHex = name.toHex()
        val app = applications.entries.firstOrNull { nameHex.startsWith(it.key) || it.key.startsWith(nameHex) }
            ?: return statusOnly(0x6A82)

        if (app.value.selectStatusWord != 0x9000) return statusOnly(app.value.selectStatusWord)

        selectedApplication = app.value
        return respond(app.value.fciHex, 0x9000)
    }

    private fun handleGpo(): ByteArray {
        val app = selectedApplication ?: return statusOnly(0x6985)
        if (app.gpoStatusWord != 0x9000) return statusOnly(app.gpoStatusWord)
        return respond(app.gpoResponseHex, 0x9000)
    }

    private fun handleReadRecord(recordNumber: Int, sfi: Int): ByteArray {
        val app = selectedApplication ?: return statusOnly(0x6985)
        val record = app.records[sfi to recordNumber] ?: return statusOnly(0x6A83)
        return respond(record, 0x9000)
    }

    private fun respond(dataHex: String, statusWord: Int): ByteArray =
        Hex.decode(dataHex) + byteArrayOf((statusWord ushr 8).toByte(), statusWord.toByte())

    private fun statusOnly(statusWord: Int): ByteArray =
        byteArrayOf((statusWord ushr 8).toByte(), statusWord.toByte())

    companion object {
        private val PPSE_NAME = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Reference test data.
 *
 * PAN 4761739001010010 is a published Visa test number (it satisfies Luhn and is not issued to
 * anyone). No data here comes from a real card.
 */
object TestCards {

    const val VISA_PAN = "4761739001010010"
    const val VISA_AID = "A0000000031010"
    const val MASTERCARD_AID = "A0000000041010"

    /**
     * PPSE FCI advertising one Visa application.
     *
     * ```
     * 6F 29                                   FCI Template
     *    84 0E 325041592E5359532E4444463031   DF Name "2PAY.SYS.DDF01"
     *    A5 17                                FCI Proprietary Template
     *       BF0C 14                           FCI Issuer Discretionary Data
     *          61 12                          Application Template
     *             4F 07 A0000000031010        ADF Name
     *             50 04 56495341              Application Label "VISA"
     *             87 01 01                    Application Priority Indicator
     * ```
     */
    const val PPSE_FCI_VISA =
        "6F29" +
            "840E" + "325041592E5359532E4444463031" +
            "A517" +
            "BF0C14" +
            "6112" +
            "4F07" + VISA_AID +
            "5004" + "56495341" +
            "8701" + "01"

    /** PPSE advertising Mastercard first (priority 1) then Visa (priority 2). */
    const val PPSE_FCI_TWO_APPS =
        "6F3B" +
            "840E" + "325041592E5359532E4444463031" +
            "A529" +
            "BF0C26" +
            "6112" +
            "4F07" + MASTERCARD_AID +
            "5004" + "4D435244" +
            "8701" + "01" +
            "6110" +
            "4F07" + VISA_AID +
            "5002" + "5649" +
            "8701" + "02"

    /**
     * PPSE advertising a single AID under RID A000000099, which is registered to no scheme and is
     * therefore in no terminal AID list.
     */
    const val PPSE_FCI_UNSUPPORTED =
        "6F28" +
            "840E" + "325041592E5359532E4444463031" +
            "A516" +
            "BF0C13" +
            "6111" +
            "4F06" + "A00000009999" +
            "5004" + "54455354" +
            "8701" + "01"

    /** Mastercard ADF FCI, same PDOL shape as the Visa one. */
    const val MASTERCARD_ADF_FCI =
        "6F23" +
            "8407" + MASTERCARD_AID +
            "A518" +
            "500A" + "4D415354455243415244" +
            "8701" + "01" +
            "9F3806" + "9F66049F0206"

    /**
     * Visa ADF FCI with a PDOL requesting 9F66 (TTQ, 4 bytes) and 9F02 (amount, 6 bytes).
     *
     * ```
     * 6F 29
     *    84 07 A0000000031010
     *    A5 1E
     *       50 0B 5649534120435245444954   "VISA CREDIT"
     *       87 01 01
     *       9F38 06 9F6604 9F0206          PDOL
     *       5F2D 02 656E                   Language preference "en"
     * ```
     */
    const val VISA_ADF_FCI =
        "6F29" +
            "8407" + VISA_AID +
            "A51E" +
            "500B" + "5649534120435245444954" +
            "8701" + "01" +
            "9F3806" + "9F66049F0206" +
            "5F2D02" + "656E"

    /** Same application but with no PDOL, so GPO carries an empty 83 template. */
    const val VISA_ADF_FCI_NO_PDOL =
        "6F20" +
            "8407" + VISA_AID +
            "A515" +
            "500B" + "5649534120435245444954" +
            "8701" + "01" +
            "5F2D02" + "656E"

    /**
     * GPO response, Format 2 (tag 77).
     * AIP 1980, AFL two entries: SFI 1 record 1, and SFI 2 records 1-3.
     */
    const val GPO_FORMAT_2 =
        "770E" +
            "8202" + "1980" +
            "9408" + "08010100" + "10010300"

    /** Same content encoded as Format 1 (tag 80): AIP concatenated with AFL, no inner tags. */
    const val GPO_FORMAT_1 = "800A" + "1980" + "08010100" + "10010300"

    /** SFI 1 record 1: Track 2 Equivalent Data only. */
    const val RECORD_SFI1_REC1 =
        "7015" +
            "5713" + VISA_PAN + "D2512201" + "0000000000000F"

    /** SFI 2 record 1: PAN, expiry and cardholder name. */
    const val RECORD_SFI2_REC1 =
        "7022" +
            "5A08" + VISA_PAN +
            "5F2403" + "251231" +
            "5F200F" + "43415244484F4C4445522F54455354"

    /** SFI 2 record 1 variant with the PAN removed, to test the Track 2 fallback and the failure path. */
    const val RECORD_SFI2_REC1_NO_PAN =
        "7018" +
            "5F2403" + "251231" +
            "5F200F" + "43415244484F4C4445522F54455354"

    const val RECORD_SFI2_REC2 = "7008" + "9F0D05" + "F040AC8000"
    const val RECORD_SFI2_REC3 = "7008" + "9F0E05" + "0010000000"

    val VISA_RECORDS: Map<Pair<Int, Int>, String> = mapOf(
        (1 to 1) to RECORD_SFI1_REC1,
        (2 to 1) to RECORD_SFI2_REC1,
        (2 to 2) to RECORD_SFI2_REC2,
        (2 to 3) to RECORD_SFI2_REC3,
    )

    fun visaApplication(
        fci: String = VISA_ADF_FCI,
        gpo: String = GPO_FORMAT_2,
        records: Map<Pair<Int, Int>, String> = VISA_RECORDS,
        gpoStatusWord: Int = 0x9000,
    ) = SimulatedApplication(fci, gpo, records, gpoStatusWord = gpoStatusWord)

    fun visaCard(
        ppse: String? = PPSE_FCI_VISA,
        application: SimulatedApplication = visaApplication(),
    ) = SimulatedCard(ppse, mapOf(VISA_AID to application))
}
