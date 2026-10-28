package com.wmorgan97.m3uplayer.tv

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.wmorgan97.m3uplayer.PlayerHolder
import kotlinx.coroutines.delay

/**
 * TiviMate-style channel preview: the highlighted channel plays in a small window once the highlight rests on it.
 * Only one stream is ever open (providers often allow one connection): it stops before full-screen playback starts,
 * when the app goes to the background and when you leave the screen.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object Preview {
    /** adb test hook: when set, previews play this stream instead of the channel's (so tests never open the provider). */
    @Volatile var testUrl: String? = null
    /** The channel in the preview window: the last one you watched, until OK picks another (TiviMate's way). */
    var channel by mutableStateOf<Channel?>(null); private set
    var list: List<Channel> = emptyList(); private set
    fun select(c: Channel, l: List<Channel>) { channel = c; list = l }
    /** The preview's channel, falling back to the last channel watched (once the channel list is loaded). */
    fun current(): Channel? = channel ?: Repo.lastChannelUrl?.let { Repo.channelByUrl(it) }?.also { c -> select(c, Repo.live?.channels?.filter { it.group == c.group }?.takeIf { c in it } ?: listOf(c)) }
    private var player: ExoPlayer? = null
    private var url: String? = null

    fun player(c: Context): ExoPlayer = player ?: PlayerHolder.create(c).also { player = it }

    fun play(c: Context, ch: Channel) {
        val src = testUrl ?: ch.url
        if (src == url) return
        url = src
        val p = player(c)
        var agent = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36"
        val headers = HashMap<String, String>()
        ch.headers.forEach { (k, v) -> if (k.equals("User-Agent", true)) agent = v else headers[k] = v }
        val http = DefaultHttpDataSource.Factory().setUserAgent(agent).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000).setReadTimeoutMs(15_000).setDefaultRequestProperties(headers)
        val media = MediaItem.Builder().setUri(src).apply { if (src.lowercase().contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        p.setMediaSource(DefaultMediaSourceFactory(http, PlayerHolder.extractors()).createMediaSource(media))
        p.prepare(); p.play()
    }

    /** Closes the stream (keeps the player for the next preview). */
    /** Gives up the running player (for the full-screen player to carry on with), or null if nothing is playing. */
    fun handOff(): ExoPlayer? {
        val p = player ?: return null
        val ok = url != null && p.playerError == null && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        if (!ok) return null
        player = null; url = null
        return p
    }
    fun stop() { url = null; player?.let { runCatching { it.stop(); it.clearMediaItems() } } }
    /** Frees the player and its decoder entirely (before full-screen playback). */
    fun release() { url = null; player?.let { runCatching { it.release() } }; player = null }
}

/** The preview window: plays [channel] (the one picked with OK, else the last watched). */
@Composable
fun PreviewBox(channel: Channel?, resumeTick: Int, modifier: Modifier = Modifier) {
    if (!Repo.preview) return
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(channel?.url, resumeTick) {
        playing = false; failed = false
        if (channel == null) { Preview.stop(); return@LaunchedEffect }
        delay(150)
        Preview.play(ctx, channel)
        val p = Preview.player(ctx)
        view?.player = p
        // The name card stays until there's a picture; after 15 s (or an error) the preview gives up and closes the stream.
        repeat(150) { if (p.playbackState == Player.STATE_READY && p.videoSize.width > 0) { playing = true; return@LaunchedEffect }; if (p.playerError != null) { failed = true; Preview.stop(); return@LaunchedEffect }; delay(100) }
        failed = true; Preview.stop()
    }
    DisposableEffect(Unit) { onDispose { Preview.stop() } }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Tv.surface)) {
        AndroidView(factory = { c ->
            PlayerView(c).apply {
                useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK); setKeepContentOnPlayerReset(false)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                player = Preview.player(c)
            }.also { view = it }
        }, modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
        if (!playing) Box(Modifier.fillMaxSize().background(Tv.surface), contentAlignment = Alignment.Center) {
            Text(if (channel == null) "Press OK on a channel to preview it" else if (failed) "${channel.name}\nNo picture" else channel.name, fontSize = Tv.caption, color = Tv.muted, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
