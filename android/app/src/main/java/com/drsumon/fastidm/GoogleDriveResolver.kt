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

object GoogleDriveResolver {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun resolve(sharedUrl: String): ResolvedGoogleDriveDownload {
        val fileId = extractFileId(sharedUrl)
            ?: throw IllegalArgumentException("Google Drive file ID পাওয়া যায়নি। Share link-টি পরীক্ষা করুন।")
        val cookies = linkedMapOf<String, String>()
        var currentUrl = Uri.parse("https://drive.google.com/uc").buildUpon()
            .appendQueryParameter("export", "download")
            .appendQueryParameter("id", fileId).build().toString()
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
                setRequestProperty("Range", "bytes=0-0")
                if (isGoogleHost(URL(currentUrl).host) && cookies.isNotEmpty()) {
                    setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "\${it.key}=\${it.value}" })
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
                    throw IllegalStateException(when (code) {
                        403 -> "Google Drive access denied বা download quota শেষ। Sharing permission পরীক্ষা করুন।"
                        404 -> "Google Drive ফাইলটি পাওয়া যায়নি।"
                        else -> "Google Drive server error: HTTP $code"
                    })
                }

                val type = connection.contentType.orEmpty()
                val disposition = connection.getHeaderField("Content-Disposition")
                if (type.contains("text/html", true) || (disposition.isNullOrBlank() && type.isBlank())) {
                    val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                        it.readTextLimited(MAX_HTML_CHARS)
                    }
                    val token = confirmationToken(html, cookies)
                    if (!confirmationAttempted && !token.isNullOrBlank()) {
                        confirmationAttempted = true
                        currentUrl = Uri.parse("https://drive.usercontent.google.com/download").buildUpon()
                            .appendQueryParameter("id", fileId)
                            .appendQueryParameter("export", "download")
                            .appendQueryParameter("confirm", token).build().toString()
                        return@repeat
                    }
                    if ("quota" in html.lowercase()) {
                        throw IllegalStateException("Google Drive download quota শেষ। পরে আবার চেষ্টা করুন।")
                    }
                    throw IllegalStateException(
                        "Google Drive সরাসরি download link দেয়নি। ফাইলটি ‘Anyone with the link’ করা আছে কি না দেখুন।"
                    )
                }

                val safeCookies = if (isGoogleHost(URL(currentUrl).host) && cookies.isNotEmpty()) {
                    cookies.entries.joinToString("; ") { "\${it.key}=\${it.value}" }
                } else null
                return ResolvedGoogleDriveDownload(currentUrl, safeCookies, disposition, type)
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Google Drive অতিরিক্ত redirect দিয়েছে; download link resolve করা যায়নি।")
    }

    fun isGoogleDrive(raw: String): Boolean {
        val host = try { Uri.parse(raw.trim()).host?.lowercase().orEmpty().removePrefix("www.") } catch (_: Exception) { return false }
        return host == "drive.google.com" || host == "docs.google.com" || host == "drive.usercontent.google.com"
    }

    fun extractFilenameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val utf8Match = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(disposition)
        if (utf8Match != null) {
            return try {
                java.net.URLDecoder.decode(utf8Match.groupValues[1], "UTF-8").trim('"', '\'')
            } catch (_: Exception) { null }
        }
        val standardMatch = Regex("""filename=["']?([^"';]+)["']?""", RegexOption.IGNORE_CASE).find(disposition)
        if (standardMatch != null) {
            return standardMatch.groupValues[1].trim('"', '\'')
        }
        return null
    }

    fun extractFileId(raw: String): String? {
        val uri = try { Uri.parse(raw.trim()) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
        if (host != "drive.google.com" && host != "docs.google.com" &&
            host != "drive.usercontent.google.com") return null
        val segments = uri.pathSegments
        val pathId = segments.windowed(2).firstOrNull { it[0] == "d" }?.getOrNull(1)
            ?: Regex("/(?:file|document|spreadsheets|presentation)/d/([^/]+)")
                .find(uri.path.orEmpty())?.groupValues?.getOrNull(1)
        return (pathId ?: uri.getQueryParameter("id"))
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{10,}")) }
    }

    private fun isGoogleHost(host: String): Boolean {
        val value = host.lowercase()
        return value == "google.com" || value.endsWith(".google.com") ||
            value == "googleusercontent.com" || value.endsWith(".googleusercontent.com")
    }

    private fun collectCookies(connection: HttpURLConnection, cookies: MutableMap<String, String>) {
        connection.headerFields.entries
            .filter { it.key.equals("Set-Cookie", true) }
            .flatMap { it.value.orEmpty() }
            .forEach { header ->
                val pair = header.substringBefore(';').split("=", limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank()) cookies[pair[0]] = pair[1]
            }
    }

    private fun confirmationToken(html: String, cookies: Map<String, String>): String? {
        cookies.entries.firstOrNull { it.key.startsWith("download_warning") }?.value
            ?.takeIf { it.isNotBlank() }?.let { return it }
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
