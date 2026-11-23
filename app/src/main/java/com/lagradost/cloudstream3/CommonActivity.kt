package com.lagradost.cloudstream3

import android.app.Activity
import android.content.res.Resources
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream3.utils.UiText
import java.lang.ref.WeakReference

/**
 * Compatibility shim for the official CloudStream app's CommonActivity object.
 *
 * Extensions call CommonActivity.getActivity() for toasts and dialogs; this
 * shim tracks the host activity through application lifecycle callbacks
 * (registered by CsRuntime.init) and shows plain Android toasts.
 */
object CommonActivity {

    private var _activity: WeakReference<Activity>? = null

    var activity: Activity?
        get() = _activity?.get()
        private set(value) {
            _activity = WeakReference(value)
        }

    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }

    val displayMetrics: DisplayMetrics = Resources.getSystem().displayMetrics

    // screenWidth/screenHeight always refer to the screen while in landscape.
    val screenWidth: Int
        get() = maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels)
    val screenHeight: Int
        get() = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels)

    val onColorSelectedEvent = Event<Pair<Int, Int>>()
    val onDialogDismissedEvent = Event<Int>()

    private var currentToast: Toast? = null

    fun showToast(@StringRes message: Int, duration: Int? = null) {
        val act = activity ?: return
        act.runOnUiThread { showToast(act, act.getString(message), duration) }
    }

    fun showToast(message: String?, duration: Int? = null) {
        val act = activity ?: return
        act.runOnUiThread { showToast(act, message, duration) }
    }

    fun showToast(message: UiText?, duration: Int? = null) {
        val act = activity ?: return
        if (message == null) return
        act.runOnUiThread { showToast(act, message.asString(act), duration) }
    }

    fun showToast(act: Activity?, text: UiText, duration: Int) {
        if (act == null) return
        text.asStringNull(act)?.let { showToast(act, it, duration) }
    }

    fun showToast(act: Activity?, @StringRes message: Int, duration: Int? = null) {
        if (act == null) return
        showToast(act, act.getString(message), duration)
    }

    /** duration is Toast.LENGTH_SHORT if null */
    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        if (act == null || message == null) {
            Log.w(TAG, "invalid showToast act = $act message = $message")
            return
        }
        try {
            currentToast?.cancel()
        } catch (_: Exception) {
        }
        try {
            val toast = Toast.makeText(act, message.trim(), duration ?: Toast.LENGTH_SHORT)
            currentToast = toast
            toast.show()
        } catch (e: Exception) {
            Log.w(TAG, "showToast failed", e)
        }
    }

    private const val TAG = "COMPACT"
}
