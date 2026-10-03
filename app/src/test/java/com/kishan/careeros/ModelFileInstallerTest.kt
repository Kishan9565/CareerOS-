package com.kishan.careeros

import com.kishan.careeros.data.intelligence.ModelFileInstaller
import com.kishan.careeros.domain.intelligence.ModelKind
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelFileInstallerTest {
    @get:Rule val folder = TemporaryFolder()
    private val payload = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun installer() = ModelFileInstaller(File(folder.root, "career_models"))
    private fun assertNoPartial() = assertTrue(folder.root.walkTopDown().none { it.name.endsWith(".partial") })

    @Test fun knownAbcVectorNormalizesExpectedAndReportsExactTemporaryBytesForBothKinds() {
        // Published SHA-256 test vector; literal avoids duplicating the implementation as the oracle.
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val installer = installer()
        ModelKind.entries.forEach { kind ->
            var reports = 0
            val actual = installer.install(kind, " \t${expected.uppercase(java.util.Locale.ROOT)}\r\n",
                checksumChecked = { normalized, calculated, matches, copied, size ->
                    reports++
                    assertEquals(expected, normalized)
                    assertEquals(expected, calculated)
                    assertTrue(matches)
                    assertEquals(3L, copied)
                    assertEquals(3L, size)
                    val temporary = File(installer.file(kind).parentFile, "${installer.file(kind).name}.partial")
                    assertEquals("abc", temporary.readText())
                    assertFalse(installer.hasFile(kind))
                }) { "abc".byteInputStream() }
            assertEquals(expected, actual)
            assertEquals(64, actual.length)
            assertEquals(1, reports)
        }
    }

    @Test fun multiChunkKnownVectorFinalizesAfterEofAndReportsMismatchBeforeCleanup() {
        // Standard million-'a' vector, deliberately provided as short reads.
        val expectedActual = "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
        val userExpected = "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6"
        val installer = installer()
        var remaining = 1_000_000
        var eof = false
        var closed = false
        var reported = false
        assertThrows(IllegalArgumentException::class.java) {
            installer.install(ModelKind.LLM, userExpected,
                checksumChecked = { normalized, actual, matches, copied, size ->
                    reported = true
                    assertTrue(eof)
                    assertTrue(closed)
                    assertEquals(userExpected, normalized)
                    assertEquals(expectedActual, actual)
                    assertFalse(matches)
                    assertEquals(1_000_000L, copied)
                    assertEquals(copied, size)
                    assertTrue(File(installer.file(ModelKind.LLM).parentFile, "gemma.litertlm.partial").isFile)
                }) {
                object : InputStream() {
                    override fun read(): Int = error("Buffered reads required")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining == 0) { eof = true; return -1 }
                        val count = minOf(remaining, len, 997)
                        java.util.Arrays.fill(b, off, off + count, 'a'.code.toByte())
                        remaining -= count
                        return count
                    }
                    override fun close() { closed = true }
                }
            }
        }
        assertTrue(reported)
        assertFalse(installer.hasFile(ModelKind.LLM))
        assertNoPartial()
    }

    @Test fun normalizationDoesNotRepairInvalidHashes() {
        listOf("a".repeat(63), "a".repeat(65), "g".repeat(64), "a".repeat(32) + " " + "a".repeat(32)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                installer().install(ModelKind.LLM, invalid) { fail("Invalid hash must not open source"); payload.inputStream() }
            }
        }
    }

    @Test fun bothKindsInstallToCanonicalNamesOnlyAfterMatchingTrustedChecksum() {
        val installer = installer()
        ModelKind.entries.forEach { kind ->
            assertFalse(installer.hasFile(kind))
            var copied = 0L
            val actual = installer.install(kind, hash(payload).uppercase(), copied = { copied = it }) { payload.inputStream() }
            assertEquals(hash(payload), actual)
            assertEquals(payload.size.toLong(), copied)
            assertTrue(installer.hasFile(kind))
            assertEquals(if (kind == ModelKind.EMBEDDING) "embedding.task" else "gemma.litertlm", installer.file(kind).name)
            assertEquals(payload.size.toLong(), installer.file(kind).length())
        }
        assertNoPartial()
    }

    @Test fun mismatchPreservesPreviousInstallationForBothKinds() {
        val installer = installer()
        ModelKind.entries.forEach { kind ->
            installer.install(kind, hash(payload)) { payload.inputStream() }
            assertThrows(IllegalArgumentException::class.java) {
                installer.install(kind, "0".repeat(64)) { byteArrayOf(1, 2, 3).inputStream() }
            }
            assertEquals(payload.size.toLong(), installer.file(kind).length())
            assertEquals(hash(payload), installer.file(kind).inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().joinToString("") { "%02x".format(it) }
            })
        }
        assertNoPartial()
    }

    @Test fun emptyFileRejectedEvenWithMatchingChecksum() {
        val installer = installer()
        assertThrows(IllegalStateException::class.java) {
            installer.install(ModelKind.EMBEDDING, hash(byteArrayOf())) { byteArrayOf().inputStream() }
        }
        assertFalse(installer.hasFile(ModelKind.EMBEDDING))
        assertNoPartial()
    }

    @Test fun sourceFailureClosesStreamAndCleansPartialFile() {
        val installer = installer()
        var closed = false
        var calls = 0
        assertThrows(IOException::class.java) {
            installer.install(ModelKind.LLM, hash(payload)) {
                object : InputStream() {
                    override fun read(): Int = error("Buffered reads required")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (calls++ > 0) throw IOException("source failed")
                        b[off] = 1
                        return 1
                    }
                    override fun close() { closed = true }
                }
            }
        }
        assertTrue(closed)
        assertFalse(installer.hasFile(ModelKind.LLM))
        assertNoPartial()
    }

    @Test fun invalidExpectedHashDoesNotOpenSource() {
        assertThrows(IllegalArgumentException::class.java) {
            installer().install(ModelKind.LLM, "invalid") { fail("Must not open source"); payload.inputStream() }
        }
        assertNoPartial()
    }

    @Test fun cancellationPreservesPreviousModelAndRemovesPartial() {
        val installer = installer()
        installer.install(ModelKind.LLM, hash(payload)) { payload.inputStream() }
        assertThrows(CancellationException::class.java) {
            installer.install(ModelKind.LLM, hash(payload), checkActive = { throw CancellationException() }) { payload.inputStream() }
        }
        assertTrue(installer.hasFile(ModelKind.LLM))
        assertEquals(payload.size.toLong(), installer.file(ModelKind.LLM).length())
        assertNoPartial()
    }

    @Test fun staleTemporaryFileIsReplacedAndAtomicReplacementSucceeds() {
        val installer = installer()
        installer.install(ModelKind.LLM, hash(payload)) { payload.inputStream() }
        File(installer.file(ModelKind.LLM).parentFile, "gemma.litertlm.partial").writeText("interrupted")
        val replacement = byteArrayOf(5, 6, 7)
        installer.install(ModelKind.LLM, hash(replacement)) { replacement.inputStream() }
        assertEquals(3L, installer.file(ModelKind.LLM).length())
        assertNoPartial()
    }

    @Test fun fileTruthRejectsMissingAndZeroByteCanonicalFiles() {
        val installer = installer()
        ModelKind.entries.forEach { kind ->
            assertFalse(installer.hasFile(kind))
            installer.file(kind).parentFile!!.mkdirs()
            installer.file(kind).createNewFile()
            assertFalse(installer.hasFile(kind))
        }
    }
}
