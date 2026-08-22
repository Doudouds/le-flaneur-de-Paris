package fr.parisfogofwar.data.image

import android.net.Uri
import android.text.Html
import android.util.Log
import androidx.annotation.VisibleForTesting
import fr.parisfogofwar.data.local.MonumentDao
import fr.parisfogofwar.domain.model.Monument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds a reusable image from Wikimedia Commons or POP only when its metadata
 * exposes an explicit reusable license. The result is persisted in Room so the
 * same file is not looked up again on every screen visit.
 */
@Singleton
class MonumentImageResolver @Inject constructor(
    private val dao: MonumentDao,
) {
    suspend fun ensureImage(monument: Monument) {
        val current = dao.findById(monument.id) ?: return
        // Retry entries without an image on a later app launch. This also lets
        // a future matcher recover entries that were previously unresolved.
        if (current.imageLookupCompleted && current.imageUrl != null) return

        val image = withContext(Dispatchers.IO) {
            CommonsClient().find(monument) ?: PopClient().find(monument)
        }
        dao.updateImage(
            monumentId = monument.id,
            imageUrl = image?.thumbnailUrl,
            imagePageUrl = image?.pageUrl,
            imageAuthor = image?.author,
            imageLicense = image?.license,
            imageLicenseUrl = image?.licenseUrl,
            imageSource = image?.source,
        )
    }
}

private data class ResolvedImage(
    val thumbnailUrl: String,
    val pageUrl: String,
    val author: String?,
    val license: String,
    val licenseUrl: String?,
    val searchText: String,
    val source: String = "Wikimedia Commons",
)

private class CommonsClient {
    fun find(monument: Monument): ResolvedImage? {
        val reference = monument.merimeeReference?.trim().orEmpty()
        if (reference.isNotBlank()) {
            val exact = request(
                buildUrl(
                    "generator" to "search",
                    "gsrsearch" to "insource:\"$reference\"",
                    "gsrnamespace" to "6",
                    "gsrlimit" to "10",
                ),
            ).firstOrNull()
            if (exact != null) return exact
        }

        return request(
            buildUrl(
                "generator" to "geosearch",
                "ggsprimary" to "all",
                "ggsnamespace" to "6",
                "ggsradius" to "250",
                "ggscoord" to "${monument.latitude}|${monument.longitude}",
                "ggslimit" to "20",
            ),
        ).firstOrNull { candidate -> candidateMatchesMonument(candidate, monument.name) }
    }

    private fun buildUrl(vararg params: Pair<String, String>): String {
        val query = buildList {
            add("action=query")
            add("prop=imageinfo")
            add("iiprop=url|mime|extmetadata")
            add("iiurlwidth=900")
            add("format=json")
            add("origin=*")
            params.forEach { (key, value) -> add("$key=${Uri.encode(value)}") }
        }.joinToString("&")
        return "https://commons.wikimedia.org/w/api.php?$query"
    }

    private fun request(url: String): List<ResolvedImage> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LeFlaneurDeParis/0.1 (heritage image lookup)")
        }
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            parseImages(JSONObject(body))
        } catch (error: Exception) {
            Log.w(TAG, "Wikimedia Commons lookup failed", error)
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseImages(root: JSONObject): List<ResolvedImage> {
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val images = mutableListOf<ResolvedImage>()
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val mime = info.optString("mime").lowercase(Locale.ROOT)
            if (mime !in setOf("image/jpeg", "image/png", "image/webp")) continue
            val metadata = info.optJSONObject("extmetadata") ?: continue
            val license = metadataText(metadata, "LicenseShortName") ?: continue
            if (!isReusableLicense(license)) continue
            val thumbnailUrl = info.optString("thumburl").takeIf(String::isNotBlank)
                ?: info.optString("url").takeIf(String::isNotBlank)
                ?: continue
            val title = page.optString("title").takeIf(String::isNotBlank) ?: continue
            val author = metadataText(metadata, "Artist")
                ?: metadataText(metadata, "Credit")
            val description = metadataText(metadata, "ImageDescription").orEmpty()
            images += ResolvedImage(
                thumbnailUrl = thumbnailUrl.toHttps(),
                pageUrl = "https://commons.wikimedia.org/wiki/${Uri.encode(title.replace(' ', '_'))}",
                author = author,
                license = license,
                licenseUrl = metadataText(metadata, "LicenseUrl") ?: licenseUrl(license),
                searchText = "$title $description",
            )
        }
        return images
    }

    private fun metadataText(metadata: JSONObject, key: String): String? {
        val value = metadata.optJSONObject(key)?.optString("value")?.trim().orEmpty()
        if (value.isBlank()) return null
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf(String::isNotBlank)
    }

    @VisibleForTesting
    internal fun candidateMatchesMonument(candidate: ResolvedImage, monumentName: String): Boolean {
        val haystack = normalize(candidate.searchText + " " + candidate.author.orEmpty())
        val name = normalize(monumentName)
        if (name.length >= 8 && haystack.contains(name)) return true
        val meaningfulTokens = name.split(' ')
            .filter { it.length >= 5 }
            .filterNot { it in GENERIC_TOKENS }
        if (meaningfulTokens.isEmpty()) return false
        val matches = meaningfulTokens.count(haystack::contains)
        return matches >= maxOf(1, (meaningfulTokens.size + 1) / 2)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace("œ", "oe")
        .replace("æ", "ae")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun String.toHttps(): String = if (startsWith("http://")) replaceFirst("http://", "https://") else this

    companion object {
        private const val TAG = "MonumentImageResolver"
        private val GENERIC_TOKENS = setOf(
            "ancien", "ancienne", "actuellement", "immeuble", "monument", "historique",
            "maison", "hôtel", "hotel", "paris", "église", "eglise", "chapelle",
        )

        @VisibleForTesting
        internal fun isReusableLicense(value: String): Boolean {
            val normalized = value.lowercase(Locale.ROOT)
            if (listOf("nc", "nd", "non-free", "non free", "fair use", "copyright", "gfdl").any(normalized::contains)) return false
            return normalized.contains("public domain") ||
                normalized.contains("public-domain") ||
                normalized.contains("cc0") ||
                normalized.matches(Regex(".*\\bcc[- ]?by(?:[- ]?sa)?\\b.*"))
        }

        @VisibleForTesting
        internal fun isReusablePopRights(value: String): Boolean {
            val normalized = value.lowercase(Locale.ROOT)
            if (normalized.isBlank()) return false
            return listOf(
                "public domain",
                "domaine public",
                "cc0",
                "cc by",
                "cc-by",
                "cc by-sa",
                "cc-by-sa",
                "creative commons attribution",
                "licence ouverte",
                "open licence",
            ).any(normalized::contains)
        }

        private fun licenseUrl(value: String): String? {
            val normalized = value.lowercase(Locale.ROOT)
            if (normalized.contains("cc0")) return "https://creativecommons.org/publicdomain/zero/1.0/"
            val sa = normalized.contains("sa")
            val version = Regex("(\\d+(?:\\.\\d+)?)").find(normalized)?.groupValues?.get(1) ?: return null
            return "https://creativecommons.org/licenses/${if (sa) "by-sa" else "by"}/$version/"
        }
    }
}

private class PopClient {
    fun find(monument: Monument): ResolvedImage? {
        val reference = monument.merimeeReference?.trim().orEmpty()
        if (reference.isBlank()) return null

        val noticeUrl = "https://pop.culture.gouv.fr/notice/merimee/${Uri.encode(reference)}"
        val apiUrl = "https://api.pop.culture.gouv.fr/notices/merimee/${Uri.encode(reference)}/public"
        val notice = request(apiUrl)?.optJSONObject("notice") ?: return null
        val images = notice.optJSONArray("MEMOIRE") ?: return null

        for (index in 0 until images.length()) {
            val image = images.optJSONObject(index) ?: continue
            val rights = image.optString("copy").trim()
            if (!CommonsClient.isReusablePopRights(rights)) continue
            val imagePath = image.optString("url").trim()
            if (imagePath.isBlank()) continue

            return ResolvedImage(
                thumbnailUrl = imagePath.toPopImageUrl(),
                pageUrl = noticeUrl,
                author = null,
                license = rights,
                licenseUrl = null,
                searchText = "${image.optString("name")} $reference",
                source = "POP — Plateforme ouverte du patrimoine",
            )
        }
        return null
    }

    private fun request(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LeFlaneurDeParis/0.1 (heritage image lookup)")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(body)
        } catch (error: Exception) {
            Log.w("MonumentImageResolver", "POP image lookup failed", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun String.toPopImageUrl(): String = if (startsWith("http://") || startsWith("https://")) {
        if (startsWith("http://")) replaceFirst("http://", "https://") else this
    } else {
        "$POP_BUCKET_URL${trimStart('/')}"
    }

    private companion object {
        const val POP_BUCKET_URL = "https://popcorn-prd-perf-assets.s3.gra.io.cloud.ovh.net/"
    }
}
