package com.softpos.emv.tlv

import com.softpos.emv.testing.TestCards
import com.softpos.emv.util.Hex
import com.softpos.emv.util.hexToBytes
import com.softpos.emv.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BerTlvParserTest {

    @Test
    fun `parses a single primitive object`() {
        val nodes = BerTlvParser.parse("5A084761739001010010".hexToBytes())

        assertEquals(1, nodes.size)
        val node = assertIs<PrimitiveTlv>(nodes[0])
        assertEquals(EmvTags.PAN, node.tag)
        assertEquals("4761739001010010", node.value.toHex())
    }

    @Test
    fun `parses a two byte tag`() {
        val nodes = BerTlvParser.parse("9F38049F660499".hexToBytes())

        assertEquals(1, nodes.size)
        assertEquals(Tag.of("9F38"), nodes[0].tag)
        assertEquals("9F660499", nodes[0].value.toHex())
    }

    @Test
    fun `parses a three byte tag`() {
        val nodes = BerTlvParser.parse("DF81170101".hexToBytes())

        assertEquals(1, nodes.size)
        assertEquals(Tag.of("DF8117"), nodes[0].tag)
        assertEquals("01", nodes[0].value.toHex())
    }

    @Test
    fun `parses nested constructed objects`() {
        val nodes = BerTlvParser.parse(TestCards.PPSE_FCI_VISA.hexToBytes())

        val fci = assertIs<ConstructedTlv>(nodes.single())
        assertEquals(EmvTags.FCI_TEMPLATE, fci.tag)

        val aids = fci.walk().filter { it.tag == EmvTags.ADF_NAME }.toList()
        assertEquals(1, aids.size)
        assertEquals(TestCards.VISA_AID, aids[0].value.toHex())

        val label = fci.walk().first { it.tag == EmvTags.APPLICATION_LABEL }
        assertEquals("VISA", label.value.toString(Charsets.US_ASCII))
    }

    @Test
    fun `reads a long form length of one subsequent byte`() {
        // Tag 93 (Signed Static Application Data) is primitive and genuinely long on real cards,
        // which is exactly where the long-form length shows up.
        val value = ByteArray(200) { 0x41 }
        val encoded = "93".hexToBytes() + byteArrayOf(0x81.toByte(), 200.toByte()) + value

        val nodes = BerTlvParser.parse(encoded)

        assertEquals(EmvTags.SIGNED_STATIC_APPLICATION_DATA, nodes.single().tag)
        assertEquals(200, nodes.single().value.size)
    }

    @Test
    fun `reads a long form length of two subsequent bytes`() {
        val value = ByteArray(300) { 0x42 }
        val encoded = "90".hexToBytes() + byteArrayOf(0x82.toByte(), 0x01, 0x2C) + value

        val nodes = BerTlvParser.parse(encoded)

        assertEquals(EmvTags.ISSUER_PUBLIC_KEY_CERTIFICATE, nodes.single().tag)
        assertEquals(300, nodes.single().value.size)
    }

    @Test
    fun `skips zero padding between objects`() {
        // EMV 4.4 Book 3 Annex B1 permits '00' bytes before, between and after data objects.
        val nodes = BerTlvParser.parse("00005A0812340000000000000000870101".hexToBytes())

        assertEquals(2, nodes.size)
        assertEquals(EmvTags.PAN, nodes[0].tag)
        assertEquals(EmvTags.APPLICATION_PRIORITY_INDICATOR, nodes[1].tag)
    }

    @Test
    fun `treats a trailing FF run as record padding`() {
        val nodes = BerTlvParser.parse("870101FFFFFFFFFF".hexToBytes())

        assertEquals(1, nodes.size)
        assertEquals(EmvTags.APPLICATION_PRIORITY_INDICATOR, nodes[0].tag)
    }

    @Test
    fun `an FF that is not trailing padding is still parsed as a tag`() {
        // Guards against blanket FF-skipping: FF8101 is a legal private-class constructed tag, and
        // only a run of FF reaching the end of the buffer counts as padding.
        val nodes = BerTlvParser.parse("FF810103870101".hexToBytes())

        val node = assertIs<ConstructedTlv>(nodes.single())
        assertEquals(Tag.of("FF8101"), node.tag)
        assertEquals(EmvTags.APPLICATION_PRIORITY_INDICATOR, node.children.single().tag)
    }

    @Test
    fun `rejects a value that overruns the buffer`() {
        val error = assertFailsWith<TlvParseException> {
            BerTlvParser.parse("5A0812345678".hexToBytes())
        }
        assertTrue(error.message!!.contains("overruns") || error.message!!.contains("remain"))
    }

    @Test
    fun `rejects indefinite length`() {
        val error = assertFailsWith<TlvParseException> {
            BerTlvParser.parse("7080".hexToBytes())
        }
        assertTrue(error.message!!.contains("indefinite"))
    }

    @Test
    fun `rejects a truncated multi byte tag`() {
        assertFailsWith<TlvParseException> {
            BerTlvParser.parse("9F".hexToBytes())
        }
    }

    @Test
    fun `parseOrEmpty swallows malformed input`() {
        assertEquals(emptyList(), BerTlvParser.parseOrEmpty("5A0812345678".hexToBytes()))
    }

    @Test
    fun `encode round trips`() {
        val original = TestCards.RECORD_SFI2_REC1.hexToBytes()
        val nodes = BerTlvParser.parse(original)

        assertEquals(Hex.encode(original), Hex.encode(BerTlvParser.encode(nodes)))
    }

    @Test
    fun `encodeLength picks the shortest form`() {
        assertEquals("7F", BerTlvParser.encodeLength(127).toHex())
        assertEquals("8180", BerTlvParser.encodeLength(128).toHex())
        assertEquals("82012C", BerTlvParser.encodeLength(300).toHex())
    }

    @Test
    fun `tag reports constructed and primitive correctly`() {
        assertTrue(Tag.of("6F").isConstructed)
        assertTrue(Tag.of("70").isConstructed)
        assertTrue(Tag.of("A5").isConstructed)
        assertTrue(Tag.of("BF0C").isConstructed)
        assertTrue(Tag.of("5A").isPrimitive)
        assertTrue(Tag.of("9F38").isPrimitive)
    }

    @Test
    fun `tag normalises case and whitespace`() {
        assertEquals(Tag.of("9F38"), Tag.of("9f 38"))
    }
}
