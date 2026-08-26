package com.softpos.emv.tlv

/**
 * Data element formats from EMV 4.4 Book 3, Annex A. The format drives DOL padding rules
 * (Book 3, section 5.4), so it is not merely documentation.
 */
enum class TagFormat {
    /** Numeric, BCD, right justified with leading zeroes. */
    NUMERIC,

    /** Compressed numeric, BCD, left justified with trailing 'F' nibbles. */
    COMPRESSED_NUMERIC,

    /** Alphanumeric / alphanumeric special, left justified with trailing spaces. */
    ALPHANUMERIC,

    /** Binary, left justified with trailing zeroes. */
    BINARY,

    /** Template or variable structure; no meaningful padding rule. */
    VARIABLE,
}

data class TagInfo(
    val tag: Tag,
    val name: String,
    val format: TagFormat,
    /**
     * True for elements that identify the cardholder or reproduce the magnetic stripe. These are
     * never written to disk in the clear and are masked out of APDU traces.
     */
    val sensitive: Boolean = false,
)

/**
 * Registry of the EMV data elements this prototype touches. References are to EMV 4.4 Book 3
 * Annex A unless noted otherwise.
 *
 * TODO: this covers the read path only. Elements needed for GENERATE AC, terminal risk management
 *  and offline data authentication are deliberately absent - see [com.softpos.emv.flow.EmvReadFlow]
 *  for the scope boundary.
 */
object EmvTags {

    // --- Templates and file control information -------------------------------------------------
    val FCI_TEMPLATE = Tag.of("6F")
    val DF_NAME = Tag.of("84")
    val FCI_PROPRIETARY_TEMPLATE = Tag.of("A5")
    val SFI_OF_DIRECTORY = Tag.of("88")
    val LANGUAGE_PREFERENCE = Tag.of("5F2D")
    val ISSUER_CODE_TABLE_INDEX = Tag.of("9F11")
    val APPLICATION_PREFERRED_NAME = Tag.of("9F12")
    val FCI_ISSUER_DISCRETIONARY_DATA = Tag.of("BF0C")
    val APPLICATION_TEMPLATE = Tag.of("61")
    val ADF_NAME = Tag.of("4F")
    val APPLICATION_LABEL = Tag.of("50")
    val APPLICATION_PRIORITY_INDICATOR = Tag.of("87")
    val PDOL = Tag.of("9F38")
    val RECORD_TEMPLATE = Tag.of("70")
    val RESPONSE_TEMPLATE_FORMAT_1 = Tag.of("80")
    val RESPONSE_TEMPLATE_FORMAT_2 = Tag.of("77")

    // --- Card data ------------------------------------------------------------------------------
    val PAN = Tag.of("5A")
    val PAN_SEQUENCE_NUMBER = Tag.of("5F34")
    val EXPIRATION_DATE = Tag.of("5F24")
    val EFFECTIVE_DATE = Tag.of("5F25")
    val TRACK_1_DISCRETIONARY = Tag.of("9F1F")
    val TRACK_2_DISCRETIONARY = Tag.of("9F20")
    val TRACK_2_EQUIVALENT_DATA = Tag.of("57")
    val TRACK_1_DATA = Tag.of("56")
    val CARDHOLDER_NAME = Tag.of("5F20")
    val CARDHOLDER_NAME_EXTENDED = Tag.of("9F0B")
    val ISSUER_COUNTRY_CODE = Tag.of("5F28")
    val APPLICATION_CURRENCY_CODE = Tag.of("9F42")
    val APPLICATION_USAGE_CONTROL = Tag.of("9F07")
    val APPLICATION_VERSION_NUMBER_CARD = Tag.of("9F08")
    val TRANSACTION_PIN_DATA = Tag.of("99")

    // --- Processing -----------------------------------------------------------------------------
    val AIP = Tag.of("82")
    val AFL = Tag.of("94")
    val CDOL_1 = Tag.of("8C")
    val CDOL_2 = Tag.of("8D")
    val CVM_LIST = Tag.of("8E")
    val ATC = Tag.of("9F36")
    val APPLICATION_CRYPTOGRAM = Tag.of("9F26")
    val CRYPTOGRAM_INFORMATION_DATA = Tag.of("9F27")
    val ISSUER_APPLICATION_DATA = Tag.of("9F10")
    val CARD_TRANSACTION_QUALIFIERS = Tag.of("9F6C")
    val ISSUER_ACTION_CODE_DEFAULT = Tag.of("9F0D")
    val ISSUER_ACTION_CODE_DENIAL = Tag.of("9F0E")
    val ISSUER_ACTION_CODE_ONLINE = Tag.of("9F0F")

    // --- Offline data authentication (parsed, never verified - see EmvReadFlow) ------------------
    val CA_PUBLIC_KEY_INDEX = Tag.of("8F")
    val ISSUER_PUBLIC_KEY_CERTIFICATE = Tag.of("90")
    val ISSUER_PUBLIC_KEY_REMAINDER = Tag.of("92")
    val ISSUER_PUBLIC_KEY_EXPONENT = Tag.of("9F32")
    val SIGNED_STATIC_APPLICATION_DATA = Tag.of("93")
    val ICC_PUBLIC_KEY_CERTIFICATE = Tag.of("9F46")
    val ICC_PUBLIC_KEY_EXPONENT = Tag.of("9F47")
    val ICC_PUBLIC_KEY_REMAINDER = Tag.of("9F48")
    val SDA_TAG_LIST = Tag.of("9F4A")
    val SIGNED_DYNAMIC_APPLICATION_DATA = Tag.of("9F4B")

    // --- Terminal supplied ----------------------------------------------------------------------
    val AMOUNT_AUTHORISED = Tag.of("9F02")
    val AMOUNT_OTHER = Tag.of("9F03")
    val TERMINAL_COUNTRY_CODE = Tag.of("9F1A")
    val TVR = Tag.of("95")
    val TRANSACTION_CURRENCY_CODE = Tag.of("5F2A")
    val TRANSACTION_DATE = Tag.of("9A")
    val TRANSACTION_TIME = Tag.of("9F21")
    val TRANSACTION_TYPE = Tag.of("9C")
    val UNPREDICTABLE_NUMBER = Tag.of("9F37")
    val TERMINAL_TYPE = Tag.of("9F35")
    val TERMINAL_CAPABILITIES = Tag.of("9F33")
    val ADDITIONAL_TERMINAL_CAPABILITIES = Tag.of("9F40")
    val TERMINAL_TRANSACTION_QUALIFIERS = Tag.of("9F66")
    val APPLICATION_VERSION_NUMBER_TERMINAL = Tag.of("9F09")
    val MERCHANT_NAME_AND_LOCATION = Tag.of("9F4E")
    val MERCHANT_CATEGORY_CODE = Tag.of("9F15")
    val MERCHANT_IDENTIFIER = Tag.of("9F16")
    val TERMINAL_IDENTIFICATION = Tag.of("9F1C")
    val ACQUIRER_IDENTIFIER = Tag.of("9F01")
    val POS_ENTRY_MODE = Tag.of("9F39")
    val TRANSACTION_SEQUENCE_COUNTER = Tag.of("9F41")
    val TERMINAL_RISK_MANAGEMENT_DATA = Tag.of("9F1D")

    private val INFO: Map<Tag, TagInfo> = listOf(
        TagInfo(FCI_TEMPLATE, "File Control Information Template", TagFormat.VARIABLE),
        TagInfo(DF_NAME, "Dedicated File Name", TagFormat.BINARY),
        TagInfo(FCI_PROPRIETARY_TEMPLATE, "FCI Proprietary Template", TagFormat.VARIABLE),
        TagInfo(SFI_OF_DIRECTORY, "SFI of the Directory Elementary File", TagFormat.BINARY),
        TagInfo(LANGUAGE_PREFERENCE, "Language Preference", TagFormat.ALPHANUMERIC),
        TagInfo(ISSUER_CODE_TABLE_INDEX, "Issuer Code Table Index", TagFormat.NUMERIC),
        TagInfo(APPLICATION_PREFERRED_NAME, "Application Preferred Name", TagFormat.ALPHANUMERIC),
        TagInfo(FCI_ISSUER_DISCRETIONARY_DATA, "FCI Issuer Discretionary Data", TagFormat.VARIABLE),
        TagInfo(APPLICATION_TEMPLATE, "Application Template", TagFormat.VARIABLE),
        TagInfo(ADF_NAME, "Application Identifier (ADF Name)", TagFormat.BINARY),
        TagInfo(APPLICATION_LABEL, "Application Label", TagFormat.ALPHANUMERIC),
        TagInfo(APPLICATION_PRIORITY_INDICATOR, "Application Priority Indicator", TagFormat.BINARY),
        TagInfo(PDOL, "Processing Options Data Object List", TagFormat.BINARY),
        TagInfo(RECORD_TEMPLATE, "Record Template", TagFormat.VARIABLE),
        TagInfo(RESPONSE_TEMPLATE_FORMAT_1, "Response Message Template Format 1", TagFormat.BINARY),
        TagInfo(RESPONSE_TEMPLATE_FORMAT_2, "Response Message Template Format 2", TagFormat.VARIABLE),

        TagInfo(PAN, "Application Primary Account Number", TagFormat.COMPRESSED_NUMERIC, sensitive = true),
        TagInfo(PAN_SEQUENCE_NUMBER, "PAN Sequence Number", TagFormat.NUMERIC),
        TagInfo(EXPIRATION_DATE, "Application Expiration Date", TagFormat.NUMERIC),
        TagInfo(EFFECTIVE_DATE, "Application Effective Date", TagFormat.NUMERIC),
        TagInfo(TRACK_1_DISCRETIONARY, "Track 1 Discretionary Data", TagFormat.ALPHANUMERIC, sensitive = true),
        TagInfo(TRACK_2_DISCRETIONARY, "Track 2 Discretionary Data", TagFormat.COMPRESSED_NUMERIC, sensitive = true),
        TagInfo(TRACK_2_EQUIVALENT_DATA, "Track 2 Equivalent Data", TagFormat.BINARY, sensitive = true),
        TagInfo(TRACK_1_DATA, "Track 1 Data", TagFormat.ALPHANUMERIC, sensitive = true),
        TagInfo(CARDHOLDER_NAME, "Cardholder Name", TagFormat.ALPHANUMERIC, sensitive = true),
        TagInfo(CARDHOLDER_NAME_EXTENDED, "Cardholder Name Extended", TagFormat.ALPHANUMERIC, sensitive = true),
        TagInfo(TRANSACTION_PIN_DATA, "Transaction PIN Data", TagFormat.BINARY, sensitive = true),
        TagInfo(ISSUER_COUNTRY_CODE, "Issuer Country Code", TagFormat.NUMERIC),
        TagInfo(APPLICATION_CURRENCY_CODE, "Application Currency Code", TagFormat.NUMERIC),
        TagInfo(APPLICATION_USAGE_CONTROL, "Application Usage Control", TagFormat.BINARY),
        TagInfo(APPLICATION_VERSION_NUMBER_CARD, "Application Version Number (Card)", TagFormat.BINARY),

        TagInfo(AIP, "Application Interchange Profile", TagFormat.BINARY),
        TagInfo(AFL, "Application File Locator", TagFormat.BINARY),
        TagInfo(CDOL_1, "Card Risk Management Data Object List 1", TagFormat.BINARY),
        TagInfo(CDOL_2, "Card Risk Management Data Object List 2", TagFormat.BINARY),
        TagInfo(CVM_LIST, "Cardholder Verification Method List", TagFormat.BINARY),
        TagInfo(ATC, "Application Transaction Counter", TagFormat.BINARY),
        TagInfo(APPLICATION_CRYPTOGRAM, "Application Cryptogram", TagFormat.BINARY),
        TagInfo(CRYPTOGRAM_INFORMATION_DATA, "Cryptogram Information Data", TagFormat.BINARY),
        TagInfo(ISSUER_APPLICATION_DATA, "Issuer Application Data", TagFormat.BINARY),
        TagInfo(CARD_TRANSACTION_QUALIFIERS, "Card Transaction Qualifiers", TagFormat.BINARY),
        TagInfo(ISSUER_ACTION_CODE_DEFAULT, "Issuer Action Code - Default", TagFormat.BINARY),
        TagInfo(ISSUER_ACTION_CODE_DENIAL, "Issuer Action Code - Denial", TagFormat.BINARY),
        TagInfo(ISSUER_ACTION_CODE_ONLINE, "Issuer Action Code - Online", TagFormat.BINARY),

        TagInfo(CA_PUBLIC_KEY_INDEX, "Certification Authority Public Key Index", TagFormat.BINARY),
        TagInfo(ISSUER_PUBLIC_KEY_CERTIFICATE, "Issuer Public Key Certificate", TagFormat.BINARY),
        TagInfo(ISSUER_PUBLIC_KEY_REMAINDER, "Issuer Public Key Remainder", TagFormat.BINARY),
        TagInfo(ISSUER_PUBLIC_KEY_EXPONENT, "Issuer Public Key Exponent", TagFormat.BINARY),
        TagInfo(SIGNED_STATIC_APPLICATION_DATA, "Signed Static Application Data", TagFormat.BINARY),
        TagInfo(ICC_PUBLIC_KEY_CERTIFICATE, "ICC Public Key Certificate", TagFormat.BINARY),
        TagInfo(ICC_PUBLIC_KEY_EXPONENT, "ICC Public Key Exponent", TagFormat.BINARY),
        TagInfo(ICC_PUBLIC_KEY_REMAINDER, "ICC Public Key Remainder", TagFormat.BINARY),
        TagInfo(SDA_TAG_LIST, "Static Data Authentication Tag List", TagFormat.BINARY),
        TagInfo(SIGNED_DYNAMIC_APPLICATION_DATA, "Signed Dynamic Application Data", TagFormat.BINARY),

        TagInfo(AMOUNT_AUTHORISED, "Amount, Authorised (Numeric)", TagFormat.NUMERIC),
        TagInfo(AMOUNT_OTHER, "Amount, Other (Numeric)", TagFormat.NUMERIC),
        TagInfo(TERMINAL_COUNTRY_CODE, "Terminal Country Code", TagFormat.NUMERIC),
        TagInfo(TVR, "Terminal Verification Results", TagFormat.BINARY),
        TagInfo(TRANSACTION_CURRENCY_CODE, "Transaction Currency Code", TagFormat.NUMERIC),
        TagInfo(TRANSACTION_DATE, "Transaction Date", TagFormat.NUMERIC),
        TagInfo(TRANSACTION_TIME, "Transaction Time", TagFormat.NUMERIC),
        TagInfo(TRANSACTION_TYPE, "Transaction Type", TagFormat.NUMERIC),
        TagInfo(UNPREDICTABLE_NUMBER, "Unpredictable Number", TagFormat.BINARY),
        TagInfo(TERMINAL_TYPE, "Terminal Type", TagFormat.NUMERIC),
        TagInfo(TERMINAL_CAPABILITIES, "Terminal Capabilities", TagFormat.BINARY),
        TagInfo(ADDITIONAL_TERMINAL_CAPABILITIES, "Additional Terminal Capabilities", TagFormat.BINARY),
        TagInfo(TERMINAL_TRANSACTION_QUALIFIERS, "Terminal Transaction Qualifiers", TagFormat.BINARY),
        TagInfo(APPLICATION_VERSION_NUMBER_TERMINAL, "Application Version Number (Terminal)", TagFormat.BINARY),
        TagInfo(MERCHANT_NAME_AND_LOCATION, "Merchant Name and Location", TagFormat.ALPHANUMERIC),
        TagInfo(MERCHANT_CATEGORY_CODE, "Merchant Category Code", TagFormat.NUMERIC),
        TagInfo(MERCHANT_IDENTIFIER, "Merchant Identifier", TagFormat.ALPHANUMERIC),
        TagInfo(TERMINAL_IDENTIFICATION, "Terminal Identification", TagFormat.ALPHANUMERIC),
        TagInfo(ACQUIRER_IDENTIFIER, "Acquirer Identifier", TagFormat.NUMERIC),
        TagInfo(POS_ENTRY_MODE, "Point-of-Service Entry Mode", TagFormat.NUMERIC),
        TagInfo(TRANSACTION_SEQUENCE_COUNTER, "Transaction Sequence Counter", TagFormat.NUMERIC),
        TagInfo(TERMINAL_RISK_MANAGEMENT_DATA, "Terminal Risk Management Data", TagFormat.BINARY),
    ).associateBy { it.tag }

    fun info(tag: Tag): TagInfo? = INFO[tag]

    fun name(tag: Tag): String = INFO[tag]?.name ?: "Unknown"

    fun format(tag: Tag): TagFormat = INFO[tag]?.format ?: TagFormat.BINARY

    /**
     * Unknown tags default to sensitive. A proprietary element we have never seen could hold track
     * data, and over-redacting a debug log is cheaper than leaking a PAN into logcat.
     */
    fun isSensitive(tag: Tag): Boolean = INFO[tag]?.sensitive ?: !tag.isConstructed
}
