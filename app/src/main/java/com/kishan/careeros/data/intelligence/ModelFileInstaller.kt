package com.kishan.careeros.data.intelligence

import com.kishan.careeros.domain.intelligence.ModelCatalog
import com.kishan.careeros.domain.intelligence.ModelKind
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Locale

/** Caller serializes installs and runs on Dispatchers.IO. Never buffers model weights in memory. */
internal class ModelFileInstaller(private val directory: File) {
    fun file(kind: ModelKind) = File(directory, ModelCatalog.file(kind))
    fun hasFile(kind: ModelKind) = file(kind).isFile && file(kind).length() > 0L

    fun install(
        kind: ModelKind,
        expectedSha256: String,
        checkActive: () -> Unit = {},
        copied: (Long) -> Unit = {},
        checksumChecked: (String, String, Boolean, Long, Long) -> Unit = { _, _, _, _, _ -> },
        openSource: () -> InputStream,
    ): String {
        val expected = expectedSha256.trim().lowercase(Locale.ROOT)
        require(expected.matches(Regex("[0-9a-f]{64}"))) {
            "Enter the SHA-256 from your trusted model download."
        }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create model installation directory" }
        val temp = File(directory, "${ModelCatalog.file(kind)}.partial")
        check(!temp.exists() || temp.delete()) { "Cannot remove incomplete model import" }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            openSource().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        require(total <= if (kind == ModelKind.LLM) 5_000_000_000L else 1_500_000_000L) { "Model exceeds supported size" }
                        check(directory.usableSpace > count + 128_000_000L) { "Insufficient storage for model import" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied(total)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            check(temp.isFile && total > 0L && temp.length() == total) { "Selected model is empty or copy is incomplete" }
            val actual = digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
            val matches = actual == expected
            checksumChecked(expected, actual, matches, total, temp.length())
            require(matches) { "Model checksum mismatch; existing model preserved" }
            checkActive()
            // Same-directory atomic rename: never delete the previous installation before replacement.
            // Fail safely if the filesystem cannot provide an atomic move.
            Files.move(temp.toPath(), file(kind).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            check(hasFile(kind) && file(kind).length() == total) { "Installed model is missing or incomplete" }
            return actual
        } finally {
            check(!temp.exists() || temp.delete()) { "Cannot clean up incomplete model import" }
        }
    }
}

