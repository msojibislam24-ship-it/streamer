package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.NONE_ID

/**
 * Compatibility shim for the official app's AuthRepo (base of SyncRepo).
 * The real class implements token refresh and storage; this shim exposes
 * the same public surface with inert behaviour so extension code links.
 */
abstract class AuthRepo(open val api: AuthAPI) {
    val idPrefix get() = api.idPrefix
    val name get() = api.name
    val icon get() = api.icon
    val requiresLogin get() = api.requiresLogin
    val createAccountUrl get() = api.createAccountUrl
    val hasOAuth2 get() = api.hasOAuth2
    val hasPin get() = api.hasPin
    val hasInApp get() = api.hasInApp

    fun isValidRedirectUrl(url: String): Boolean = false

    /** Returns the currently logged-in user, or null when not authenticated. */
    fun authUser(): AuthUser? = null

    suspend fun logOut() {}

    protected fun currentAccountIndex(): Int = NONE_ID
}

/** General-purpose repo */
class PlainAuthRepo(api: AuthAPI) : AuthRepo(api)
