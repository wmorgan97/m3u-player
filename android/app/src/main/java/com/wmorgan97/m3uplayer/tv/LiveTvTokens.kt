package com.wmorgan97.m3uplayer.tv

import androidx.compose.ui.unit.dp

/** Live TV and player-banner sizes that more than one place must agree on. Horizontal offsets are never here: they
 *  come from [Tv.margin], so the shell can change them in one place. */
object LiveTvTokens {
    /** Height of the top block (preview + now/next). The Guide's top block is the same height, and the preview window
     *  takes the same place and size in both, so switching between them never makes it jump. */
    val headerH = 152.dp
    /** The group column's content width (its start inset is [Tv.margin] - 12 so names line up with the page margin). */
    val groupW = 220.dp
    /** One row height for groups and channels. */
    val rowH = 48.dp
    /** Channel row: the "next" column (time and title), then the gap before it. */
    val nextW = 176.dp
    val colGap = 16.dp
    /** Player banner: the bottom gradient never rises above about a third of the screen. */
    val bannerGradientH = 186.dp
    const val bannerGradientTop = 0x00000000
    const val bannerGradientMid = 0x99000000
    const val bannerGradientEnd = 0xE6000000
    /** The banner stays up this long (ms). */
    const val bannerMs = 4000L
}
