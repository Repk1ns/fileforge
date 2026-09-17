package net.mimochodek.fileforge.model

/**
 * One page of a listing.
 *
 * Listing is always paginated so that a large bucket can never be materialized
 * into memory by accident. Pass [nextPageToken] back to `list` to fetch the
 * following page; it is `null` on the last page.
 *
 * @property keys the object keys on this page.
 * @property commonPrefixes when a delimiter was supplied, the prefixes that were
 *   rolled up instead of being listed - the "subfolders" of the queried prefix.
 *   Always empty without a delimiter.
 */
public data class FilePage(
    val keys: List<String>,
    val nextPageToken: String? = null,
    val commonPrefixes: List<String> = emptyList(),
) {
    /** `true` when there is at least one more page to fetch. */
    public val hasMore: Boolean get() = nextPageToken != null
}
