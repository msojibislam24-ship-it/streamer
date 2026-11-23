package com.lagradost.cloudstream3.utils

import android.util.Log

/**
 * Compatibility shims for the official app's misc UI utilities.
 *
 * AppContextUtils.loadResult is called by extensions after resolving a
 * deep link into a result page; the host has no result screen, so the
 * call only logs. UIHelper is referenced for its object instance only.
 */
object AppContextUtils {
    private const val TAG = "AppContextUtils"

    fun loadResult(
        url: String,
        apiName: String,
        name: String,
        startAction: Int = 0,
        startValue: Int = 0,
    ) {
        Log.i(TAG, "Extension requested loadResult: $name ($apiName) — not supported by host")
    }
}

object UIHelper {
    fun toPx(value: Int): Int = value
}
