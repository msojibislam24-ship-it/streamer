package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compatibility shim for the official app's RepositoryManager and plugin
 * wrapper types (plugins/RepositoryManager.kt).
 *
 * Only the surface extensions actually call is implemented: repository
 * enumeration backed by the same CloudStreamApp keys the host uses, and
 * inert plugin wrappers so enumeration code links.
 */

@kotlinx.serialization.Serializable
data class Repository(
    @JsonProperty("iconUrl") val iconUrl: String?,
    @JsonProperty("name") val name: String,
    @JsonProperty("description") val description: String?,
    @JsonProperty("manifestVersion") val manifestVersion: Int,
    @JsonProperty("pluginLists") val pluginLists: List<String>,
)

/** Status int: 0 down, 1 ok, 2 slow, 3 beta only */
@kotlinx.serialization.Serializable
data class SitePlugin(
    @JsonProperty("url") val url: String,
    @JsonProperty("status") val status: Int,
    @JsonProperty("version") val version: Int,
    @JsonProperty("apiVersion") val apiVersion: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("internalName") val internalName: String,
    @JsonProperty("authors") val authors: List<String>,
    @JsonProperty("description") val description: String?,
    @JsonProperty("repositoryUrl") val repositoryUrl: String?,
    @JsonProperty("tvTypes") val tvTypes: List<String>?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("iconUrl") val iconUrl: String?,
    @JsonProperty("fileSize") val fileSize: Long?,
    @JsonProperty("fileHash") val fileHash: String?,
)

@kotlinx.serialization.Serializable
data class PluginWrapper(
    @JsonProperty("repository") val repository: Repository,
    @JsonProperty("repositoryData") val repositoryData: RepositoryData,
    @JsonProperty("plugin") val plugin: SitePlugin,
) {
    companion object {
        private val localRepository = Repository("", "", "", 1, emptyList())
        private val localRepositoryData = RepositoryData(name = "", url = "")

        fun getLocalPluginWrapper(plugin: SitePlugin): PluginWrapper =
            PluginWrapper(localRepository, localRepositoryData, plugin)
    }
}

object RepositoryManager {
    const val ONLINE_PLUGINS_FOLDER = "Extensions"

    fun getRepositories(): Array<RepositoryData> =
        getKey<Array<RepositoryData>>(REPOSITORIES_KEY) ?: emptyArray()

    suspend fun getRepoPlugins(repositoryData: RepositoryData): List<PluginWrapper>? =
        withContext(Dispatchers.IO) { null }
}
