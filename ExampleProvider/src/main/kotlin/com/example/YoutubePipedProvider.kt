package com.looknorook.youtubepiped

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

class YouTubePipedProvider : MainAPI() {
    override var name = "YouTube (Piped)"
    override var mainUrl = "https://piped.video" // you can change to another Piped instance
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    override val mainPage = mainPageOf(
        Pair("trending", "Trending"),
        Pair("popular", "Popular")
    )

    private fun pipedGet(path: String, params: Map<String, String> = emptyMap()): HttpResponse {
        return app.get("$mainUrl$path", params = params)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val endpoint = when (request.data) {
            "trending" -> "/api/v1/trending"
            "popular" -> "/api/v1/popular"
            else -> "/api/v1/trending"
        }
        val res = pipedGet(endpoint)
        val arr = JSONArray(res.text)
        val items = (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            val title = o.optString("title")
            val videoId = o.optString("id")
            val thumbnail = o.optString("thumbnail")
            if (title.isBlank() || videoId.isBlank()) return@mapNotNull null
            MovieSearchResponse(
                title = title,
                url = "$mainUrl/watch?v=$videoId",
                apiName = this.name,
                type = TvType.Movie,
                posterUrl = thumbnail
            )
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = pipedGet("/api/v1/search", mapOf("q" to query))
        val arr = JSONArray(res.text)
        val out = arrayListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "video") continue
            val title = o.optString("title")
            val videoId = o.optString("id")
            val thumbnail = o.optString("thumbnail")
            val channelName = o.optString("uploaderName")
            if (title.isBlank() || videoId.isBlank()) continue
            out.add(
                MovieSearchResponse(
                    title = title,
                    url = "$mainUrl/watch?v=$videoId",
                    apiName = this.name,
                    type = TvType.Movie,
                    posterUrl = thumbnail
                ).apply { plot = "Uploader: $channelName" }
            )
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = Regex("[?&]v=([a-zA-Z0-9_-]{6,})").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("No videoId")
        val res = pipedGet("/api/v1/streams/$videoId")
        val obj = JSONObject(res.text)
        val title = obj.optString("title", videoId)
        val description = obj.optString("description")
        val thumbnail = obj.optString("thumbnailUrl")
        val uploader = obj.optString("uploader")

        return newMovieLoadResponse(title, url, TvType.Movie, mutableListOf()) {
            posterUrl = thumbnail
            plot = description
            addActors(listOfNotNull(uploader))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = Regex("[?&]v=([a-zA-Z0-9_-]{6,})").find(data)?.groupValues?.get(1)
            ?: return false
        val res = pipedGet("/api/v1/streams/$videoId")
        val obj = JSONObject(res.text)

        // HLS streams
        obj.optJSONArray("hls")?.let { hls ->
            for (i in 0 until hls.length()) {
                val h = hls.optJSONObject(i) ?: continue
                val url = h.optString("url")
                val quality = h.optString("quality", "HLS")
                if (url.isNotBlank()) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "HLS $quality",
                            url = url,
                            referer = mainUrl,
                            quality = getQualityFromName(quality),
                            isM3u8 = true
                        )
                    )
                }
            }
        }

        // Adaptive formats
        obj.optJSONArray("adaptiveFormats")?.let { adaptive ->
            for (i in 0 until adaptive.length()) {
                val f = adaptive.optJSONObject(i) ?: continue
                val url = f.optString("url")
                val qualityLabel = f.optString("qualityLabel", "Adaptive")
                val mimeType = f.optString("type")
                if (url.isNotBlank()) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "Adaptive $qualityLabel",
                            url = url,
                            referer = mainUrl,
                            quality = getQualityFromName(qualityLabel),
                            isM3u8 = mimeType.contains("application/x-mpegURL", true)
                        )
                    )
                }
            }
        }

        // Subtitles
        obj.optJSONArray("captions")?.let { captions ->
            for (i in 0 until captions.length()) {
                val c = captions.optJSONObject(i) ?: continue
                val cUrl = c.optString("url")
                val lang = c.optString("language")
                if (cUrl.isNotBlank()) {
                    subtitleCallback(SubtitleFile(lang.ifBlank { "Unknown" }, cUrl))
                }
            }
        }

        return true
    }

    private fun getQualityFromName(name: String): Int {
        return Regex("(\\d{3,4})p").find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
