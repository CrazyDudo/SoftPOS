package com.softpos.emv.oda

enum class OdaMethod(val label: String) {
    /** Static Data Authentication, EMV 4.4 Book 2 section 5. */
    SDA("SDA"),

    /**
     * Dynamic Data Authentication. In the contactless read this is the "fast DDA" variant of
     * EMV Contactless Book C-3, where the card signs inside the GET PROCESSING OPTIONS response
     * rather than answering a separate INTERNAL AUTHENTICATE.
     */
    DDA("DDA"),
}

/** Why authentication was never attempted. None of these means the card is bad. */
enum class OdaSkipReason {
    /** The read options switched offline data authentication off. */
    DISABLED,

    /** The terminal holds no CA public keys, so there is nothing to verify against. */
    NO_CAPK_TABLE,

    /** The Application Interchange Profile claims neither SDA nor DDA. */
    CARD_DOES_NOT_SUPPORT,

    /**
     * The AIP claims CDA only. CDA is verified over the GENERATE AC response, and this project
     * never issues GENERATE AC - see [com.softpos.emv.flow.EmvReadFlow].
     */
    CDA_ONLY,

    /** The card named a CA public key index (tag 8F) the terminal does not hold for its RID. */
    CAPK_NOT_FOUND,

    /** A mandatory element for the chosen method is absent; [OdaResult.NotPerformed.detail] names it. */
    MISSING_DATA,
}

/** Why authentication was attempted and did not succeed. These do mean something is wrong. */
enum class OdaFailureReason {
    /** The CA public key failed its own checksum or expiry check before it was used. */
    CAPK_INVALID,

    /** A certificate or signature is not the same length as the key that should recover it. */
    LENGTH_MISMATCH,

    /** Recovered data does not start with 6A. */
    BAD_HEADER,

    /** Recovered data does not end with BC. */
    BAD_TRAILER,

    /** The certificate or signature format byte is not the one this method expects. */
    BAD_FORMAT,

    /** A hash or public key algorithm indicator other than SHA-1 / RSA. */
    UNSUPPORTED_ALGORITHM,

    /** The recovered hash does not match the data it should cover. The core failure. */
    HASH_MISMATCH,

    /** The issuer identifier in the issuer certificate does not prefix the card's PAN. */
    ISSUER_IDENTIFIER_MISMATCH,

    /** The PAN in the ICC certificate is not the card's PAN. */
    PAN_MISMATCH,

    /** A certificate's expiry month has passed. */
    CERTIFICATE_EXPIRED,

    /** A recovered key length is inconsistent with the certificate that carries it. */
    KEY_LENGTH,

    /** The CTQ signed inside the ICC dynamic data differs from the CTQ the card returned in clear. */
    CTQ_MISMATCH,

    /** A record inside the AFL's ODA range in SFI 1-10 was not a 70 template (Book 3 section 10.3). */
    MALFORMED_RECORD,
}

/**
 * Outcome of offline data authentication for one read.
 *
 * [tvrByte1] is the contribution to Terminal Verification Results byte 1 (EMV 4.4 Book 3, Annex
 * C5) a real terminal would record, exposed so a caller can see the result the way a terminal
 * action analysis would.
 */
sealed interface OdaResult {

    val tvrByte1: Int

    data class NotPerformed(val reason: OdaSkipReason, val detail: String? = null) : OdaResult {
        override val tvrByte1: Int get() = TVR_ODA_NOT_PERFORMED
    }

    data class Authenticated(val method: OdaMethod, val detail: String) : OdaResult {
        override val tvrByte1: Int get() = 0
    }

    data class Failed(val method: OdaMethod, val reason: OdaFailureReason, val detail: String) : OdaResult {
        override val tvrByte1: Int
            get() = when (method) {
                OdaMethod.SDA -> TVR_SDA_FAILED
                OdaMethod.DDA -> TVR_DDA_FAILED
            }
    }

    fun summary(): CardAuthentication = when (this) {
        is NotPerformed -> CardAuthentication.NOT_PERFORMED
        is Authenticated -> if (method == OdaMethod.SDA) CardAuthentication.SDA_AUTHENTICATED else CardAuthentication.DDA_AUTHENTICATED
        is Failed -> CardAuthentication.FAILED
    }

    fun describe(): String = when (this) {
        is NotPerformed -> "not performed: ${reason.name.lowercase().replace('_', ' ')}" +
            (detail?.let { " ($it)" } ?: "")

        is Authenticated -> "${method.label} authenticated; $detail"
        is Failed -> "${method.label} failed: ${reason.name.lowercase().replace('_', ' ')} ($detail)"
    }

    companion object {
        /** TVR byte 1 b8: offline data authentication was not performed. */
        const val TVR_ODA_NOT_PERFORMED = 0x80

        /** TVR byte 1 b7: SDA failed. */
        const val TVR_SDA_FAILED = 0x40

        /** TVR byte 1 b6: ICC data missing. */
        const val TVR_ICC_DATA_MISSING = 0x20

        /** TVR byte 1 b4: DDA failed. */
        const val TVR_DDA_FAILED = 0x08
    }
}

/** The reduced, persistable view of an [OdaResult]. */
enum class CardAuthentication(val label: String) {
    NOT_PERFORMED("Not authenticated"),
    SDA_AUTHENTICATED("Authenticated (SDA)"),
    DDA_AUTHENTICATED("Authenticated (DDA)"),
    FAILED("Authentication FAILED"),
}
