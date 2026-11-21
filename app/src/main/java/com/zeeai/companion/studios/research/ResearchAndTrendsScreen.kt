package com.zeeai.companion.studios.research

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeai.companion.ai.GeminiNativeClient
import com.zeeai.companion.ai.GroundedCitation
import com.zeeai.companion.data.local.MemoryEntity
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.data.local.ZeeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val BgNavy = Color(0xFF060913)
private val CardNavy = Color(0xFF0B1120)
private val Silver = Color(0xFFC8D1E0)
private val ElectricBlue = Color(0xFF2F80FF)

private val REGIONS = listOf("Bangladesh", "India", "USA", "UK", "Global")
private val CATEGORIES = listOf(
    "YouTube Trends",
    "Film & Cinema",
    "Entertainment",
    "Technology & AI",
    "Social Media"
)

@Composable
fun ResearchAndTrendsScreen(
    activeCharId: String,
    database: ZeeDatabase,
    keyVault: SecureKeyVault,
    geminiClient: GeminiNativeClient,
    isOnline: () -> Boolean
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val dao = remember { database.dao() }

    var mode by remember { mutableStateOf("research") } // research | trends
    var query by remember { mutableStateOf("") }
    var selectedRegion by remember { mutableStateOf(REGIONS.first()) }
    var selectedCategory by remember { mutableStateOf(CATEGORIES.first()) }

    var reportText by remember { mutableStateOf("") }
    var citations by remember { mutableStateOf<List<GroundedCitation>>(emptyList()) }
    var searchQueries by remember { mutableStateOf<List<String>>(emptyList()) }
    var statusText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // SAF Markdown Report Export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val mdContent = buildString {
                append(reportText)
                if (citations.isNotEmpty()) {
                    append("\n\n## Verified Sources\n")
                    citations.forEachIndexed { idx, c ->
                        append("${idx + 1}. [${c.title}](${c.uri})\n")
                    }
                }
            }
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(mdContent.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                withContext(Dispatchers.Main) { statusText = "Exported Markdown report to phone storage." }
            }.onFailure { err ->
                withContext(Dispatchers.Main) { statusText = "Export failed: ${err.message}" }
            }
        }
    }

    fun executeGroundedSearch(prompt: String, label: String) {
        if (busy) return
        if (!isOnline()) {
            statusText =
                "Offline: Live Web Research & Trend Radar require internet. ZEE AI never generates fake trend reports offline."
            return
        }
        val apiKey = keyVault.getKey("gemini_api_key")
        if (apiKey.isBlank()) {
            statusText = "Add your Gemini API Key in Settings first."
            return
        }

        busy = true
        reportText = ""
        citations = emptyList()
        searchQueries = emptyList()
        statusText = "Searching live web for: $label..."

        val charName = if (activeCharId == "zhee") "ZHEE" else "ZEE"
        val proModel = keyVault.getKey("gemini_pro_model").ifBlank { "gemini-2.5-pro" }
        val sysInstruction =
            "You are $charName running ZEE Research Studio with live Google Search Grounding. Always perform a real web search, synthesize verified up-to-date facts with dates, and keep structure crisp. Match the user's language."

        scope.launch {
            val res = geminiClient.streamGrounded(
                apiKey = apiKey,
                model = proModel,
                systemInstruction = sysInstruction,
                userPrompt = prompt,
                onChunk = { text, cites, queries ->
                    scope.launch(Dispatchers.Main) {
                        reportText = text
                        citations = cites
                        searchQueries = queries
                    }
                }
            )
            res.onSuccess { final ->
                reportText = final.text
                citations = final.citations
                searchQueries = final.searchQueries
                statusText =
                    "Completed with ${final.citations.size} verified web source(s) across ${final.searchQueries.size} search query(s)."
            }.onFailure { err ->
                statusText = err.message ?: "Research failed."
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header & Mode Switcher
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Research Studio & Trend Radar",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                "Live Gemini Google Search Grounding with clickable source citations and Markdown report export.",
                color = Silver.copy(alpha = 0.75f),
                fontSize = 11.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == "research",
                    onClick = { mode = "research" },
                    label = { Text("Deep Research", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = mode == "trends",
                    onClick = { mode = "trends" },
                    label = { Text("Trend Radar", fontSize = 12.sp) }
                )
            }

            if (mode == "research") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Research Topic or Investigation") },
                    placeholder = { Text("e.g., Best indie thriller film festivals accepting mobile shorts...") },
                    minLines = 2,
                    maxLines = 4
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = query.isNotBlank() && !busy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            executeGroundedSearch(
                                prompt = "Perform a live web search and produce a structured Deep Research Report on: \"${query.trim()}\". Include: 1) Executive Summary, 2) Verified Current Facts & Dates, 3) Key Angles / Gaps, and 4) Concrete Next Actions.",
                                label = query.trim()
                            )
                        }
                    ) {
                        Text(if (busy) "Searching..." else "Run Grounded Research", fontSize = 12.sp)
                    }
                    if (busy) {
                        OutlinedButton(onClick = {
                            geminiClient.cancelActive()
                            busy = false
                            statusText = "Search cancelled."
                        }) { Text("Stop", color = Color(0xFFF87171), fontSize = 12.sp) }
                    }
                }
            } else {
                Text("Region", color = Silver, fontSize = 11.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    REGIONS.forEach { region ->
                        FilterChip(
                            selected = selectedRegion == region,
                            onClick = { selectedRegion = region },
                            label = { Text(region, fontSize = 11.sp) }
                        )
                    }
                }

                Text("Category", color = Silver, fontSize = 11.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, fontSize = 11.sp) }
                        )
                    }
                }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        executeGroundedSearch(
                            prompt = "Perform a live web search right now for what is actively trending in $selectedRegion under \"$selectedCategory\". Return the top 6 current trends with: why each is trending today, audience reaction, and 1 actionable YouTube/creator angle per trend.",
                            label = "$selectedRegion • $selectedCategory"
                        )
                    }
                ) {
                    Text(
                        if (busy) "Scanning Live Web..." else "Scan $selectedRegion $selectedCategory",
                        fontSize = 12.sp
                    )
                }
            }

            if (statusText.isNotBlank()) {
                Text(statusText, color = ElectricBlue, fontSize = 11.sp)
            }
        }

        // Executed Search Queries Chips
        if (searchQueries.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardNavy, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Google Queries Executed", color = Silver, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    searchQueries.forEach { q ->
                        Box(
                            modifier = Modifier
                                .background(BgNavy, RoundedCornerShape(8.dp))
                                .border(1.dp, ElectricBlue.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(q, color = Color(0xFF93C5FD), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Report Output + Save/Export Controls
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
                Text("Research Report", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        enabled = reportText.isNotBlank(),
                        onClick = {
                            scope.launch {
                                dao.upsertMemory(
                                    MemoryEntity(
                                        id = UUID.randomUUID().toString(),
                                        owner = "shared",
                                        category = "study",
                                        content = "[Research] ${reportText.take(450)}"
                                    )
                                )
                                statusText = "Saved research summary to Room Long-Term Memory."
                            }
                        }
                    ) { Text("Save Note", fontSize = 11.sp) }

                    Button(
                        enabled = reportText.isNotBlank(),
                        onClick = { exportLauncher.launch("zee-research-${System.currentTimeMillis()}.md") }
                    ) { Text("Export .md", fontSize = 11.sp) }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgNavy, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = reportText.ifBlank {
                        "Run a Deep Research query or Trend Radar scan above to see live cited web findings."
                    },
                    color = if (reportText.isBlank()) Silver.copy(alpha = 0.55f) else Color.White,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }

        // Clickable Verified Citations Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Verified Web Sources (${citations.size})",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )

            if (citations.isEmpty()) {
                Text(
                    "Clickable source citations returned by Google Search Grounding will appear here.",
                    color = Silver.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )
            } else {
                citations.forEachIndexed { index, cite ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgNavy, RoundedCornerShape(10.dp))
                            .border(1.dp, Silver.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .clickable { runCatching { uriHandler.openUri(cite.uri) } }
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "${index + 1}. ${cite.title}",
                            color = Color(0xFF60A5FA),
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            textDecoration = TextDecoration.Underline
                        )
                        Text(
                            text = cite.uri,
                            color = Silver.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
