package com.lagradost.cloudstream3.ui.player

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Compatibility shims for the official app's player abstractions.
 *
 * WatchParty and Ultima register themselves as players through IPlayer.
 * The host app uses its own Media3 player, so these types exist only so
 * the extension classes link. handleEvent/setPreferredSubtitles are inert.
 */

enum class CSPlayerEvent(val value: Int) {
    Play(-3),
    Pause(-2),
    PlayToggle(-1),
    SeekForward(0),
    SeekBack(1),
    NextEpisode(2),
    PrevEpisode(3),
    SkipOp(4),
    NextChapter(5),
    PrevChapter(6),
    PlayPauseToggle(7),
    Restart(8),
}

enum class PlayerEventSource {
    UI,
    Player,
    System,
    Sync,
}

interface IPlayer {
    /** Returns the current playback position in milliseconds */
    fun getPosition(): Long?

    /** Returns the duration of the media or null if unknown */
    fun getDuration(): Long?

    fun seekTo(time: Long, source: PlayerEventSource = PlayerEventSource.UI)

    fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource = PlayerEventSource.UI)

    fun isActive(): Boolean

    fun getIsPlaying(): Boolean

    fun getCurrentPreferredSubtitle(): SubtitleData?

    fun setPreferredSubtitles(subtitle: SubtitleData?): Boolean
}

data class SubtitleData(
    @JsonProperty("name") val name: String,
    @JsonProperty("originalName") val originalName: String? = null,
    @JsonProperty("url") val url: String,
    @JsonProperty("mimeType") val mimeType: String? = null,
    @JsonProperty("headers") val headers: Map<String, String>? = null,
    @JsonProperty("languageCode") val languageCode: String? = null,
    @JsonProperty("origin") val origin: SubtitleOrigin? = null,
    @JsonProperty("syncId") val syncId: String? = null,
    @JsonProperty("removeFromVideo") val removeFromVideo: Boolean? = null,
    @JsonProperty("blurFirstSubtitles") val blurFirstSubtitles: Pair<Long, Long>? = null,
) {
    enum class SubtitleOrigin {
        URL,
        EMBEDDED_IN_VIDEO,
        OPEN_SUBTITLES,
        DOWNLOADED_FILE,
    }
}
