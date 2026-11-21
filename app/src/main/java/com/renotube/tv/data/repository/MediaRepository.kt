package com.renotube.tv.data.repository

import android.content.Context
import com.liskovsoft.mediaserviceinterfaces.MediaItemService
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.renotube.tv.data.model.SponsorSegmentModel
import com.renotube.tv.data.model.VideoPlaybackMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaItemService: MediaItemService
) {
    private val formatCache = ConcurrentHashMap<String, Pair<Long, MediaItemFormatInfo>>()
    private val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes cache: keep formats warm during browsing

    private val inFlightJobs = ConcurrentHashMap<String, Deferred<MediaItemFormatInfo?>>()

    @Volatile
    var isPlaybackActive: Boolean = false

    @Volatile
    var isResolvingPrimaryStream: Boolean = false

    private val prefetchScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    var ambilightLowResUrl: String? = null
        private set

    /**
     * Extracts the lowest-resolution video-only adaptive stream URL from formatInfo:
     * 1. 144p H.264 (itag=160) - CPU H.264 software decoding uses <1% CPU
     * 2. 240p H.264 (itag=133)
     * 3. 144p VP9 (itag=278)
     * 4. 240p VP9 (itag=242)
     * 5. Any lowest-resolution video format (<= 240p)
     */
    fun extractLowResVideoStreamUrl(formatInfo: MediaItemFormatInfo?): String? {
        if (formatInfo == null) return null

        // 0. Prefer true progressive MP4 stream (itag 18/22) for ProgressiveMediaSource
        val progressive = formatInfo.createUrlList()?.firstOrNull { it.isNotEmpty() }
        if (!progressive.isNullOrEmpty()) return progressive

        val formats = formatInfo.adaptiveFormats ?: return null

        // 1. Prefer 144p H.264 (itag 160)
        val itag160 = formats.firstOrNull { it.iTag == "160" && !it.url.isNullOrBlank() }
        if (itag160 != null) return itag160.url

        // 2. Prefer 240p H.264 (itag 133)
        val itag133 = formats.firstOrNull { it.iTag == "133" && !it.url.isNullOrBlank() }
        if (itag133 != null) return itag133.url

        // 3. Fallback to 144p VP9 (itag 278)
        val itag278 = formats.firstOrNull { it.iTag == "278" && !it.url.isNullOrBlank() }
        if (itag278 != null) return itag278.url

        // 4. Fallback to 240p VP9 (itag 242)
        val itag242 = formats.firstOrNull { it.iTag == "242" && !it.url.isNullOrBlank() }
        if (itag242 != null) return itag242.url

        // 5. Fallback to any low-res video stream (height in 1..240 or lowest height)
        val lowRes = formats
            .filter { (it.mimeType?.contains("video") == true || it.height in 1..240) && !it.url.isNullOrBlank() }
            .minByOrNull { it.height }
        return lowRes?.url
    }

    init {
        resetClientToDefault()
    }

    fun resetClientToDefault() {
        try {
            inFlightJobs?.values?.forEach { it.cancel() }
            inFlightJobs?.clear()
        } catch (_: Exception) {}
        formatCache?.clear()
        ambilightLowResUrl = null
        try {
            com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().invalidateCache()
        } catch (e: Exception) {
            android.util.Log.w("MediaRepository", "Failed resetting format client: ${e.message}")
        }
    }

    fun invalidateFormat(videoId: String) {
        try {
            inFlightJobs?.remove(videoId)?.cancel()
        } catch (_: Exception) {}
        formatCache?.remove(videoId)
        try {
            File(context.cacheDir, "manifest_${videoId}.mpd").delete()
            File(context.cacheDir, "manifest_${videoId}.tmp").delete()
        } catch (_: Exception) {}
    }

    suspend fun getFormatInfo(videoId: String, clickTrackingParams: String? = null): MediaItemFormatInfo? =
        withContext(Dispatchers.IO) {
            // 1. Instant Cache Hit (0ms)
            val cached = formatCache[videoId]
            val now = System.currentTimeMillis()
            if (cached != null && (now - cached.first) < CACHE_TTL_MS && !cached.second.isUnplayable) {
                ambilightLowResUrl = extractLowResVideoStreamUrl(cached.second)
                android.util.Log.i("MediaRepository", "Format cache HIT for $videoId (instant 0ms startup)")
                return@withContext cached.second
            }

            // 2. In-flight prefetch JOIN: If this video is already being fetched in the background, await it!
            val existingJob = inFlightJobs[videoId]
            if (existingJob != null && existingJob.isActive) {
                android.util.Log.i("MediaRepository", "Joining active in-flight prefetch for $videoId")
                try {
                    val result = existingJob.await()
                    if (result != null && !result.isUnplayable) {
                        ambilightLowResUrl = extractLowResVideoStreamUrl(result)
                        return@withContext result
                    }
                } catch (_: Throwable) {}
            }

            // 3. Cold Resolution: Cancel prefetch jobs for OTHER videos to dedicate 100% bandwidth to primary video
            inFlightJobs.forEach { (id, job) ->
                if (id != videoId && job.isActive) {
                    job.cancel()
                }
            }

            isResolvingPrimaryStream = true
            try {
                val start = System.currentTimeMillis()
                var info: MediaItemFormatInfo? = null
                try {
                    info = withTimeoutOrNull(12000) {
                        mediaItemService.getFormatInfo(videoId, clickTrackingParams)
                    }

                    // If primary format timed out or returned unplayable, perform soft failover
                    if (info == null || info.isUnplayable) {
                        android.util.Log.w("MediaRepository", "Primary format unplayable for $videoId. Trying soft failover...")
                        com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().switchNextClient()
                        info = withTimeoutOrNull(8000) {
                            mediaItemService.getFormatInfo(videoId, clickTrackingParams)
                        }
                        if (info == null || info.isUnplayable) {
                            resetClientToDefault()
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("MediaRepository", "Format info resolution failed for $videoId: ${t.message}")
                }

                val success = (info != null && !info.isUnplayable)
                com.renotube.tv.perf.PerformanceTracker.recordApiLatency("Resolve Stream", start, isSuccess = success)

                if (success) {
                    formatCache[videoId] = Pair(System.currentTimeMillis(), info!!)
                    ambilightLowResUrl = extractLowResVideoStreamUrl(info)
                    // Pre-generate MPD in cache so player doesn't block on disk write
                    if (info.containsDashFormats() || info.containsSabrFormats()) {
                        try {
                            getOrGenerateMpdFile(info)
                        } catch (_: Exception) {}
                    }
                }
                info
            } finally {
                isResolvingPrimaryStream = false
            }
        }

    private val mpdLock = Any()

    fun getOrGenerateMpdFile(formatInfo: MediaItemFormatInfo): File? {
        val file = File(context.cacheDir, "manifest_${formatInfo.videoId}.mpd")
        if (file.exists() && file.length() > 200 && (System.currentTimeMillis() - file.lastModified()) < (2 * 60 * 1000L)) {
            return file
        }
        synchronized(mpdLock) {
            if (file.exists() && file.length() > 200 && (System.currentTimeMillis() - file.lastModified()) < (2 * 60 * 1000L)) {
                return file
            }
            val tempFile = File(context.cacheDir, "manifest_${formatInfo.videoId}.tmp")
            return try {
                if (formatInfo.containsDashFormats() || formatInfo.containsSabrFormats()) {
                    formatInfo.createMpdStream()?.use { input ->
                        tempFile.outputStream().buffered().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                if (tempFile.exists() && tempFile.length() > 200) {
                    if (file.exists()) file.delete()
                    tempFile.renameTo(file)
                    file
                } else {
                    tempFile.delete()
                    if (file.exists() && file.length() > 200) file else null
                }
            } catch (e: Exception) {
                android.util.Log.w("MediaRepository", "Failed creating MPD for ${formatInfo.videoId}: ${e.message}")
                try { tempFile.delete() } catch (_: Exception) {}
                null
            }
        }
    }

    fun createDataSourceFactory(formatInfo: MediaItemFormatInfo?): androidx.media3.datasource.DataSource.Factory {
        val userAgent = formatInfo?.clientInfo?.userAgent?.takeIf { it.isNotEmpty() }
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = userAgent

        val clientName = formatInfo?.clientInfo?.clientName
        if (clientName != null && (clientName.startsWith("TV", ignoreCase = true) || clientName.startsWith("WEB", ignoreCase = true))) {
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = if (clientName.startsWith("TV", ignoreCase = true)) "https://www.youtube.com/tv" else "https://www.youtube.com"
        }

        formatInfo?.visitorCookie?.takeIf { it.isNotEmpty() }?.let {
            headers["Cookie"] = it
        }

        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(10_000)

        return androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
    }

    fun createMediaSource(formatInfo: MediaItemFormatInfo): androidx.media3.exoplayer.source.MediaSource? {
        val dataSourceFactory = createDataSourceFactory(formatInfo)
        val mpdFile = getOrGenerateMpdFile(formatInfo)
        if (mpdFile != null) {
            val mediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(mpdFile))
                .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                .build()
            return androidx.media3.exoplayer.dash.DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
        if (formatInfo.containsHlsUrl() && !formatInfo.hlsManifestUrl.isNullOrEmpty()) {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(formatInfo.hlsManifestUrl)
            return androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
        val progressive = formatInfo.createUrlList()?.firstOrNull { it.isNotEmpty() }
        if (!progressive.isNullOrEmpty()) {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(progressive)
            return androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
        return null
    }

    suspend fun prefetchFormatInfo(videoId: String) = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || isResolvingPrimaryStream || isPlaybackActive) return@withContext
        val cached = formatCache[videoId]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.first) < CACHE_TTL_MS && !cached.second.isUnplayable) {
            return@withContext
        }

        val existing = inFlightJobs[videoId]
        if (existing != null && existing.isActive) {
            return@withContext
        }

        // Limit concurrent background prefetch to at most 2 jobs to keep TV Wi-Fi light
        val activeCount = inFlightJobs.values.count { it.isActive }
        if (activeCount >= 2) return@withContext

        val job = prefetchScope.async {
            try {
                delay(80) // 80ms debounce for rapid navigation
                if (isResolvingPrimaryStream || isPlaybackActive) return@async null

                val info = withTimeoutOrNull(6000) {
                    mediaItemService.getFormatInfo(videoId)
                }
                if (info != null && !info.isUnplayable) {
                    formatCache[videoId] = Pair(System.currentTimeMillis(), info)
                    ambilightLowResUrl = extractLowResVideoStreamUrl(info)
                    if (info.containsDashFormats() || info.containsSabrFormats()) {
                        try {
                            getOrGenerateMpdFile(info)
                        } catch (_: Exception) {}
                    }
                    android.util.Log.d("MediaRepository", "Predictive prefetch successful for $videoId")
                }
                info
            } catch (_: kotlinx.coroutines.CancellationException) {
                null
            } catch (t: Throwable) {
                android.util.Log.w("MediaRepository", "Prefetch failed for $videoId: ${t.message}")
                null
            } finally {
                inFlightJobs.remove(videoId)
            }
        }
        inFlightJobs[videoId] = job
    }

    suspend fun getMetadata(videoId: String): VideoPlaybackMetadata? = withContext(Dispatchers.IO) {
        val meta = mediaItemService.getMetadata(videoId) ?: return@withContext null
        val mapped = VideoPlaybackMetadata.fromMediaItemMetadata(meta)
        if (mapped.videoId.isBlank()) mapped.copy(videoId = videoId) else mapped
    }

    suspend fun getSponsorSegments(videoId: String): List<SponsorSegmentModel> = withContext(Dispatchers.IO) {
        val segments = mediaItemService.getSponsorSegments(videoId) ?: emptyList()
        segments.map { SponsorSegmentModel.fromSponsorSegment(it) }
    }

    suspend fun updateHistory(videoId: String, positionSec: Float) = withContext(Dispatchers.IO) {
        mediaItemService.updateHistoryPosition(videoId, positionSec)
    }

    suspend fun subscribe(channelId: String) = withContext(Dispatchers.IO) {
        mediaItemService.subscribe(channelId)
    }

    suspend fun unsubscribe(channelId: String) = withContext(Dispatchers.IO) {
        mediaItemService.unsubscribe(channelId)
    }

    suspend fun switchNextFormat(force: Boolean = true) = withContext(Dispatchers.IO) {
        formatCache.clear()
        ambilightLowResUrl = null
        if (force) {
            com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().switchNextClientNow()
        } else {
            com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().switchNextClient()
        }
    }
}

