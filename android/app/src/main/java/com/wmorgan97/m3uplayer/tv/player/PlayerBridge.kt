package com.wmorgan97.m3uplayer.tv.player

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.wmorgan97.m3uplayer.tv.Repo
import kotlinx.coroutines.launch

/**
 * PlayerActivity's way into the new interface (Java calls these). The legacy interface stays built and is the fallback:
 *  - [host] throws if the overlay can't be set up; PlayerActivity catches it and carries on with the legacy UI;
 *  - a crash while the overlay is being drawn for the first time leaves a marker ("newUiBoot"); the next player screen
 *    sees it, turns the new interface off and uses the legacy one ([crashedLastTime]).
 */
object PlayerBridge {
    fun interface Done { fun on(favourite: Boolean) }

    private fun prefs(a: AppCompatActivity) = a.getSharedPreferences("player", android.content.Context.MODE_PRIVATE)

    /** A first draw that never finished last time: the new interface is switched off (and stays off until re-enabled). */
    @JvmStatic fun crashedLastTime(a: AppCompatActivity): Boolean {
        if (!prefs(a).getBoolean("newUiBoot", false)) return false
        Log.w("M3U", "player ui: the new interface didn't finish starting last time; using the classic one")
        prefs(a).edit().putBoolean("newUiBoot", false).putBoolean("newUi", false).apply()
        return true
    }

    /** The overlay view, ready to add above the video. [fail]: test hook ("init" / "compose") to prove the fallback. */
    @JvmStatic fun host(a: AppCompatActivity, ui: PlayerUi, fail: String?): ComposeView {
        if (fail == "init") throw IllegalStateException("test: new player interface told to fail at init")
        prefs(a).edit().putBoolean("newUiBoot", true).commit()   // commit: must be on disk before a crash could happen
        return ComposeView(a).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            isFocusable = true
            setContent {
                if (fail == "compose") throw IllegalStateException("test: new player interface told to fail while composing")
                PlayerOverlay(ui)
                SideEffect { if (prefs(a).getBoolean("newUiBoot", false)) prefs(a).edit().putBoolean("newUiBoot", false).apply() }
            }
        }
    }

    /** The channel's favourite state: null when it isn't a channel in your lists (favourites don't apply). */
    @JvmStatic fun isFavourite(url: String?): Boolean? = url?.let { u -> Repo.channelByUrl(u)?.let { Repo.isFavourite(u) } }

    @JvmStatic fun toggleFavourite(a: AppCompatActivity, url: String, done: Done) {
        val c = Repo.channelByUrl(url) ?: return
        a.lifecycleScope.launch {
            runCatching { Repo.toggleFavourite(c) }.onFailure { Log.w("M3U", "favourite toggle failed: ${it.javaClass.simpleName}") }
            done.on(Repo.isFavourite(url))
        }
    }
}
