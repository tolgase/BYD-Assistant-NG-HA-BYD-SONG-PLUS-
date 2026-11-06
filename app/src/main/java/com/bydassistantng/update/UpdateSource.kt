package com.bydassistantng.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where updates come from: the GitHub releases of one repository, named by the build (see `assistant.updateRepo`
 * in app/build.gradle.kts). An update is only ever downloaded from that repository's own release assets.
 */
class UpdateSource private constructor(
    val repo: String,
    /** The list-releases API URL. */
    val releasesUrl: String,
    private val anyHost: Boolean,
) {
    /** Set when this source is a server of your own: downloads are held to that host instead of GitHub's. */
    internal var ownHost: String? = null

    /** Whether [url] is one of this source's release downloads (always over https). */
    fun allowsDownload(url: HttpUrl): Boolean = when {
        anyHost -> true
        ownHost != null -> url.isHttps && url.host == ownHost
        else -> url.isHttps && url.host == "github.com" && url.encodedPath.startsWith("/$repo/releases/download/")
    }

    /** Whether a download that was redirected ends up on a host this source is allowed to fetch from. */
    fun allowsRedirectTarget(url: HttpUrl): Boolean = when {
        anyHost -> true
        ownHost != null -> url.isHttps && url.host == ownHost
        else -> url.isHttps && (url.host == "github.com" || url.host.endsWith(".githubusercontent.com"))
    }

    companion object {
        private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

        /** The source for `owner/name`, or null when [repo] isn't one (an empty value means "this build has no updates"). */
        fun forRepo(repo: String): UpdateSource? =
            if (REPO.matches(repo)) UpdateSource(repo, "https://api.github.com/repos/$repo/releases?per_page=30", anyHost = false) else null

        /**
         * Song PLUS edition: the releases of your own server, which answers in the shape this reads. Downloads are
         * held to that server's own host, exactly as a GitHub source is held to github.com, so a release list from
         * there still cannot send the app to fetch an APK from somewhere else.
         */
        fun forUrl(releasesUrl: String): UpdateSource? {
            val url = releasesUrl.toHttpUrlOrNull() ?: return null
            if (!url.isHttps) return null
            return UpdateSource("self/${url.host}", releasesUrl, anyHost = false).also { it.ownHost = url.host }
        }

        /** A source at any URL, for exercising the update flow against a local server from a debug build. */
        fun forTesting(releasesUrl: String): UpdateSource? =
            releasesUrl.toHttpUrlOrNull()?.let { UpdateSource("test/test", releasesUrl, anyHost = true) }
    }
}
