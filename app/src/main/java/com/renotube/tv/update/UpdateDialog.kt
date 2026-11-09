package com.renotube.tv.update

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.renotube.tv.BuildConfig
import com.renotube.tv.ui.theme.YouTubeBlack
import com.renotube.tv.ui.theme.YouTubeFocusedBorder
import com.renotube.tv.ui.theme.YouTubeRed
import com.renotube.tv.ui.theme.YouTubeSecondaryText
import com.renotube.tv.ui.theme.YouTubeWhite

@Composable
fun UpdateDialog(
    updateState: UpdateState,
    onStartDownload: (UpdateInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onInstallApk: (java.io.File) -> Unit,
    onDismiss: () -> Unit
) {
    val isVisible = updateState !is UpdateState.Idle && updateState !is UpdateState.NoUpdateAvailable

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val primaryFocusRequester = remember { FocusRequester() }

        BackHandler {
            if (updateState is UpdateState.Downloading) {
                onCancelDownload()
            } else {
                onDismiss()
            }
        }

        LaunchedEffect(updateState) {
            if (updateState is UpdateState.UpdateAvailable || updateState is UpdateState.DownloadReady) {
                try {
                    primaryFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        }

        // Fullscreen translucent TV scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (updateState !is UpdateState.Downloading) onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            // Main Update Dialog Card
            Box(
                modifier = Modifier
                    .width(640.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(1.5.dp, Color(0xFF333333), RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Catch clicks inside card */ }
                    .padding(32.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (updateState) {
                        is UpdateState.Checking -> {
                            UpdateCheckingContent()
                        }
                        is UpdateState.UpdateAvailable -> {
                            UpdateAvailableContent(
                                info = updateState.info,
                                primaryFocusRequester = primaryFocusRequester,
                                onUpdateNow = { onStartDownload(updateState.info) },
                                onLater = onDismiss
                            )
                        }
                        is UpdateState.Downloading -> {
                            UpdateDownloadingContent(
                                state = updateState,
                                onCancel = onCancelDownload
                            )
                        }
                        is UpdateState.DownloadReady -> {
                            UpdateReadyContent(
                                state = updateState,
                                primaryFocusRequester = primaryFocusRequester,
                                onInstall = { onInstallApk(updateState.file) },
                                onClose = onDismiss
                            )
                        }
                        is UpdateState.Error -> {
                            UpdateErrorContent(
                                state = updateState,
                                primaryFocusRequester = primaryFocusRequester,
                                onRetry = {
                                    if (updateState.info != null) {
                                        onStartDownload(updateState.info)
                                    } else {
                                        onDismiss()
                                    }
                                },
                                onDismiss = onDismiss
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateCheckingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = YouTubeRed,
            modifier = Modifier.size(54.dp),
            strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Checking for updates...",
            color = YouTubeWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Connecting to GitHub Releases",
            color = YouTubeSecondaryText,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun UpdateAvailableContent(
    info: UpdateInfo,
    primaryFocusRequester: FocusRequester,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit
) {
    // Header
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(YouTubeRed.copy(alpha = 0.15f), CircleShape)
                .border(1.5.dp, YouTubeRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = YouTubeRed,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(18.dp))

        Column {
            Text(
                text = "NEW VERSION AVAILABLE",
                color = YouTubeRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "FreeTube TV ${info.versionName}",
                color = YouTubeWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(modifier = Modifier.height(18.dp))

    // Version Pill Info Bar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF141414))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = YouTubeSecondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = YouTubeSecondaryText,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "v${info.versionName}",
                color = Color(0xFF4CAF50),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val sizeMb = String.format("%.1f MB", info.fileSize / (1024f * 1024f))
            BadgeText(text = sizeMb)
            BadgeText(text = info.targetAbi.uppercase())
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Release Notes Box
    Text(
        text = "What's New in This Update:",
        color = YouTubeWhite,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF141414))
            .border(1.dp, Color(0xFF282828), RoundedCornerShape(10.dp))
            .padding(14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = info.releaseNotes.ifBlank { "Performance enhancements and stability fixes for Android TV." },
            color = Color(0xFFDDDDDD),
            fontSize = 13.5.sp,
            lineHeight = 19.sp
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Action Buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TvDialogButton(
            text = "Later",
            onClick = onLater,
            isPrimary = false
        )
        Spacer(modifier = Modifier.width(14.dp))
        TvDialogButton(
            text = "Update Now",
            icon = Icons.Default.Download,
            onClick = onUpdateNow,
            isPrimary = true,
            focusRequester = primaryFocusRequester
        )
    }
}

@Composable
private fun UpdateDownloadingContent(
    state: UpdateState.Downloading,
    onCancel: () -> Unit
) {
    val pct = (state.progress * 100).toInt()
    val downloadedMb = String.format("%.1f", state.bytesDownloaded / (1024f * 1024f))
    val totalMb = String.format("%.1f", state.totalBytes / (1024f * 1024f))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(YouTubeRed.copy(alpha = 0.15f), CircleShape)
                .border(1.5.dp, YouTubeRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { state.progress },
                color = YouTubeRed,
                strokeWidth = 3.5.dp,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.width(18.dp))

        Column {
            Text(
                text = "DOWNLOADING UPDATE",
                color = YouTubeRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "FreeTube TV ${state.info.versionName}",
                color = YouTubeWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(modifier = Modifier.height(26.dp))

    // Progress bar
    LinearProgressIndicator(
        progress = { state.progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
        color = YouTubeRed,
        trackColor = Color(0xFF2E2E2E)
    )

    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$downloadedMb MB of $totalMb MB",
            color = YouTubeSecondaryText,
            fontSize = 14.sp
        )
        Text(
            text = "$pct%",
            color = YouTubeWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TvDialogButton(
            text = "Cancel",
            onClick = onCancel,
            isPrimary = false
        )
    }
}

@Composable
private fun UpdateReadyContent(
    state: UpdateState.DownloadReady,
    primaryFocusRequester: FocusRequester,
    onInstall: () -> Unit,
    onClose: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color(0x224CAF50), CircleShape)
                .border(1.5.dp, Color(0xFF4CAF50), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(30.dp)
            )
        }

        Spacer(modifier = Modifier.width(18.dp))

        Column {
            Text(
                text = "DOWNLOAD COMPLETE",
                color = Color(0xFF4CAF50),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Ready to Install",
                color = YouTubeWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(modifier = Modifier.height(18.dp))

    Text(
        text = "FreeTube TV ${state.info.versionName} has been downloaded successfully. Click 'Install Now' to launch the Android TV package installer and complete the update.",
        color = Color(0xFFCCCCCC),
        fontSize = 14.5.sp,
        lineHeight = 21.sp
    )

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TvDialogButton(
            text = "Later",
            onClick = onClose,
            isPrimary = false
        )
        Spacer(modifier = Modifier.width(14.dp))
        TvDialogButton(
            text = "Install Now",
            icon = Icons.Default.SystemUpdate,
            onClick = onInstall,
            isPrimary = true,
            focusRequester = primaryFocusRequester
        )
    }
}

@Composable
private fun UpdateErrorContent(
    state: UpdateState.Error,
    primaryFocusRequester: FocusRequester,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color(0x22FF5252), CircleShape)
                .border(1.5.dp, Color(0xFFFF5252), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(30.dp)
            )
        }

        Spacer(modifier = Modifier.width(18.dp))

        Column {
            Text(
                text = "UPDATE NOTICE",
                color = Color(0xFFFF5252),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Update Check",
                color = YouTubeWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(modifier = Modifier.height(18.dp))

    Text(
        text = state.message,
        color = Color(0xFFDDDDDD),
        fontSize = 14.5.sp,
        lineHeight = 21.sp
    )

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TvDialogButton(
            text = "Close",
            onClick = onDismiss,
            isPrimary = false
        )
        if (state.info != null) {
            Spacer(modifier = Modifier.width(14.dp))
            TvDialogButton(
                text = "Retry",
                onClick = onRetry,
                isPrimary = true,
                focusRequester = primaryFocusRequester
            )
        }
    }
}

@Composable
private fun BadgeText(text: String) {
    Box(
        modifier = Modifier
            .background(Color(0xFF262626), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Color(0xFFB0B0B0),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TvDialogButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    isPrimary: Boolean,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        label = "btnScale"
    )

    val bgColor = when {
        isFocused -> YouTubeWhite
        isPrimary -> YouTubeRed
        else -> Color(0xFF2A2A2A)
    }

    val contentColor = when {
        isFocused -> YouTubeBlack
        isPrimary -> YouTubeWhite
        else -> YouTubeWhite
    }

    val borderModifier = if (isFocused) {
        Modifier.border(2.dp, YouTubeFocusedBorder, RoundedCornerShape(10.dp))
    } else {
        Modifier.border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(10.dp))
    }

    Box(
        modifier = Modifier
            .scale(scale)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .then(borderModifier)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = contentColor,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
