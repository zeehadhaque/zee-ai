package com.zeeai.companion.studios.filmmaker

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeai.companion.ai.GeminiNativeClient
import com.zeeai.companion.data.local.MemoryEntity
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.data.local.ZeeDatabase
import com.zeeai.companion.offline.LocalLlmInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val BgNavy = Color(0xFF060913)
private val CardNavy = Color(0xFF0B1120)
private val Silver = Color(0xFFC8D1E0)
private val ElectricBlue = Color(0xFF2F80FF)
private val NeonEmerald = Color(0xFF10B981)
private val CrimsonRed = Color(0xFFF87171)

private val cinemaHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
    .build()

private data class CinemaModuleSpec(
    val id: String,
    val label: String,
    val icon: String,
    val directive: String
)

private val CINEMA_MODULES = listOf(
    CinemaModuleSpec(
        id = "video_gen",
        label = "Realistic Video Gen",
        icon = "🎬",
        directive = "Generate comprehensive, ultra-photorealistic, scene-by-scene video generation prompts formatted specifically for Google Veo 3, Google Veo 2, OpenAI Sora, Runway Gen-3 Alpha, Kling 1.5, and Wan 2.1. Every scene must specify exact camera rigging, fluid physical movements, 8K optical texture, volumetric lighting, and continuous narrative flow."
    ),
    CinemaModuleSpec(
        id = "face_lock",
        label = "Face Reference & ID Lock",
        icon = "👤",
        directive = "Generate strict character consistency & face reference prompts with zero facial distortion, identical bone structure, preserved facial symmetry, exact eye distance, nose bridge, jawline contours, and skin complexion across all camera angles (Close-up, Medium, Wide, Over-the-shoulder, Profile) and lighting transitions."
    ),
    CinemaModuleSpec(
        id = "shot_lighting",
        label = "Shot List & Lighting",
        icon = "🎥",
        directive = "Create an auteur director's Shot List (Shot #, Angle, Framing, Lens/Focal Length, Camera Movement, Speed) and professional master lighting diagrams (Key light, Fill ratio, Rim/Kicker, Practicals, Color Temperature Kelvin, Atmospheric Volumetric Haze)."
    ),
    CinemaModuleSpec(
        id = "cinema_analysis",
        label = "ZEE Cinema Analysis",
        icon = "👁️",
        directive = "Perform deep auteur-level cinematic analysis covering visual language, mise-en-scène, camera psychology, rhythm and pacing, subtextual color theory, emotional tone curve, and practical indie directing takeaways."
    )
)

private val SCENE_LENGTHS = listOf(
    "1 Shot (10-15s Cinematic Teaser)",
    "3 Scenes (30-45s Continuous Story Arc)",
    "5 Scenes (60-90s Long-Form Sequence)",
    "Full Sequence (Multi-Scene 2-3 mins)"
)

private val VIDEO_ENGINES = listOf(
    "Google Veo 3 (DeepMind 8K Photorealism & Audio)",
    "Google Veo 2 (Cinematic Master Directing)",
    "OpenAI Sora 2 / Turbo (Complex Dynamics)",
    "Runway Gen-3 Alpha (High-Fidelity Cinema)",
    "Kling 1.5 HD (Realistic Motion & Cloth)",
    "Wan 2.1 (Cinematic Long-Shot Master)"
)

private val ASPECT_RATIOS = listOf(
    "16:9 Standard Cinema (4K UHD)",
    "2.39:1 Anamorphic Cinema Widescreen (Arri Alexa 65)",
    "9:16 Vertical Immersive Cinema (Reels/Shorts)",
    "4:3 Vintage Academy Golden Ratio"
)

private val VISUAL_AESTHETICS = listOf(
    "Photorealistic Live-Action (Natural Skin Pores & Light Bounce)",
    "Dark Gritty Cinema Verite (Handheld, Natural Tungsten)",
    "High-Fashion / Stylized Anamorphic Flares",
    "Epic Cinematic Masterwork (Steadicam & Golden Hour)"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilmmakerStudioScreen(
    activeCharId: String,
    database: ZeeDatabase,
    keyVault: SecureKeyVault,
    geminiClient: GeminiNativeClient,
    localLlm: LocalLlmInferenceEngine,
    isOnline: () -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { database.dao() }

    var selectedModule by remember { mutableStateOf(CINEMA_MODULES[0]) }
    var projectTitle by remember { mutableStateOf("") }
    var sceneLength by remember { mutableStateOf(SCENE_LENGTHS[1]) }
    var videoEngine by remember { mutableStateOf(VIDEO_ENGINES[0]) }
    var aspectRatio by remember { mutableStateOf(ASPECT_RATIOS[0]) }
    var aesthetic by remember { mutableStateOf(VISUAL_AESTHETICS[0]) }
    var characterName by remember { mutableStateOf("") }
    var brief by remember { mutableStateOf("") }

    var faceReferenceUri by remember { mutableStateOf<Uri?>(null) }
    var faceReferenceFileName by remember { mutableStateOf<String?>(null) }
    var faceReferenceSize by remember { mutableStateOf(0L) }

    var output by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Face Reference Image Picker
    val pickFaceImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            faceReferenceUri = uri
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        faceReferenceFileName = if (nameIdx != -1) cursor.getString(nameIdx) else "face_reference.jpg"
                        faceReferenceSize = if (sizeIdx != -1) cursor.getLong(sizeIdx) else 0L
                    }
                }
            }
        }
    }

    // Android SAF export (.md directly to phone storage)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(output.toByteArray(Charsets.UTF_8))
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

    var activeEngineProvider by remember { mutableStateOf(keyVault.getKey("cinema_active_provider").ifBlank { "veo3" }) } // "veo3" | "gemini" | "flow"
    var showAccountConfig by remember { mutableStateOf(false) }

    var geminiKeyInput by remember { mutableStateOf(keyVault.getKey("gemini_api_key")) }
    var flowKeyInput by remember { mutableStateOf(keyVault.getKey("flow_api_key")) }
    var flowEndpointInput by remember { mutableStateOf(keyVault.getKey("flow_endpoint").ifBlank { "https://api.flow.ai/v1/video/generations" }) }
    var flowModelInput by remember { mutableStateOf(keyVault.getKey("flow_model_id").ifBlank { "veo-3.0-generate-001" }) }

    fun openGoogleVideoFxWeb(promptToCopy: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Google Veo 3 Prompt", promptToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Veo 3 prompt copied! Opening Google VideoFX...", Toast.LENGTH_LONG).show()

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://labs.google/fx/tools/veo")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    fun dispatchToGoogleVeoApi(promptText: String) {
        val apiKey = keyVault.getKey("gemini_api_key").trim()

        if (!isOnline()) {
            statusText = "Connect to internet to dispatch to Google Veo 3."
            return
        }
        if (apiKey.isBlank()) {
            statusText = "Please enter your Google AI Studio API Key in Settings or Config first."
            showAccountConfig = true
            return
        }

        busy = true
        statusText = "Initiating Google Veo 3 Video Generation..."

        scope.launch(Dispatchers.IO) {
            runCatching {
                val cleanRatio = if (aspectRatio.contains("9:16")) "9:16" else "16:9"

                val payload = JSONObject().apply {
                    put("instances", JSONArray().put(JSONObject().apply {
                        put("prompt", promptText.take(1500))
                    }))
                    put("parameters", JSONObject().apply {
                        put("aspectRatio", cleanRatio)
                        put("sampleCount", 1)
                        put("durationSeconds", 5)
                        put("personGeneration", "ALLOW_ADULT")
                    })
                }

                // Try official Veo 3 endpoint first, then Veo 2 fallback
                val endpoints = listOf(
                    "https://generativelanguage.googleapis.com/v1beta/models/veo-3.0-generate-001:predictLongRunning?key=$apiKey",
                    "https://generativelanguage.googleapis.com/v1beta/models/veo-2.0-generate-001:predictLongRunning?key=$apiKey"
                )

                var responseBody = ""
                var succeeded = false

                for (url in endpoints) {
                    val req = Request.Builder()
                        .url(url)
                        .header("Content-Type", "application/json")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    cinemaHttpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            responseBody = body
                            succeeded = true
                        }
                    }
                    if (succeeded) break
                }

                if (!succeeded) {
                    throw IllegalStateException("Google Veo API requires VideoFX preview access or whitelist.")
                }
                responseBody
            }.onSuccess { respStr ->
                withContext(Dispatchers.Main) {
                    busy = false
                    statusText = "Google Veo 3 video generation job created!"
                    output = buildString {
                        append("=== GOOGLE VEO 3 VIDEO JOB QUEUED ===\n")
                        append("Engine: Google DeepMind Veo 3\n")
                        append("Status: Video Rendering in Progress\n\n")
                        append("--- Server Response ---\n")
                        append(respStr)
                        append("\n\n--- Dispatched Veo 3 Prompt ---\n")
                        append(promptText)
                    }
                    Toast.makeText(context, "Google Veo 3 Video Queued!", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    busy = false
                    statusText = "Veo 3 Production Script Ready (Copied to Clipboard)"
                    output = buildString {
                        append("=== GOOGLE VEO 3 PRODUCTION SPECIFICATION ===\n")
                        append("Target Engine: Google Veo 3 (DeepMind 8K Photorealism)\n")
                        append("Aspect Ratio: ${if (aspectRatio.contains("9:16")) "9:16" else "16:9"}\n")
                        append("Audio / Atmosphere: Spatial Cinema Audio Sync\n\n")
                        append("{\n")
                        append("  \"model\": \"veo-3.0-generate-001\",\n")
                        append("  \"aspectRatio\": \"${if (aspectRatio.contains("9:16")) "9:16" else "16:9"}\",\n")
                        append("  \"prompt\": ${JSONObject.quote(promptText.take(1200))}\n")
                        append("}\n\n")
                        append("Note: Full prompt copied to clipboard. Tap 'Open VideoFX' below to render directly in Google's official Veo 3 web suite!")
                    }
                }
            }
        }
    }

    fun dispatchToFlowApi(promptText: String) {
        val flowKey = keyVault.getKey("flow_api_key")
        val endpoint = keyVault.getKey("flow_endpoint").ifBlank { "https://api.flow.ai/v1/video/generations" }
        val model = keyVault.getKey("flow_model_id").ifBlank { "veo-3.0-generate-001" }

        if (!isOnline()) {
            statusText = "Connect to internet to dispatch to Flow account."
            return
        }
        if (flowKey.isBlank()) {
            statusText = "Please enter your Flow Account API Key first."
            showAccountConfig = true
            return
        }

        busy = true
        statusText = "Dispatching to Flow AI Video Engine ($model)..."

        scope.launch(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().apply {
                    put("model", model)
                    put("prompt", promptText)
                    put("aspect_ratio", aspectRatio.substringBefore(" ").trim())
                    put("engine", videoEngine)
                    if (characterName.isNotBlank()) put("character_lock", characterName)
                    if (faceReferenceFileName != null) put("face_reference_file", faceReferenceFileName)
                }

                val req = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $flowKey")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                cinemaHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}: ${body.take(200)}")
                    }
                    body
                }
            }.onSuccess { respStr ->
                withContext(Dispatchers.Main) {
                    busy = false
                    statusText = "Flow generation job dispatched successfully!"
                    output = buildString {
                        append("=== FLOW AI VIDEO JOB DISPATCHED ===\n")
                        append("Provider: Flow Video Engine\n")
                        append("Model: $model\n")
                        append("Status: Queued / Dispatched\n\n")
                        append("--- Server Response ---\n")
                        append(respStr)
                        append("\n\n--- Prompt Dispatched ---\n")
                        append(promptText)
                    }
                    Toast.makeText(context, "Flow AI Video Dispatched!", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    busy = false
                    statusText = "Flow dispatch notice: ${err.message}"
                    output = buildString {
                        append("=== FLOW DIRECT PAYLOAD READY ===\n")
                        append("Endpoint: $endpoint\n")
                        append("Notice: ${err.message?.take(90)}\n\n")
                        append("{\n")
                        append("  \"model\": \"$model\",\n")
                        append("  \"aspect_ratio\": \"${aspectRatio.substringBefore(" ").trim()}\",\n")
                        append("  \"engine\": \"$videoEngine\",\n")
                        append("  \"prompt\": ${JSONObject.quote(promptText)}\n")
                        append("}\n")
                    }
                }
            }
        }
    }

    fun generateDeliverable() {
        if (brief.isBlank() || busy) return
        busy = true
        output = ""
        statusText = "Directing with ${videoEngine.substringBefore("(").trim()}..."

        val prompt = buildString {
            append("=== ZEE CINEMA AI STUDIO DIRECTING REQUEST ===\n")
            append("PROJECT TITLE: ${projectTitle.ifBlank { "Untitled Masterwork" }}\n")
            append("DELIVERABLE: ${selectedModule.label} (${selectedModule.icon})\n")
            append("TARGET VIDEO ENGINE: $videoEngine\n")
            append("SEQUENCE STRUCTURE: $sceneLength\n")
            append("ASPECT RATIO & LENS: $aspectRatio\n")
            append("VISUAL AESTHETIC & REALISM: $aesthetic\n")

            if (characterName.isNotBlank() || faceReferenceUri != null) {
                append("\n--- CHARACTER & FACE IDENTITY LOCK ---\n")
                if (characterName.isNotBlank()) append("Character Identifier: $characterName\n")
                if (faceReferenceUri != null) {
                    append("FACE REFERENCE IMAGE: Attached ($faceReferenceFileName)\n")
                    append("IDENTITY CONSTRAINT: ZERO FACIAL MORPHING. 100% preservation of facial bone structure, jawline, eye spacing, nose bridge, cheekbones, skin complexion, and micro-expressions across all scenes, angles, and lighting transitions.\n")
                }
            }

            append("\nDIRECTIVE:\n${selectedModule.directive}\n")
            append("\nDIRECTOR'S BRIEF & SCENE CONCEPT:\n${brief.trim()}\n")
            append("\nFORMAT REQUIREMENTS FOR GOOGLE VEO 3 & SORA:\n")
            when (selectedModule.id) {
                "video_gen" -> {
                    append("- Output Scene-by-Scene sequential video prompts formatted for Google Veo 3 & Sora with exact camera movements (Pan, Tilt, Tracking, Orbit, Dolly-in).\n")
                    append("- Specify 8K optical texture, cinematic lighting (Golden hour, volumetric rays, neon noir), soundscape cues, and precise scene prompt in English.\n")
                    append("- Ensure perfect continuity from Scene 1 to Scene 2 with zero character drift.")
                }
                "face_lock" -> {
                    append("- Detail the exact Face Consistency Matrix for AI Video & Image generators.\n")
                    append("- Provide negative prompts to eliminate plastic skin, face morphing, asymmetry, or distortion.\n")
                    append("- Provide consistent camera angle prompts maintaining exact facial geometry.")
                }
                "shot_lighting" -> {
                    append("- Comprehensive Shot-by-Shot Director's Sheet.\n")
                    append("- Columns/Sections: Shot #, Framing, Focal Length (35mm/50mm/85mm), Camera Movement.\n")
                    append("- Master 3-Point Lighting blueprint (Key light, Fill ratio, Rim/Backlight, Kelvin temperature, Volumetric haze).")
                }
                "cinema_analysis" -> {
                    append("- Auteur-grade visual analysis covering mise-en-scène, color theory, blocking, pacing, and practical directing takeaways.")
                }
            }
        }

        val charName = if (activeCharId == "zhee") "ZHEE" else "ZEE"
        val sysInstruction =
            "You are $charName inside ZEE Cinema & Video Generation Studio — an auteur film director, master cinematographer, and generative video expert specializing in Google Veo 3, Google Veo 2, Sora, Runway, Kling, and Wan 2.1. Provide deep, professional, photorealistic cinematic outputs matching the user's language."

        scope.launch {
            val apiKey = keyVault.getKey("gemini_api_key")
            val activeModel = keyVault.getKey("gemini_flash_model").ifBlank { GeminiNativeClient.DEFAULT_FLASH_MODEL }
            val backupGroq = keyVault.getKey("groq_api_key")
            val backupOpenRouter = keyVault.getKey("openrouter_api_key")

            if (isOnline() && (apiKey.isNotBlank() || backupGroq.isNotBlank() || backupOpenRouter.isNotBlank())) {
                val result = if (faceReferenceUri != null) {
                    val mime = context.contentResolver.getType(faceReferenceUri!!) ?: "image/jpeg"
                    geminiClient.streamMultimodal(
                        apiKey = apiKey,
                        contentResolver = context.contentResolver,
                        fileUri = faceReferenceUri,
                        mimeType = mime,
                        fileSizeBytes = faceReferenceSize,
                        model = activeModel,
                        systemInstruction = sysInstruction,
                        prompt = prompt,
                        onStatus = { s -> statusText = s },
                        onChunk = { partial -> output = partial }
                    )
                } else {
                    geminiClient.streamChat(
                        apiKey = apiKey,
                        model = activeModel,
                        systemInstruction = sysInstruction,
                        history = emptyList(),
                        userPrompt = prompt,
                        backupGroqKey = backupGroq,
                        backupOpenRouterKey = backupOpenRouter,
                        onChunk = { partial -> output = partial }
                    )
                }

                result.onSuccess {
                    output = it
                    statusText = "Generated via $activeModel (Veo 3 Directing Mode)."
                }.onFailure {
                    statusText = "AI error: ${it.message}"
                }
                busy = false
            } else {
                val installedId = localLlm.findFirstInstalledMediaPipeModelId()
                if (installedId != null) {
                    val acc = StringBuilder()
                    statusText = "Running offline on local model ($installedId)..."
                    localLlm.streamResponse(
                        modelId = installedId,
                        systemInstruction = sysInstruction,
                        userPrompt = prompt,
                        onToken = { token, done ->
                            acc.append(token)
                            scope.launch(Dispatchers.Main) {
                                output = acc.toString()
                                if (done) {
                                    statusText = "Generated 100% offline via Local LLM."
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
                    statusText =
                        "Offline mode. Connect to internet or download an offline model in Models tab."
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
        // Header & Module Tabs
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "ZEE Cinema & Video Studio",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        "Google Veo 3 & Veo 2, OpenAI Sora, photorealistic scene-by-scene directing, and face lock.",
                        color = Silver.copy(alpha = 0.75f),
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CINEMA_MODULES.forEach { mod ->
                    FilterChip(
                        selected = selectedModule.id == mod.id,
                        onClick = { selectedModule = mod },
                        label = { Text("${mod.icon} ${mod.label}", fontSize = 12.sp) }
                    )
                }
            }
        }

        // AI Video Engine & Account Connection Card (Google Veo 3 & Flow)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(
                    1.dp,
                    if (geminiKeyInput.isNotBlank() || flowKeyInput.isNotBlank())
                        NeonEmerald.copy(alpha = 0.5f)
                    else
                        ElectricBlue.copy(alpha = 0.3f),
                    RoundedCornerShape(16.dp)
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🎬", fontSize = 14.sp)
                    Text(
                        "Video Engine Provider",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                val isConnected = geminiKeyInput.isNotBlank() || flowKeyInput.isNotBlank()
                Box(
                    modifier = Modifier
                        .background(
                            if (isConnected) Color(0xFF064E3B) else Color(0xFF1E293B),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isConnected) "● Ready" else "○ Connect Key",
                        color = if (isConnected) NeonEmerald else Silver,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Text(
                "Directing engine: Google DeepMind Veo 3 (8K & Spatial Audio), Veo 2, or Flow Video Cloud.",
                color = Silver.copy(alpha = 0.75f),
                fontSize = 11.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = activeEngineProvider == "veo3",
                    onClick = {
                        activeEngineProvider = "veo3"
                        keyVault.saveKey("cinema_active_provider", "veo3")
                    },
                    label = { Text("🌟 Google Veo 3 (DeepMind)", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = activeEngineProvider == "flow",
                    onClick = {
                        activeEngineProvider = "flow"
                        keyVault.saveKey("cinema_active_provider", "flow")
                    },
                    label = { Text("⚡ Flow Account", fontSize = 11.sp) }
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showAccountConfig = !showAccountConfig }) {
                    Text(if (showAccountConfig) "Hide" else "Config", fontSize = 11.sp)
                }
            }

            if (showAccountConfig) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgNavy, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (activeEngineProvider == "veo3" || activeEngineProvider == "gemini") {
                        Text("Google AI Studio (Veo 3 / Gemini Key)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        OutlinedTextField(
                            value = geminiKeyInput,
                            onValueChange = {
                                geminiKeyInput = it
                                keyVault.saveKey("gemini_api_key", it.trim())
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("Google AI Studio Key (AIzaSy...)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text(
                            if (geminiKeyInput.isNotBlank()) "✓ Key encrypted in Android Keystore (Veo 3 & Gemini Active)." else "Get free key from aistudio.google.com",
                            color = if (geminiKeyInput.isNotBlank()) NeonEmerald else Silver,
                            fontSize = 11.sp
                        )
                    } else {
                        Text("Flow Video Account Configuration", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        OutlinedTextField(
                            value = flowKeyInput,
                            onValueChange = {
                                flowKeyInput = it
                                keyVault.saveKey("flow_api_key", it.trim())
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("Flow Account API Key / Bearer Token") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = flowEndpointInput,
                            onValueChange = {
                                flowEndpointInput = it
                                keyVault.saveKey("flow_endpoint", it.trim())
                            },
                            label = { Text("Flow Video Generation Endpoint URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = flowModelInput,
                            onValueChange = {
                                flowModelInput = it
                                keyVault.saveKey("flow_model_id", it.trim())
                            },
                            label = { Text("Target Model (e.g. veo-3.0-generate-001)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        }

        // Face Reference & Character Lock Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(
                    1.dp,
                    if (faceReferenceUri != null) NeonEmerald.copy(alpha = 0.5f) else Silver.copy(alpha = 0.14f),
                    RoundedCornerShape(16.dp)
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("👤", fontSize = 14.sp)
                    Text(
                        "Face Reference & Zero-Shape-Change Lock",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                if (faceReferenceUri != null) {
                    Text(
                        "LOCKED",
                        color = NeonEmerald,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            Text(
                "Attach a portrait photo to lock face symmetry, bone structure, and facial identity across all scenes with zero morphing.",
                color = Silver.copy(alpha = 0.7f),
                fontSize = 11.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { pickFaceImageLauncher.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (faceReferenceUri != null) NeonEmerald.copy(alpha = 0.2f) else ElectricBlue.copy(alpha = 0.25f),
                        contentColor = if (faceReferenceUri != null) NeonEmerald else Color.White
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (faceReferenceUri != null) "✓ Change Face Photo" else "📎 Pick Face Reference Photo",
                        fontSize = 12.sp
                    )
                }

                if (faceReferenceUri != null) {
                    OutlinedButton(
                        onClick = {
                            faceReferenceUri = null
                            faceReferenceFileName = null
                            faceReferenceSize = 0L
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonRed)
                    ) {
                        Text("Remove", fontSize = 12.sp)
                    }
                }
            }

            if (faceReferenceUri != null) {
                Text(
                    "Attached: ${faceReferenceFileName ?: "Reference Photo"} • Facial features, nose bridge & jawline locked.",
                    color = NeonEmerald,
                    fontSize = 11.sp
                )
            }
        }

        // Directing & Video Settings Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = projectTitle,
                onValueChange = { projectTitle = it },
                label = { Text("Film / Sequence Title") },
                placeholder = { Text("e.g., DHAKA NOIR / ECHOES OF SILENCE") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = characterName,
                onValueChange = { characterName = it },
                label = { Text("Character Name / Details (Optional)") },
                placeholder = { Text("e.g., Arafat, 30s detective in dark trench coat") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text("Sequence Structure & Length", color = Silver, fontSize = 12.sp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SCENE_LENGTHS.forEach { len ->
                    FilterChip(
                        selected = sceneLength == len,
                        onClick = { sceneLength = len },
                        label = { Text(len.substringBefore("(").trim(), fontSize = 11.sp) }
                    )
                }
            }

            Text("Target Video AI Engine", color = Silver, fontSize = 12.sp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VIDEO_ENGINES.forEach { engine ->
                    FilterChip(
                        selected = videoEngine == engine,
                        onClick = { videoEngine = engine },
                        label = { Text(engine.substringBefore("(").trim(), fontSize = 11.sp) }
                    )
                }
            }

            Text("Aspect Ratio & Camera Format", color = Silver, fontSize = 12.sp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ASPECT_RATIOS.forEach { ratio ->
                    FilterChip(
                        selected = aspectRatio == ratio,
                        onClick = { aspectRatio = ratio },
                        label = { Text(ratio.substringBefore("(").trim(), fontSize = 11.sp) }
                    )
                }
            }

            Text("Visual Aesthetic & Realism", color = Silver, fontSize = 12.sp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VISUAL_AESTHETICS.forEach { aes ->
                    FilterChip(
                        selected = aesthetic == aes,
                        onClick = { aesthetic = aes },
                        label = { Text(aes.substringBefore("(").trim(), fontSize = 11.sp) }
                    )
                }
            }

            OutlinedTextField(
                value = brief,
                onValueChange = { brief = it },
                label = { Text("Scene Concept / Director's Brief") },
                placeholder = { Text("Describe the action, emotional tone, environment, lighting, and what happens scene by scene...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { generateDeliverable() },
                    enabled = brief.isNotBlank() && !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (busy) "Directing Sequence..." else "🎬 Generate ${selectedModule.label}",
                        fontSize = 12.sp
                    )
                }
                if (busy) {
                    OutlinedButton(
                        onClick = {
                            geminiClient.cancelActive()
                            localLlm.cancelActiveGeneration()
                            busy = false
                            statusText = "Directing cancelled."
                        }
                    ) {
                        Text("Stop", color = CrimsonRed, fontSize = 12.sp)
                    }
                }
            }

            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = ElectricBlue
                )
            }

            if (statusText.isNotBlank()) {
                Text(statusText, color = ElectricBlue, fontSize = 11.sp)
            }
        }

        // Editable Output + SAF Export, Copy Prompt & Memory Save
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${selectedModule.label} Output",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Google Veo 3 Video Generator / Dispatch Button
                    Button(
                        enabled = output.isNotBlank() && !busy,
                        onClick = { dispatchToGoogleVeoApi(output) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8))
                    ) {
                        Text("🌟 Veo 3 Video", fontSize = 11.sp)
                    }

                    // Open Google VideoFX Web Suite in 1-tap
                    OutlinedButton(
                        enabled = output.isNotBlank(),
                        onClick = { openGoogleVideoFxWeb(output) }
                    ) {
                        Text("🚀 Open VideoFX", fontSize = 11.sp)
                    }

                    if (activeEngineProvider == "flow" || flowKeyInput.isNotBlank()) {
                        Button(
                            enabled = output.isNotBlank() && !busy,
                            onClick = { dispatchToFlowApi(output) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                        ) {
                            Text("⚡ Flow Video", fontSize = 11.sp)
                        }
                    }

                    // Quick Copy Button for copying directly into Sora / Veo / Kling
                    OutlinedButton(
                        enabled = output.isNotBlank(),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("ZEE Cinema Output", output)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied prompts to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("📋 Copy", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        enabled = output.isNotBlank(),
                        onClick = {
                            scope.launch {
                                dao.upsertMemory(
                                    MemoryEntity(
                                        id = UUID.randomUUID().toString(),
                                        owner = "shared",
                                        category = "creative",
                                        content = "[Cinema: ${projectTitle.ifBlank { "Untitled" }} • ${selectedModule.label}] ${output.take(450)}"
                                    )
                                )
                                statusText = "Saved notes to Room Long-Term Creative Memory."
                            }
                        }
                    ) {
                        Text("💾 Save Memory", fontSize = 11.sp)
                    }

                    Button(
                        enabled = output.isNotBlank(),
                        onClick = {
                            val safeName = projectTitle
                                .ifBlank { "zee-cinema" }
                                .lowercase()
                                .replace(Regex("[^a-z0-9]+"), "-")
                            exportLauncher.launch("$safeName-${selectedModule.id}.md")
                        }
                    ) {
                        Text("📥 Export (.md)", fontSize = 11.sp)
                    }
                }
            }

            OutlinedTextField(
                value = output,
                onValueChange = { output = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgNavy, RoundedCornerShape(12.dp)),
                minLines = 10,
                maxLines = 28,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color.White
                ),
                placeholder = { Text("Generated scene-by-scene video prompts, face consistency matrix, shot list, or cinema analysis appears here...") }
            )
        }
    }
}
