package org.mlm.miniter.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.mlm.miniter.project.RecentProject
import org.mlm.miniter.project.RecentProjectsRepository

class EditorViewModel(
    private val recentProjectsRepository: RecentProjectsRepository,
) : ViewModel() {

    val recentProjects: StateFlow<List<RecentProject>> = recentProjectsRepository.recents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            recentProjectsRepository.load()
        }
    }

    fun removeRecent(path: String) {
        viewModelScope.launch {
            recentProjectsRepository.removeRecent(path)
        }
    }

    fun clearRecents() {
        viewModelScope.launch {
            recentProjectsRepository.clearAll()
        }
    }
}
