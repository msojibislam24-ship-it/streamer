package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.utils.DataStoreHelper.ResumeWatchingResult

/**
 * Compatibility shim for the official app's HomeViewModel.
 *
 * Ultima and WatchParty read the resume-watching list to integrate with
 * the host's "continue watching" section; the host keeps no such state,
 * so the getter returns an empty list.
 */
class HomeViewModel {
    companion object {
        suspend fun getResumeWatching(): List<ResumeWatchingResult>? = emptyList()

        /** Fired when the home page should refresh. */
        val homeViewModelLoadedEvent = com.lagradost.cloudstream3.utils.EmptyEvent()
    }
}
