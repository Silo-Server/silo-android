package org.siloserver.silo.model.personal

/**
 * How far a collection's `item_count` can be trusted, from its
 * `item_count_status`. Servers before the status field omit it, so their
 * counts keep today's reading; any explicit status other than `available` is
 * treated as unavailable, including ones a newer server adds.
 */
enum class CollectionCountState {
    /** No status: an older server. A 0 can mean the count isn't computed yet. */
    Legacy,

    /** `available`: the count is exact, so 0 is an empty collection. */
    Exact,

    /** Any other status: the count is not usable and must not be shown. */
    Unavailable,
}

fun collectionCountState(status: String?): CollectionCountState = when (status) {
    null -> CollectionCountState.Legacy
    "available" -> CollectionCountState.Exact
    else -> CollectionCountState.Unavailable
}
