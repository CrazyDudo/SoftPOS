package com.softpos.sdk.nfc

import android.nfc.tech.IsoDep
import com.softpos.emv.apdu.ApduTransceiver
import com.softpos.emv.apdu.ApduTransportException
import java.io.IOException

/**
 * Carries APDUs over ISO/IEC 14443-4 using [IsoDep].
 *
 * The connection is opened by [open] and must be closed by the caller; [use] does both.
 * Every [IOException] is converted to [ApduTransportException] so the EMV flow, which knows nothing
 * about Android, can treat "the card moved out of the field" as an ordinary outcome.
 */
class IsoDepTransceiver private constructor(private val isoDep: IsoDep) : ApduTransceiver, AutoCloseable {

    /** Maximum APDU the tag will accept, useful when deciding whether chaining is needed. */
    val maxTransceiveLength: Int get() = isoDep.maxTransceiveLength

    /** ISO 14443-4 Type A historical bytes, or Type B higher-layer response. Diagnostics only. */
    val historicalBytes: ByteArray? get() = isoDep.historicalBytes ?: isoDep.hiLayerResponse

    override fun transceive(command: ByteArray): ByteArray = try {
        isoDep.transceive(command)
    } catch (e: IOException) {
        throw ApduTransportException("Card left the field during an exchange", e)
    } catch (e: SecurityException) {
        // Thrown when the tag has already been invalidated by a newer discovery.
        throw ApduTransportException("Tag handle is no longer valid", e)
    }

    override fun close() {
        runCatching { isoDep.close() }
    }

    companion object {
        /**
         * @param timeoutMillis applied to each exchange. The platform default is around 300 ms,
         *   which some cards exceed while computing a response.
         */
        @Throws(ApduTransportException::class)
        fun open(isoDep: IsoDep, timeoutMillis: Int): IsoDepTransceiver {
            try {
                isoDep.timeout = timeoutMillis
                if (!isoDep.isConnected) isoDep.connect()
            } catch (e: IOException) {
                throw ApduTransportException("Could not connect to the card", e)
            }
            return IsoDepTransceiver(isoDep)
        }
    }
}
