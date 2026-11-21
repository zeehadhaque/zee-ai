package com.zeeai.companion.studios.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeai.companion.ai.GeminiNativeClient
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.offline.LocalLlmInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BgNavy = Color(0xFF060913)
private val CardNavy = Color(0xFF0B1120)
private val Silver = Color(0xFFC8D1E0)
private val ElectricBlue = Color(0xFF2F80FF)

@Composable
fun UniversalReaderScreen(
    activeCharId: String,
    keyVault: SecureKeyVault,
    geminiClient: GeminiNativeClient,
    localLlm: LocalLlmInferenceEngine,
    isOnline: () -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var primaryFile by remember { mutableStateOf<ParsedAndroidFile?>(null) }
    var compareFile by remember { mutableStateOf<ParsedAndroidFile?>(null) }
    var targetLanguage by remember { mutableStateOf("Bangla") }
    var customQuestion by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var outputText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val pickPrimaryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            statusText = "Reading selected file..."
            val parsed = AndroidFileParser.parseUri(context, uri)
            primaryFile = parsed
            statusText = parsed.notice
            if (parsed.extractedText != null && !isOnline()) {
                outputText = parsed.extractedText.take(3000)
            }
        }
    }

    val pickCompareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = AndroidFileParser.parseUri(context, uri)
            compareFile = parsed
            statusText = "Loaded comparison file: ${parsed.displayName}"
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(outputText.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    statusText = "Exported cleanly to phone storage."
                    Toast.makeText(context, "Saved to device storage!", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    statusText = "Export failed: ${err.message}"
                }
            }
        }
    }

    fun runReaderAction(actionId: String) {
        val file = primaryFile ?: return
        if (busy) return

        val taskDirective = when (actionId) {
            "summary" -> "Provide a clear, structured executive summary of this file with key takeaways."
            "extract" -> "Extract all important facts, entities, dates, numbers, and action items."
            "translate" -> "Translate the content faithfully into natural $targetLanguage while preserving meaning and structure."
            "notes" -> "Create structured study notes followed by 5 practice Q&A revision questions."
            "transcribe" -> "Provide a clean verbatim transcript with approximate timestamps where audible, followed by a short summary."
            "subtitles" -> "Analyze subtitle pacing, dialogue clarity, timing consistency, and phrasing quality."
            "compare" -> {
                val second = compareFile
                if (second == null) {
                    statusText = "Pick a second file first to compare."
                    return
                }
                "Compare File A (${file.displayName}) and File B (${second.displayName}): highlight concrete differences, additions, removals, and overlaps."
            }
            else -> customQuestion.ifBlank { "Explain the key points of this file." }
        }

        busy = true
        outputText = ""
        val charName = if (activeCharId == "zhee") "ZHEE" else "ZEE"
        val sysInstruction =
            "You are $charName inside ZEE Universal Reader. Base every statement strictly on the provided file. Never fabricate unseen content."

        scope.launch {
            val apiKey = keyVault.getKey("gemini_api_key")
            val online = isOnline()

            if (online && apiKey.isNotBlank()) {
                val promptBuilder = StringBuilder(taskDirective)
                if (!file.extractedText.isNullOrBlank()) {
                    promptBuilder.append("\n\n--- FILE A (${file.displayName}) ---\n")
                    promptBuilder.append(file.extractedText.take(60_000))
                }
                if (actionId == "compare" && !compareFile?.extractedText.isNullOrBlank()) {
                    promptBuilder.append("\n\n--- FILE B (${compareFile!!.displayName}) ---\n")
                    promptBuilder.append(compareFile!!.extractedText!!.take(40_000))
                }

                val flashModel = keyVault.getKey("gemini_flash_model").ifBlank { "gemini-2.5-flash" }
                val isMultimodalBinary = file.kind == NativeFileKind.MULTIMODAL
                val res = geminiClient.streamMultimodal(
                    apiKey = apiKey,
                    contentResolver = context.contentResolver,
                    fileUri = if (isMultimodalBinary) file.uri else null,
                    mimeType = if (isMultimodalBinary) file.mimeType else null,
                    fileSizeBytes = file.sizeBytes,
                    model = flashModel,
                    systemInstruction = sysInstruction,
                    prompt = promptBuilder.toString(),
                    onStatus = { s ->
                        scope.launch(Dispatchers.Main) { statusText = s }
                    },
                    onChunk = { partial ->
                        scope.launch(Dispatchers.Main) { outputText = partial }
                    }
                )
                res.onSuccess {
                    outputText = it
                    statusText = "Completed \"$actionId\" via Gemini Multimodal."
                }.onFailure { err ->
                    statusText = "Analysis error: ${err.message}"
                }
                busy = false
            } else {
                // Offline Execution Path
                if (file.kind == NativeFileKind.MULTIMODAL) {
                    statusText =
                        "PDF, Image, Audio, and Video AI analysis requires internet + Gemini API key."
                    busy = false
                    return@launch
                }

                val textContent = file.extractedText.orEmpty()
                val installedId = localLlm.findFirstInstalledMediaPipeModelId()
                if (installedId != null && textContent.isNotBlank()) {
                    val acc = StringBuilder()
                    statusText = "Analyzing offline using local LLM ($installedId)..."
                    localLlm.streamResponse(
                        modelId = installedId,
                        systemInstruction = sysInstruction,
                        userPrompt = "$taskDirective\n\nDocument (${file.displayName}):\n${textContent.take(3500)}",
                        onToken = { tok, done ->
                            acc.append(tok)
                            scope.launch(Dispatchers.Main) {
                                outputText = acc.toString()
                                if (done) {
                                    statusText = "Completed offline via Local LLM."
                                    busy = false
                                }
                            }
                        },
                        onError = { err ->
                            scope.launch(Dispatchers.Main) {
                                statusText = "Offline LLM error: $err"
                                busy = false
                            }
                        }
                    )
                } else {
                    outputText = buildString {
                        append("[Offline Local Reader • ${file.displayName}]\n")
                        append("Characters: ${textContent.length}\n")
                        if (file.subtitleCueCount != null) {
                            append("Subtitles: ${file.subtitleCueCount} cues (ends ${file.subtitleDurationEnd})\n")
                        }
                        append("\n--- Local Text Preview ---\n")
                        append(textContent.take(3500))
                    }
                    statusText = "Displayed local offline text (no internet or downloaded LLM)."
                    busy = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header & SAF File Pickers
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Universal Reader (Phone Storage SAF)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                "Reads PDF, DOCX, XLSX, CSV, PPTX, TXT, MD, JSON, SRT, VTT, Images, Audio & Video directly from your phone.",
                color = Silver.copy(alpha = 0.75f),
                fontSize = 12.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickPrimaryLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (primaryFile == null) "📁 Pick File" else "📁 Change File", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { pickCompareLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (compareFile == null) "+ 📑 Compare File" else "📑 Change File B", fontSize = 12.sp)
                }
            }

            primaryFile?.let { f ->
                val sizeKb = if (f.sizeBytes > 0) "${f.sizeBytes / 1024} KB" else "Unknown size"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgNavy, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text("File A: ${f.displayName} ($sizeKb)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${f.kind} • ${f.notice}", color = ElectricBlue, fontSize = 11.sp)
                }
            }

            compareFile?.let { f ->
                Text(
                    "File B: ${f.displayName} (${f.kind})",
                    color = Silver,
                    fontSize = 11.sp
                )
            }
        }

        // Action Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "summary" to "📝 Summarize",
                    "extract" to "🔍 Extract Info",
                    "notes" to "📚 Study Notes",
                    "transcribe" to "🎙️ Transcribe",
                    "subtitles" to "💬 Subtitles",
                    "compare" to "⚖️ Compare A vs B"
                ).forEach { (id, label) ->
                    Button(
                        enabled = primaryFile != null && !busy && (id != "compare" || compareFile != null),
                        onClick = { runReaderAction(id) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }

            // Translate Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Bangla", "English", "Hindi", "Urdu").forEach { lang ->
                        FilterChip(
                            selected = targetLanguage == lang,
                            onClick = { targetLanguage = lang },
                            label = { Text(lang, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedButton(
                    enabled = primaryFile != null && !busy,
                    onClick = { runReaderAction("translate") }
                ) {
                    Text("Translate", fontSize = 11.sp)
                }
            }

            // Custom Q&A
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customQuestion,
                    onValueChange = { customQuestion = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask anything about this file...", fontSize = 12.sp) },
                    singleLine = true
                )
                Button(
                    enabled = primaryFile != null && customQuestion.isNotBlank() && !busy,
                    onClick = { runReaderAction("custom") }
                ) {
                    Text("Ask", fontSize = 12.sp)
                }
            }

            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(statusText.ifBlank { "Processing..." }, color = ElectricBlue, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        geminiClient.cancelActive()
                        localLlm.cancelActiveGeneration()
                        busy = false
                        statusText = "Cancelled."
                    }) {
                        Text("Stop", color = Color(0xFFF87171), fontSize = 11.sp)
                    }
                }
            } else if (statusText.isNotBlank()) {
                Text(statusText, color = ElectricBlue, fontSize = 11.sp)
            }
        }

        // Output Box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Reader Output", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        enabled = outputText.isNotBlank(),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("ZEE Universal Reader Output", outputText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied analysis to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("📋 Copy", fontSize = 11.sp)
                    }

                    Button(
                        enabled = outputText.isNotBlank(),
                        onClick = {
                            val safeName = primaryFile?.displayName
                                ?.substringBeforeLast(".")
                                ?.lowercase()
                                ?.replace(Regex("[^a-z0-9]+"), "-")
                                ?: "zee-reader"
                            exportLauncher.launch("$safeName-analysis.md")
                        }
                    ) {
                        Text("📥 Export (.md)", fontSize = 11.sp)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgNavy, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = outputText.ifBlank {
                        "Tap Pick File to open any document, subtitle, photo, voice memo, or video from your Android phone."
                    },
                    color = if (outputText.isBlank()) Silver.copy(alpha = 0.55f) else Color.White,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}
