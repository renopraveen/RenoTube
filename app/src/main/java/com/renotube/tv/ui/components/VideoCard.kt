package com.renotube.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.renotube.tv.data.model.VideoCardModel
import com.renotube.tv.ui.theme.YouTubeBadgeBackground
import com.renotube.tv.ui.theme.YouTubeFocusedBorder
import com.renotube.tv.ui.theme.YouTubeLiveBadge
import com.renotube.tv.ui.theme.YouTubeRed
import com.renotube.tv.ui.theme.YouTubeSecondaryText
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.renotube.tv.ui.theme.YouTubeSurface
import com.renotube.tv.ui.theme.YouTubeWhite

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File

@Composable
fun VideoCard(
    video: VideoCardModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 296.dp,
    isShorts: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    isLastFocused: Boolean = false,
    isFirstInRow: Boolean = false,
    isLastInRow: Boolean = false,
    onLeftBoundaryPress: (() -> Boolean)? = null,
    onRightBoundaryPress: (() -> Boolean)? = null,
    onUpBoundaryPress: (() -> Boolean)? = null,
    onDownBoundaryPress: (() -> Boolean)? = null,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1.0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 150,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "cardScale"
    )
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var isLongPressTriggered by remember { mutableStateOf(false) }
    var keyDownTimeMs by remember { mutableStateOf(0L) }
    val cardFocusRequester = remember { FocusRequester() }
    val effectiveFocusRequester = focusRequester ?: cardFocusRequester

    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocused?.invoke()
        }
    }

    // Card focus is naturally managed by Compose 2D spatial navigation matching YouTube TV

    Column(
        modifier = modifier
            .width(cardWidth)
            .scale(animatedScale)
            .zIndex(if (isFocused) 10f else 1f)
            .padding(6.dp)
            .focusRequester(effectiveFocusRequester)
            .onFocusChanged { 
                isFocused = it.isFocused 
                if (!it.isFocused) {
                    longPressJob?.cancel()
                    longPressJob = null
                }
            }
            .focusable(true)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionRight -> {
                            if (onRightBoundaryPress != null) {
                                return@onKeyEvent onRightBoundaryPress()
                            } else if (isLastInRow) {
                                return@onKeyEvent true // Clamped at end of row: stays on this card
                            }
                        }
                        Key.DirectionLeft -> {
                            if (onLeftBoundaryPress != null) {
                                return@onKeyEvent onLeftBoundaryPress()
                            }
                        }
                        Key.DirectionUp -> {
                            if (onUpBoundaryPress != null) {
                                return@onKeyEvent onUpBoundaryPress()
                            }
                        }
                        Key.DirectionDown -> {
                            if (onDownBoundaryPress != null) {
                                return@onKeyEvent onDownBoundaryPress()
                            }
                        }
                    }
                }

                if (keyEvent.key == Key.Menu) {
                    if (keyEvent.type == KeyEventType.KeyUp) {
                        onLongClick?.invoke()
                    }
                    return@onKeyEvent true
                }

                val isConfirmKey = keyEvent.key == Key.DirectionCenter ||
                        keyEvent.key == Key.Enter ||
                        keyEvent.key == Key.NumPadEnter

                if (isConfirmKey) {
                    val native = keyEvent.nativeKeyEvent
                    val isLongPressFlag = native.isLongPress || 
                            ((native.flags and android.view.KeyEvent.FLAG_LONG_PRESS) != 0)

                    if (keyEvent.type == KeyEventType.KeyDown) {
                        if (native.repeatCount == 0) {
                            keyDownTimeMs = if (native.downTime > 0) native.downTime else System.currentTimeMillis()
                            isLongPressTriggered = isLongPressFlag
                            if (onLongClick != null) {
                                longPressJob?.cancel()
                                longPressJob = coroutineScope.launch {
                                    delay(450)
                                    isLongPressTriggered = true
                                }
                            }
                        } else {
                            // Hardware key repeat while holding: mark as long press without opening yet
                            val pressDuration = if (native.downTime > 0) {
                                native.eventTime - native.downTime
                            } else {
                                System.currentTimeMillis() - keyDownTimeMs
                            }
                            if (pressDuration >= 400 || native.repeatCount > 0 || isLongPressFlag) {
                                isLongPressTriggered = true
                            }
                        }
                        return@onKeyEvent true
                    } else if (keyEvent.type == KeyEventType.KeyUp) {
                        val pressDuration = if (native.downTime > 0) {
                            native.eventTime - native.downTime
                        } else {
                            System.currentTimeMillis() - keyDownTimeMs
                        }
                        val wasLongPress = isLongPressTriggered || 
                                (pressDuration >= 400) || 
                                (native.repeatCount > 0) || 
                                isLongPressFlag
                        
                        longPressJob?.cancel()
                        longPressJob = null
                        val wasTriggered = isLongPressTriggered
                        isLongPressTriggered = false
                        keyDownTimeMs = 0L

                        if ((wasLongPress || wasTriggered) && onLongClick != null) {
                            // User pressed, held, AND RELEASED the button: open context menu now!
                            view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                            onLongClick.invoke()
                            return@onKeyEvent true
                        } else {
                            // Authentic short click: open video
                            view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                            onClick()
                            return@onKeyEvent true
                        }
                    }
                    return@onKeyEvent true
                }
                false
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                        onClick()
                    },
                    onLongPress = {
                        view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                        onLongClick?.invoke()
                    }
                )
            }
    ) {
        val effectiveIsShorts = isShorts || video.isShorts
        val isShortsShelf = effectiveIsShorts && cardWidth < 220.dp
        val thumbRatio = if (isShortsShelf) (9f / 16f) else (16f / 9f)

        // Thumbnail Container: 9:16 for Shorts shelf, 16:9 for Standard Video
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(thumbRatio)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = if (isFocused) 2.5.dp else 0.dp,
                    color = if (isFocused) YouTubeFocusedBorder else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                )
                .background(YouTubeSurface)
        ) {
            val videoId = video.videoId
            val mqFallback = if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/mqdefault.jpg" else null
            val hqFallback = if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else null
            val imageTargetUrl = video.thumbnailUrl.ifBlank { hqFallback ?: mqFallback }

            val imageRequest = remember(imageTargetUrl, videoId) {
                coil.request.ImageRequest.Builder(context)
                    .data(imageTargetUrl)
                    .size(if (isShortsShelf) 360 else 520, if (isShortsShelf) 640 else 293)
                    .precision(coil.size.Precision.EXACT)
                    .crossfade(100)
                    .build()
            }
            coil.compose.AsyncImage(
                model = imageRequest,
                contentDescription = video.title,
                contentScale = if (!isShortsShelf && effectiveIsShorts) ContentScale.Fit else ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            )

            // Live Badge
            if (video.isLive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(YouTubeLiveBadge, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (effectiveIsShorts) {
                // Official Red Shorts Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(YouTubeRed, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "SHORTS",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Duration Pill (Official YouTube TV rounded pill)
            if (!video.isLive && video.durationText.isNotEmpty() && !effectiveIsShorts) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(YouTubeBadgeBackground, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.5.dp)
                ) {
                    Text(
                        text = video.durationText,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Watch Progress Bar
            if (video.percentWatched > 0) {
                LinearProgressIndicator(
                    progress = { video.percentWatched / 100f },
                    color = YouTubeRed,
                    trackColor = Color.Transparent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isShortsShelf) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    text = video.title,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFocused) YouTubeWhite else YouTubeWhite.copy(alpha = 0.90f),
                    fontSize = 13.sp,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                val authorClean = video.author.takeIf { it.isNotBlank() } ?: ""
                if (authorClean.isNotEmpty()) {
                    Text(
                        text = authorClean,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isFocused) YouTubeWhite.copy(alpha = 0.85f) else YouTubeSecondaryText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        } else {
            // Official YouTube TV Metadata Layout: Channel Avatar Circle + Title & Channel Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Channel Avatar Circle (Author initial / avatar)
                val authorInitial = video.author.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "Y"
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isFocused) YouTubeRed else Color(0xFF2C2C2C)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = authorInitial,
                        color = YouTubeWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                // Title & De-duplicated Details
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Title (Exactly 2 lines for uniform card height & identical metadata baseline)
                    Text(
                        text = video.title,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isFocused) YouTubeWhite else YouTubeWhite.copy(alpha = 0.90f),
                        fontSize = 14.5.sp,
                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val metadataLine = remember(video.author, video.secondTitle) {
                        val (cleanAuth, cleanSec) = VideoCardModel.cleanAuthorAndDetails(video.author, video.secondTitle)
                        when {
                            cleanAuth.isNotEmpty() && cleanSec.isNotEmpty() -> "$cleanAuth • $cleanSec"
                            cleanAuth.isNotEmpty() -> cleanAuth
                            cleanSec.isNotEmpty() -> cleanSec
                            else -> ""
                        }
                    }

                    if (metadataLine.isNotEmpty()) {
                        Text(
                            text = metadataLine,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isFocused) YouTubeWhite.copy(alpha = 0.85f) else YouTubeSecondaryText,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
