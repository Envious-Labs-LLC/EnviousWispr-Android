package com.envi.wispr.history

/** Prevents insertion from outrunning the durable history row that tracks its outcome. */
object HistoryPublicationPolicy {
    enum class Route { AUTO_INSERT, COPY_ONLY }

    fun route(persistedId: Long, persistenceSucceeded: Boolean): Route {
        return if (persistenceSucceeded && persistedId > 0L) Route.AUTO_INSERT else Route.COPY_ONLY
    }
}
