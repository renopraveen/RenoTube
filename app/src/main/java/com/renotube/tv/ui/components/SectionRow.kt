package com.renotube.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.renotube.tv.data.model.SectionRowModel
import com.renotube.tv.data.model.VideoCardModel
import com.renotube.tv.ui.theme.YouTubeWhite
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun SectionRow(
    section: SectionRowModel,
    onVideoClick: (VideoCardModel) -> Unit,
    modifier: Modifier = Modifier,
    onVideoLongClick: ((VideoCardModel) -> Unit)? = null,
    onVideoFocused: ((VideoCardModel) -> Unit)? = null,
    onNextVideoPrefetch: ((VideoCardModel) -> Unit)? = null,
    focusedVideoId: String? = null,
    onNavigateToSidebar: (() -> Unit)? = null,
    onUpPress: (() -> Boolean)? = null,
    onDownPress: (() -> Boolean)? = null,
    onLoadMore: () -> Unit = {}
) {
    // Enforce full, rich YouTube TV rows: never show stubby rows with fewer than 5 cards
    if (section.items.size < 5) return

    val rowListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scrollOffsetPx = remember(density) { with(density) { 136.dp.roundToPx() } }
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        // Row Section Header
        Text(
            text = section.title,
            color = YouTubeWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 32.dp, bottom = 10.dp)
        )

        // Horizontal Carousel - smooth auto-scroll keeping focused card perfectly framed with 48dp right margin
        LazyRow(
            state = rowListState,
            contentPadding = PaddingValues(start = 32.dp, end = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(
                items = section.items,
                key = { index, item -> "${section.title}_${item.videoId}_$index" },
                contentType = { _, _ -> "video_card" }
            ) { index, video ->
                VideoCard(
                    video = video,
                    onClick = { onVideoClick(video) },
                    onLongClick = { onVideoLongClick?.invoke(video) },
                    onFocused = {
                        onVideoFocused?.invoke(video)
                        if (index + 1 < section.items.size) {
                            onNextVideoPrefetch?.invoke(section.items[index + 1])
                        }
                        val targetIndex = (index - 2).coerceAtLeast(0)
                        val offsetPx = if (index <= 1) 0 else scrollOffsetPx
                        scrollJob?.cancel()
                        scrollJob = coroutineScope.launch {
                            rowListState.animateScrollToItem(targetIndex, offsetPx)
                        }
                    },
                    isLastFocused = (focusedVideoId != null && video.videoId == focusedVideoId),
                    isFirstInRow = (index == 0),
                    isLastInRow = (index == section.items.lastIndex),
                    onLeftBoundaryPress = {
                        if (index == 0 && onNavigateToSidebar != null) {
                            onNavigateToSidebar()
                            true
                        } else false
                    },
                    onRightBoundaryPress = {
                        if (index == section.items.lastIndex) {
                            true // Clamp at end of row: stay on this card
                        } else false
                    },
                    onUpBoundaryPress = onUpPress,
                    onDownBoundaryPress = onDownPress
                )

                // Trigger pagination when reaching last 3 items
                if (index >= section.items.size - 3) {
                    onLoadMore()
                }
            }
        }
    }
}
