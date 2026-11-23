package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.extractorApis
import java.io.File

/**
 * Compatibility shim for the official CloudStream app's PluginManager.
 *
 * A few extensions query the plugin registry (e.g. to reload themselves or
 * read their own PluginData). The host wires its own loader through
 * [externalLoader]; unload semantics follow the official implementation.
 */

const val PLUGINS_KEY = "PLUGINS_KEY"
const val PLUGINS_KEY_LOCAL = "PLUGINS_KEY_LOCAL"

// Data class for internal storage (verbatim from the official app).
data class PluginData(
    @JsonProperty("internalName") val internalName: String,
    @JsonProperty("url") val url: String?,
    @JsonProperty("isOnline") val isOnline: Boolean,
    @JsonProperty("filePath") val filePath: String,
    @JsonProperty("version") val version: Int,
)

object PluginManager {
    const val TAG = "PluginManager"
    const val ONLINE_PLUGINS_FOLDER = "cs3_plugins"

    /** Set by the host (CsEngine) so the shim can load .cs3 files itself. */
    @Volatile
    var externalLoader: (suspend (context: Context, internalName: String, file: File) -> Boolean)? = null

    /** Name of the extension currently being loaded, for crash reports. */
    var currentlyLoading: String? = null

    /** Maps file path to plugin. */
    val plugins: MutableMap<String, BasePlugin> = LinkedHashMap()

    /** Maps urls to plugin. */
    val urlPlugins: MutableMap<String, BasePlugin> = LinkedHashMap()

    private fun sanitize(name: String): String = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    fun getPluginsOnline(): Array<PluginData> = getKey<Array<PluginData>>(PLUGINS_KEY) ?: emptyArray()

    fun getPluginsLocal(): Array<PluginData> = getKey<Array<PluginData>>(PLUGINS_KEY_LOCAL) ?: emptyArray()

    /**
     * Mirrors the host storage layout (CsEngine.pluginFile) so paths line up
     * with the files the host actually installs.
     */
    fun getPluginPath(
        context: Context,
        internalName: String,
        repositoryUrl: String,
    ): File {
        return File(File(context.filesDir, ONLINE_PLUGINS_FOLDER), "${sanitize(internalName)}.cs3")
    }

    fun registerPlugin(filePath: String, plugin: BasePlugin) {
        plugins[filePath] = plugin
    }

    fun unloadPlugin(absolutePath: String) {
        Log.i(TAG, "Unloading plugin: $absolutePath")
        val plugin = plugins[absolutePath] ?: return

        try {
            plugin.beforeUnload()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to run beforeUnload: ${e.message}")
        }

        // remove all registered apis
        runCatching {
            APIHolder.allProviders.removeAll { it.sourcePlugin == plugin.filename }
        }
        runCatching {
            extractorApis.removeAll { it.sourcePlugin == plugin.filename }
        }

        plugins.remove(absolutePath)
        urlPlugins.values.removeIf { it == plugin }
    }

    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean {
        val saved = getPluginsOnline().firstOrNull {
            it.internalName.replace("provider", "", ignoreCase = true) == apiName
        } ?: getPluginsLocal().firstOrNull {
            it.internalName.replace("provider", "", ignoreCase = true) == apiName
        } ?: return false

        val file = File(saved.filePath)
        if (!file.exists()) return false
        return externalLoader?.invoke(context, saved.internalName, file) ?: false
    }

    fun setPluginsOnline(plugins: List<PluginData>) {
        setKey(PLUGINS_KEY, plugins)
    }
}
