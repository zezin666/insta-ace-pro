package com.acegallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acegallery.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val cameraHost: Flow<String> = repository.cameraHost

    fun save(host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.setCameraHost(trimmed) }
    }
}
