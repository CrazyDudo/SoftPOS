package com.softpos.emv.capk

import com.softpos.emv.util.Hex
import com.softpos.emv.util.toHex
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keys below are synthetic - a modulus of repeating bytes with a checksum computed here rather
 * than copied from a bulletin. They exercise the lookup, checksum and validation logic. No real
 * CA public key is reproduced anywhere in this project; see [CapkRegistry] for why.
 */
private const val TEST_RID = "A000000003"

private fun syntheticModulus(bits: Int, fill: Byte = 0xAB.toByte()) = ByteArray(bits / 8) { fill }

private fun publishedChecksum(rid: String, index: Int, modulus: ByteArray, exponent: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-1").run {
        update(Hex.decode(rid))
        update(byteArrayOf(index.toByte()))
        update(modulus)
        update(exponent)
        digest()
    }

private fun testEntry(
    index: Int = 0x92,
    bits: Int = 1152,
    fill: Byte = 0xAB.toByte(),
    exponent: ByteArray = byteArrayOf(0x03),
    withChecksum: Boolean = true,
    expiry: LocalDate? = LocalDate.of(2030, 12, 31),
): CapkEntry {
    val modulus = syntheticModulus(bits, fill)
    return CapkEntry(
        rid = TEST_RID,
        index = index,
        modulus = modulus,
        exponent = exponent,
        checksum = if (withChecksum) publishedChecksum(TEST_RID, index, modulus, exponent) else null,
        expiry = expiry,
    )
}

class CapkEntryTest {

    @Test
    fun `checksum follows the emv definition`() {
        // EMV 4.4 Book 2 section 11.2.2: SHA-1 over RID || index || modulus || exponent.
        val entry = testEntry()

        assertEquals(20, entry.computeChecksum().size)
        assertEquals(true, entry.checksumMatches())
    }

    @Test
    fun `a corrupted modulus fails the checksum`() {
        val good = testEntry()
        // Same published checksum, one byte of the modulus different.
        val tampered = good.copy(modulus = syntheticModulus(1152, fill = 0xAC.toByte()))

        assertEquals(false, tampered.checksumMatches())
    }

    @Test
    fun `a key with no published checksum cannot be checked`() {
        assertNull(testEntry(withChecksum = false).checksumMatches())
    }

    @Test
    fun `rejects a malformed rid or checksum length`() {
        assertFailsWith<IllegalArgumentException> { testEntry().copy(rid = "A0000000") }
        assertFailsWith<IllegalArgumentException> { testEntry().copy(checksum = ByteArray(16)) }
    }

    @Test
    fun `toString never dumps the modulus`() {
        val entry = testEntry()

        assertFalse(entry.toString().contains(entry.modulus.toHex()))
        assertTrue(entry.toString().contains("1152bit"))
    }

    @Test
    fun `expiry is inclusive of the last day`() {
        val entry = testEntry(expiry = LocalDate.of(2030, 12, 31))

        assertFalse(entry.isExpiredOn(LocalDate.of(2030, 12, 31)))
        assertTrue(entry.isExpiredOn(LocalDate.of(2031, 1, 1)))
    }
}

class CapkRegistryTest {

    @Test
    fun `the shipped default table is empty`() {
        // Deliberate. No CA public key is hardcoded in this project.
        assertTrue(CapkRegistry.Empty.isEmpty())
    }

    @Test
    fun `finds a key by rid and index`() {
        val registry = CapkRegistry(listOf(testEntry(index = 0x92), testEntry(index = 0x95)))

        assertEquals(0x92, assertNotNull(registry.find(TEST_RID, 0x92)).index)
        assertEquals(0x95, assertNotNull(registry.find(TEST_RID, 0x95)).index)
        assertNull(registry.find(TEST_RID, 0x99))
        assertNull(registry.find("A000000004", 0x92))
    }

    @Test
    fun `lookup is case insensitive and accepts raw bytes`() {
        val registry = CapkRegistry(listOf(testEntry()))

        assertNotNull(registry.find("a000000003", 0x92))
        assertNotNull(registry.find(Hex.decode(TEST_RID), 0x92))
    }

    @Test
    fun `verify accepts a well formed key`() {
        val registry = CapkRegistry(listOf(testEntry()))

        assertIs<CapkValidation.Ok>(registry.verify(testEntry(), LocalDate.of(2026, 1, 1)))
        assertTrue(registry.validateAll(LocalDate.of(2026, 1, 1)).isEmpty())
    }

    @Test
    fun `verify reports a checksum mismatch`() {
        val tampered = testEntry().copy(modulus = syntheticModulus(1152, fill = 0x01))

        val result = assertIs<CapkValidation.ChecksumMismatch>(CapkRegistry().verify(tampered))
        assertFalse(result.expected == result.actual)
    }

    @Test
    fun `verify reports an expired key`() {
        val entry = testEntry(expiry = LocalDate.of(2020, 1, 31))

        val result = assertIs<CapkValidation.Expired>(CapkRegistry().verify(entry, LocalDate.of(2026, 1, 1)))
        assertEquals(LocalDate.of(2020, 1, 31), result.expiry)
    }

    @Test
    fun `verify reports a modulus outside the emv bounds`() {
        val short = testEntry(bits = 512)

        val result = assertIs<CapkValidation.UnsupportedModulusLength>(CapkRegistry().verify(short))
        assertEquals(512, result.bits)
    }

    @Test
    fun `verify flags a key that shipped without a checksum`() {
        assertIs<CapkValidation.NoChecksumPublished>(CapkRegistry().verify(testEntry(withChecksum = false)))
    }

    @Test
    fun `validateAll returns only the problem entries`() {
        val registry = CapkRegistry(
            listOf(
                testEntry(index = 0x01),
                testEntry(index = 0x02, expiry = LocalDate.of(2020, 1, 1)),
                testEntry(index = 0x03, bits = 512),
            ),
        )

        val problems = registry.validateAll(LocalDate.of(2026, 1, 1))

        assertEquals(2, problems.size)
        assertEquals(setOf(0x02, 0x03), problems.map { it.first.index }.toSet())
    }
}

class CapkTextParserTest {

    private fun line(entry: CapkEntry): String = buildString {
        append(entry.rid).append(' ')
        append("%02X".format(entry.index)).append(' ')
        append(Hex.encode(entry.exponent)).append(' ')
        append("20301231").append(' ')
        append("01 01 ")
        append(Hex.encode(entry.modulus)).append(' ')
        append(Hex.encode(entry.checksum!!))
    }

    @Test
    fun `parses a well formed table`() {
        val entry = testEntry()
        val result = CapkTextParser.parse(
            """
            # a comment

            ${line(entry)}
            """.trimIndent(),
        )

        assertFalse(result.hasErrors, result.errors.toString())
        assertEquals(1, result.entries.size)
        val parsed = result.entries.single()
        assertEquals(TEST_RID, parsed.rid)
        assertEquals(0x92, parsed.index)
        assertEquals(LocalDate.of(2030, 12, 31), parsed.expiry)
        assertEquals(true, parsed.checksumMatches())
    }

    @Test
    fun `accepts a six digit expiry and a missing checksum`() {
        val entry = testEntry()
        val result = CapkTextParser.parse(
            "${entry.rid} 92 03 301231 01 01 ${Hex.encode(entry.modulus)} -",
        )

        assertFalse(result.hasErrors, result.errors.toString())
        assertEquals(LocalDate.of(2030, 12, 31), result.entries.single().expiry)
        assertNull(result.entries.single().checksum)
    }

    @Test
    fun `keeps good lines when one line is malformed`() {
        val entry = testEntry()
        val result = CapkTextParser.parse(
            """
            ${line(entry)}
            GARBAGE
            ZZZZZZZZZZ 92 03 301231 01 01 ${Hex.encode(entry.modulus)} -
            """.trimIndent(),
        )

        assertEquals(1, result.entries.size)
        assertEquals(2, result.errors.size)
        assertEquals(2, result.errors[0].lineNumber)
        assertTrue(result.errors[1].reason.contains("RID"))
    }

    @Test
    fun `rejects a checksum of the wrong length`() {
        val entry = testEntry()
        val result = CapkTextParser.parse(
            "${entry.rid} 92 03 301231 01 01 ${Hex.encode(entry.modulus)} AABBCC",
        )

        assertTrue(result.entries.isEmpty())
        assertTrue(result.errors.single().reason.contains("40 hex"))
    }

    @Test
    fun `rejects an odd length modulus`() {
        val result = CapkTextParser.parse("$TEST_RID 92 03 301231 01 01 ABC -")

        assertTrue(result.errors.single().reason.contains("even-length"))
    }

    @Test
    fun `round trips through format`() {
        val original = CapkRegistry(listOf(testEntry(index = 0x92), testEntry(index = 0x95)))

        val reparsed = CapkTextParser.parse(CapkTextParser.format(original))

        assertFalse(reparsed.hasErrors, reparsed.errors.toString())
        assertEquals(2, reparsed.entries.size)
        assertEquals(original.entries.toSet(), reparsed.entries.toSet())
    }

    @Test
    fun `an empty or comment only file yields an empty registry`() {
        val result = CapkTextParser.parse("# nothing here\n\n   \n")

        assertTrue(result.entries.isEmpty())
        assertFalse(result.hasErrors)
    }
}
