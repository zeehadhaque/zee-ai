package com.zeeai.companion.offline

import android.content.Context
import java.io.File

data class DiscoveredOfflineModel(
    val id: String,
    val displayName: String,
    val filePath: String,
    val sizeMb: Long
)

object OfflineModelResolver {
    private const val PREFS_NAME = "zee_offline_model_prefs"
    private const val KEY_ACTIVE_PATH = "active_offline_model_path"
    private const val MIN_VALID_BYTES = 50L * 1024L * 1024L // কমপক্ষে 50 MB

    /**
     * অ্যাপের সব সম্ভাব্য স্টোরেজ ডিরেক্টরি স্ক্যান করে আসল ডাউনলোড/ইম্পোর্ট করা .task/.bin মডেল বের করে
     */
    fun scanInstalledModels(context: Context): List<DiscoveredOfflineModel> {
        val candidateDirs = listOfNotNull(
            context.filesDir,
            File(context.filesDir, "models"),
            File(context.filesDir, "offline_models"),
            File(context.filesDir, "llm_models"),
            context.getExternalFilesDir(null),
            context.getExternalFilesDir("models")
        ).distinctBy { it.absolutePath }

        val found = mutableMapOf<String, DiscoveredOfflineModel>()
        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown()
                .maxDepth(4)
                .filter { it.isFile && it.length() >= MIN_VALID_BYTES }
                .forEach { file ->
                    val lower = file.name.lowercase()
                    if (lower.endsWith(".task") || lower.endsWith(".bin")) {
                        val abs = file.absolutePath
                        if (!found.containsKey(abs)) {
                            found[abs] = DiscoveredOfflineModel(
                                id = file.name,
                                displayName = prettifyModelName(file.name),
                                filePath = abs,
                                sizeMb = file.length() / (1024L * 1024L)
                            )
                        }
                    }
                }
        }
        return found.values.sortedByDescending { it.sizeMb }
    }

    /**
     * সিলেক্ট করা মডেল পাথ রিটার্ন করে; পাথ খালি বা মুছে গেলে অটোমেটিক ডিস্কে থাকা প্রথম ভ্যালিড মডেল বেছে নেয়
     */
    fun resolveActiveModel(context: Context): DiscoveredOfflineModel? {
        val installed = scanInstalledModels(context)
        if (installed.isEmpty()) return null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_ACTIVE_PATH, null)

        val matched = installed.firstOrNull { it.filePath == savedPath && File(it.filePath).exists() }
        if (matched != null) return matched

        // অটো-রিকভারি: ডিস্কে মডেল থাকলে কখনোই "Model not found" দেখাবে না
        val autoPicked = installed.first()
        setActiveModel(context, autoPicked.filePath)
        return autoPicked
    }

    fun setActiveModel(context: Context, filePath: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_PATH, filePath)
            .apply()
    }

    private fun prettifyModelName(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.contains("gemma-4") || lower.contains("gemma4") -> "Google Gemma 4 Mobile (.task)"
            lower.contains("gemma-3n") || lower.contains("3n") -> "Google Gemma 3n E2B (.task)"
            lower.contains("gemma-3-1b") || lower.contains("1b") -> "Google Gemma 3 1B Fast (.task)"
            lower.contains("gemma-2") -> "Google Gemma 2B (.task)"
            lower.contains("phi") -> "Microsoft Phi-2 (.bin)"
            lower.contains("falcon") -> "Falcon 1B (.bin)"
            else -> fileName.removeSuffix(".task").removeSuffix(".bin")
        }
    }
}
