package org.siloserver.silo.model.personal

/** Older servers omit status; any explicit status other than available is unknown. */
fun availableCollectionItemCount(count: Int?, status: String?): Int? =
    count?.takeIf { (status == null || status == "available") && it >= 0 }

fun collectionItemCountLabel(count: Int?, status: String?): String {
    val available = availableCollectionItemCount(count, status) ?: return "Count unavailable"
    return if (available == 1) "1 item" else "$available items"
}
