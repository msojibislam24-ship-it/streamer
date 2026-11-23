package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.Event

/**
 * Compatibility shim for the official CloudStream app's MainActivity.
 *
 * A few extensions observe lifecycle events (e.g. afterPluginsLoadedEvent)
 * exposed on com.lagradost.cloudstream3.MainActivity. The host app's real
 * activity lives in a different package (com.streamer.app), so this shim
 * provides the event hooks extensions expect.
 */
class MainActivity {
    companion object {
        /** Fired after extensions finished loading. */
        val afterPluginsLoadedEvent = Event<Boolean>()

        /** Fired when bookmark data changes. */
        val bookmarksUpdatedEvent = Event<Any>()

        /** Fired when the extension library should be reloaded. */
        val reloadLibraryEvent = Event<Boolean>()
    }
}
