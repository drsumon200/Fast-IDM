package com.drsumon.fastidm

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

data class ResolvedGoogleDriveDownload(
    val url: String,
    val cookieHeader: String?,
    val contentDisposition: String?,
    val contentType: String?
)

/**
 * Resolves public Google Drive share links to a real file response before
 * Android DownloadManager starts. It deliberately rejects HTML preview/error
 * pages so they are never saved as if they were the requested file.
 */
object GoogleDriveResolver {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun resolve(sharedUrl: String): ResolvedGoogleDriveDownload {
        val fileId = extractFileId(sharedUrl)
            ?: throw IllegalArgumentException("Google Drive file ID পাওয়া যায়নি। Share link-টি পরীক্ষা করুন।")
        val cookies = linkedMapOf<String, String>()
        var currentUrl = Uri.parse("https://drive.google.com/uc")
            .buildUpon()
            .appendQueryParameter("export", "download")
            .appendQueryParameter("id", fileId)
            .build().toString()
        var confirmationAttempted = false

        repeat(MAX_REDIRECTS) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.8")
                // Probe only one byte so resolving a large Drive file never starts a hidden full download.
                setRequestProperty("Range", "bytes=0-0")
                val requestHost = URL(currentUrl).host.lowercase()
                val isGoogleHost = requestHost == "google.com" || requestHost.endsWith(".google.com") ||
                    requestHost == "googleusercontent.com" || requestHost.endsWith(".googleusercontent.com")
                if (isGoogleHost && cookies.isNotEmpty()) {
                    setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }

            try {
                val code = connection.responseCode
                collectCookies(connection, cookies)
                if (code in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Google Drive redirect-এর Location পাওয়া যায়নি।")
                    currentUrl = URL(URL(currentUrl), location).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    throw IllegalStateException(
                        when (code) {
                            403 -> "Google Drive access denied বা download quota শেষ। ফাইলটি ‘Anyone with the link’ করে আবার চেষ্টা করুন।"
                            404 -> "Google Drive ফাইলটি পাওয়া যায়নি।"
                            else -> "Google Drive server error: HTTP $code"
                        }
                    )
                }

                val type = connection.contentType.orEmpty()
                val disposition = connection.getHeaderField("Content-Disposition")
                if (type.contains("text/html", ignoreCase = true) || disposition.isNullOrBlank() && type.isBlank()) {
                    val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readTextLimited(MAX_HTML_CHARS) }
                    val token = confirmationToken(html, cookies)
                    if (!confirmationAttempted && !token.isNullOrBlank()) {
                        confirmationAttempted = true
                        currentUrl = Uri.parse("https://drive.usercontent.google.com/download")
                            .buildUpon()
                            .appendQueryParameter("id", fileId)
                            .appendQueryParameter("export", "download")
                            .appendQueryParameter("confirm", token)
                            .build().toString()
                        return@repeat
                    }
                    val lower = html.lowercase()
                    if ("quota" in lower || "download quota" in lower) {
                        throw IllegalStateException("Google Drive download quota শেষ। কিছুক্ষণ পরে আবার চেষ্টা করুন।")
                    }
                    throw IllegalStateException(
                        "Google Drive ফাইলের সরাসরি download পাওয়া যায়নি। Sharing permission ‘Anyone with the link’ আছে কি না দেখুন।"
                    )
                }

                return ResolvedGoogleDriveDownload(
                    url = currentUrl,
                    cookieHeader = cookies.takeIf { it.isNotEmpty() }
                        ?.entries?.joinToString("; ") { "${it.key}=${it.value}" },
                    contentDisposition = disposition,
                    contentType = type
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Google Drive অতিরিক্ত redirect দিয়েছে; download link resolve করা যায়নি।")
    }

    private fun extractFileId(raw: String): String? {
        val uri = try { Uri.parse(raw.trim()) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase().orEmpty()
        if (host != "drive.google.com" && host != "docs.google.com") return null
        val segments = uri.pathSegments
        val fromPath = segments.windowed(2).firstOrNull { it[0] == "d" }?.getOrNull(1)
        return (fromPath ?: uri.getQueryParameter("id"))
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{10,}")) }
    }

    private fun collectCookies(connection: HttpURLConnection, cookies: MutableMap<String, String>) {
        connection.headerFields.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value.orEmpty() }
            .forEach { header ->
                val pair = header.substringBefore(';').split("=", limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank()) cookies[pair[0]] = pair[1]
            }
    }

    private fun confirmationToken(html: String, cookies: Map<String, String>): String? {
        val cookieToken = cookies.entries.firstOrNull { it.key.startsWith("download_warning") }?.value
        if (!cookieToken.isNullOrBlank()) return cookieToken
        val patterns = listOf(
            Regex("""[?&]confirm=([A-Za-z0-9_-]+)"""),
            Regex("""name=["']confirm["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""value=["']([^"']+)["'][^>]*name=["']confirm["']""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
    }

    private fun java.io.BufferedReader.readTextLimited(maxChars: Int): String {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (out.length < maxChars) {
            val count = read(buffer, 0, minOf(buffer.size, maxChars - out.length))
            if (count <= 0) break
            out.append(buffer, 0, count)
        }
        return out.toString()
    }

    private const val MAX_REDIRECTS = 8
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_HTML_CHARS = 1_000_000
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
