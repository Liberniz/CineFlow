package com.cineflow.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Native playback screen (ExoPlayer). Launched by the JS shim via
 * CineflowNative.playVideo({url, kind, title}); re-launching with a new
 * intent (episode switch) swaps the media item in place.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var currentUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        playerView = PlayerView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        setContentView(playerView)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        if (url == currentUrl && player != null) return
        currentUrl = url
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()

        ensurePlayer()
        val mime = mimeForKind(kind)
        val item = MediaItem.Builder().setUri(url).apply {
            if (mime != null) setMimeType(mime)
        }.build()
        val p = player ?: return
        p.setMediaItem(item)
        p.prepare()
        p.play()
    }

    private fun ensurePlayer() {
        if (player != null) return
        // Reuse the user's proxy setting for segment fetching.
        val okClient = Net.client(Store(this).proxy)
        val dataSourceFactory = OkHttpDataSource.Factory(okClient)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(
                            this@PlayerActivity,
                            "播放失败：${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }
    }

    private fun mimeForKind(kind: String): String? = when (kind) {
        "hls" -> MimeTypes.APPLICATION_M3U8
        "dash" -> MimeTypes.APPLICATION_MPD
        "mp4" -> MimeTypes.VIDEO_MP4
        "webm" -> MimeTypes.VIDEO_WEBM
        "mpegts" -> MimeTypes.VIDEO_MP2T
        else -> null // let ExoPlayer sniff (covers mp4/webm/ogg and others)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        // Only auto-resume if we were playing before pause.
        if (player?.playbackState == Player.STATE_READY) player?.play()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
