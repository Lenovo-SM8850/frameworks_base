/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.quickactions.media.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.ui.compose.PopupSurface

private val PopupShape = RoundedCornerShape(34.dp)

typealias LyricLine = io.github.miner7222.systemui.lyrics.LyricLine

@Composable
fun LyricsCard(
    model: MediaControlChipModel,
    modifier: Modifier = Modifier,
) {
    val plainLyrics = model.lyrics
    val lyricLines = model.lyricState.lines
    val activeIndex = model.lyricState.activeIndex

    PopupSurface(
        shape = PopupShape,
        modifier = modifier.widthIn(min = 320.dp, max = 400.dp).height(200.dp),
    ) {
        if (lyricLines.isNotEmpty()) {
            val lazyListState = rememberLazyListState()

            LaunchedEffect(activeIndex) {
                if (activeIndex >= 0 && activeIndex < lyricLines.size) {
                    lazyListState.animateScrollToItem(activeIndex)
                }
            }

            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(vertical = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                itemsIndexed(lyricLines) { index, line ->
                    val isActive = index == activeIndex
                    val alpha by animateFloatAsState(if (isActive) 1f else 0.4f, label = "lyric_alpha")
                    val scale = if (isActive) 1.1f else 0.9f
                    val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

                    Text(
                        text = line.text,
                        color = LocalContentColor.current,
                        fontSize = (16.sp * scale),
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .alpha(alpha),
                    )
                }
            }
        } else if (!plainLyrics.isNullOrBlank()) {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                item {
                    Text(
                        text = plainLyrics,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
