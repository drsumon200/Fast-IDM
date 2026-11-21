package com.drsumon.fastidm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    FastIdmHome(initialUrl = extractSharedText(intent))
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
    private fun extractSharedText(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND) return ""
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        return Regex("""https?://\S+""").find(shared)?.value
            ?.trimEnd('.', ',', ')', ']', '>').orEmpty()
    }
}

@Composable
private fun FastIdmHome(initialUrl: String) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var message by rememberSaveable { mutableStateOf("Ready for a download link") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Fast IDM", style = MaterialTheme.typography.headlineLarge)
        Text("Android Edition · 0.1.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text("New download", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = url, onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Paste a URL") },
                    placeholder = { Text("https://example.com/file.mp4") }, minLines = 2)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    message = if (url.trim().startsWith("https://") || url.trim().startsWith("http://"))
                        "Link accepted. Download engine is coming in Phase 2."
                    else "Please enter a valid http:// or https:// URL."
                }, modifier = Modifier.fillMaxWidth()) { Text("Add to downloads") }
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Downloads", style = MaterialTheme.typography.titleLarge)
        Card { Column(Modifier.padding(16.dp)) {
            Text("No downloads yet", style = MaterialTheme.typography.titleMedium)
            Text("Your active downloads and progress will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        Text("Planned features", style = MaterialTheme.typography.titleLarge)
        FeatureRow("Direct downloads", "Multi-connection, retry and resume")
        FeatureRow("Video downloads", "yt-dlp format and quality selection")
        FeatureRow("Share to Fast IDM", "Receive links from browser share menus")
        FeatureRow("Download history", "Saved files and completed tasks")
    }
}
@Composable
private fun FeatureRow(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
