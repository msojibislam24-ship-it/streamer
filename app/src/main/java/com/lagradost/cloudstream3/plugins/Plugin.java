package com.lagradost.cloudstream3.plugins;

import android.content.Context;
import android.content.res.Resources;

import kotlin.jvm.functions.Function1;

/**
 * Android entry point for CloudStream extensions (.cs3).
 *
 * Mirrors com.lagradost.cloudstream3.plugins.Plugin from the official
 * CloudStream app module (recloudstream/cloudstream). The published library
 * artifact only ships BasePlugin; extension dex files are compiled against
 * the app-module Plugin class, so any host that wants to run .cs3
 * extensions must provide this class itself.
 *
 * NOTE: $stable is the synthetic Kotlin ABI stability marker that the
 * Kotlin 2.x compiler emits on the real class. Compiled extensions read it
 * from their static initializers (<clinit>), so this field MUST exist even
 * though its value is never used at runtime.
 */
public abstract class Plugin extends BasePlugin {

    // Synthetic Kotlin stability field expected by extension <clinit>.
    public static final int $stable = 0;

    /**
     * Called when the plugin is loaded.
     * Extensions override this to register providers/extractors.
     */
    public void load(Context context) throws Throwable {
        // If not overridden by an extension then try the cross-platform load().
        load();
    }

    /**
     * Contains the plugin resources if the manifest sets requiresResources.
     */
    public Resources resources = null;

    /**
     * Optional callback the host may invoke to show custom plugin settings.
     */
    public Function1<? super Context, kotlin.Unit> openSettings = null;
}
