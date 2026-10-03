package com.kishan.careeros.data.intelligence

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.kishan.careeros.domain.*
import com.kishan.careeros.domain.intelligence.*
import kotlinx.coroutines.*

class PdfResumeTextExtractor(private val context: Context): ResumeTextExtractor {
    override suspend fun extract(document: String): CapabilityResult<String> = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(document); require(uri.scheme == "content")
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(15 * 1024 * 1024) } ?: return@withContext CapabilityResult.Unavailable("Could not open this document.")
            if (!bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) return@withContext CapabilityResult.Unavailable("Select a PDF resume.")
            PDFBoxResourceLoader.init(context)
            PDDocument.load(bytes).use { pdf ->
                if (pdf.isEncrypted) return@withContext CapabilityResult.Unavailable("Encrypted PDFs are not supported. Import an unencrypted copy.")
                if (pdf.numberOfPages !in 1..30) return@withContext CapabilityResult.Unavailable("Import a resume containing 1-30 pages.")
                val output = StringBuilder(); val stripper = PDFTextStripper().apply { sortByPosition = true }
                for (page in 1..pdf.numberOfPages) {
                    ensureActive(); stripper.startPage = page; stripper.endPage = page; output.append(stripper.getText(pdf)).append('\n')
                    if (output.length > 100_000) return@withContext CapabilityResult.Unavailable("Resume text exceeds the 100,000-character limit.")
                }
                val text = output.toString().trim()
                if (text.isBlank()) CapabilityResult.Unavailable("This resume appears to contain no extractable text. Scanned-image OCR is not available.") else CapabilityResult.Available(text)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { CapabilityResult.Unavailable("Encrypted PDFs are not supported. Import an unencrypted copy.") }
        catch (_: Exception) { CapabilityResult.Unavailable("Could not extract this PDF. It may be damaged, unsupported or larger than 15 MB.") }
    }
}
private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= limit); out.write(buffer, 0, n) }; return out.toByteArray()
}
