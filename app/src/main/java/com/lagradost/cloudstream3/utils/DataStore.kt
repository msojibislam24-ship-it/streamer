package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.mvvm.logError

/**
 * Compatibility shim for the official CloudStream app's DataStore object.
 *
 * Extensions (and the inlined CloudStreamApp.getKey code inside them) call
 * DataStore.getSharedPrefs(context) etc. directly, so the object and its
 * member-extension API must exist with identical JVM signatures.
 *
 * Values are serialized with the library's AppUtils mapper (Jackson, with
 * the Kotlin module) into the same "rebuild_preference" store the real app
 * uses, so data written by extensions round-trips correctly.
 */
object DataStore {
    const val PREFERENCES_NAME = "rebuild_preference"

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    fun Context.getSharedPrefs(): SharedPreferences = getPreferences(this)

    fun Context.getDefaultSharedPrefs(): SharedPreferences =
        getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

    fun Context.getKeys(folder: String): List<String> {
        // Ensure the folder ends with "/" to prevent matching other folders
        val fixedFolder = folder.trimEnd('/') + "/"
        return getSharedPrefs().all.keys.filter { it.startsWith(fixedFolder) }
    }

    fun Context.containsKey(folder: String, path: String): Boolean =
        containsKey(getFolderName(folder, path))

    fun Context.containsKey(path: String): Boolean = getSharedPrefs().contains(path)

    fun Context.removeKey(folder: String, path: String) {
        removeKey(getFolderName(folder, path))
    }

    fun Context.removeKey(path: String) {
        try {
            val prefs = getSharedPrefs()
            if (prefs.contains(path)) prefs.edit().remove(path).apply()
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys("$folder/")
        return try {
            val editor = getSharedPrefs().edit()
            keys.forEach { editor.remove(it) }
            editor.apply()
            keys.size
        } catch (e: Exception) {
            logError(e)
            0
        }
    }

    @OptIn(InternalAPI::class)
    fun <T> Context.setKey(path: String, value: T) {
        try {
            val literal = value?.let { with(AppUtils) { it.toJsonLiteral() } }
            getSharedPrefs().edit().putString(path, literal).apply()
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    @OptIn(InternalAPI::class)
    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        return try {
            val json: String = getSharedPrefs().getString(path, null) ?: return null
            AppUtils.parseJson(json, valueType.kotlin)
        } catch (_: Exception) {
            null
        }
    }

    @OptIn(InternalAPI::class)
    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? {
        return try {
            val json: String = getSharedPrefs().getString(path, null) ?: return defVal
            AppUtils.parseJson(json, T::class)
        } catch (_: Exception) {
            null
        }
    }

    inline fun <reified T : Any> Context.getKey(path: String): T? = getKey(path, null)

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? =
        getKey(getFolderName(folder, path), null)

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T?): T? =
        getKey(getFolderName(folder, path), defVal) ?: defVal
}
