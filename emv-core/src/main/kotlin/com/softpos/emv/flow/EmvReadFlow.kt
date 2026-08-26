package com.softpos.emv.flow

import com.softpos.emv.apdu.ApduTransceiver
import com.softpos.emv.apdu.ApduTransportException
import com.softpos.emv.apdu.CommandApdu
import com.softpos.emv.apdu.EmvCommands
import com.softpos.emv.apdu.ResponseApdu
import com.softpos.emv.apdu.StatusWord
import com.softpos.emv.model.AflEntry
import com.softpos.emv.model.AflParser
import com.softpos.emv.model.AidRegistry
import com.softpos.emv.model.CardCandidate
import com.softpos.emv.model.CardScheme
import com.softpos.emv.model.EmvKernel
import com.softpos.emv.model.ExpiryDate
import com.softpos.emv.model.Pan
import com.softpos.emv.model.RawCardData
import com.softpos.emv.model.Track2Data
import com.softpos.emv.terminal.TerminalProfile
import com.softpos.emv.tlv.BerTlvParser
import com.softpos.emv.tlv.ConstructedTlv
import com.softpos.emv.tlv.DolBuilder
import com.softpos.emv.tlv.DolParser
import com.softpos.emv.tlv.EmvTags
import com.softpos.emv.tlv.TlvDatabase
import com.softpos.emv.tlv.TlvNode
import com.softpos.emv.tlv.TlvParseException
import com.softpos.emv.tlv.walk
import com.softpos.emv.util.Hex

enum class ReadStage {
    PPSE_SELECT,
    CANDIDATE_SELECTION,
    APPLICATION_SELECT,
    GET_PROCESSING_OPTIONS,
    READ_RECORDS,
    DATA_EXTRACTION,
}

enum class ReadErrorCode {
    /** No AID advertised by the card is in the terminal's registry. */
    NO_SUPPORTED_APPLICATION,

    /** The card answered, but with a status word that ends this attempt. */
    CARD_RESPONSE_ERROR,

    /** The card is present but the application or card itself is blocked. */
    CARD_BLOCKED,

    /** A response could not be decoded as BER-TLV. */
    MALFORMED_RESPONSE,

    /** Decoding succeeded but a mandatory element is absent. */
    MISSING_MANDATORY_DATA,

    /** The tag went out of range, or the radio link dropped. */
    TRANSPORT_ERROR,
}

sealed interface EmvReadResult {

    val trace: ApduTrace

    class Success(
        val card: RawCardData,
        /** Non-fatal anomalies worth surfacing, e.g. a record the AFL promised but the card lacks. */
        val warnings: List<String>,
        override val trace: ApduTrace,
    ) : EmvReadResult

    class Failure(
        val stage: ReadStage,
        val code: ReadErrorCode,
        val message: String,
        val statusWord: StatusWord? = null,
        override val trace: ApduTrace,
        /** True when another candidate application might still succeed. */
        val recoverable: Boolean = false,
    ) : EmvReadResult {
        override fun toString(): String = "$stage/$code: $message" + (statusWord?.let { " (SW=$it)" } ?: "")
    }
}

data class ReadOptions(
    /**
     * When a card exposes no PPSE, fall back to selecting each registered AID in turn
     * (EMV 4.4 Book 1, section 12.3.3, "list of AIDs" method).
     */
    val allowAidListFallback: Boolean = true,

    /**
     * EMV requires every record named by the AFL to exist. Real cards occasionally disagree; with
     * this false a missing record becomes a warning instead of ending the read.
     */
    val strictAflReads: Boolean = false,

    val traceApdu: Boolean = true,

    val redactTrace: Boolean = true,

    /** Guard against a malfunctioning or hostile card driving an unbounded read. */
    val maxRecordsToRead: Int = 64,
)

/**
 * Offline contactless read: PPSE, application selection, GET PROCESSING OPTIONS, READ RECORD.
 *
 * Sequence follows EMV 4.4 Book 1 section 12.3 (application selection) and Book 3 section 10.1-10.2
 * (initiate application processing, read application data), with the contactless entry point
 * behaviour of EMV Contactless Book B section 3.3.
 *
 * ## What this deliberately does not do
 *
 * The flow stops after READ RECORD. It never issues GENERATE AC, so it produces no cryptogram and
 * reaches no approve or decline decision. That is a scope choice, not an omission to be filled in
 * casually - a terminal that generates an AC is performing a payment transaction.
 *
 * Also absent, each of which would need verification against physical cards before being trusted:
 *  - TODO Offline Data Authentication (SDA, DDA, CDA - EMV Book 2 sections 5, 6 and 6.6). The
 *    certificate elements are parsed into the TLV database but no signature is checked and no CA
 *    public key table exists. Nothing in this codebase may be read as evidence a card is genuine.
 *  - TODO Cardholder Verification Method processing (Book 3 section 10.5). The CVM list is read
 *    but not evaluated; no PIN is ever requested.
 *  - TODO Terminal Risk Management and Terminal Action Analysis (Book 3 sections 10.6 and 10.7).
 *  - TODO Multi-application conflict resolution beyond priority ordering. EMV Book 1 section 12.4
 *    requires cardholder confirmation when the Application Priority Indicator sets b8; the
 *    candidate exposes [CardCandidate.requiresCardholderConfirmation] but this flow does not
 *    prompt, it simply proceeds in priority order.
 *  - TODO Kernel-specific behaviour. Visa Kernel 3 and Mastercard Kernel 2 diverge sharply after
 *    GPO; the shared prefix implemented here is enough to read card data and no more.
 */
class EmvReadFlow(
    private val transceiver: ApduTransceiver,
    private val terminal: TerminalProfile,
    private val amountMinor: Long,
    private val registry: AidRegistry = AidRegistry.Default,
    private val options: ReadOptions = ReadOptions(),
) {

    private val trace = ApduTrace(enabled = options.traceApdu, redact = options.redactTrace)
    private val warnings = ArrayList<String>()

    /** Internal plumbing so intermediate steps can short-circuit without pretending to be a result. */
    private sealed interface Step<out T> {
        class Ok<T>(val value: T) : Step<T>
        class Err(val failure: EmvReadResult.Failure) : Step<Nothing>
    }

    fun execute(): EmvReadResult {
        val candidates = try {
            buildCandidateList()
        } catch (e: ApduTransportException) {
            return transportFailure(ReadStage.PPSE_SELECT, e)
        }

        val list = when (candidates) {
            is Step.Err -> return candidates.failure
            is Step.Ok -> candidates.value
        }

        if (list.isEmpty()) {
            return EmvReadResult.Failure(
                stage = ReadStage.CANDIDATE_SELECTION,
                code = ReadErrorCode.NO_SUPPORTED_APPLICATION,
                message = "Card advertised no application this terminal supports",
                trace = trace,
            )
        }

        var lastFailure: EmvReadResult.Failure? = null
        for (candidate in list) {
            val outcome = try {
                readApplication(candidate)
            } catch (e: ApduTransportException) {
                return transportFailure(ReadStage.APPLICATION_SELECT, e)
            }
            when (outcome) {
                is EmvReadResult.Success -> return outcome
                is EmvReadResult.Failure -> {
                    lastFailure = outcome
                    if (!outcome.recoverable) return outcome
                    // EMV Contactless Book B 3.3.2.4: drop this candidate and try the next one.
                    warnings += "Candidate ${candidate.aidHex} rejected: ${outcome.message}"
                }
            }
        }
        return lastFailure ?: EmvReadResult.Failure(
            stage = ReadStage.CANDIDATE_SELECTION,
            code = ReadErrorCode.NO_SUPPORTED_APPLICATION,
            message = "Every candidate application was rejected",
            trace = trace,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Step 1: candidate list
    // -----------------------------------------------------------------------------------------

    private fun buildCandidateList(): Step<List<CardCandidate>> {
        val response = transmit("SELECT PPSE", EmvCommands.selectByName(EmvCommands.PPSE_NAME))

        if (response.isSuccess) {
            val nodes = try {
                BerTlvParser.parse(response.data)
            } catch (e: TlvParseException) {
                return Step.Err(
                    EmvReadResult.Failure(
                        stage = ReadStage.PPSE_SELECT,
                        code = ReadErrorCode.MALFORMED_RESPONSE,
                        message = "PPSE FCI is not valid BER-TLV: ${e.message}",
                        trace = trace,
                    ),
                )
            }
            val candidates = parsePpseCandidates(nodes)
            if (candidates.isNotEmpty()) return Step.Ok(candidates)
            warnings += "PPSE returned no supported application; falling back to the AID list"
        } else {
            warnings += "PPSE unavailable (SW=${response.statusWord}); falling back to the AID list"
        }

        if (!options.allowAidListFallback) {
            return Step.Err(
                EmvReadResult.Failure(
                    stage = ReadStage.PPSE_SELECT,
                    code = ReadErrorCode.NO_SUPPORTED_APPLICATION,
                    message = "PPSE yielded no candidate and AID list fallback is disabled",
                    statusWord = response.statusWord,
                    trace = trace,
                ),
            )
        }
        return Step.Ok(probeAidList())
    }

    /**
     * Extracts Application Templates from the PPSE FCI.
     *
     * Structure per EMV Contactless Book B section 3.3.2.5:
     * `6F -> A5 -> BF0C -> 61* -> { 4F ADF name, 50 label, 87 priority, 9F2A kernel id }`.
     *
     * The tree is walked rather than descended strictly, because some cards nest `61` slightly
     * differently and the templates are unambiguous wherever they appear.
     */
    private fun parsePpseCandidates(nodes: List<TlvNode>): List<CardCandidate> {
        val templates = nodes.walk().filterIsInstance<ConstructedTlv>()
            .filter { it.tag == EmvTags.APPLICATION_TEMPLATE }
            .toList()

        return templates.mapNotNull { template ->
            val db = TlvDatabase.from(template.children)
            val aid = db[EmvTags.ADF_NAME] ?: return@mapNotNull null
            val registered = registry.match(aid) ?: return@mapNotNull null
            CardCandidate(
                aid = aid,
                label = db.text(EmvTags.APPLICATION_LABEL),
                preferredName = db.text(EmvTags.APPLICATION_PREFERRED_NAME),
                priority = db.int(EmvTags.APPLICATION_PRIORITY_INDICATOR),
                registered = registered,
            )
        }.sortedBy { it.sortKey }
    }

    /** Selects each registered AID in turn; anything the card accepts becomes a candidate. */
    private fun probeAidList(): List<CardCandidate> {
        val found = ArrayList<CardCandidate>()
        for (registered in registry.probeList()) {
            val response = transmit(
                "SELECT ${registered.aidHex}",
                EmvCommands.selectByName(registered.bytes),
            )
            if (!response.isSuccess) continue

            val db = runCatching { TlvDatabase.parse(response.data) }.getOrNull() ?: continue
            val aid = db[EmvTags.DF_NAME] ?: registered.bytes
            found += CardCandidate(
                aid = aid,
                label = db.text(EmvTags.APPLICATION_LABEL),
                preferredName = db.text(EmvTags.APPLICATION_PREFERRED_NAME),
                priority = db.int(EmvTags.APPLICATION_PRIORITY_INDICATOR),
                registered = registered,
            )
        }
        return found.sortedBy { it.sortKey }
    }

    // -----------------------------------------------------------------------------------------
    // Steps 2-4: select, GPO, read records
    // -----------------------------------------------------------------------------------------

    private fun readApplication(candidate: CardCandidate): EmvReadResult {
        val selectResponse = transmit("SELECT ${candidate.aidHex}", EmvCommands.selectByName(candidate.aid))

        if (!selectResponse.isSuccess) {
            val blocked = selectResponse.statusWord.value == StatusWord.FILE_INVALIDATED
            return EmvReadResult.Failure(
                stage = ReadStage.APPLICATION_SELECT,
                code = if (blocked) ReadErrorCode.CARD_BLOCKED else ReadErrorCode.CARD_RESPONSE_ERROR,
                message = "SELECT ${candidate.aidHex} failed: ${selectResponse.statusWord.describe()}",
                statusWord = selectResponse.statusWord,
                trace = trace,
                recoverable = !blocked,
            )
        }

        val fci = try {
            TlvDatabase.parse(selectResponse.data)
        } catch (e: TlvParseException) {
            return EmvReadResult.Failure(
                stage = ReadStage.APPLICATION_SELECT,
                code = ReadErrorCode.MALFORMED_RESPONSE,
                message = "FCI for ${candidate.aidHex} is not valid BER-TLV: ${e.message}",
                trace = trace,
                recoverable = true,
            )
        }

        val processing = when (val gpo = performGpo(candidate, fci)) {
            is Step.Err -> return gpo.failure
            is Step.Ok -> gpo.value
        }

        val collected = TlvDatabase.builder().putAll(fci).putAll(processing.db)
        readRecords(processing.afl, collected)?.let { return it }

        return extract(candidate, processing.aip, processing.afl, collected.build())
    }

    private class ProcessingOptions(
        val aip: ByteArray,
        val afl: List<AflEntry>,
        val db: TlvDatabase,
    )

    /**
     * GET PROCESSING OPTIONS. The PDOL from the FCI decides what the terminal must supply; when the
     * application has no PDOL an empty `83` template is sent. EMV 4.4 Book 3, section 10.1.
     */
    private fun performGpo(candidate: CardCandidate, fci: TlvDatabase): Step<ProcessingOptions> {
        val pdolBytes = fci[EmvTags.PDOL]
        val pdolData = if (pdolBytes == null || pdolBytes.isEmpty()) {
            ByteArray(0)
        } else {
            val entries = try {
                DolParser.parse(pdolBytes)
            } catch (e: TlvParseException) {
                return gpoError(ReadErrorCode.MALFORMED_RESPONSE, "PDOL is malformed: ${e.message}")
            }
            val source = terminal.tagSource(
                amountMinor = amountMinor,
                kernel = candidate.kernel.takeIf { it != EmvKernel.UNSUPPORTED } ?: EmvKernel.KERNEL_3,
            )
            DolBuilder.build(entries, source)
        }

        val response = transmit("GET PROCESSING OPTIONS", EmvCommands.getProcessingOptions(pdolData))

        if (!response.isSuccess) {
            // 6985 is the card saying "not with these terminal parameters". Another application on
            // the same card may still accept the transaction.
            return gpoError(
                code = ReadErrorCode.CARD_RESPONSE_ERROR,
                message = "GPO rejected: ${response.statusWord.describe()}",
                statusWord = response.statusWord,
                recoverable = response.statusWord.value == StatusWord.CONDITIONS_NOT_SATISFIED,
            )
        }

        val nodes = try {
            BerTlvParser.parse(response.data)
        } catch (e: TlvParseException) {
            return gpoError(ReadErrorCode.MALFORMED_RESPONSE, "GPO response is not valid BER-TLV: ${e.message}")
        }

        val db = TlvDatabase.from(nodes)
        val aip: ByteArray
        val aflBytes: ByteArray

        val format1 = db[EmvTags.RESPONSE_TEMPLATE_FORMAT_1]
        if (format1 != null) {
            // Format 1 (tag 80): the value is AIP concatenated with AFL, with no inner tags.
            // EMV 4.4 Book 3, section 6.5.8.4.
            if (format1.size < 2) {
                return gpoError(
                    ReadErrorCode.MALFORMED_RESPONSE,
                    "Format 1 GPO response is only ${format1.size} bytes",
                )
            }
            aip = format1.copyOfRange(0, 2)
            aflBytes = format1.copyOfRange(2, format1.size)
        } else {
            // Format 2 (tag 77): AIP and AFL arrive as ordinary TLV children.
            aip = db[EmvTags.AIP] ?: return gpoError(
                ReadErrorCode.MISSING_MANDATORY_DATA,
                "GPO response has neither tag 80 nor tag 82",
            )
            aflBytes = db[EmvTags.AFL] ?: ByteArray(0)
        }

        val afl = if (aflBytes.isEmpty()) {
            // Legal for MSD-only cards, which carry their data in the GPO response itself.
            warnings += "GPO returned no AFL; no records to read"
            emptyList()
        } else {
            AflParser.parseOrNull(aflBytes) ?: return gpoError(
                ReadErrorCode.MALFORMED_RESPONSE,
                "AFL is malformed: ${Hex.encode(aflBytes)}",
            )
        }

        val enriched = TlvDatabase.builder()
            .putAll(db)
            .put(EmvTags.AIP, aip)
            .apply { if (aflBytes.isNotEmpty()) put(EmvTags.AFL, aflBytes) }
            .build()

        return Step.Ok(ProcessingOptions(aip, afl, enriched))
    }

    private fun gpoError(
        code: ReadErrorCode,
        message: String,
        statusWord: StatusWord? = null,
        recoverable: Boolean = true,
    ) = Step.Err(
        EmvReadResult.Failure(
            stage = ReadStage.GET_PROCESSING_OPTIONS,
            code = code,
            message = message,
            statusWord = statusWord,
            trace = trace,
            recoverable = recoverable,
        ),
    )

    /** Returns a [EmvReadResult.Failure] on a fatal problem, or null when reading completed. */
    private fun readRecords(afl: List<AflEntry>, into: TlvDatabase.Builder): EmvReadResult.Failure? {
        var recordsRead = 0

        for (entry in afl) {
            for (recordNumber in entry.records) {
                if (recordsRead >= options.maxRecordsToRead) {
                    warnings += "Stopped after ${options.maxRecordsToRead} records"
                    return null
                }
                recordsRead++

                val response = transmit(
                    "READ RECORD sfi=${entry.sfi} rec=$recordNumber",
                    EmvCommands.readRecord(recordNumber, entry.sfi),
                )

                if (!response.isSuccess) {
                    val message = "READ RECORD sfi=${entry.sfi} rec=$recordNumber failed: " +
                        response.statusWord.describe()
                    if (options.strictAflReads) {
                        return EmvReadResult.Failure(
                            stage = ReadStage.READ_RECORDS,
                            code = ReadErrorCode.CARD_RESPONSE_ERROR,
                            message = message,
                            statusWord = response.statusWord,
                            trace = trace,
                        )
                    }
                    warnings += message
                    continue
                }

                // Records in SFI 1-10 are always wrapped in a 70 template; SFI 11-30 are issuer
                // proprietary and need not be TLV at all. EMV 4.4 Book 3, section 10.2.
                val nodes = BerTlvParser.parseOrEmpty(response.data)
                if (nodes.isEmpty()) {
                    warnings += "Record sfi=${entry.sfi} rec=$recordNumber is not TLV-encoded; skipped"
                    continue
                }
                into.putAll(nodes)

                // TODO: records inside the ODA range would need to be retained verbatim and
                //  concatenated to rebuild the Static Data Authentication input. Recorded here so
                //  the gap is visible where it matters.
                if (recordNumber < entry.firstRecord + entry.odaRecordCount) {
                    // Intentionally no accumulation - see the ODA note in the class documentation.
                }
            }
        }
        return null
    }

    // -----------------------------------------------------------------------------------------
    // Step 5: turn the accumulated TLV into card data
    // -----------------------------------------------------------------------------------------

    private fun extract(
        candidate: CardCandidate,
        aip: ByteArray,
        afl: List<AflEntry>,
        db: TlvDatabase,
    ): EmvReadResult {
        val track2 = db[EmvTags.TRACK_2_EQUIVALENT_DATA]?.let { bytes ->
            Track2Data.parse(bytes).also {
                if (it == null) warnings += "Track 2 Equivalent Data present but could not be parsed"
            }
        }

        // Tag 5A is the authoritative PAN; Track 2 is the fallback when the card omits it.
        val pan = db[EmvTags.PAN]?.let { Pan.fromCompressedNumeric(it) }
            ?: track2?.pan

        if (pan == null) {
            track2?.close()
            return EmvReadResult.Failure(
                stage = ReadStage.DATA_EXTRACTION,
                code = ReadErrorCode.MISSING_MANDATORY_DATA,
                message = "Neither tag 5A nor tag 57 yielded a usable PAN",
                trace = trace,
                recoverable = true,
            )
        }

        if (!pan.isLuhnValid()) {
            warnings += "PAN failed the Luhn check; the decode is probably wrong"
        }

        val expiry = db[EmvTags.EXPIRATION_DATE]?.let { ExpiryDate.fromTag5F24(it) }
            ?: track2?.expiry
        if (expiry == null) warnings += "No application expiration date found"

        val scheme = candidate.scheme.takeIf { it != CardScheme.UNKNOWN }
            ?: pan.reveal { CardScheme.fromPanPrefix(it) }

        val card = RawCardData(
            aid = candidate.aid,
            applicationLabel = db.text(EmvTags.APPLICATION_LABEL) ?: candidate.label,
            preferredName = db.text(EmvTags.APPLICATION_PREFERRED_NAME) ?: candidate.preferredName,
            scheme = scheme,
            kernel = scheme.kernel,
            pan = pan,
            panSequenceNumber = db.int(EmvTags.PAN_SEQUENCE_NUMBER),
            expiry = expiry,
            cardholderName = db.text(EmvTags.CARDHOLDER_NAME),
            track2 = track2,
            aip = aip,
            afl = afl,
            tlv = db,
        )
        return EmvReadResult.Success(card, warnings.toList(), trace)
    }

    // -----------------------------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------------------------

    /**
     * Sends one command and normalises the two ISO 7816-4 continuation cases:
     *  - `6Cxx`: the Le we guessed was wrong; xx is the right one, so resend.
     *  - `61xx`: more data is waiting; drain it with GET RESPONSE.
     *
     * IsoDep usually hides both, but a card that answers this way would otherwise look like a
     * hard failure.
     */
    private fun transmit(step: String, command: CommandApdu): ResponseApdu {
        var response = exchange(step, command)

        if (response.statusWord.wrongLength) {
            response = exchange("$step (retry Le=${response.sw2})", command.withLe(response.sw2))
        }

        var guard = 0
        while (response.statusWord.hasMoreData) {
            if (++guard > MAX_GET_RESPONSE_CHAIN) {
                warnings += "Card kept requesting GET RESPONSE; stopped after $MAX_GET_RESPONSE_CHAIN"
                break
            }
            val pending = response.data
            val more = exchange("$step (GET RESPONSE)", EmvCommands.getResponse(response.sw2))
            response = ResponseApdu(pending + more.bytes)
        }
        return response
    }

    private fun exchange(step: String, command: CommandApdu): ResponseApdu {
        val startedAt = System.nanoTime()
        val raw = try {
            transceiver.transceive(command.toBytes())
        } catch (e: ApduTransportException) {
            trace.recordFailure(step, command, e.message ?: e.javaClass.simpleName)
            throw e
        }
        if (raw.size < 2) {
            trace.recordFailure(step, command, "response shorter than SW1 SW2")
            throw ApduTransportException("Card returned ${raw.size} bytes; expected at least SW1 SW2")
        }
        val response = ResponseApdu(raw)
        trace.record(step, command, response, System.nanoTime() - startedAt)
        return response
    }

    private fun transportFailure(stage: ReadStage, e: ApduTransportException) = EmvReadResult.Failure(
        stage = stage,
        code = ReadErrorCode.TRANSPORT_ERROR,
        message = e.message ?: "Card connection lost",
        trace = trace,
    )

    private companion object {
        const val MAX_GET_RESPONSE_CHAIN = 16
    }
}
