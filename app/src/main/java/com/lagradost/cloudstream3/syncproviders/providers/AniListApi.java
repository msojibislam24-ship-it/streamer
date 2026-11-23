package com.lagradost.cloudstream3.syncproviders.providers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lagradost.cloudstream3.syncproviders.SyncAPI;

/**
 * Compatibility shim for the official app's AniList API.
 *
 * Extensions like Anichi read season/recommendation data through these
 * models. The shapes mirror recloudstream/cloudstream
 * (syncproviders/providers/AniListApi.kt) so JSON deserialization works.
 * Written in Java with explicit $stable fields because compiled
 * extensions read the synthetic Kotlin stability markers from their
 * static initializers.
 */
public class AniListApi extends SyncAPI {
    public static final int $stable = 0;

    public AniListApi() {
        // SyncAPI exposes Kotlin properties through JVM accessors.
        setRequireLibraryRefresh(true);
    }

    public static final class Variables {
        @JsonProperty("search") public final String search;
        @JsonProperty("page") public final Integer page;
        @JsonProperty("sort") public final String sort;

        public Variables(String search, Integer page, String sort) {
            this.search = search;
            this.page = page;
            this.sort = sort;
        }
    }

    public static final class Title {
        public static final int $stable = 0;

        @JsonProperty("english") public final String english;
        @JsonProperty("romaji") public final String romaji;

        public Title(String english, String romaji) {
            this.english = english;
            this.romaji = romaji;
        }

        public String getEnglish() { return english; }
        public String getRomaji() { return romaji; }

        @Override public int hashCode() {
            int result = english != null ? english.hashCode() : 0;
            result = 31 * result + (romaji != null ? romaji.hashCode() : 0);
            return result;
        }
    }

    public static final class CoverImage {
        public static final int $stable = 0;

        @JsonProperty("medium") public final String medium;
        @JsonProperty("large") public final String large;
        @JsonProperty("extraLarge") public final String extraLarge;

        public CoverImage(String medium, String large, String extraLarge) {
            this.medium = medium;
            this.large = large;
            this.extraLarge = extraLarge;
        }

        public String getMedium() { return medium; }
        public String getLarge() { return large; }
        public String getExtraLarge() { return extraLarge; }

        @Override public int hashCode() {
            int result = medium != null ? medium.hashCode() : 0;
            result = 31 * result + (large != null ? large.hashCode() : 0);
            result = 31 * result + (extraLarge != null ? extraLarge.hashCode() : 0);
            return result;
        }
    }

    public static final class MediaTitle {
        @JsonProperty("romaji") public final String romaji;
        @JsonProperty("english") public final String english;
        @JsonProperty("native") public final String native_;
        @JsonProperty("userPreferred") public final String userPreferred;

        public MediaTitle(String romaji, String english, String native_, String userPreferred) {
            this.romaji = romaji;
            this.english = english;
            this.native_ = native_;
            this.userPreferred = userPreferred;
        }

        public String getRomaji() { return romaji; }
        public String getEnglish() { return english; }
    }

    public static final class MediaCoverImage {
        @JsonProperty("extraLarge") public final String extraLarge;
        @JsonProperty("large") public final String large;
        @JsonProperty("medium") public final String medium;
        @JsonProperty("color") public final String color;

        public MediaCoverImage(String extraLarge, String large, String medium, String color) {
            this.extraLarge = extraLarge;
            this.large = large;
            this.medium = medium;
            this.color = color;
        }

        public String getLarge() { return large; }
    }

    public static final class SeasonNextAiringEpisode {
        public static final int $stable = 0;

        @JsonProperty("episode") public final Integer episode;
        @JsonProperty("timeUntilAiring") public final Integer timeUntilAiring;

        public SeasonNextAiringEpisode(Integer episode, Integer timeUntilAiring) {
            this.episode = episode;
            this.timeUntilAiring = timeUntilAiring;
        }

        public Integer getEpisode() { return episode; }

        @Override public int hashCode() {
            int result = episode != null ? episode.hashCode() : 0;
            result = 31 * result + (timeUntilAiring != null ? timeUntilAiring.hashCode() : 0);
            return result;
        }
    }

    public static final class LikePageInfo {
        public static final int $stable = 0;

        @JsonProperty("total") public final Integer total;
        @JsonProperty("currentPage") public final Integer currentPage;
        @JsonProperty("lastPage") public final Integer lastPage;
        @JsonProperty("perPage") public final Integer perPage;
        @JsonProperty("hasNextPage") public final Boolean hasNextPage;

        public LikePageInfo(Integer total, Integer currentPage, Integer lastPage,
                            Integer perPage, Boolean hasNextPage) {
            this.total = total;
            this.currentPage = currentPage;
            this.lastPage = lastPage;
            this.perPage = perPage;
            this.hasNextPage = hasNextPage;
        }

        public Boolean getHasNextPage() { return hasNextPage; }

        @Override public int hashCode() {
            int result = total != null ? total.hashCode() : 0;
            result = 31 * result + (currentPage != null ? currentPage.hashCode() : 0);
            result = 31 * result + (lastPage != null ? lastPage.hashCode() : 0);
            result = 31 * result + (perPage != null ? perPage.hashCode() : 0);
            result = 31 * result + (hasNextPage != null ? hasNextPage.hashCode() : 0);
            return result;
        }
    }

    public static final class RecommendedMedia {
        @JsonProperty("id") public final Integer id;
        @JsonProperty("title") public final MediaTitle title;
        @JsonProperty("coverImage") public final MediaCoverImage coverImage;

        public RecommendedMedia(Integer id, MediaTitle title, MediaCoverImage coverImage) {
            this.id = id;
            this.title = title;
            this.coverImage = coverImage;
        }

        public Integer getId() { return id; }
        public MediaTitle getTitle() { return title; }
        public MediaCoverImage getCoverImage() { return coverImage; }
    }

    public static final class Recommendation {
        @JsonProperty("mediaRecommendation") public final RecommendedMedia mediaRecommendation;

        public Recommendation(RecommendedMedia mediaRecommendation) {
            this.mediaRecommendation = mediaRecommendation;
        }

        public RecommendedMedia getMediaRecommendation() { return mediaRecommendation; }
    }

    public static final class RecommendationEdge {
        @JsonProperty("node") public final Recommendation node;

        public RecommendationEdge(Recommendation node) {
            this.node = node;
        }

        public Recommendation getNode() { return node; }
    }

    public static final class RecommendationConnection {
        public static final int $stable = 0;

        @JsonProperty("edges") public final java.util.List<RecommendationEdge> edges;
        @JsonProperty("nodes") public final java.util.List<Recommendation> nodes;

        public RecommendationConnection(java.util.List<RecommendationEdge> edges,
                                        java.util.List<Recommendation> nodes) {
            this.edges = edges != null ? edges : java.util.Collections.emptyList();
            this.nodes = nodes != null ? nodes : java.util.Collections.emptyList();
        }

        public java.util.List<RecommendationEdge> getEdges() { return edges; }

        @Override public int hashCode() {
            return edges.hashCode() * 31 + nodes.hashCode();
        }
    }

    public static final class SeasonResponse {
        @JsonProperty("data") public final SeasonData data;

        public SeasonResponse(SeasonData data) {
            this.data = data;
        }
    }

    public static final class SeasonData {
        @JsonProperty("Media") public final SeasonMedia media;

        public SeasonData(SeasonMedia media) {
            this.media = media;
        }
    }

    public static final class SeasonMedia {
        @JsonProperty("id") public final Integer id;
        @JsonProperty("title") public final MediaTitle title;
        @JsonProperty("nextAiringEpisode") public final SeasonNextAiringEpisode nextAiringEpisode;
        @JsonProperty("recommendations") public final RecommendationConnection recommendations;
        @JsonProperty("coverImage") public final MediaCoverImage coverImage;

        public SeasonMedia(Integer id, MediaTitle title, SeasonNextAiringEpisode nextAiringEpisode,
                           RecommendationConnection recommendations, MediaCoverImage coverImage) {
            this.id = id;
            this.title = title;
            this.nextAiringEpisode = nextAiringEpisode;
            this.recommendations = recommendations;
            this.coverImage = coverImage;
        }
    }

    public static final String ANILIST_SUCCESS_RESULT = "Successfully added to AniList";
}
