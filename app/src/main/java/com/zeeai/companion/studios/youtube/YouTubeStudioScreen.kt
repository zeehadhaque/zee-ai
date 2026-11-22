package com.zeeai.companion.studios.youtube

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeai.companion.ai.GeminiNativeClient
import com.zeeai.companion.data.local.OfflineDraftEntity
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.data.local.ZeeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private val BgNavy = Color(0xFF060913)
private val CardNavy = Color(0xFF0B1120)
private val Silver = Color(0xFFC8D1E0)
private val ElectricBlue = Color(0xFF2F80FF)
private val NeonEmerald = Color(0xFF10B981)
private val CrimsonRed = Color(0xFFF87171)

@Composable
fun YouTubeStudioScreen(
    activeCharId: String,
    database: ZeeDatabase,
    keyVault: SecureKeyVault,
    geminiClient: GeminiNativeClient,
    isOnline: () -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { database.dao() }
    val authManager = remember { YouTubeAuthManager(context, keyVault) }
    val apiClient = remember { YouTubeApiClient() }

    var activeSubTab by remember { mutableStateOf("seo") } // seo | script | upload | channels | comments | queue
    var accessToken by remember { mutableStateOf(authManager.getSavedAccessToken()) }
    var hasMonetary by remember { mutableStateOf(authManager.hasSavedMonetaryScope()) }
    var showManualTokenDialog by remember { mutableStateOf(false) }
    var manualTokenInput by remember { mutableStateOf(accessToken) }
    var statusText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Channel Stats & Videos
    var channels by remember { mutableStateOf<List<NativeYouTubeChannel>>(emptyList()) }
    var videos by remember { mutableStateOf<List<NativeYouTubeVideo>>(emptyList()) }
    var selectedVideoId by remember { mutableStateOf("") }

    // SEO Package State
    var topicInput by remember { mutableStateOf("") }
    var videoType by remember { mutableStateOf("Full Video (16:9)") }
    var videoTone by remember { mutableStateOf("High-Energy & Viral") }
    var titleInput by remember { mutableStateOf("") }
    var hookInput by remember { mutableStateOf("") }
    var descInput by remember { mutableStateOf("") }
    var tagsInput by remember { mutableStateOf("") }
    var hashtagsInput by remember { mutableStateOf("") }
    var thumbnailPrompt by remember { mutableStateOf("") }

    // Script Automation State
    var scriptOutput by remember { mutableStateOf("") }

    // Upload & SAF State
    var privacy by remember { mutableStateOf("private") }
    var scheduleIso by remember { mutableStateOf("") }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoName by remember { mutableStateOf("") }
    var selectedVideoSize by remember { mutableStateOf(0L) }
    var selectedThumbUri by remember { mutableStateOf<Uri?>(null) }
    var uploadProgress by remember { mutableStateOf<Int?>(null) }

    // Confirmation Dialog State
    var confirmTitle by remember { mutableStateOf<String?>(null) }
    var confirmDetails by remember { mutableStateOf("") }
    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val allDrafts by dao.observeDrafts().collectAsState(initial = emptyList())
    val ytDrafts = remember(allDrafts) { allDrafts.filter { it.platform == "youtube" } }

    fun loadChannelAndVideos(token: String) {
        if (token.isBlank() || !isOnline()) return
        scope.launch {
            busy = true
            apiClient.fetchMyChannels(token).onSuccess { chList ->
                channels = chList
                val first = chList.firstOrNull()
                if (first != null) {
                    statusText = "Connected: ${first.title}"
                    apiClient.fetchChannelVideos(token, first.uploadsPlaylistId).onSuccess { vids ->
                        videos = vids
                        if (selectedVideoId.isBlank() && vids.isNotEmpty()) {
                            selectedVideoId = vids.first().id
                        }
                    }
                }
            }.onFailure { err ->
                statusText = err.message ?: "Channel fetch failed."
            }
            busy = false
        }
    }

    LaunchedEffect(accessToken) {
        if (accessToken.isNotBlank() && isOnline()) {
            loadChannelAndVideos(accessToken)
        }
    }

    val oauthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            when (val outcome = authManager.handleConsentResult(result.data)) {
                is YouTubeAuthOutcome.Authorized -> {
                    accessToken = outcome.accessToken
                    hasMonetary = outcome.hasMonetaryScope
                    statusText = "YouTube OAuth authorized."
                    loadChannelAndVideos(outcome.accessToken)
                }
                is YouTubeAuthOutcome.Error -> statusText = outcome.message
                else -> Unit
            }
        } else {
            statusText = "YouTube OAuth consent cancelled. Creator Mode is active."
        }
    }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        selectedVideoUri = uri
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    selectedVideoName = c.getString(0) ?: "video.mp4"
                    selectedVideoSize = c.getLong(1)
                }
            }
    }

    val pickThumbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) selectedThumbUri = uri
    }

    fun startOAuth(includeMonetary: Boolean) {
        if (!isOnline()) {
            statusText = "Connect to internet to authorize YouTube."
            return
        }
        authManager.requestAuthorization(includeMonetary) { outcome ->
            when (outcome) {
                is YouTubeAuthOutcome.Authorized -> {
                    accessToken = outcome.accessToken
                    hasMonetary = outcome.hasMonetaryScope
                    loadChannelAndVideos(outcome.accessToken)
                }
                is YouTubeAuthOutcome.NeedsConsent -> oauthLauncher.launch(outcome.intentSenderRequest)
                is YouTubeAuthOutcome.Error -> {
                    statusText = "OAuth Notice: ${outcome.message} (Creator Mode active)"
                }
            }
        }
    }

    fun shareToYouTubeApp(videoUri: Uri, title: String, desc: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val fullText = "$title\n\n$desc\n\nTags: $tagsInput"
        clipboard.setPrimaryClip(ClipData.newPlainText("YouTube Metadata", fullText))
        Toast.makeText(context, "Title & Description copied to clipboard!", Toast.LENGTH_SHORT).show()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(videoUri) ?: "video/*"
            putExtra(Intent.EXTRA_STREAM, videoUri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, desc)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Suggest YouTube package if installed
            setPackage("com.google.android.youtube")
        }

        try {
            context.startActivity(shareIntent)
        } catch (_: Exception) {
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = context.contentResolver.getType(videoUri) ?: "video/*"
                    putExtra(Intent.EXTRA_STREAM, videoUri)
                    putExtra(Intent.EXTRA_TEXT, desc)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Open with YouTube or Video Editor"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header & Connection Card
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
                Column {
                    Text("YouTube Automation Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("AI SEO packaging, viral scripts, thumbnail ideas, and publishing.", color = Silver.copy(alpha = 0.75f), fontSize = 11.sp)
                }
                Box(
                    modifier = Modifier
                        .background(
                            if (accessToken.isNotBlank()) Color(0xFF064E3B) else Color(0xFF1E293B),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (accessToken.isNotBlank()) "● Connected" else "● Creator Mode",
                        color = if (accessToken.isNotBlank()) NeonEmerald else ElectricBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (accessToken.isBlank()) {
                    Button(onClick = { startOAuth(false) }, enabled = isOnline()) {
                        Text("Connect YouTube", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { showManualTokenDialog = !showManualTokenDialog }) {
                        Text("Paste Token", fontSize = 11.sp)
                    }
                } else {
                    Text(
                        "Connected${channels.firstOrNull()?.let { ": ${it.title}" } ?: ""}",
                        color = NeonEmerald,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        authManager.disconnect()
                        accessToken = ""
                        channels = emptyList()
                        videos = emptyList()
                        statusText = "Disconnected YouTube account."
                    }) {
                        Text("Disconnect", color = CrimsonRed, fontSize = 11.sp)
                    }
                }
            }

            if (showManualTokenDialog) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgNavy, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Direct Google OAuth Access Token / API Key:", color = Color.White, fontSize = 11.sp)
                    OutlinedTextField(
                        value = manualTokenInput,
                        onValueChange = { manualTokenInput = it },
                        placeholder = { Text("ya29... or AIzaSy...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val clean = manualTokenInput.trim()
                            if (clean.isNotBlank()) {
                                accessToken = clean
                                keyVault.saveKey("yt_native_access_token", clean)
                                showManualTokenDialog = false
                                loadChannelAndVideos(clean)
                            }
                        }) {
                            Text("Save & Connect", fontSize = 11.sp)
                        }
                        TextButton(onClick = { showManualTokenDialog = false }) {
                            Text("Cancel", fontSize = 11.sp)
                        }
                    }
                }
            }

            if (statusText.isNotBlank()) {
                Text(statusText, color = ElectricBlue, fontSize = 11.sp)
            }
        }

        // Sub-Tabs
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "seo" to "🚀 AI SEO & Metadata",
                "script" to "📝 Full Script & Shorts",
                "upload" to "📤 Upload & Publish",
                "channels" to "📊 Channel Stats",
                "queue" to "⏳ Offline Queue (${ytDrafts.size})"
            ).forEach { (id, label) ->
                FilterChip(
                    selected = activeSubTab == id,
                    onClick = { activeSubTab = id },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        // TAB 1: AI SEO & METADATA PACKAGER
        if (activeSubTab == "seo") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardNavy, RoundedCornerShape(16.dp))
                    .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("AI YouTube SEO & Packaging Engine", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                OutlinedTextField(
                    value = topicInput,
                    onValueChange = { topicInput = it },
                    label = { Text("Video Topic / Concept / Focus") },
                    placeholder = { Text("e.g. How to Build an AI Android App from Scratch") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("Format & Tone", color = Silver, fontSize = 11.sp)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Full Video (16:9)", "Shorts / Reel (9:16)", "Tutorial / Explainer", "Documentary").forEach { type ->
                        FilterChip(selected = videoType == type, onClick = { videoType = type }, label = { Text(type, fontSize = 11.sp) })
                    }
                }

                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("High-Energy & Viral", "Educational & In-Depth", "Storytelling / Cinematic", "Humorous & Fast").forEach { tone ->
                        FilterChip(selected = videoTone == tone, onClick = { videoTone = tone }, label = { Text(tone, fontSize = 11.sp) })
                    }
                }

                Button(
                    enabled = topicInput.isNotBlank() && !busy,
                    onClick = {
                        val apiKey = keyVault.getKey("gemini_api_key")
                        val activeFlash = keyVault.getKey("gemini_flash_model").ifBlank { "gemini-2.5-flash" }
                        val backupGroq = keyVault.getKey("groq_api_key")
                        val backupOpenRouter = keyVault.getKey("openrouter_api_key")

                        scope.launch {
                            busy = true
                            statusText = "Crafting high-CTR YouTube SEO package..."

                            val prompt = """
                                Topic: $topicInput
                                Format: $videoType
                                Tone: $videoTone
                                Generate a complete, viral YouTube package:
                                1. Title: The single best high-CTR title (under 70 chars).
                                2. Viral Hook: The first 5 seconds opening line.
                                3. Description: Engaging video description with timestamped chapters and links.
                                4. Tags: Exactly 12 comma-separated high-ranking SEO tags.
                                5. Hashtags: 5 viral hashtags.
                                6. Thumbnail Concept: A high-contrast visual thumbnail prompt.
                                Return strictly in JSON format:
                                {"title":"...","hook":"...","description":"...","tags":"...","hashtags":"...","thumbnail":"..."}
                            """.trimIndent()

                            val res = geminiClient.streamChat(
                                apiKey = apiKey,
                                model = activeFlash,
                                systemInstruction = "You are a top YouTube strategist and SEO expert. Return valid JSON only.",
                                history = emptyList(),
                                userPrompt = prompt,
                                backupGroqKey = backupGroq,
                                backupOpenRouterKey = backupOpenRouter,
                                onChunk = {}
                            )

                            res.onSuccess { raw ->
                                runCatching {
                                    val clean = raw.replace("```json", "").replace("```", "").trim()
                                    val obj = JSONObject(clean)
                                    titleInput = obj.optString("title", topicInput)
                                    hookInput = obj.optString("hook", "")
                                    descInput = obj.optString("description", "")
                                    tagsInput = obj.optString("tags", "")
                                    hashtagsInput = obj.optString("hashtags", "")
                                    thumbnailPrompt = obj.optString("thumbnail", "")
                                }.onFailure {
                                    titleInput = topicInput
                                    descInput = raw
                                }
                                statusText = "✓ AI SEO Package Ready!"
                            }.onFailure { err ->
                                titleInput = "$topicInput (Offline Draft)"
                                descInput = "Video on $topicInput\n\nTimestamps:\n0:00 Intro\n1:00 Core Concepts\n3:00 Demo\n5:00 Summary"
                                tagsInput = topicInput.lowercase().split(" ").filter { it.length > 2 }.joinToString(", ")
                                statusText = "Offline fallback package created (${err.message?.take(60)})."
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Generating SEO Package..." else "🚀 Generate AI Titles, Tags & Description", fontSize = 12.sp)
                }

                if (titleInput.isNotBlank()) {
                    OutlinedTextField(value = titleInput, onValueChange = { titleInput = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                    if (hookInput.isNotBlank()) {
                        OutlinedTextField(value = hookInput, onValueChange = { hookInput = it }, label = { Text("Viral Opening Hook (0-5s)") }, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(value = descInput, onValueChange = { descInput = it }, label = { Text("Description & Chapters") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
                    OutlinedTextField(value = tagsInput, onValueChange = { tagsInput = it }, label = { Text("SEO Tags") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (hashtagsInput.isNotBlank()) {
                        OutlinedTextField(value = hashtagsInput, onValueChange = { hashtagsInput = it }, label = { Text("Hashtags") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    if (thumbnailPrompt.isNotBlank()) {
                        OutlinedTextField(value = thumbnailPrompt, onValueChange = { thumbnailPrompt = it }, label = { Text("Thumbnail Concept & Prompt") }, modifier = Modifier.fillMaxWidth())
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val full = "$titleInput\n\n$descInput\n\nTags: $tagsInput\n$hashtagsInput"
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("YouTube SEO", full))
                                Toast.makeText(context, "Full SEO Package Copied!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📋 Copy All", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val json = JSONObject()
                                        .put("title", titleInput)
                                        .put("description", descInput)
                                        .put("tags", tagsInput)
                                        .put("hashtags", hashtagsInput)
                                        .put("thumbnail", thumbnailPrompt)
                                        .toString()
                                    dao.upsertDraft(
                                        OfflineDraftEntity(
                                            id = UUID.randomUUID().toString(),
                                            platform = "youtube",
                                            title = titleInput,
                                            payloadJson = json
                                        )
                                    )
                                    statusText = "Saved to Room Offline Draft Queue."
                                    Toast.makeText(context, "Saved to Offline Queue!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("💾 Save Queue", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // TAB 2: FULL SCRIPT & SHORTS AUTOMATION
        if (activeSubTab == "script") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardNavy, RoundedCornerShape(16.dp))
                    .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Full Video & Shorts Script Generator", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Generates complete, scene-by-scene voiceover scripts with on-screen visual directions, b-roll cues, and music markers.", color = Silver, fontSize = 11.sp)

                OutlinedTextField(
                    value = topicInput,
                    onValueChange = { topicInput = it },
                    label = { Text("Video Topic / Core Message") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Button(
                    enabled = topicInput.isNotBlank() && !busy,
                    onClick = {
                        val apiKey = keyVault.getKey("gemini_api_key")
                        val activeFlash = keyVault.getKey("gemini_flash_model").ifBlank { "gemini-2.5-flash" }
                        val backupGroq = keyVault.getKey("groq_api_key")
                        val backupOpenRouter = keyVault.getKey("openrouter_api_key")

                        scope.launch {
                            busy = true
                            statusText = "Generating full video script..."

                            val prompt = """
                                Write a full, highly engaging YouTube video script for: $topicInput
                                Format: $videoType
                                Tone: $videoTone
                                Include:
                                - [0:00-0:15] Viral Hook & Problem Statement
                                - [Visual Cues / B-Roll]: Camera shots, on-screen text, transitions
                                - [Voiceover Script]: Conversational, human, engaging narration
                                - [Call to Action & Outro]
                            """.trimIndent()

                            val res = geminiClient.streamChat(
                                apiKey = apiKey,
                                model = activeFlash,
                                systemInstruction = "You are a professional YouTube scriptwriter. Output clear scene-by-scene script.",
                                history = emptyList(),
                                userPrompt = prompt,
                                backupGroqKey = backupGroq,
                                backupOpenRouterKey = backupOpenRouter,
                                onChunk = { scriptOutput = it }
                            )

                            res.onSuccess { scriptOutput = it; statusText = "✓ Script Generated!" }
                                .onFailure { statusText = "Script error: ${it.message}" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Writing Script..." else "📝 Write Full Script", fontSize = 12.sp)
                }

                if (scriptOutput.isNotBlank()) {
                    OutlinedTextField(
                        value = scriptOutput,
                        onValueChange = { scriptOutput = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 10,
                        maxLines = 24,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
                    )

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("YouTube Script", scriptOutput))
                            Toast.makeText(context, "Full Script Copied!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📋 Copy Full Script", fontSize = 11.sp)
                    }
                }
            }
        }

        // TAB 3: UPLOAD & PUBLISH / SHARE TO YOUTUBE
        if (activeSubTab == "upload") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardNavy, RoundedCornerShape(16.dp))
                    .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Video Upload & Native Publishing", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                OutlinedTextField(value = titleInput, onValueChange = { titleInput = it }, label = { Text("Video Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = descInput, onValueChange = { descInput = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("private", "unlisted", "public").forEach { vis ->
                        FilterChip(selected = privacy == vis, onClick = { privacy = vis }, label = { Text(vis.uppercase(), fontSize = 11.sp) })
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickVideoLauncher.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) {
                        Text(if (selectedVideoUri == null) "📁 Pick Video File" else selectedVideoName.take(16), fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { pickThumbLauncher.launch(arrayOf("image/jpeg", "image/png")) }, modifier = Modifier.weight(1f)) {
                        Text(if (selectedThumbUri == null) "🖼️ Pick Thumbnail" else "Thumb Ready", fontSize = 11.sp)
                    }
                }

                uploadProgress?.let { pct ->
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth(), color = ElectricBlue)
                    Text("Uploading: $pct%", color = ElectricBlue, fontSize = 11.sp)
                }

                // 1-Tap Share to YouTube App (Works for 100% of Android Users)
                Button(
                    enabled = selectedVideoUri != null,
                    onClick = {
                        val uri = selectedVideoUri ?: return@Button
                        shareToYouTubeApp(uri, titleInput, descInput)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🚀 Publish via YouTube App / Studio", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Direct API Upload if OAuth is connected
                if (accessToken.isNotBlank()) {
                    OutlinedButton(
                        enabled = selectedVideoUri != null && titleInput.isNotBlank() && !busy,
                        onClick = {
                            val uri = selectedVideoUri ?: return@OutlinedButton
                            confirmTitle = "Upload Directly to YouTube API?"
                            confirmDetails = "Title: $titleInput\nFile: $selectedVideoName (${selectedVideoSize / (1024 * 1024)} MB)\nVisibility: $privacy"
                            confirmAction = {
                                scope.launch {
                                    busy = true
                                    uploadProgress = 0
                                    val mime = context.contentResolver.getType(uri) ?: "video/mp4"
                                    val tags = tagsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                    val uploadRes = apiClient.uploadVideoResumable(
                                        accessToken = accessToken,
                                        contentResolver = context.contentResolver,
                                        videoUri = uri,
                                        mimeType = mime,
                                        fileSizeBytes = selectedVideoSize,
                                        meta = NativeYouTubeUploadMeta(
                                            title = titleInput,
                                            description = descInput,
                                            tags = tags,
                                            privacyStatus = privacy,
                                            publishAtIso = scheduleIso.ifBlank { null }
                                        ),
                                        onProgress = { pct ->
                                            scope.launch(Dispatchers.Main) { uploadProgress = pct }
                                        }
                                    )
                                    uploadRes.onSuccess { videoId ->
                                        selectedThumbUri?.let { thumb ->
                                            val tMime = context.contentResolver.getType(thumb) ?: "image/jpeg"
                                            apiClient.uploadThumbnail(accessToken, context.contentResolver, videoId, thumb, tMime)
                                        }
                                        statusText = "Upload complete! Video ID: $videoId"
                                        loadChannelAndVideos(accessToken)
                                    }.onFailure { err ->
                                        statusText = err.message ?: "Upload failed."
                                    }
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Direct Cloud API Upload", fontSize = 11.sp)
                    }
                }
            }
        }

        // TAB 4: CHANNEL STATS
        if (activeSubTab == "channels") {
            val ch = channels.firstOrNull()
            if (ch == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardNavy, RoundedCornerShape(14.dp))
                        .padding(18.dp)
                ) {
                    Text(
                        if (accessToken.isBlank()) "Connect YouTube or paste your token above to display live channel stats."
                        else "No channel data returned for this account.",
                        color = Silver.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard("Subscribers", ch.subscriberCount, Modifier.weight(1f))
                    StatCard("Total Views", ch.viewCount, Modifier.weight(1f))
                    StatCard("Videos", ch.videoCount, Modifier.weight(1f))
                }
                videos.forEach { v ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardNavy, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(v.title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("${v.publishedAt.take(10)} • ${v.privacyStatus.uppercase()}", color = Silver, fontSize = 11.sp)
                    }
                }
            }
        }

        // TAB 5: OFFLINE DRAFT QUEUE
        if (activeSubTab == "queue") {
            if (ytDrafts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardNavy, RoundedCornerShape(14.dp))
                        .padding(18.dp)
                ) {
                    Text("No drafts in queue. Save generated SEO packages to queue to store offline.", color = Silver.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            } else {
                ytDrafts.forEach { draft ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardNavy, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(draft.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            TextButton(onClick = { scope.launch { dao.deleteDraft(draft.id) } }) {
                                Text("Delete", color = CrimsonRed, fontSize = 11.sp)
                            }
                        }
                        Text(draft.payloadJson.take(140) + "...", color = Silver, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    // Global Confirmation Dialog
    confirmTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { confirmTitle = null },
            containerColor = CardNavy,
            title = { Text(title, color = Color.White) },
            text = { Text(confirmDetails, color = Silver, fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = {
                    val action = confirmAction
                    confirmTitle = null
                    action?.invoke()
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTitle = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(CardNavy, RoundedCornerShape(12.dp))
            .border(1.dp, Silver.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(label, color = Silver, fontSize = 10.sp)
        Text(value, color = ElectricBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
