package com.openminis.app.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/** Credential edits unseen by the config flow; revisions contain no credential material. */
object ProviderCredentialEdits {
    private val _ids = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val ids: SharedFlow<String> = _ids
    private val revisions = ConcurrentHashMap<String, Long>()

    fun revision(instanceId: String): Long = revisions[instanceId] ?: 0L

    fun changed(instanceId: String) {
        revisions.merge(instanceId, 1L) { before, increment -> before + increment }
        _ids.tryEmit(instanceId)
    }
}
