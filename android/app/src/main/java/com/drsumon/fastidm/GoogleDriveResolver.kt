package com.drsumon.fastidm

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

data class ResolvedGoogleDriveDownload(
    val url: String,
    val cookieHeader: String?,
    val contentDisposition: String?,
    val contentType: String?
)

object GoogleDriveResolver {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    fun resolve(sharedUrl: String): ResolvedGoogleDriveDownload {
        val cleanUrl = sharedUrl.trim()
        if (cleanUrl.contains("drive.usercontent.google.com/download") && cleanUrl.contains("confirm=")) {
            return ResolvedGoogleDriveDownload(cleanUrl, null, null, null)
        }

        val fileId = extractFileId(cleanUrl)
            ?: throw IllegalArgumentException("Google Drive file ID পাওয়া যায়নি। Share link-টি পরীক্ষা করুন।")
        val cookies = linkedMapOf<String, String>()

        // 1. Primary entry point: docs.google.com/uc is the standard Google Drive direct endpoint
        var currentUrl = "https://docs.google.com/uc?export=download&id=$fileId"
        var confirmationAttempted = false

        repeat(MAX_REDIRECTS) {
            val urlObj = try { URL(currentUrl) } catch (e: Exception) {
                throw IllegalStateException("Invalid Google Drive URL: $currentUrl")
            }

            val connection = (urlObj.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (isGoogleHost(urlObj.host) && cookies.isNotEmpty()) {
                    setRequestProperty("Cookie", formatCookies(cookies))
                }
            }

            try {
                val code = connection.responseCode
                collectCookies(connection, cookies)

                if (code in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Google Drive redirect Location missing.")
                    currentUrl = URL(urlObj, location).toString()
                    return@repeat
                }

                if (code !in 200..299) {
                    // Fallback to drive.usercontent.google.com directly if docs.google.com fails
                    if (!confirmationAttempted && !currentUrl.contains("drive.usercontent.google.com")) {
                        currentUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                        confirmationAttempted = true
                        return@repeat
                    }
                    throw IllegalStateException(when (code) {
                        403 -> "Google Drive access denied বা download quota শেষ। Sharing permission পরীক্ষা করুন।"
                        404 -> "Google Drive ফাইলটি পাওয়া যায়নি। ফাইলটি মুছে ফেলা হতে পারে।"
                        else -> "Google Drive server response error: HTTP $code"
                    })
                }

                val type = connection.contentType.orEmpty()
                val disposition = connection.getHeaderField("Content-Disposition")

                // If HTML confirmation page (e.g. virus scan warning for files > 100MB)
                if (type.contains("text/html", true)) {
                    val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                        it.readTextLimited(MAX_HTML_CHARS)
                    }
                    val lowerHtml = html.lowercase()
                    if ("quota" in lowerHtml && ("exceeded" in lowerHtml || "limit" in lowerHtml)) {
                        throw IllegalStateException("Google Drive download quota শেষ। কিছুক্ষণ পর আবার চেষ্টা করুন।")
                    }

                    val nextUrl = extractConfirmationUrl(html, currentUrl, fileId, cookies)
                    if (!nextUrl.isNullOrBlank() && nextUrl != currentUrl) {
                        currentUrl = nextUrl
                        confirmationAttempted = true
                        return@repeat
                    }

                    // If no explicit confirmation link extracted, return current URL with cookies
                    return ResolvedGoogleDriveDownload(currentUrl, formatCookies(cookies), disposition, type)
                }

                // File binary stream detected (APK, PDF, Video, Audio, ZIP, etc.)
                // Return immediately without downloading file content in this resolver
                return ResolvedGoogleDriveDownload(currentUrl, formatCookies(cookies), disposition, type)
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Google Drive অতিরিক্ত redirect দিয়েছে; download link resolve করা যায়নি।")
    }

    private fun extractConfirmationUrl(
        html: String,
        baseUrl: String,
        fileId: String,
        cookies: Map<String, String>
    ): String? {
        // 1. Try finding <form id="download-form" action="..." method="...">
        val formMatch = Regex("""<form\b[^>]*id=["']download-form["'][^>]*action=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<form\b[^>]*action=["']([^"']+)["'][^>]*id=["']download-form["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<form\b[^>]*action=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)

        if (formMatch != null) {
            val action = formMatch.groupValues[1].replace("&amp;", "&")
            val formFull = html.substring(formMatch.range.first, minOf(html.length, formMatch.range.first + 4000))
            val inputRegex = Regex("""<input\b[^>]*name=["']([^"']+)["'][^>]*value=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            val inputRegexRev = Regex("""<input\b[^>]*value=["']([^"']*)["'][^>]*name=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

            val params = mutableListOf<String>()
            for (m in inputRegex.findAll(formFull)) {
                val name = m.groupValues[1]
                val value = m.groupValues[2]
                if (name.isNotBlank()) params.add("${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}")
            }
            for (m in inputRegexRev.findAll(formFull)) {
                val value = m.groupValues[1]
                val name = m.groupValues[2]
                if (name.isNotBlank() && !params.any { it.startsWith("${name}=") }) {
                    params.add("${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}")
                }
            }

            if (params.isNotEmpty()) {
                val fullUrl = if (action.startsWith("http")) action else URL(URL(baseUrl), action).toString()
                return if (fullUrl.contains("?")) "$fullUrl&${params.joinToString("&")}" else "$fullUrl?${params.joinToString("&")}"
            }
        }

        // 2. Try <a id="uc-download-link" href="...">
        val linkMatch = Regex("""<a\b[^>]*id=["']uc-download-link["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<a\b[^>]*href=["']([^"']+)["'][^>]*id=["']uc-download-link["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""href=["'](/uc\?export=download[^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""href=["'](https://drive\.usercontent\.google\.com/download[^"']+)["']""", RegexOption.IGNORE_CASE).find(html)

        if (linkMatch != null) {
            val href = linkMatch.groupValues[1].replace("&amp;", "&")
            return if (href.startsWith("http")) href else URL(URL(baseUrl), href).toString()
        }

        // 3. Try token in cookie or text
        val token = confirmationToken(html, cookies)
        if (!token.isNullOrBlank()) {
            return "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=$token"
        }

        return null
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
                URLDecoder.decode(utf8Match.groupValues[1], "UTF-8").trim('"', '\'')
            } catch (_: Exception) { null }
        }
        val standardMatch = Regex("""filename=["']?([^"';]+)["']?""", RegexOption.IGNORE_CASE).find(disposition)
        if (standardMatch != null) {
            return standardMatch.groupValues[1].trim('"', '\'')
        }
        return null
    }

    fun extractFileId(raw: String): String? {
        val text = raw.trim()
        val uri = try { Uri.parse(text) } catch (_: Exception) { null }
        val queryId = uri?.getQueryParameter("id")
        if (!queryId.isNullOrBlank() && queryId.matches(Regex("[A-Za-z0-9_-]{10,}"))) {
            return queryId
        }

        val patterns = listOf(
            Regex("""/file/d/([A-Za-z0-9_-]{10,})"""),
            Regex("""/d/([A-Za-z0-9_-]{10,})"""),
            Regex("""[?&]id=([A-Za-z0-9_-]{10,})"""),
            Regex("""/(?:file|document|spreadsheets|presentation)/d/([A-Za-z0-9_-]{10,})""")
        )
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1]
        }
        return null
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

    private fun formatCookies(cookies: Map<String, String>): String? {
        return if (cookies.isNotEmpty()) {
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else null
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

    private const val MAX_REDIRECTS = 6
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 6_000
    private const val MAX_HTML_CHARS = 200_000
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
