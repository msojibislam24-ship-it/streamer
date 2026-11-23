package com.lagradost.cloudstream3.utils

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes

/**
 * Compatibility shim for the official CloudStream app's UiText.
 *
 * Extensions construct UiText values (via txt()) for toasts and error
 * messages; StringResource ids are resolved against the host package when
 * possible, otherwise a readable placeholder is returned.
 */
sealed class UiText {
    companion object {
        const val TAG = "UiText"
    }

    data class DynamicString(val value: String) : UiText() {
        override fun toString(): String = value
    }

    class StringResource(
        @StringRes val resId: Int,
        val args: List<Any>,
    ) : UiText() {
        override fun toString(): String =
            "resId = $resId\nargs = ${args.map { "(${it::class} = $it)" }}"
    }

    fun asStringNull(context: Context?): String? {
        return try {
            asString(context ?: return null)
        } catch (e: Exception) {
            Log.e(TAG, "Got invalid data from $this", e)
            null
        }
    }

    fun asString(context: Context): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> {
                val str = runCatching { context.getString(resId) }.getOrNull()
                    ?: "res:$resId"
                if (args.isEmpty() || str == "res:$resId") {
                    str
                } else {
                    str.format(*args.map {
                        when (it) {
                            is UiText -> it.asString(context)
                            else -> it
                        }
                    }.toTypedArray())
                }
            }
        }
    }
}

fun txt(value: String): UiText = UiText.DynamicString(value)

fun txt(@StringRes resId: Int, vararg args: Any): UiText =
    UiText.StringResource(resId, args.toList())
