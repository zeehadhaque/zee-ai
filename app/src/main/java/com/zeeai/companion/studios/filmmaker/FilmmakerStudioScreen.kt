package com.zeeai.companion.studios.filmmaker

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
import com.zeeai.companion.data.local.MemoryEntity
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.data.local.ZeeDatabase
import com.zeeai.companion.offline.LocalLlmInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val BgNavy = Color(0xFF060913)
private val CardNavy = Color(0xFF0B1120)
private val Silver = Color(0xFFC8D1E0)
private val ElectricBlue = Color(0xFF2F80FF)

private data class FilmModuleSpec(
    val id: String,
    val label: String,
    val directive: String
)

private val FILM_MODULES = listOf(
    FilmModuleSpec(
        "concept",
        "Concept & Treatment",
        "Generate: 1) Logline, 2) Synopsis, 3) 3-Act Treatment, and 4) Psychological Character Arcs."
    ),
    FilmModuleSpec(
        "screenplay",
        "Screenplay",
        "Write a properly formatted Fountain screenplay scene (INT./EXT. sluglines, minimalist visual action lines, silence-driven pacing, subtext-rich dialogue)."
    ),
    FilmModuleSpec(
        "breakdown",
        "Scene Breakdown",
        "Produce a lean indie scene breakdown (Location, Time, Cast, Props, Key Action) and practical shooting schedule."
    ),
    FilmModuleSpec(
        "cinematography",
        "Shot List & Lighting",
        "Create a detailed Shot List (Shot #, Framing, Movement, Purpose) and practical Natural-Light / Mobile Cinematography & Color Palette direction."
    ),
    FilmModuleSpec(
        "sound",
        "Sound & Edit",
        "Design a Psychoacoustic Sound Design blueprint (room tone, foley, silence beats, J/L-cuts, score cues) and editing pacing notes."
    ),
    FilmModuleSpec(
        "cinema",
        "ZEE Cinema Analysis",
        "Perform an auteur-level film analysis covering visual language, blocking, thematic architecture, and takeaways for independent filmmaking."
    )
)

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

    var selectedModule by remember { mutableStateOf(FILM_MODULES[1]) }
    var projectTitle by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("Psychological Thriller / Noir") }
    var tone by remember { mutableStateOf("Dark atmospheric, natural light, silence-driven") }
    var brief by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Native Android SAF export (.fountain or .md directly to phone storage)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
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
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    statusText = "Export failed: ${err.message}"
                }
            }
        }
    }

    fun generateDeliverable() {
        if (brief.isBlank() || busy) return
        busy = true
        output = ""
        statusText = "Directing ${selectedModule.label}..."

        val prompt = buildString {
            append("Project Title: ${projectTitle.ifBlank { "Untitled" }}\n")
            append("Genre: $genre\n")
            append("Visual & Pacing Tone: $tone\n")
            append("Deliverable: ${selectedModule.label}\n")
            append("Directive: ${selectedModule.directive}\n\n")
            append("Director's Brief:\n${brief.trim()}")
        }

        val charName = if (activeCharId == "zhee") "ZHEE" else "ZEE"
        val sysInstruction =
            "You are $charName inside ZEE Filmmaker Studio — an independent cinema directing, screenwriting, cinematography, and editing partner. Prioritize visual storytelling over dialogue-heavy exposition. Match the user's language (Bangla, Banglish, English, Hindi, or Urdu)."

        scope.launch {
            val apiKey = keyVault.getKey("gemini_api_key")
            val proModel = keyVault.getKey("gemini_pro_model").ifBlank { "gemini-2.5-pro" }
            if (isOnline() && apiKey.isNotBlank()) {
                val result = geminiClient.streamChat(
                    apiKey = apiKey,
                    model = proModel,
                    systemInstruction = sysInstruction,
                    history = emptyList(),
                    userPrompt = prompt,
                    onChunk = { partial -> output = partial }
                )
                result.onSuccess {
                    output = it
                    statusText = "Generated online via $proModel."
                }.onFailure {
                    statusText = "Gemini error: ${it.message}"
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
                        "Offline with no downloaded Local LLM. Connect to internet for Gemini or download a model in Models tab."
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
            Text(
                "ZEE Cinema & Filmmaker Studio",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                "Screenplays, shot lists, natural-light cinematography, psychoacoustic sound design & film analysis.",
                color = Silver.copy(alpha = 0.75f),
                fontSize = 12.sp
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FILM_MODULES.forEach { mod ->
                    FilterChip(
                        selected = selectedModule.id == mod.id,
                        onClick = { selectedModule = mod },
                        label = { Text(mod.label, fontSize = 12.sp) }
                    )
                }
            }
        }

        // Director Input Card
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
                label = { Text("Project Title") },
                placeholder = { Text("e.g., AUTOPSY / SHADOW IV") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("Genre") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = tone,
                    onValueChange = { tone = it },
                    label = { Text("Visual Tone") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = brief,
                onValueChange = { brief = it },
                label = { Text("Scene / Story Brief") },
                placeholder = { Text("Describe the premise, scene beat, conflict, or movie to analyze...") },
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
                    Text(if (busy) "Generating..." else "Generate ${selectedModule.label}", fontSize = 12.sp)
                }
                if (busy) {
                    OutlinedButton(
                        onClick = {
                            geminiClient.cancelActive()
                            localLlm.cancelActiveGeneration()
                            busy = false
                            statusText = "Generation cancelled."
                        }
                    ) {
                        Text("Stop", color = Color(0xFFF87171), fontSize = 12.sp)
                    }
                }
            }

            if (statusText.isNotBlank()) {
                Text(statusText, color = ElectricBlue, fontSize = 11.sp)
            }
        }

        // Editable Output + SAF Export & Memory Save
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
                    "${selectedModule.label} Draft",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        enabled = output.isNotBlank(),
                        onClick = {
                            scope.launch {
                                dao.upsertMemory(
                                    MemoryEntity(
                                        id = UUID.randomUUID().toString(),
                                        owner = "shared",
                                        category = "creative",
                                        content = "[Film: ${projectTitle.ifBlank { "Untitled" }} • ${selectedModule.label}] ${output.take(450)}"
                                    )
                                )
                                statusText = "Saved notes to Room Long-Term Creative Memory."
                            }
                        }
                    ) {
                        Text("Save Memory", fontSize = 11.sp)
                    }
                    Button(
                        enabled = output.isNotBlank(),
                        onClick = {
                            val ext = if (selectedModule.id == "screenplay") "fountain" else "md"
                            val safeName = projectTitle
                                .ifBlank { "zee-film" }
                                .lowercase()
                                .replace(Regex("[^a-z0-9]+"), "-")
                            exportLauncher.launch("$safeName-${selectedModule.id}.$ext")
                        }
                    ) {
                        Text("Export", fontSize = 11.sp)
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
                maxLines = 24,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = if (selectedModule.id == "screenplay") FontFamily.Monospace else FontFamily.Default,
                    fontSize = 13.sp,
                    color = Color.White
                ),
                placeholder = { Text("Generated script, shot list, or analysis appears here...") }
            )
        }
    }
}
