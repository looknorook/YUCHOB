package com.looknorook.youtubepiped

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManifest

@PluginManifest(
    name = "YouTube via Piped",
    description = "Unofficial YouTube provider using Piped API",
    version = 1,
    author = "John"
)
class YouTubePipedPlugin : Plugin() {
    override fun load() {
        registerMainAPI(YouTubePipedProvider())
    }
}
