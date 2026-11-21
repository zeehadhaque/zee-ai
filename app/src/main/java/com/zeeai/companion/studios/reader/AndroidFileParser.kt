package com.zeeai.companion.studios.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

enum class NativeFileKind {
    TEXT,
    SUBTITLE,
    OFFICE_XML,
    MULTIMODAL
}

data class ParsedAndroidFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val extension: String,
    val kind: NativeFileKind,
    val extractedText: String? = null,
    val subtitleCueCount: Int? = null,
    val subtitleDurationEnd: String? = null,
    val notice: String
)

object AndroidFileParser {

    suspend fun parseUri(context: Context, uri: Uri): ParsedAndroidFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = "selected-file"
        var sizeBytes = -1L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                    if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                }
            }

        val ext = displayName.substringAfterLast('.', "").lowercase()
        val mime = resolver.getType(uri) ?: inferMimeFromExtension(ext)

        // 1. Plain text & structured formats (100% offline)
        if (ext in setOf("txt", "md", "json", "csv")) {
            val text = readTextLimited(context, uri, 180_000)
            return@withContext ParsedAndroidFile(
                uri = uri,
                displayName = displayName,
                sizeBytes = sizeBytes,
                mimeType = mime,
                extension = ext,
                kind = NativeFileKind.TEXT,
                extractedText = text,
                notice = "Parsed natively offline from phone storage."
            )
        }

        // 2. Subtitle files SRT / VTT (100% offline)
        if (ext in setOf("srt", "vtt")) {
            val text = readTextLimited(context, uri, 180_000)
            val timeRegex = Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})")
            val matches = timeRegex.findAll(text).toList()
            val endTime = matches.lastOrNull()?.groupValues?.getOrNull(1) ?: "Unknown"
            return@withContext ParsedAndroidFile(
                uri = uri,
                displayName = displayName,
                sizeBytes = sizeBytes,
                mimeType = mime,
                extension = ext,
                kind = NativeFileKind.SUBTITLE,
                extractedText = text,
                subtitleCueCount = matches.size,
                subtitleDurationEnd = endTime,
                notice = "Subtitle parsed offline (${matches.size} cues, ends at $endTime)."
            )
        }

        // 3. Office OpenXML formats DOCX / XLSX / PPTX via ZipInputStream (100% offline)
        if (ext in setOf("docx", "xlsx", "pptx")) {
            val extracted = extractOfficeXml(context, uri, ext)
            return@withContext ParsedAndroidFile(
                uri = uri,
                displayName = displayName,
                sizeBytes = sizeBytes,
                mimeType = mime,
                extension = ext,
                kind = NativeFileKind.OFFICE_XML,
                extractedText = extracted,
                notice = if (extracted.isNotBlank()) {
                    "Extracted text from .${ext.uppercase()} locally offline."
                } else {
                    "Could not extract XML text (file may be encrypted or binary). Export as PDF or TXT."
                }
            )
        }

        // 4. Multimodal binary formats: PDF, Images, Audio, Video -> Gemini Native Client
        val isMultimodal = ext == "pdf" ||
            mime.startsWith("image/") ||
            mime.startsWith("audio/") ||
            mime.startsWith("video/")

        if (isMultimodal) {
            return@withContext ParsedAndroidFile(
                uri = uri,
                displayName = displayName,
                sizeBytes = sizeBytes,
                mimeType = mime,
                extension = ext,
                kind = NativeFileKind.MULTIMODAL,
                notice = "Ready for Gemini multimodal analysis (requires internet + API key)."
            )
        }

        // Fallback text read
        val fallback = readTextLimited(context, uri, 120_000)
        ParsedAndroidFile(
            uri = uri,
            displayName = displayName,
            sizeBytes = sizeBytes,
            mimeType = mime,
            extension = ext,
            kind = NativeFileKind.TEXT,
            extractedText = fallback,
            notice = "Loaded as plain text fallback."
        )
    }

    private fun readTextLimited(context: Context, uri: Uri, maxChars: Int): String {
        val sb = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val buf = CharArray(4096)
                while (sb.length < maxChars) {
                    val read = reader.read(buf)
                    if (read == -1) break
                    val remaining = maxChars - sb.length
                    sb.append(buf, 0, minOf(read, remaining))
                }
            }
        }
        return sb.toString()
    }

    private fun extractOfficeXml(context: Context, uri: Uri, ext: String): String {
        val output = StringBuilder()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null && output.length < 150_000) {
                        val name = entry.name
                        val isTarget = when (ext) {
                            "docx" -> name == "word/document.xml"
                            "pptx" -> Regex("^ppt/slides/slide\\d+\\.xml$").matches(name)
                            "xlsx" -> name == "xl/sharedStrings.xml" ||
                                Regex("^xl/worksheets/sheet\\d+\\.xml$").matches(name)
                            else -> false
                        }

                        if (isTarget) {
                            val xml = zis.readBytes().toString(Charsets.UTF_8)
                            val clean = xml
                                .replace(Regex("</w:p>|</a:p>|</row>"), "\n")
                                .replace(Regex("<[^>]+>"), " ")
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace(Regex("[ \\t]+"), " ")
                                .trim()
                            if (clean.isNotEmpty()) {
                                output.append("[$name]\n").append(clean).append("\n\n")
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
        return output.toString().trim()
    }

    private fun inferMimeFromExtension(ext: String): String = when (ext) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> "text/plain"
    }
}
