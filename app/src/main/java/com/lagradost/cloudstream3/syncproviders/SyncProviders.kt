package com.lagradost.cloudstream3.syncproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.UiText

/**
 * Compatibility shims for the official CloudStream app's sync providers.
 *
 * A handful of extensions (Anichi, Kickassanime, Reanime, TorraStream)
 * integrate with AniList through the app's sync API. The full login/OAuth
 * machinery is out of scope for the host, so the API surface below mirrors
 * the official classes closely enough that those code paths link and
 * degradate gracefully instead of throwing NoClassDefFoundError.
 */

// ===================== AuthAPI.kt =====================

data class AuthLoginPage(
    val url: String,
    val payload: String? = null,
)

data class AuthToken(
    @JsonProperty("accessToken") val accessToken: String? = null,
    @JsonProperty("refreshToken") val refreshToken: String? = null,
    @JsonProperty("accessTokenLifetime") val accessTokenLifetime: Long? = null,
    @JsonProperty("refreshTokenLifetime") val refreshTokenLifetime: Long? = null,
    @JsonProperty("payload") val payload: String? = null,
)

data class AuthUser(
    @JsonProperty("name") val name: String?,
    @JsonProperty("id") val id: Int,
    @JsonProperty("profilePicture") val profilePicture: String? = null,
    @JsonProperty("profilePictureHeaders") val profilePictureHeaders: Map<String, String>? = null,
)

data class AuthData(
    @JsonProperty("user") val user: AuthUser,
    @JsonProperty("token") val token: AuthToken,
)

/** Stateless Authentication class used for all personalized content */
abstract class AuthAPI {
    open val name: String = "NONE"
    open val idPrefix: String = "NONE"
    open val icon: Int? = null
    open val requiresLogin: Boolean = true
    open val createAccountUrl: String? = null
    open val redirectUrlIdentifier: String? = null
    open val hasOAuth2: Boolean = false
    open val hasPin: Boolean = false
    open val hasInApp: Boolean = false

    open suspend fun authLocal(): AuthData? = null

    open fun authRequest(): AuthLoginPage? = null

    open suspend fun authRepo(url: String): AuthData? = null

    open suspend fun refreshToken(data: AuthData): AuthData? = data

    open suspend fun authUser(token: AuthData): AuthUser? = null

    open suspend fun login(data: AuthData): Boolean = false

    open suspend fun logOut() {}
}

// ===================== SyncAPI.kt (minimal surface) =====================

abstract class SyncAPI : AuthAPI() {
    open var requireLibraryRefresh: Boolean = true
    open val mainUrl: String = "NONE"

    interface AbstractSyncStatus

    data class SyncSearchResult(
        val name: String,
        val apiName: String,
        val url: String,
        val score: Int? = null,
        val posterUrl: String? = null,
    )

    data class SyncStatus(
        val status: AbstractSyncStatus? = null,
        val score: Int? = null,
        val watchedEpisodes: Int? = null,
        val totalEpisodes: Int? = null,
        val airingStatus: String? = null,
    )

    data class SyncResult(
        val idString: String,
        val totalEpisodes: Int? = null,
        val title: String,
        val engName: String? = null,
        val japName: String? = null,
        val status: String? = null,
        val synonyms: List<String>? = null,
        val poster: String? = null,
        val year: Int? = null,
        val aired: String? = null,
        val studios: List<String>? = null,
        val genres: List<String>? = null,
        val synopsis: String? = null,
        val meanScore: Int? = null,
        val malId: Int? = null,
        val aniId: String? = null,
        val anilistId: Int? = null,
        val kitsuId: Int? = null,
        val simklId: Int? = null,
        val nextAiringEpisode: Int? = null,
        val nextAiringTime: Long? = null,
        val nextAiringEpisodeSynopsis: String? = null,
        val type: String? = null,
        val trailers: List<String> = emptyList(),
    )

    data class Page(
        val list: List<SyncSearchResult>,
        val hasNext: Boolean,
    )

    open suspend fun search(auth: AuthData?, query: String): Page? = null

    open suspend fun status(auth: AuthData?, id: String): SyncStatus? = null

    open suspend fun load(auth: AuthData?, id: String): SyncResult? = null

    open suspend fun updateStatus(
        auth: AuthData?,
        id: String,
        status: AbstractSyncStatus,
        episode: Int? = null,
        score: Int? = null,
    ): Boolean = false

    data class LibraryMetadata(
        val allLibraryLists: List<LibraryList>,
        val supportedListSorting: Set<String> = emptySet(),
    )

    data class LibraryList(
        val name: UiText,
        val items: List<LibraryItem> = emptyList(),
    )

    data class LibraryItem(
        val name: String,
        val url: String,
        val syncId: String,
        val episodesCompleted: Int? = null,
        val episodesTotal: Int? = null,
        val personalRating: com.lagradost.cloudstream3.Score? = null,
        val lastUpdatedUnixTime: Long? = null,
        val apiName: String,
        val type: com.lagradost.cloudstream3.TvType? = null,
        val posterUrl: String? = null,
    )

    open suspend fun library(auth: AuthData?): LibraryMetadata? = null
}

// ===================== SyncRepo.kt =====================

/** Stateless safe abstraction of SyncAPI */
class SyncRepo(override val api: SyncAPI) : AuthRepo(api) {
    val syncIdName get() = api.mainUrl

    suspend fun updateStatus(id: String, newStatus: SyncAPI.AbstractSyncStatus): Result<Boolean> =
        runCatching { false }

    suspend fun status(id: String): Result<SyncAPI.SyncStatus?> = runCatching { null }

    suspend fun load(id: String): Result<SyncAPI.SyncResult?> = runCatching { null }

    suspend fun library(): Result<SyncAPI.LibraryMetadata?> = runCatching { null }
}

// ===================== AccountManager.kt (minimal) =====================

abstract class AccountManager {
    companion object {
        const val NONE_ID: Int = -1
        const val ACCOUNT_TOKEN = "auth_tokens"
        const val ACCOUNT_IDS = "auth_ids"

        // The real app instantiates AniListApi here; the shim keeps the
        // property so AccountManager.Companion.getAniListApi() resolves.
        val aniListApi: com.lagradost.cloudstream3.syncproviders.providers.AniListApi =
            com.lagradost.cloudstream3.syncproviders.providers.AniListApi()
    }
}
