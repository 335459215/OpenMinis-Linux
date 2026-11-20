package com.openminis.app.data.repository

import com.openminis.app.provider.ModelRefreshGeneration

/** Refuse a stale request before resolving credentials or contacting its old endpoint. */
internal fun <T : Any, R> withCurrentModelCatalogConnection(
    configLock: Any,
    expectedConnection: T,
    currentConnection: () -> T?,
    action: () -> R,
): R? = synchronized(configLock) {
    if (currentConnection() != expectedConnection) null else action()
}

/** Check the request configuration and generation in the same critical section as publication. */
internal fun <T : Any> publishModelCatalogIfCurrent(
    configLock: Any,
    generations: ModelRefreshGeneration,
    providerId: String,
    generation: Int,
    expectedConnection: T,
    currentConnection: () -> T?,
    publish: () -> Unit,
): Boolean = synchronized(configLock) {
    if (currentConnection() != expectedConnection) false
    else generations.publishIfCurrent(providerId, generation, publish)
}
