package com.lagradost.cloudstream3.utils

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

/**
 * Compatibility shim for the official CloudStream app's DataStoreHelper.
 *
 * Only the surface extensions actually use is provided: account selection
 * and the resume-watching result types (some providers read watch progress
 * to hide already-watched episodes).
 */
object DataStoreHelper {
    private const val ACCOUNTS_KEY = "data_store_helper/account"
    private const val ACCOUNT_KEY_INDEX = "data_store_helper/account_key_index"

    data class PosDur(
        @JsonProperty("position") val position: Long,
        @JsonProperty("duration") val duration: Long,
    )

    data class Account(
        @JsonProperty("keyIndex") val keyIndex: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("customImage") val customImage: String? = null,
        @JsonProperty("defaultImageIndex") val defaultImageIndex: Int,
        @JsonProperty("lockPin") val lockPin: String? = null,
    )

    data class ResumeWatchingResult(
        @JsonProperty("name") override val name: String,
        @JsonProperty("url") override val url: String,
        @JsonProperty("apiName") override val apiName: String,
        @JsonProperty("type") override var type: TvType? = null,
        @JsonProperty("posterUrl") override var posterUrl: String?,
        @JsonProperty("watchPos") val watchPos: PosDur? = null,
        @JsonProperty("id") override var id: Int?,
        @JsonProperty("parentId") val parentId: Int?,
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("season") val season: Int?,
        @JsonProperty("isFromDownload") val isFromDownload: Boolean = false,
        @JsonProperty("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("score") override var score: Score? = null,
    ) : SearchResponse

    fun getAccounts(): Array<Account> = getKey<Array<Account>>(ACCOUNTS_KEY) ?: emptyArray()

    fun setAccounts(values: Array<Account>) {
        setKey(ACCOUNTS_KEY, values)
    }

    // Generates getSelectedKeyIndex()I / setSelectedKeyIndex(I)V expected by extensions.
    var selectedKeyIndex: Int
        get() = getKey<Int>(ACCOUNT_KEY_INDEX) ?: 0
        set(value) {
            setKey(ACCOUNT_KEY_INDEX, value)
        }

    val currentAccount: String get() = selectedKeyIndex.toString()
}
