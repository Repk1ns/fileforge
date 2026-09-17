package net.mimochodek.fileforge.model

/**
 * One page of object keys.
 *
 * Listing is always paginated so that a large bucket can never be materialized
 * into memory by accident. Pass [nextPageToken] back to `list` to fetch the
 * following page; it is `null` on the last page.
 */
public data class FilePage(
    val keys: List<String>,
    val nextPageToken: String? = null,
) {
    /** `true` when there is at least one more page to fetch. */
    public val hasMore: Boolean get() = nextPageToken != null
}
