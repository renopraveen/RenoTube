package com.renotube.tv.update

import com.google.gson.annotations.SerializedName
import java.io.File

data class GitHubReleaseDto(
    @SerializedName("tag_name")
    val tagName: String,
    @SerializedName("name")
    val name: String?,
    @SerializedName("body")
    val body: String?,
    @SerializedName("published_at")
    val publishedAt: String?,
    @SerializedName("prerelease")
    val prerelease: Boolean = false,
    @SerializedName("assets")
    val assets: List<GitHubAssetDto> = emptyList()
)

data class GitHubAssetDto(
    @SerializedName("name")
    val name: String,
    @SerializedName("size")
    val size: Long,
    @SerializedName("browser_download_url")
    val downloadUrl: String,
    @SerializedName("content_type")
    val contentType: String?
)

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileName: String,
    val fileSize: Long,
    val publishedAt: String,
    val targetAbi: String
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    object NoUpdateAvailable : UpdateState()
    data class Downloading(
        val info: UpdateInfo,
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : UpdateState()
    data class DownloadReady(val file: File, val info: UpdateInfo) : UpdateState()
    data class Error(val message: String, val info: UpdateInfo? = null) : UpdateState()
}
