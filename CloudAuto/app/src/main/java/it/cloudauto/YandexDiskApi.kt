package it.cloudauto

import android.net.Uri
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

data class DiskEntry(
    val name: String,
    /** Percorso WebDAV, es. "/Musica/brano.mp3" ("/" è la radice) */
    val path: String,
    val isDir: Boolean,
    val mimeType: String?,
)

/** Client minimale WebDAV per Yandex Disk (sola lettura), con login + password per app. */
class YandexDiskApi(private val accountProvider: () -> Account?) {

    class AuthException : IOException("Login o password per app non validi")

    /** Dove leggere davvero un file: URL finale ed eventuali header (Authorization). */
    data class Source(val url: String, val headers: Map<String, String>)

    private val client = OkHttpClient()
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val base = "https://webdav.yandex.com".toHttpUrl()
    private val cache = ConcurrentHashMap<String, Pair<Source, Long>>()

    private fun authHeader(): String {
        val account = accountProvider() ?: throw AuthException()
        return Credentials.basic(account.login, account.password)
    }

    private fun urlFor(path: String): HttpUrl {
        val segments = path.trim('/')
        return if (segments.isEmpty()) base else base.newBuilder().addPathSegments(segments).build()
    }

    /** Elenca cartelle e file audio direttamente contenuti in [path]. */
    fun list(path: String): List<DiskEntry> {
        val body = PROPFIND_BODY.toRequestBody("application/xml".toMediaType())
        val request = Request.Builder()
            .url(urlFor(path))
            .method("PROPFIND", body)
            .header("Authorization", authHeader())
            .header("Depth", "1")
            .build()
        val xml = client.newCall(request).execute().use { response ->
            if (response.code == 401) throw AuthException()
            if (response.code != 207) throw IOException("HTTP ${response.code}")
            response.body?.bytes() ?: throw IOException("Risposta vuota")
        }

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        } catch (e: Exception) {
            throw IOException("Risposta WebDAV non valida", e)
        }

        val self = path.trim('/')
        val out = mutableListOf<DiskEntry>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val el = responses.item(i) as Element
            val href = el.getElementsByTagNameNS(DAV, "href").item(0)?.textContent?.trim() ?: continue
            val rawPath = if (href.startsWith("http")) java.net.URI(href).rawPath else href
            val full = Uri.decode(rawPath).trim('/')
            if (full == self) continue // la cartella stessa

            val name = full.substringAfterLast('/')
            val isDir = el.getElementsByTagNameNS(DAV, "collection").length > 0
            val mime = el.getElementsByTagNameNS(DAV, "getcontenttype").item(0)?.textContent?.trim()
            val isAudio = !isDir &&
                (mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in AUDIO_EXT)
            if (isDir || isAudio) {
                out += DiskEntry(
                    name = name,
                    path = "/$full",
                    isDir = isDir,
                    mimeType = mime?.takeIf { it.startsWith("audio/") },
                )
            }
        }
        return out.sortedWith(
            compareByDescending<DiskEntry> { it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }

    /**
     * Risolve da dove leggere il file. Se Yandex risponde con un redirect, usa il link
     * temporaneo (senza credenziali); altrimenti legge dal WebDAV con l'header Authorization.
     */
    fun resolve(path: String): Source {
        val now = System.currentTimeMillis()
        cache[path]?.let { (source, ts) -> if (now - ts < 5 * 60_000) return source }

        val auth = authHeader()
        val url = urlFor(path)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Range", "bytes=0-0")
            .build()
        val source = noRedirectClient.newCall(request).execute().use { r ->
            when {
                r.isRedirect -> {
                    val location = r.header("Location") ?: throw IOException("Redirect senza destinazione")
                    val target = r.request.url.resolve(location)?.toString() ?: location
                    Source(target, emptyMap())
                }
                r.code == 401 -> throw AuthException()
                r.isSuccessful -> Source(url.toString(), mapOf("Authorization" to auth))
                else -> throw IOException("HTTP ${r.code}")
            }
        }
        cache[path] = source to now
        return source
    }

    private companion object {
        const val DAV = "DAV:"
        val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "m4b")
        const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"""
    }
}
