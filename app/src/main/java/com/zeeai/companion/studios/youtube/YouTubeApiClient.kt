package com.zeeai.companion.studios.youtube

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class NativeYouTubeChannel(
    val id: String,
    val title: String,
    val subscriberCount: String,
    val viewCount: String,
    val videoCount: String,
    val uploadsPlaylistId: String
)

data class NativeYouTubeVideo(
    val id: String,
    val title: String,
    val publishedAt: String,
    val privacyStatus: String
)

data class NativeYouTubeComment(
    val commentId: String,
    val authorName: String,
    val text: String,
    val likeCount: Int,
    val replyCount: Int
)

data class NativeYouTubeUploadMeta(
    val title: String,
    val description: String,
    val tags: List<String>,
    val categoryId: String = "22",
    val privacyStatus: String = "private",
    val publishAtIso: String? = null,
    val madeForKids: Boolean = false
)

data class NativeYouTubeAnalytics(
    val startDate: String,
    val endDate: String,
    val totalViews: Long,
    val minutesWatched: Long,
    val avgDurationSeconds: Int,
    val avgViewPercentage: Double,
    val likes: Long,
    val comments: Long,
    val subsGained: Long,
    val subsLost: Long,
    val estimatedRevenue: Double?,
    val revenueNote: String,
    val trafficSources: List<Pair<String, Long>>
)

class YouTubeApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private val dataBase = "https://www.googleapis.com/youtube/v3"
    private val uploadBase = "https://www.googleapis.com/upload/youtube/v3"
    private val analyticsBase = "https://youtubeanalytics.googleapis.com/v2/reports"

    suspend fun fetchMyChannels(accessToken: String): Result<List<NativeYouTubeChannel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = getJson("$dataBase/channels?part=snippet,statistics,contentDetails&mine=true", accessToken)
                val items = json.optJSONArray("items") ?: JSONArray()
                (0 until items.length()).map { i ->
                    val ch = items.getJSONObject(i)
                    val snippet = ch.optJSONObject("snippet")
                    val stats = ch.optJSONObject("statistics")
                    val uploads = ch.optJSONObject("contentDetails")
                        ?.optJSONObject("relatedPlaylists")
                        ?.optString("uploads", "") ?: ""
                    NativeYouTubeChannel(
                        id = ch.optString("id"),
                        title = snippet?.optString("title", "Untitled Channel") ?: "Untitled Channel",
                        subscriberCount = stats?.optString("subscriberCount", "Hidden") ?: "Hidden",
                        viewCount = stats?.optString("viewCount", "0") ?: "0",
                        videoCount = stats?.optString("videoCount", "0") ?: "0",
                        uploadsPlaylistId = uploads
                    )
                }
            }
        }

    suspend fun fetchChannelVideos(
        accessToken: String,
        uploadsPlaylistId: String,
        maxResults: Int = 15
    ): Result<List<NativeYouTubeVideo>> = withContext(Dispatchers.IO) {
        if (uploadsPlaylistId.isBlank()) return@withContext Result.success(emptyList())
        runCatching {
            val encodedId = URLEncoder.encode(uploadsPlaylistId, "UTF-8")
            val json = getJson(
                "$dataBase/playlistItems?part=snippet,contentDetails,status&playlistId=$encodedId&maxResults=$maxResults",
                accessToken
            )
            val items = json.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                NativeYouTubeVideo(
                    id = item.optJSONObject("contentDetails")?.optString("videoId") ?: item.optString("id"),
                    title = item.optJSONObject("snippet")?.optString("title", "Untitled") ?: "Untitled",
                    publishedAt = item.optJSONObject("snippet")?.optString("publishedAt", "") ?: "",
                    privacyStatus = item.optJSONObject("status")?.optString("privacyStatus", "unknown") ?: "unknown"
                )
            }
        }
    }

    /**
     * Streams a video directly from Android SAF Uri using YouTube Data API v3 resumable upload.
     */
    suspend fun uploadVideoResumable(
        accessToken: String,
        contentResolver: ContentResolver,
        videoUri: Uri,
        mimeType: String,
        fileSizeBytes: Long,
        meta: NativeYouTubeUploadMeta,
        onProgress: (Int) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val effectivePrivacy = if (!meta.publishAtIso.isNullOrBlank()) "private" else meta.privacyStatus
            val metadataJson = JSONObject().apply {
                put("snippet", JSONObject().apply {
                    put("title", meta.title.take(100))
                    put("description", meta.description.take(5000))
                    put("tags", JSONArray(meta.tags.take(25)))
                    put("categoryId", meta.categoryId.ifBlank { "22" })
                })
                put("status", JSONObject().apply {
                    put("privacyStatus", effectivePrivacy)
                    if (!meta.publishAtIso.isNullOrBlank()) put("publishAt", meta.publishAtIso)
                    put("selfDeclaredMadeForKids", meta.madeForKids)
                })
            }

            // Step 1: Initiate resumable upload session
            val initReqBuilder = Request.Builder()
                .url("$uploadBase/videos?uploadType=resumable&part=snippet,status")
                .header("Authorization", "Bearer $accessToken")
                .header("X-Upload-Content-Type", mimeType)
                .post(metadataJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))

            if (fileSizeBytes > 0L) {
                initReqBuilder.header("X-Upload-Content-Length", fileSizeBytes.toString())
            }

            val uploadSessionUrl = client.newCall(initReqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Upload init failed (${resp.code}): ${resp.body?.string()?.take(240)}")
                }
                resp.header("Location")
                    ?: throw IllegalStateException("YouTube did not return a resumable upload Location URI.")
            }

            // Step 2: Stream binary bytes from SAF Uri with real progress callbacks
            val streamingBody = object : RequestBody() {
                override fun contentType(): MediaType = mimeType.toMediaType()
                override fun contentLength(): Long = if (fileSizeBytes > 0L) fileSizeBytes else -1L
                override fun writeTo(sink: BufferedSink) {
                    val input = contentResolver.openInputStream(videoUri)
                        ?: throw IllegalStateException("Unable to open selected video file.")
                    input.use { stream ->
                        val buf = ByteArray(32 * 1024)
                        var uploaded = 0L
                        var lastPct = -1
                        while (true) {
                            val read = stream.read(buf)
                            if (read == -1) break
                            sink.write(buf, 0, read)
                            uploaded += read
                            if (fileSizeBytes > 0L) {
                                val pct = ((uploaded * 100L) / fileSizeBytes).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }

            val putReq = Request.Builder()
                .url(uploadSessionUrl)
                .header("Authorization", "Bearer $accessToken")
                .put(streamingBody)
                .build()

            client.newCall(putReq).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Video upload failed (${resp.code}): ${raw.take(240)}")
                }
                onProgress(100)
                JSONObject(raw).optString("id", "unknown")
            }
        }
    }

    suspend fun uploadThumbnail(
        accessToken: String,
        contentResolver: ContentResolver,
        videoId: String,
        imageUri: Uri,
        mimeType: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Cannot read selected thumbnail.")
            if (bytes.size > 2 * 1024 * 1024) {
                throw IllegalArgumentException("YouTube thumbnails must be 2 MB or smaller.")
            }
            val req = Request.Builder()
                .url("$uploadBase/thumbnails/set?videoId=${URLEncoder.encode(videoId, "UTF-8")}&uploadType=media")
                .header("Authorization", "Bearer $accessToken")
                .post(bytes.toRequestBody(mimeType.toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Thumbnail upload failed (${resp.code}): ${resp.body?.string()?.take(200)}")
                }
            }
        }
    }

    suspend fun fetchVideoComments(
        accessToken: String,
        videoId: String,
        maxResults: Int = 20
    ): Result<List<NativeYouTubeComment>> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(videoId, "UTF-8")
            val json = getJson(
                "$dataBase/commentThreads?part=snippet&videoId=$encoded&maxResults=$maxResults&order=relevance",
                accessToken
            )
            val items = json.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).map { i ->
                val thread = items.getJSONObject(i)
                val snippet = thread.optJSONObject("snippet")
                val top = snippet?.optJSONObject("topLevelComment")
                val topSnippet = top?.optJSONObject("snippet")
                NativeYouTubeComment(
                    commentId = top?.optString("id") ?: thread.optString("id"),
                    authorName = topSnippet?.optString("authorDisplayName", "Unknown") ?: "Unknown",
                    text = topSnippet?.optString("textOriginal")
                        ?: topSnippet?.optString("textDisplay", "") ?: "",
                    likeCount = topSnippet?.optInt("likeCount", 0) ?: 0,
                    replyCount = snippet?.optInt("totalReplyCount", 0) ?: 0
                )
            }
        }
    }

    suspend fun publishCommentReply(
        accessToken: String,
        parentCommentId: String,
        replyText: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put(
                "snippet",
                JSONObject()
                    .put("parentId", parentCommentId)
                    .put("textOriginal", replyText.trim())
            )
            val req = Request.Builder()
                .url("$dataBase/comments?part=snippet")
                .header("Authorization", "Bearer $accessToken")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Reply failed (${resp.code}): ${resp.body?.string()?.take(220)}")
                }
            }
        }
    }

    suspend fun fetchAnalytics28Days(
        accessToken: String,
        includeMonetary: Boolean
    ): Result<NativeYouTubeAnalytics> = withContext(Dispatchers.IO) {
        runCatching {
            val end = LocalDate.now()
            val start = end.minusDays(28)
            val startStr = start.toString()
            val endStr = end.toString()

            val coreUrl = "$analyticsBase?ids=channel%3D%3DMINE&startDate=$startStr&endDate=$endStr" +
                "&metrics=views,estimatedMinutesWatched,averageViewDuration,averageViewPercentage,likes,comments,subscribersGained,subscribersLost" +
                "&dimensions=day&sort=day"

            val coreJson = getJson(coreUrl, accessToken)
            val rows = coreJson.optJSONArray("rows") ?: JSONArray()

            var views = 0L
            var minutes = 0L
            var durSum = 0.0
            var pctSum = 0.0
            var likes = 0L
            var comments = 0L
            var subsGained = 0L
            var subsLost = 0L

            for (i in 0 until rows.length()) {
                val r = rows.getJSONArray(i)
                views += r.optLong(1)
                minutes += r.optLong(2)
                durSum += r.optDouble(3)
                pctSum += r.optDouble(4)
                likes += r.optLong(5)
                comments += r.optLong(6)
                subsGained += r.optLong(7)
                subsLost += r.optLong(8)
            }

            val count = maxOf(rows.length(), 1)

            // Traffic sources
            val trafficUrl = "$analyticsBase?ids=channel%3D%3DMINE&startDate=$startStr&endDate=$endStr" +
                "&metrics=views&dimensions=insightTrafficSourceType&sort=-views"
            val trafficList = runCatching {
                val tJson = getJson(trafficUrl, accessToken)
                val tRows = tJson.optJSONArray("rows") ?: JSONArray()
                (0 until tRows.length()).map { i ->
                    val r = tRows.getJSONArray(i)
                    r.optString(0) to r.optLong(1)
                }
            }.getOrDefault(emptyList())

            // Optional monetary revenue
            var revenue: Double? = null
            var revNote = "Revenue scope not requested."
            if (includeMonetary) {
                val revUrl = "$analyticsBase?ids=channel%3D%3DMINE&startDate=$startStr&endDate=$endStr&metrics=estimatedRevenue"
                runCatching {
                    val revJson = getJson(revUrl, accessToken)
                    val revRows = revJson.optJSONArray("rows")
                    if (revRows != null && revRows.length() > 0) {
                        revenue = revRows.getJSONArray(0).optDouble(0)
                        revNote = "Live YouTube Analytics monetary data."
                    } else {
                        revNote = "No revenue rows returned (channel may not be monetized)."
                    }
                }.onFailure {
                    revNote = "Revenue unavailable for this account/permission."
                }
            }

            NativeYouTubeAnalytics(
                startDate = startStr,
                endDate = endStr,
                totalViews = views,
                minutesWatched = minutes,
                avgDurationSeconds = (durSum / count).toInt(),
                avgViewPercentage = Math.round((pctSum / count) * 10.0) / 10.0,
                likes = likes,
                comments = comments,
                subsGained = subsGained,
                subsLost = subsLost,
                estimatedRevenue = revenue,
                revenueNote = revNote,
                trafficSources = trafficList
            )
        }
    }

    private fun getJson(url: String, accessToken: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("YouTube API error (${resp.code}): ${raw.take(240)}")
            }
            JSONObject(raw)
        }
    }
}
