package com.zeeai.companion.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class OfflineVoicePackDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // ব্রেক ছাড়া বড় ডাউনলোডের জন্য
        .followRedirects(true)
        .build()

    suspend fun downloadAndInstallPack(
        pack: OfflineNeuralVoicePack,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<InstalledVoiceFiles> = withContext(Dispatchers.IO) {
        val targetDir = OfflineVoiceCatalog.getPackDir(context, pack.id)
        val tempArchive = File(context.cacheDir, "${pack.id}.tar.bz2.part")

        runCatching {
            onProgress(1, "Connecting to neural voice server...")
            val req = Request.Builder()
                .url(pack.downloadUrl)
                .header("User-Agent", "ZEE-AI-Android/1.0")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Voice pack download failed (HTTP ${resp.code})")
                }
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("text/html")) {
                    error("Server returned HTML instead of voice model archive.")
                }

                val body = resp.body ?: error("Empty download response")
                val totalBytes = body.contentLength().coerceAtLeast(1L)
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempArchive).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var read: Int
                        var lastPct = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            val pct = ((downloaded * 80L) / totalBytes).toInt().coerceIn(1, 80)
                            if (pct != lastPct) {
                                lastPct = pct
                                val mbDone = downloaded / (1024 * 1024)
                                val mbTot = (totalBytes / (1024 * 1024)).coerceAtLeast(1)
                                onProgress(pct, "Downloading ${mbDone}MB / ${mbTot}MB...")
                            }
                        }
                    }
                }
            }

            if (tempArchive.length() < 5L * 1024L * 1024L) {
                error("Downloaded archive is incomplete.")
            }

            onProgress(85, "Extracting neural voice model...")
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            extractTarBz2Safely(tempArchive, targetDir)
            tempArchive.delete()

            val installed = OfflineVoiceCatalog.resolveInstalledFiles(context, pack.id)
                ?: error("Extracted pack is missing .onnx or tokens.txt")

            onProgress(100, "Installed & Ready!")
            installed
        }.onFailure {
            runCatching { tempArchive.delete() }
            runCatching { targetDir.deleteRecursively() }
        }
    }

    fun deletePack(packId: String) {
        runCatching { OfflineVoiceCatalog.getPackDir(context, packId).deleteRecursively() }
    }

    private fun extractTarBz2Safely(archive: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath + File.separator
        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextEntry
                        val buffer = ByteArray(32 * 1024)
                        while (entry != null) {
                            val outFile = File(destDir, entry.name)
                            // Zip-slip path traversal protection
                            if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                                error("Unsafe archive path detected: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    var len: Int
                                    while (tarIn.read(buffer).also { len = it } != -1) {
                                        fos.write(buffer, 0, len)
                                    }
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }
    }
}
