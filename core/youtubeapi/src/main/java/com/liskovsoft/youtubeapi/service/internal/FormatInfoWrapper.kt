package com.liskovsoft.youtubeapi.service.internal

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.app.PoTokenGate
import com.liskovsoft.youtubeapi.app.ReloadPlaybackGate
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.innertube.InnertubeService
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService

internal object FormatInfoWrapper {
    private val mFormatCache = java.util.concurrent.ConcurrentHashMap<String, MediaItemFormatInfo>()
    private var mTryInnertubeFirst: Boolean = false
    private val mInnertubeResult = object { var isUnplayable: Boolean = false; var client: AppClient? = null }
    private val mInnertubeProvider: (String, String?) -> MediaItemFormatInfo? =
        { videoId, clickTrackingParams ->
            getFormatInfoInnertube(videoId, clickTrackingParams).also {
                mInnertubeResult.isUnplayable = it?.isUnplayable ?: true
                mInnertubeResult.client = it?.clientInfo as? AppClient
                persistFormatInfoType(it)
            }
        }
    private val mLegacyProvider: (String, String?) -> MediaItemFormatInfo? =
        { videoId, clickTrackingParams -> getFormatInfoLegacy(videoId, clickTrackingParams) }
    private val videoLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    @JvmStatic
    fun getFormatInfo(videoId: String, clickTrackingParams: String?): MediaItemFormatInfo? {
        // Fast path: if video is already resolved, return in 0ms without waiting for lock
        getCachedFormatInfo(videoId)?.let { return it }

        val vLock = videoLocks.computeIfAbsent(videoId) { Any() }
        try {
            synchronized(vLock) {
                getCachedFormatInfo(videoId)?.let { return it }
                return selectPlaybackFormatInfo(videoId, clickTrackingParams)
            }
        } finally {
            videoLocks.remove(videoId)
            ReloadPlaybackGate.reset(videoId)
        }
    }

    @JvmStatic
    fun switchNextFormat(force: Boolean) {
        if (mTryInnertubeFirst && !mInnertubeResult.isUnplayable) {
            if (!force && mInnertubeResult.client?.let { PoTokenGate.resetCache(it) } ?: false)
                return

            mTryInnertubeFirst = false
        } else {
            val endReached: Boolean = getVideoInfoService().switchNextFormat(force)
            if (endReached)
                mTryInnertubeFirst = true
        }
    }

    @JvmStatic
    fun resetFormat() {
        mTryInnertubeFirst = false
        getVideoInfoService().resetInfoType()
    }

    @JvmStatic
    fun invalidateCache() {
        mFormatCache.clear()
    }

    @JvmStatic
    fun syncWithAuthInfoIfNeeded(formatInfo: MediaItemFormatInfo?) {
        if (formatInfo == null) {
            return
        }

        if (shouldBeSynced(formatInfo) && !formatInfo.isSynced()) {
            val videoInfo = getVideoInfoService().getAuthVideoInfo(formatInfo.getVideoId(), formatInfo.getClickTrackingParams())
            formatInfo.sync(YouTubeMediaItemFormatInfo.from(videoInfo))
        }

        if (shouldBeSynced(formatInfo)) { // still not synced
            throw IllegalStateException("Update history error: the format should be synced first")
        }
    }

    private fun shouldBeSynced(formatInfo: MediaItemFormatInfo): Boolean {
        return !formatInfo.isAuth() && !formatInfo.isUnplayable() && getSignInService().isSigned
    }

    private fun selectPlaybackFormatInfo(videoId: String, clickTrackingParams: String?): MediaItemFormatInfo? {
        var formatInfo = getPrimaryProvider()(videoId, clickTrackingParams)

        if (formatInfo != null && formatInfo.isUnplayable()) {
            formatInfo = getSecondaryProvider()(videoId, clickTrackingParams)
        }

        return formatInfo
    }

    private fun getPrimaryProvider(): (String, String?) -> MediaItemFormatInfo? {
        return if (mTryInnertubeFirst) mInnertubeProvider else mLegacyProvider
    }

    private fun getSecondaryProvider(): (String, String?) -> MediaItemFormatInfo? {
        return if (mTryInnertubeFirst) mLegacyProvider else mInnertubeProvider
    }

    private fun getFormatInfoInnertube(videoId: String, clickTrackingParams: String?): MediaItemFormatInfo? {
        //videoId = "K04WmBtVsOs"; // the testing video: Memories of Memories

        val cachedFormatInfo: MediaItemFormatInfo? = getCachedFormatInfo(videoId)

        if (cachedFormatInfo != null) {
            return cachedFormatInfo
        }

        checkSigned()

        AppService.instance().resetClientPlaybackNonce() // history fix: unique value per each video info

        val formatInfo = InnertubeService.createFormatInfo(videoId)

        setCachedFormatInfo(formatInfo, clickTrackingParams)

        return formatInfo
    }

    private fun getFormatInfoLegacy(videoId: String?, clickTrackingParams: String?): MediaItemFormatInfo? {
        val cachedFormatInfo = getCachedFormatInfo(videoId)

        if (cachedFormatInfo != null) {
            return cachedFormatInfo
        }

        checkSigned()

        val videoInfo = getVideoInfoService().getVideoInfo(videoId, clickTrackingParams)

        val formatInfo = YouTubeMediaItemFormatInfo.from(videoInfo)

        setCachedFormatInfo(formatInfo, clickTrackingParams)

        return formatInfo
    }

    private fun getCachedFormatInfo(videoId: String?): MediaItemFormatInfo? {
        if (videoId == null) return null
        val cached = mFormatCache[videoId]
        if (cached != null && cached.isCacheActual() && !cached.isUnplayable()) {
            return cached
        }
        return null
    }

    private fun setCachedFormatInfo(formatInfo: MediaItemFormatInfo?, clickTrackingParams: String?) {
        if (formatInfo == null || formatInfo.isUnplayable()) {
            return
        }

        val videoId = formatInfo.getVideoId()
        if (!videoId.isNullOrEmpty()) {
            formatInfo.setClickTrackingParams(clickTrackingParams)
            mFormatCache[videoId] = formatInfo
        }
    }

    private fun persistFormatInfoType(formatInfo: MediaItemFormatInfo?) {
        if (!GlobalPreferences.isInitialized()) {
            return
        }

        val appClient = formatInfo?.clientInfo as? AppClient

        val videoInfoType = appClient?.ordinal ?: -1

        if (getData().videoInfoType != videoInfoType)
            getData().videoInfoType = videoInfoType
    }

    private fun checkSigned() {
        getSignInService().checkAuth()
    }

    private fun getSignInService(): YouTubeSignInService {
        return YouTubeSignInService.instance()
    }

    private fun getVideoInfoService(): VideoInfoService {
        return VideoInfoService.instance()
    }

    private fun getData(): MediaServiceData {
        return MediaServiceData.instance()
    }
}