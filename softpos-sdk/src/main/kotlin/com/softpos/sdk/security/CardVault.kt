package com.softpos.sdk.security

import com.softpos.emv.model.RawCardData
import com.softpos.emv.model.RedactedCard
import com.softpos.sdk.SoftPosConfig
import java.time.LocalDate

/**
 * What survives a card read.
 *
 * @param card the masked view - the only card data allowed to reach the UI or the database.
 * @param fingerprint keyed HMAC over the PAN, or null if the Keystore was unavailable. Groups
 *   repeat visits by the same card; cannot be reversed and cannot be recomputed off-device.
 * @param encryptedPan base64 AES-256-GCM ciphertext, present only when the caller explicitly
 *   opted in through [SoftPosConfig.persistEncryptedPan].
 */
data class CapturedCard(
    val card: RedactedCard,
    val fingerprint: String?,
    val encryptedPan: String?,
)

/**
 * The boundary between "raw card data in memory" and "reduced card data at rest".
 *
 * [ingest] is the only way to obtain a [CapturedCard], and it consumes the [RawCardData] it is
 * given: the PAN, the track data and every sensitive TLV value are wiped before it returns, whether
 * it returns normally or throws. Nothing downstream of this class can reach the full number, which
 * is the property the project brief asks for and the reason redaction is not left to each caller.
 */
class CardVault(
    private val crypto: KeystoreCryptoService,
    private val config: SoftPosConfig,
) {

    fun ingest(raw: RawCardData, today: LocalDate = LocalDate.now()): CapturedCard =
        raw.use { card ->
            val pan = card.pan

            val fingerprint = pan?.let {
                runCatching { it.reveal { digits -> crypto.fingerprint(digits.toString().toByteArray()) } }
                    .getOrNull()
            }

            val encryptedPan = if (config.persistEncryptedPan && pan != null) {
                runCatching { pan.reveal { digits -> crypto.encryptToBase64(digits.toString().toByteArray()) } }
                    .getOrNull()
            } else {
                null
            }

            CapturedCard(
                card = card.redact(policy = config.maskPolicy, today = today),
                fingerprint = fingerprint,
                encryptedPan = encryptedPan,
            )
        }

    /**
     * Recovers a PAN previously stored under [SoftPosConfig.persistEncryptedPan].
     *
     * Only meaningful for a reconciliation tool the developer runs against their own test data.
     * Nothing in the demo application calls this.
     */
    fun revealStoredPan(encryptedPan: String): String? =
        runCatching { crypto.decryptFromBase64(encryptedPan).toString(Charsets.US_ASCII) }.getOrNull()
}
