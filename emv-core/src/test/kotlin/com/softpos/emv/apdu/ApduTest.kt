package com.softpos.emv.apdu

import com.softpos.emv.tlv.Tag
import com.softpos.emv.util.hexToBytes
import com.softpos.emv.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandApduTest {

    @Test
    fun `case 1 has header only`() {
        assertEquals("00A40400", CommandApdu(0x00, 0xA4, 0x04, 0x00).toBytes().toHex())
    }

    @Test
    fun `case 2 appends le`() {
        assertEquals("00B2010C00", CommandApdu(0x00, 0xB2, 0x01, 0x0C, le = 0).toBytes().toHex())
    }

    @Test
    fun `case 4 carries lc data and le`() {
        val apdu = CommandApdu(0x00, 0xA4, 0x04, 0x00, "A0000000031010".hexToBytes(), le = 0)

        assertEquals("00A4040007A000000003101000", apdu.toBytes().toHex())
    }

    @Test
    fun `le of 256 encodes as zero`() {
        assertEquals("00C0000000", CommandApdu(0x00, 0xC0, 0x00, 0x00, le = 256).toBytes().toHex())
    }

    @Test
    fun `rejects data beyond the short form limit`() {
        assertFailsWith<IllegalArgumentException> {
            CommandApdu(0x00, 0xA4, 0x04, 0x00, ByteArray(256))
        }
    }
}

class ResponseApduTest {

    @Test
    fun `splits data from the status word`() {
        val response = ResponseApdu("6F0A84089000".hexToBytes())

        assertEquals("6F0A8408", response.data.toHex())
        assertEquals(0x90, response.sw1)
        assertEquals(0x00, response.sw2)
        assertTrue(response.isSuccess)
    }

    @Test
    fun `a status only response has empty data`() {
        val response = ResponseApdu("6A83".hexToBytes())

        assertEquals(0, response.data.size)
        assertFalse(response.isSuccess)
        assertEquals("Record not found", response.statusWord.describe())
    }

    @Test
    fun `rejects a response shorter than the status word`() {
        assertFailsWith<IllegalArgumentException> { ResponseApdu(byteArrayOf(0x90.toByte())) }
    }

    @Test
    fun `recognises the iso continuation status words`() {
        assertTrue(StatusWord(0x6108).hasMoreData)
        assertTrue(StatusWord(0x6C1A).wrongLength)
        assertFalse(StatusWord(0x9000).hasMoreData)
    }
}

class EmvCommandsTest {

    @Test
    fun `select ppse uses the contactless directory name`() {
        val apdu = EmvCommands.selectByName(EmvCommands.PPSE_NAME)

        assertEquals("00A404000E325041592E535953" + "2E4444463031" + "00", apdu.toBytes().toHex())
    }

    @Test
    fun `gpo wraps pdol data in an 83 template`() {
        val apdu = EmvCommands.getProcessingOptions("28000000000000001234".hexToBytes())

        assertEquals("80A800000C830A2800000000000000123400", apdu.toBytes().toHex())
    }

    @Test
    fun `gpo with no pdol sends an empty template`() {
        assertEquals("80A80000028300" + "00", EmvCommands.getProcessingOptions(ByteArray(0)).toBytes().toHex())
    }

    @Test
    fun `read record encodes the sfi in the top five bits of p2`() {
        // EMV 4.4 Book 3 section 6.5.11: P2 = SFI shifted left three, low bits 100b.
        assertEquals("00B2010C00", EmvCommands.readRecord(1, 1).toBytes().toHex())
        assertEquals("00B2021400", EmvCommands.readRecord(2, 2).toBytes().toHex())
        assertEquals("00B201F400", EmvCommands.readRecord(1, 30).toBytes().toHex())
    }

    @Test
    fun `read record rejects an out of range sfi`() {
        assertFailsWith<IllegalArgumentException> { EmvCommands.readRecord(1, 0) }
        assertFailsWith<IllegalArgumentException> { EmvCommands.readRecord(1, 31) }
    }

    @Test
    fun `get data addresses a two byte tag through p1 p2`() {
        assertEquals("80CA9F3600", EmvCommands.getData(Tag.of("9F36")).toBytes().toHex())
    }

    @Test
    fun `get data rejects a single byte tag`() {
        assertFailsWith<IllegalArgumentException> { EmvCommands.getData(Tag.of("5A")) }
    }
}
