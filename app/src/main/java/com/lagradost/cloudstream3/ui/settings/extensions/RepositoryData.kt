package com.lagradost.cloudstream3.ui.settings.extensions

import com.fasterxml.jackson.annotation.JsonProperty

const val REPOSITORIES_KEY = "repositories_key"

/**
 * Compatibility shim for the official app's RepositoryData
 * (ui/settings/extensions). Field order mirrors the official class so
 * the generated constructor matches what extensions were compiled
 * against: RepositoryData(iconUrl, name, url, description).
 */
data class RepositoryData(
    @JsonProperty("iconUrl") val iconUrl: String? = null,
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("description") val description: String? = null,
)
