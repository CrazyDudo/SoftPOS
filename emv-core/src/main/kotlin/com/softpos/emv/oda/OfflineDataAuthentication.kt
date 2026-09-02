package com.softpos.emv.oda

import com.softpos.emv.capk.CapkRegistry
import com.softpos.emv.capk.CapkValidation
import com.softpos.emv.model.AipBits
import com.softpos.emv.model.Pan
import com.softpos.emv.tlv.EmvTags
import com.softpos.emv.tlv.Tag
import com.softpos.emv.tlv.TlvDatabase
import com.softpos.emv.util.Hex
import java.time.LocalDate

/**
 * Everything the read flow has to hand over for authentication.
 *
 * @param rid the first five bytes of the selected AID, which is how the CA public key is looked up.
 * @param staticData the static data to be authenticated, assembled while reading records; see
 *   [OfflineDataAuthentication.StaticDataBuilder].
 * @param terminalDynamicData for fDDA: Unpredictable Number, Amount Authorised and Transaction
 *   Currency Code exactly as they went into the PDOL response. Null when unavailable.
 */
class OdaInput(
    val db: TlvDatabase,
    val aip: ByteArray,
    val rid: String,
    val pan: Pan?,
    val staticData: ByteArray,
    val staticDataIntact: Boolean,
    val terminalDynamicData: ByteArray?,
)

/**
 * Offline data authentication as a contactless reader can do it without GENERATE AC.
 *
 * ## Method selection
 *
 * The Application Interchange Profile says what the card supports. DDA is preferred when the card
 * supports it and actually signed - a Kernel 3 card that does fast DDA returns tag 9F4B in its
 * GET PROCESSING OPTIONS response - and SDA is the fallback when it did not. CDA cannot be done
 * here: its signature is over the GENERATE AC response, and this project never issues that
 * command. A card offering only CDA is reported as [OdaSkipReason.CDA_ONLY].
 *
 * ## What "authenticated" means
 *
 * A successful result proves that the issuer certificate chains to a CA key this terminal holds,
 * that the ICC certificate chains to that issuer, and that the card signed either its static data
 * (SDA) or a challenge including this transaction's Unpredictable Number (DDA). It does not prove
 * the card is not blocked, not stolen, or authorised for this amount - those are online questions.
 */
object OfflineDataAuthentication {

    fun authenticate(
        input: OdaInput,
        capks: CapkRegistry,
        enabled: Boolean,
        today: LocalDate = LocalDate.now(),
    ): OdaResult {
        if (!enabled) return OdaResult.NotPerformed(OdaSkipReason.DISABLED)
        if (capks.isEmpty()) return OdaResult.NotPerformed(OdaSkipReason.NO_CAPK_TABLE)

        val aip = input.aip.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        val sda = aip and AipBits.SDA_SUPPORTED != 0
        val dda = aip and AipBits.DDA_SUPPORTED != 0
        val cda = aip and AipBits.CDA_SUPPORTED != 0
        if (!sda && !dda) {
            return OdaResult.NotPerformed(if (cda) OdaSkipReason.CDA_ONLY else OdaSkipReason.CARD_DOES_NOT_SUPPORT)
        }

        val db = input.db
        val sdad = db[EmvTags.SIGNED_DYNAMIC_APPLICATION_DATA]
        val method = when {
            dda && sdad != null && input.terminalDynamicData != null -> OdaMethod.DDA
            sda -> OdaMethod.SDA
            else -> return OdaResult.NotPerformed(
                OdaSkipReason.MISSING_DATA,
                if (sdad == null) "9F4B - the card supports DDA but did not sign" else "terminal dynamic data",
            )
        }

        if (!input.staticDataIntact) {
            return OdaResult.Failed(method, OdaFailureReason.MALFORMED_RECORD, "an ODA record in SFI 1-10 is not a 70 template")
        }

        val index = db.int(EmvTags.CA_PUBLIC_KEY_INDEX) ?: return missing(EmvTags.CA_PUBLIC_KEY_INDEX)
        val issuerCertificate = db[EmvTags.ISSUER_PUBLIC_KEY_CERTIFICATE]
            ?: return missing(EmvTags.ISSUER_PUBLIC_KEY_CERTIFICATE)
        val issuerExponent = db[EmvTags.ISSUER_PUBLIC_KEY_EXPONENT]
            ?: return missing(EmvTags.ISSUER_PUBLIC_KEY_EXPONENT)

        val capk = capks.find(input.rid, index)
            ?: return OdaResult.NotPerformed(OdaSkipReason.CAPK_NOT_FOUND, "RID ${input.rid} index $index")
        val validation = capks.verify(capk, today)
        if (validation != CapkValidation.Ok) {
            return OdaResult.Failed(method, OdaFailureReason.CAPK_INVALID, "$capk: $validation")
        }

        val issuer = input.pan.revealOrNull { pan ->
            EmvCertificates.recoverIssuerKey(
                capk = capk,
                certificate = issuerCertificate,
                remainder = db[EmvTags.ISSUER_PUBLIC_KEY_REMAINDER],
                exponent = issuerExponent,
                pan = pan,
                today = today,
            )
        }
        val issuerKey = when (issuer) {
            is Recovery.Failed -> return OdaResult.Failed(method, issuer.reason, issuer.detail)
            is Recovery.Ok -> issuer.value
        }

        return when (method) {
            OdaMethod.SDA -> staticAuthentication(input, issuerKey)
            OdaMethod.DDA -> dynamicAuthentication(input, issuerKey, sdad!!, today)
        }
    }

    private fun staticAuthentication(input: OdaInput, issuer: RecoveredPublicKey): OdaResult {
        val ssad = input.db[EmvTags.SIGNED_STATIC_APPLICATION_DATA]
            ?: return missing(EmvTags.SIGNED_STATIC_APPLICATION_DATA)

        return when (val result = EmvCertificates.verifySignedStaticData(issuer, ssad, input.staticData)) {
            is Recovery.Failed -> OdaResult.Failed(OdaMethod.SDA, result.reason, result.detail)
            is Recovery.Ok -> OdaResult.Authenticated(
                OdaMethod.SDA,
                "issuer key ${issuer.modulusBits} bit, DAC ${result.value.dataAuthenticationCodeHex}",
            )
        }
    }

    /**
     * Fast DDA, EMV Contactless Book C-3: the terminal-side hash input is Unpredictable Number,
     * Amount Authorised, Transaction Currency Code and then Card Authentication Related Data
     * (tag 9F69) as the card returned it. When 9F69 announces fDDA version 01, the ICC dynamic
     * data ends with the Card Transaction Qualifiers and must match the CTQ sent in clear.
     */
    private fun dynamicAuthentication(
        input: OdaInput,
        issuer: RecoveredPublicKey,
        sdad: ByteArray,
        today: LocalDate,
    ): OdaResult {
        val db = input.db
        val iccCertificate = db[EmvTags.ICC_PUBLIC_KEY_CERTIFICATE] ?: return missing(EmvTags.ICC_PUBLIC_KEY_CERTIFICATE)
        val iccExponent = db[EmvTags.ICC_PUBLIC_KEY_EXPONENT] ?: return missing(EmvTags.ICC_PUBLIC_KEY_EXPONENT)
        val cardAuthenticationData = db[EmvTags.CARD_AUTHENTICATION_RELATED_DATA]
            ?: return missing(EmvTags.CARD_AUTHENTICATION_RELATED_DATA)

        val icc = input.pan.revealOrNull { pan ->
            EmvCertificates.recoverIccKey(
                issuer = issuer,
                certificate = iccCertificate,
                remainder = db[EmvTags.ICC_PUBLIC_KEY_REMAINDER],
                exponent = iccExponent,
                staticData = input.staticData,
                pan = pan,
                today = today,
            )
        }
        val iccKey = when (icc) {
            is Recovery.Failed -> return OdaResult.Failed(OdaMethod.DDA, icc.reason, icc.detail)
            is Recovery.Ok -> icc.value
        }

        val terminalData = input.terminalDynamicData!! + cardAuthenticationData
        val dynamic = when (val result = EmvCertificates.verifySignedDynamicData(iccKey, sdad, terminalData)) {
            is Recovery.Failed -> return OdaResult.Failed(OdaMethod.DDA, result.reason, result.detail)
            is Recovery.Ok -> result.value
        }

        val fddaVersion = cardAuthenticationData.firstOrNull()?.toInt()?.and(0xFF)
        if (fddaVersion == 0x01) {
            val signedCtq = dynamic.trailingDynamicData.takeIf { it.size >= 2 }?.copyOfRange(0, 2)
            val clearCtq = db[EmvTags.CARD_TRANSACTION_QUALIFIERS]
            if (signedCtq == null || clearCtq == null || !signedCtq.contentEquals(clearCtq)) {
                return OdaResult.Failed(
                    OdaMethod.DDA,
                    OdaFailureReason.CTQ_MISMATCH,
                    "signed ${signedCtq?.let(Hex::encode) ?: "absent"} vs clear ${clearCtq?.let(Hex::encode) ?: "absent"}",
                )
            }
        }

        return OdaResult.Authenticated(
            OdaMethod.DDA,
            "issuer key ${issuer.modulusBits} bit, ICC key ${iccKey.modulusBits} bit, " +
                "ICC dynamic number ${dynamic.iccDynamicNumberHex}",
        )
    }

    private fun missing(tag: Tag): OdaResult =
        OdaResult.NotPerformed(OdaSkipReason.MISSING_DATA, "$tag ${EmvTags.name(tag)}")

    private fun <R> Pan?.revealOrNull(block: (CharSequence?) -> R): R =
        if (this == null) block(null) else reveal { block(it) }

    /**
     * Accumulates the static data to be authenticated while records are read, following EMV 4.4
     * Book 3 section 10.3:
     *  - for a record in SFI 1-10 the value of its 70 template is used, tag and length excluded;
     *    a record that is not a 70 template makes the whole input invalid,
     *  - for a record in SFI 11-30 the entire record is used as returned,
     *  - the AIP is appended afterwards when the SDA tag list (9F4A) names tag 82.
     */
    class StaticDataBuilder {
        private val buffer = java.io.ByteArrayOutputStream()
        private var intact = true

        fun addRecord(sfi: Int, record: ByteArray) {
            if (sfi in 1..10) {
                val body = templateBody(record)
                if (body == null) {
                    intact = false
                    return
                }
                buffer.write(body)
            } else {
                buffer.write(record)
            }
        }

        fun finish(sdaTagList: ByteArray?, aip: ByteArray): Pair<ByteArray, Boolean> {
            if (sdaTagList != null && sdaTagList.contentEquals(EmvTags.AIP.bytes)) buffer.write(aip)
            return buffer.toByteArray() to intact
        }

        /** The value of a leading 70 template, or null when the record is not one. */
        private fun templateBody(record: ByteArray): ByteArray? {
            if (record.size < 2 || record[0].toInt() and 0xFF != 0x70) return null
            val first = record[1].toInt() and 0xFF
            val (length, valueStart) = when {
                first and 0x80 == 0 -> first to 2
                first == 0x81 && record.size >= 3 -> (record[2].toInt() and 0xFF) to 3
                first == 0x82 && record.size >= 4 ->
                    (((record[2].toInt() and 0xFF) shl 8) or (record[3].toInt() and 0xFF)) to 4

                else -> return null
            }
            if (valueStart + length > record.size) return null
            return record.copyOfRange(valueStart, valueStart + length)
        }
    }
}
