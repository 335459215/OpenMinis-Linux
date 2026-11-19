package com.openminis.app.ui.gateway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.gateway.GatewayRuntime
import com.openminis.app.gateway.store.ChannelAccountEntity
import com.openminis.app.gateway.store.ChannelEntity
import com.openminis.app.gateway.store.ChannelMappingEntity
import com.openminis.app.gateway.store.ChannelModelEntity
import com.openminis.app.gateway.store.GatewayAccountHealth
import com.openminis.app.gateway.store.GatewayRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-only inputs. A supplied key leaves this object immediately after saving. */
data class GatewayAccountDraft(
    val id: String? = null,
    val channelId: String,
    val name: String = "",
    val priority: Int = 0,
    val maxConcurrent: Int = 0,
    val apiKey: String = "",
)

data class GatewaySettingsState(
    val loading: Boolean = true,
    val error: String? = null,
    val channels: List<ChannelEntity> = emptyList(),
    val selectedChannelId: String? = null,
    val accounts: List<ChannelAccountEntity> = emptyList(),
    val mappings: List<ChannelMappingEntity> = emptyList(),
    val health: Map<String, GatewayAccountHealth> = emptyMap(),
)

class GatewaySettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: GatewayRepository =
        GatewayRuntime.repository ?: GatewayRepository.getInstance(application.applicationContext)
    private val _state = MutableStateFlow(GatewaySettingsState())
    val state: StateFlow<GatewaySettingsState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            val channels = repository.channels().sortedWith(compareBy({ it.sortOrder }, { it.id }))
            val selected = channels.firstOrNull { it.id == _state.value.selectedChannelId }
                ?: channels.firstOrNull()
            val accounts = selected?.let { repository.accountsForChannel(it.id) }.orEmpty()
            val mappings = selected?.let { repository.mappingsForChannel(it.id) }.orEmpty()
            val health = if (accounts.isEmpty()) emptyMap() else {
                repository.accountHealth(accounts.map { it.id }).associateBy { it.accountId }
            }
            _state.value = GatewaySettingsState(
                loading = false,
                channels = channels,
                selectedChannelId = selected?.id,
                accounts = accounts.sortedWith(compareBy({ it.priority }, { it.id })),
                mappings = mappings.sortedWith(compareBy({ it.alias }, { it.id })),
                health = health,
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                loading = false,
                error = error.localizedMessage ?: error.javaClass.simpleName,
            )
        }
    }

    fun selectChannel(id: String) {
        if (_state.value.selectedChannelId != id) {
            _state.value = _state.value.copy(selectedChannelId = id)
            refresh()
        }
    }

    fun saveChannel(existing: ChannelEntity?, name: String, protocol: String, baseUrl: String, order: Int) {
        mutate {
            val now = System.currentTimeMillis()
            repository.upsertChannel(
                existing?.copy(name = name, protocol = protocol, baseUrl = baseUrl, sortOrder = order)
                    ?: ChannelEntity(
                        id = "ch_" + newId(), name = name, protocol = protocol,
                        baseUrl = baseUrl, sortOrder = order, createdAt = now,
                    ),
            )
        }
    }

    fun deleteChannel(id: String) = mutate { repository.deleteChannel(id) }

    fun saveAccount(draft: GatewayAccountDraft) {
        mutate {
            val previous = draft.id?.let { repository.accountById(it) }
            val id = draft.id ?: ("acct_" + newId())
            val key = draft.apiKey
            if (key.isNotBlank()) repository.putApiKey(id, key)
            repository.upsertAccount(
                previous?.copy(
                    name = draft.name,
                    priority = draft.priority,
                    maxConcurrent = draft.maxConcurrent,
                    keyTail = if (key.isBlank()) previous.keyTail else key.takeLast(4),
                ) ?: ChannelAccountEntity(
                    id = id,
                    channelId = draft.channelId,
                    name = draft.name,
                    keyRef = "",
                    keyTail = key.takeLast(4),
                    priority = draft.priority,
                    maxConcurrent = draft.maxConcurrent,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteAccount(id: String) = mutate { repository.deleteAccount(id) }

    fun saveMapping(existing: ChannelMappingEntity?, channelId: String, alias: String, target: String) {
        mutate {
            val id = channelId + ":" + alias
            if (existing != null && existing.id != id) repository.deleteMapping(existing.id)
            repository.upsertModel(
                ChannelModelEntity(id = "$channelId:$target", channelId = channelId, modelId = target),
            )
            repository.upsertMapping(
                existing?.copy(id = id, alias = alias, target = target)
                    ?: ChannelMappingEntity(
                        id = id,
                        channelId = channelId,
                        alias = alias,
                        target = target,
                    ),
            )
        }
    }

    fun deleteMapping(id: String) = mutate { repository.deleteMapping(id) }

    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { block() }
            .onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    error = it.localizedMessage ?: it.javaClass.simpleName,
                )
            }
            .onSuccess { refresh() }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
