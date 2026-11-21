package com.zeeai.companion.studios.youtube

import android.app.Activity
import android.net.Uri
import android.provider.OpenableColumns
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
import com.zeeai.companion.data.local.OfflineDraftEntity
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.data.local.ZeeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

private val BgNavy = Color(0xFF060913)
private val CardNavy = Color(0xFF0B1120)
private val Silver = Color(0xFFC8D1E0)
private val ElectricBlue = Color(0xFF2F80FF)

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

    var activeSubTab by remember { mutableStateOf("overview") } // overview | upload | comments | analytics | queue
    var accessToken by remember { mutableStateOf(authManager.getSavedAccessToken()) }
    var hasMonetary by remember { mutableStateOf(authManager.hasSavedMonetaryScope()) }
    var statusText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var channels by remember { mutableStateOf<List<NativeYouTubeChannel>>(emptyList()) }
    var videos by remember { mutableStateOf<List<NativeYouTubeVideo>>(emptyList()) }
    var selectedVideoId by remember { mutableStateOf("") }
    var comments by remember { mutableStateOf<List<NativeYouTubeComment>>(emptyList()) }
    var replyDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var analytics by remember { mutableStateOf<NativeYouTubeAnalytics?>(null) }
    var strategyText by remember { mutableStateOf("") }

    // Upload & AI Packaging State
    var topicInput by remember { mutableStateOf("") }
    var titleInput by remember { mutableStateOf("") }
    var descInput by remember { mutableStateOf("") }
    var tagsInput by remember { mutableStateOf("") }
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
                statusText = err.message ?: "Failed to load channel."
            }
            busy = false
        }
    }

    LaunchedEffect(accessToken) {
        if (accessToken.isNotBlank() && isOnline()) {
            loadChannelAndVideos(accessToken)
        }
    }

    // Native Google OAuth Consent Launcher
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
            statusText = "YouTube OAuth consent cancelled."
        }
    }

    // SAF Pickers for Video & Thumbnail
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
            statusText = "Connect to the internet to authorize YouTube."
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
                is YouTubeAuthOutcome.Error -> statusText = outcome.message
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header & OAuth Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("YouTube Automation Studio (Native)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Official YouTube Data API v3 & Analytics API v2. Mandatory confirmation before publishing; Room offline draft queue supported.",
                color = Silver.copy(alpha = 0.75f),
                fontSize = 11.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (accessToken.isBlank()) {
                    Button(onClick = { startOAuth(false) }, enabled = isOnline()) {
                        Text("Connect YouTube", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { startOAuth(true) }, enabled = isOnline()) {
                        Text("+ Revenue Scope", fontSize = 12.sp)
                    }
                } else {
                    Text(
                        "Connected${channels.firstOrNull()?.let { ": ${it.title}" } ?: ""}",
                        color = Color(0xFF34D399),
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterVertically).weight(1f)
                    )
                    OutlinedButton(onClick = {
                        authManager.disconnect()
                        accessToken = ""
                        channels = emptyList()
                        videos = emptyList()
                        analytics = null
                        statusText = "Disconnected YouTube account."
                    }) {
                        Text("Disconnect", color = Color(0xFFF87171), fontSize = 11.sp)
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
                "overview" to "📊 Channel",
                "upload" to "🚀 SEO Package & Upload",
                "comments" to "💬 Comments",
                "analytics" to "📈 Analytics",
                "queue" to "⏳ Offline Queue (${ytDrafts.size})"
            ).forEach { (id, label) ->
                FilterChip(
                    selected = activeSubTab == id,
                    onClick = { activeSubTab = id },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        // TAB 1: OVERVIEW
        if (activeSubTab == "overview") {
            val ch = channels.firstOrNull()
            if (ch == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardNavy, RoundedCornerShape(14.dp))
                        .padding(18.dp)
                ) {
                    Text(
                        "Tap Connect YouTube above to load your real channel stats and uploads.",
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

        // TAB 2: AI PACKAGING & RESUMABLE UPLOAD
        if (activeSubTab == "upload") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardNavy, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = topicInput,
                    onValueChange = { topicInput = it },
                    label = { Text("Video Topic / Idea") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    enabled = topicInput.isNotBlank() && !busy,
                    onClick = {
                        val apiKey = keyVault.getKey("gemini_api_key")
                        if (!isOnline() || apiKey.isBlank()) {
                            titleInput = "${topicInput.take(70)} (Offline Draft)"
                            descInput = "Offline draft prepared locally for: $topicInput"
                            tagsInput = topicInput.lowercase().split(" ").filter { it.length > 2 }.joinToString(", ")
                            statusText = "Prepared local offline metadata. Save to Queue until online."
                            return@Button
                        }
                        scope.launch {
                            busy = true
                            statusText = "Generating SEO title, description & tags with Gemini..."
                            val res = geminiClient.streamChat(
                                apiKey = apiKey,
                                systemInstruction = "Return strict JSON only: {\"title\":\"...\",\"description\":\"...\",\"tags\":[\"a\",\"b\"]}",
                                history = emptyList(),
                                userPrompt = "Create a compelling YouTube title, full description with chapters & hashtags, and 8 SEO tags for: $topicInput",
                                onChunk = {}
                            )
                            res.onSuccess { raw ->
                                runCatching {
                                    val clean = raw.replace("```json", "").replace("```", "").trim()
                                    val obj = JSONObject(clean)
                                    titleInput = obj.optString("title", topicInput)
                                    descInput = obj.optString("description", raw)
                                    val arr = obj.optJSONArray("tags")
                                    if (arr != null) {
                                        tagsInput = (0 until arr.length()).joinToString(", ") { arr.optString(it) }
                                    }
                                }.onFailure {
                                    titleInput = topicInput.take(80)
                                    descInput = raw
                                }
                                statusText = "AI SEO package ready."
                            }.onFailure { statusText = it.message ?: "AI generation failed." }
                            busy = false
                        }
                    }
                ) {
                    Text("AI Generate Title, Description & Tags", fontSize = 12.sp)
                }

                OutlinedTextField(value = titleInput, onValueChange = { titleInput = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = descInput, onValueChange = { descInput = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(value = tagsInput, onValueChange = { tagsInput = it }, label = { Text("Tags (comma separated)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = scheduleIso,
                    onValueChange = { scheduleIso = it },
                    label = { Text("Optional Schedule ISO (e.g. 2026-11-25T15:00:00Z)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("private", "unlisted", "public").forEach { vis ->
                        FilterChip(selected = privacy == vis, onClick = { privacy = vis }, label = { Text(vis.uppercase(), fontSize = 11.sp) })
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickVideoLauncher.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) {
                        Text(if (selectedVideoUri == null) "Pick Video" else selectedVideoName.take(16), fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { pickThumbLauncher.launch(arrayOf("image/jpeg", "image/png")) }, modifier = Modifier.weight(1f)) {
                        Text(if (selectedThumbUri == null) "Pick Thumbnail" else "Thumb Ready", fontSize = 11.sp)
                    }
                }

                uploadProgress?.let { pct ->
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth(), color = ElectricBlue)
                    Text("Uploading: $pct%", color = ElectricBlue, fontSize = 11.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = titleInput.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                val json = JSONObject()
                                    .put("title", titleInput)
                                    .put("description", descInput)
                                    .put("tags", tagsInput)
                                    .put("privacy", privacy)
                                    .put("scheduleIso", scheduleIso)
                                    .toString()
                                dao.upsertDraft(
                                    OfflineDraftEntity(
                                        id = UUID.randomUUID().toString(),
                                        platform = "youtube",
                                        title = titleInput,
                                        payloadJson = json
                                    )
                                )
                                statusText = "Saved to Room Offline YouTube Draft Queue."
                            }
                        }
                    ) { Text("Save Offline Queue", fontSize = 11.sp) }

                    Button(
                        enabled = accessToken.isNotBlank() && selectedVideoUri != null && titleInput.isNotBlank() && !busy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val uri = selectedVideoUri ?: return@Button
                            confirmTitle = if (scheduleIso.isNotBlank()) "Schedule YouTube Upload?" else "Upload Video to YouTube?"
                            confirmDetails = "Title: $titleInput\nFile: $selectedVideoName (${selectedVideoSize / (1024 * 1024)} MB)\nVisibility: ${if (scheduleIso.isNotBlank()) "Scheduled ($scheduleIso)" else privacy.uppercase()}"
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
                        }
                    ) { Text("Preview & Upload", fontSize = 11.sp) }
                }
            }
        }

        // TAB 3: COMMENTS AUTOMATION
        if (activeSubTab == "comments") {
            Button(
                enabled = accessToken.isNotBlank() && selectedVideoId.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        apiClient.fetchVideoComments(accessToken, selectedVideoId)
                            .onSuccess { comments = it; statusText = "Loaded ${it.size} comments." }
                            .onFailure { statusText = it.message ?: "Failed to load comments." }
                        busy = false
                    }
                }
            ) { Text("Load Latest Video Comments", fontSize = 12.sp) }

            comments.forEach { c ->
                val draft = replyDrafts[c.commentId].orEmpty()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardNavy, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("${c.authorName} • ${c.likeCount} likes", color = ElectricBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(c.text, color = Color.White, fontSize = 13.sp)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { replyDrafts = replyDrafts + (c.commentId to it) },
                        placeholder = { Text("Reply or tap AI Draft...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val apiKey = keyVault.getKey("gemini_api_key")
                            if (apiKey.isBlank()) return@OutlinedButton
                            scope.launch {
                                val res = geminiClient.streamChat(
                                    apiKey = apiKey,
                                    systemInstruction = "Draft a warm, authentic 1-2 sentence YouTube creator reply matching the commenter's language.",
                                    history = emptyList(),
                                    userPrompt = "Comment by ${c.authorName}: ${c.text}",
                                    onChunk = {}
                                )
                                res.onSuccess { replyDrafts = replyDrafts + (c.commentId to it) }
                            }
                        }) { Text("AI Draft", fontSize = 11.sp) }

                        Button(
                            enabled = draft.isNotBlank() && accessToken.isNotBlank(),
                            onClick = {
                                confirmTitle = "Publish Public Comment Reply?"
                                confirmDetails = "To: ${c.authorName}\nReply: $draft"
                                confirmAction = {
                                    scope.launch {
                                        apiClient.publishCommentReply(accessToken, c.commentId, draft)
                                            .onSuccess { statusText = "Reply published to ${c.authorName}." }
                                            .onFailure { statusText = it.message ?: "Reply failed." }
                                    }
                                }
                            }
                        ) { Text("Publish Reply", fontSize = 11.sp) }
                    }
                }
            }
        }

        // TAB 4: REAL ANALYTICS & AI STRATEGY
        if (activeSubTab == "analytics") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = accessToken.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            apiClient.fetchAnalytics28Days(accessToken, hasMonetary)
                                .onSuccess { analytics = it; statusText = "Loaded real 28-day YouTube Analytics." }
                                .onFailure { statusText = it.message ?: "Analytics failed." }
                            busy = false
                        }
                    }
                ) { Text("Fetch Real Analytics", fontSize = 12.sp) }

                OutlinedButton(
                    enabled = analytics != null && !busy,
                    onClick = {
                        val report = analytics ?: return@OutlinedButton
                        val apiKey = keyVault.getKey("gemini_api_key")
                        if (apiKey.isBlank()) return@OutlinedButton
                        scope.launch {
                            busy = true
                            geminiClient.streamChat(
                                apiKey = apiKey,
                                systemInstruction = "Give sharp, data-grounded YouTube growth, retention, title/thumbnail, and Shorts recommendations using only the provided real metrics.",
                                history = emptyList(),
                                userPrompt = "28d Views=${report.totalViews}, WatchMin=${report.minutesWatched}, AvgRetention=${report.avgViewPercentage}%, SubsNet=${report.subsGained - report.subsLost}, TopSources=${report.trafficSources.take(4)}",
                                onChunk = { strategyText = it }
                            )
                            busy = false
                        }
                    }
                ) { Text("AI Strategy", fontSize = 12.sp) }
            }

            analytics?.let { a ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard("28d Views", a.totalViews.toString(), Modifier.weight(1f))
                    StatCard("Watch Min", a.minutesWatched.toString(), Modifier.weight(1f))
                    StatCard("Retention", "${a.avgViewPercentage}%", Modifier.weight(1f))
                }
                Text(
                    "Revenue: ${a.estimatedRevenue?.let { "$${String.format("%.2f", it)}" } ?: "Unavailable"} (${a.revenueNote})",
                    color = Silver,
                    fontSize = 11.sp
                )
                if (strategyText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardNavy, RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Text(strategyText, color = Color.White, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }
        }

        // TAB 5: ROOM OFFLINE QUEUE
        if (activeSubTab == "queue") {
            if (ytDrafts.isEmpty()) {
                Text("No offline YouTube drafts saved yet.", color = Silver.copy(alpha = 0.6f), fontSize = 12.sp)
            } else {
                ytDrafts.forEach { d ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardNavy, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(d.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Status: ${d.status}", color = Silver, fontSize = 11.sp)
                        }
                        Button(onClick = {
                            runCatching {
                                val obj = JSONObject(d.payloadJson)
                                titleInput = obj.optString("title", d.title)
                                descInput = obj.optString("description", "")
                                tagsInput = obj.optString("tags", "")
                                privacy = obj.optString("privacy", "private")
                                scheduleIso = obj.optString("scheduleIso", "")
                                activeSubTab = "upload"
                            }
                        }) { Text("Load", fontSize = 11.sp) }
                    }
                }
            }
        }
    }

    // Mandatory Confirmation Gate
    confirmTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { confirmTitle = null },
            containerColor = CardNavy,
            title = { Text(title, color = Color.White) },
            text = { Text(confirmDetails, color = Silver, fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = {
                    val run = confirmAction
                    confirmTitle = null
                    run?.invoke()
                }) { Text("Approve") }
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
            .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(label, color = Silver.copy(alpha = 0.7f), fontSize = 11.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
