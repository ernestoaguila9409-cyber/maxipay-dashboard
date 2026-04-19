package com.ernesto.kds.data

/** KDS device menu filter from the web dashboard (categories OR explicit item ids). */
data class KdsMenuAssignment(
    val categoryIds: Set<String> = emptySet(),
    val itemIds: Set<String> = emptySet(),
)
