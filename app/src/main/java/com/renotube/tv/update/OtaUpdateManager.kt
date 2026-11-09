package com.renotube.tv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.renotube.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OtaUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OtaUpdateManager"
        const val GITHUB_OWNER = "renopraveen"
        const val GITHUB_REPO = "FreeTube"
        private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        @Volatile
        private var instance: OtaUpdateManager? = null

        fun getInstance(context: Context): OtaUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: OtaUpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        instance = this
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var downloadJob: Job? = null

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    fun checkForUpdates(manual: Boolean = false, forceShowEvenIfSame: Boolean = false) {
        if (_updateState.value is UpdateState.Checking || _updateState.value is UpdateState.Downloading) {
            return
        }

        _updateState.value = UpdateState.Checking

        scope.launch {
            try {
                val release = fetchLatestRelease()
                if (release == null) {
                    _updateState.value = if (manual) {
                        UpdateState.Error("Could not retrieve release info from GitHub.")
                    } else {
                        UpdateState.Idle
                    }
                    return@launch
                }

                val installedVersion = BuildConfig.VERSION_NAME
                val remoteVersion = release.tagName.trimStart('v', 'V')
                val isNewer = isVersionNewer(installedVersion, remoteVersion) || forceShowEvenIfSame

                Log.i(TAG, "Current version: $installedVersion, Remote version: $remoteVersion, isNewer: $isNewer")

                if (isNewer) {
                    val assetMatch = selectBestAsset(release.assets)
                    if (assetMatch != null) {
                        val (asset, targetAbi) = assetMatch
                        val info = UpdateInfo(
                            tagName = release.tagName,
                            versionName = remoteVersion,
                            releaseName = release.name ?: "FreeTube TV $remoteVersion",
                            releaseNotes = release.body ?: "Performance improvements and bug fixes.",
                            downloadUrl = asset.downloadUrl,
                            fileName = asset.name,
                            fileSize = asset.size,
                            publishedAt = release.publishedAt ?: "",
                            targetAbi = targetAbi
                        )
                        _updateState.value = UpdateState.UpdateAvailable(info)
                    } else {
                        _updateState.value = if (manual) {
                            UpdateState.Error("A newer version ($remoteVersion) was found, but no compatible APK asset was attached to the release.")
                        } else {
                            UpdateState.Idle
                        }
                    }
                } else {
                    _updateState.value = if (manual) {
                        UpdateState.NoUpdateAvailable
                    } else {
                        UpdateState.Idle
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                _updateState.value = if (manual) {
                    UpdateState.Error("Check failed: ${e.localizedMessage ?: e.message}")
                } else {
                    UpdateState.Idle
                }
            }
        }
    }

    fun startDownload(info: UpdateInfo) {
        if (_updateState.value is UpdateState.Downloading) return

        downloadJob?.cancel()
        downloadJob = scope.launch {
            _updateState.value = UpdateState.Downloading(info, 0f, 0L, info.fileSize)

            try {
                val downloadedFile = withContext(Dispatchers.IO) {
                    downloadApkFile(info)
                }

                if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0) {
                    _updateState.value = UpdateState.DownloadReady(downloadedFile, info)
                } else {
                    _updateState.value = UpdateState.Error("Download completed, but the APK file is invalid.", info)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Download failed", e)
                    _updateState.value = UpdateState.Error("Download failed: ${e.localizedMessage ?: e.message}", info)
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _updateState.value = UpdateState.Idle
    }

    fun dismiss() {
        cancelDownload()
        _updateState.value = UpdateState.Idle
    }

    fun installApk(file: File) {
        if (!file.exists() || file.length() == 0L) {
            _updateState.value = UpdateState.Error("APK file not found.")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            _updateState.value = UpdateState.Error("Could not launch installer: ${e.message}")
        }
    }

    private suspend fun fetchLatestRelease(): GitHubReleaseDto? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.apply {
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "RenoTube-TV-OTA")
            }

            if (conn.responseCode in 200..299) {
                InputStreamReader(conn.inputStream).use { reader ->
                    gson.fromJson(reader, GitHubReleaseDto::class.java)
                }
            } else {
                Log.w(TAG, "GitHub API returned HTTP ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query GitHub API", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun downloadApkFile(info: UpdateInfo): File? = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val targetFile = File(updatesDir, info.fileName)
        if (targetFile.exists()) targetFile.delete()

        val conn = openConnectionWithRedirects(info.downloadUrl)
        val totalBytes = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.fileSize

        var bytesDownloaded = 0L
        var lastEmitMs = 0L

        conn.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs > 250 || bytesDownloaded == totalBytes) {
                        lastEmitMs = now
                        val progress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
                        _updateState.value = UpdateState.Downloading(info, progress, bytesDownloaded, totalBytes)
                    }
                }
            }
        }
        conn.disconnect()
        targetFile
    }

    private fun openConnectionWithRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 6) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "RenoTube-TV-OTA")
            conn.setRequestProperty("Accept", "application/octet-stream")

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == 307 || code == 308
            ) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc != null) {
                    currentUrl = loc
                    redirects++
                    continue
                }
            }
            return conn
        }
        throw IOException("Too many redirects: $redirects")
    }

    fun selectBestAsset(assets: List<GitHubAssetDto>): Pair<GitHubAssetDto, String>? {
        if (assets.isEmpty()) return null

        val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()

        for (abi in supportedAbis) {
            val match = assets.firstOrNull { it.name.contains(abi, ignoreCase = true) }
            if (match != null) {
                return Pair(match, abi)
            }
        }

        val universal = assets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
        if (universal != null) {
            return Pair(universal, "universal")
        }

        val anyApk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        if (anyApk != null) {
            return Pair(anyApk, "generic")
        }

        return null
    }

    fun isVersionNewer(installedVersion: String, remoteVersion: String): Boolean {
        val cleanInstalled = installedVersion.trimStart('v', 'V').substringBefore('-')
        val cleanRemote = remoteVersion.trimStart('v', 'V').substringBefore('-')

        val installedParts = cleanInstalled.split('.').mapNotNull { it.toIntOrNull() }
        val remoteParts = cleanRemote.split('.').mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(installedParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val inst = installedParts.getOrElse(i) { 0 }
            val rem = remoteParts.getOrElse(i) { 0 }
            if (rem > inst) return true
            if (rem < inst) return false
        }
        return false
    }
}
