package com.drsumon.fastidm

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class VideoPlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mediaUri: Uri? = intent.data
        val title = intent.getStringExtra("title") ?: "Video"
        if (mediaUri == null) { finish(); return }

        player = ExoPlayer.Builder(this).build().also {
            it.setMediaItem(MediaItem.fromUri(mediaUri))
            it.prepare()
            it.playWhenReady = true
        }

        setContent {
            var fullscreen by remember { mutableStateOf(false) }

            fun setFullscreen(enabled: Boolean) {
                fullscreen = enabled
                requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                if (enabled) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            BackHandler(enabled = fullscreen) { setFullscreen(false) }

            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (fullscreen) {
                        AndroidView(
                            factory = { context ->
                                PlayerView(context).apply {
                                    player = this@VideoPlayerActivity.player
                                    useController = true
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    keepScreenOn = true
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        TextButton(
                            onClick = { setFullscreen(false) },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).statusBarsPadding()
                        ) { Text("Exit full screen", color = Color.White) }
                    } else {
                        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
                            Row(
                                Modifier.fillMaxWidth().background(Color(0xFF17131F)).padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { finish() }) { Text("← Back", color = Color.White) }
                                Text(title, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                TextButton(onClick = { setFullscreen(true) }) { Text("⛶", color = Color.White) }
                            }
                            AndroidView(
                                factory = { context ->
                                    PlayerView(context).apply {
                                        player = this@VideoPlayerActivity.player
                                        useController = true
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        keepScreenOn = true
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
