package com.otakustream.core.database.download

// A download's url paired with the headers stored for it — the projection headerRowsBlocking
// returns. Not an entity: it is a view of two columns of `downloads`, and giving it its own name
// keeps the query's shape out of the caller.
data class DownloadHeaderRow(
    val videoUrl: String,
    val headersJson: String?,
)
