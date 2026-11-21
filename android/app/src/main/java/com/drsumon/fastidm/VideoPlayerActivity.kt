package com.drsumon.fastidm

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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
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
            BackHandler { finish() }
            MaterialTheme {
                Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().background(Color(0xFF17131F)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        TextButton(onClick = { finish() }) { Text("← Back", color = Color.White) }
                        Text(title, color = Color.White, maxLines = 1, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
                    }
                    AndroidView(
                        factory = { context -> PlayerView(context).apply { player = this@VideoPlayerActivity.player; useController = true } },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
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
