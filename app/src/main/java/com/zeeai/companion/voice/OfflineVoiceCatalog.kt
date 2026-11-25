package com.zeeai.companion.voice

import android.content.Context
import java.io.File

enum class VoiceGender { MALE_ZEE, FEMALE_ZHEE }
enum class VoiceLang { BENGALI, ENGLISH }

data class OfflineNeuralVoicePack(
    val id: String,
    val title: String,
    val subtitle: String,
    val gender: VoiceGender,
    val language: VoiceLang,
    val sizeMb: Int,
    val downloadUrl: String,
    val defaultSpeakerId: Int = 0,
    val defaultSpeed: Float = 1.0f,
    val isTarBz2Archive: Boolean = true
)

object OfflineVoiceCatalog {

    /**
     * HTTP 200 live verified URLs from official k2-fsa/sherpa-onnx releases
     */
    val packs = listOf(
        OfflineNeuralVoicePack(
            id = "zee_bn_male_neural",
            title = "ZEE Bengali Human Voice (Male)",
            subtitle = "100% Offline Bangla Neural VITS • Natural Male Tone",
            gender = VoiceGender.MALE_ZEE,
            language = VoiceLang.BENGALI,
            sizeMb = 76,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mimic3-bn-multi_low.tar.bz2",
            defaultSpeakerId = 0,
            defaultSpeed = 0.96f
        ),
        OfflineNeuralVoicePack(
            id = "zhee_bn_female_neural",
            title = "ZHEE Bengali Human Voice (Female)",
            subtitle = "100% Offline Bangla Neural VITS • Warm Expressive Female",
            gender = VoiceGender.FEMALE_ZHEE,
            language = VoiceLang.BENGALI,
            sizeMb = 103,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-coqui-bn-custom_female.tar.bz2",
            defaultSpeakerId = 0,
            defaultSpeed = 0.98f
        ),
        OfflineNeuralVoicePack(
            id = "zee_en_male_neural",
            title = "ZEE English Human Voice (Male • Ryan HD)",
            subtitle = "100% Offline Piper Neural • Deep Natural Male",
            gender = VoiceGender.MALE_ZEE,
            language = VoiceLang.ENGLISH,
            sizeMb = 64,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-medium.tar.bz2",
            defaultSpeakerId = 0,
            defaultSpeed = 0.98f
        ),
        OfflineNeuralVoicePack(
            id = "zhee_en_female_neural",
            title = "ZHEE English Human Voice (Female • Amy HD)",
            subtitle = "100% Offline Piper Neural • Warm Caring Female",
            gender = VoiceGender.FEMALE_ZHEE,
            language = VoiceLang.ENGLISH,
            sizeMb = 64,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2",
            defaultSpeakerId = 0,
            defaultSpeed = 1.0f
        )
    )

    fun getVoiceRootDir(context: Context): File =
        File(context.filesDir, "offline_neural_voices").apply { mkdirs() }

    fun getPackDir(context: Context, packId: String): File =
        File(getVoiceRootDir(context), packId)

    /**
     * Pack-er bhetor ashol .onnx ar tokens.txt thaklei Installed hishebe dekhabe
     */
    fun resolveInstalledFiles(context: Context, packId: String): InstalledVoiceFiles? {
        val dir = getPackDir(context, packId)
        if (!dir.exists()) return null

        val allFiles = dir.walkTopDown().filter { it.isFile }.toList()
        val onnxFile = allFiles.firstOrNull { it.name.endsWith(".onnx") && it.length() > 5L * 1024L * 1024L }
            ?: return null
        val tokensFile = allFiles.firstOrNull { it.name == "tokens.txt" }
            ?: return null
        val dataDir = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
        val lexiconFile = allFiles.firstOrNull { it.name == "lexicon.txt" }

        return InstalledVoiceFiles(
            modelPath = onnxFile.absolutePath,
            tokensPath = tokensFile.absolutePath,
            dataDirPath = dataDir?.absolutePath.orEmpty(),
            lexiconPath = lexiconFile?.absolutePath.orEmpty()
        )
    }

    fun isInstalled(context: Context, packId: String): Boolean =
        resolveInstalledFiles(context, packId) != null

    fun countInstalledPacks(context: Context): Int =
        packs.count { isInstalled(context, it.id) }
}

data class InstalledVoiceFiles(
    val modelPath: String,
    val tokensPath: String,
    val dataDirPath: String,
    val lexiconPath: String
)
