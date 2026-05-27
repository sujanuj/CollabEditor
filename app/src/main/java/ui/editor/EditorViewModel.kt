package com.collabedit.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.collabedit.app.ai.AiCompletionService
import com.collabedit.app.sync.EditorRepository
import com.collabedit.app.sync.PresenceManager
import com.collabedit.app.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EditorViewModel : ViewModel() {

    private val repository = EditorRepository(viewModelScope)
    private val aiService = AiCompletionService(viewModelScope)

    val text: StateFlow<String> = repository.textState

    // Exposes the CRDT's remote op counter directly to the UI.
    // Every time a remote operation arrives, this increments.
    // The UI uses it to distinguish remote updates from user keystrokes.
    val remoteOpCount: StateFlow<Long> = repository.remoteOpCount

    val connectionState: StateFlow<SyncService.ConnectionState> = repository.connectionState
    val users: StateFlow<Map<String, PresenceManager.UserPresence>> = repository.users

    val aiSuggestion: StateFlow<String?> = aiService.suggestion
    val aiLoading: StateFlow<Boolean> = aiService.isLoading

    private val _sessionJoined = MutableStateFlow(false)
    val sessionJoined: StateFlow<Boolean> = _sessionJoined.asStateFlow()

    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _sessionId = MutableStateFlow("")
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val _aiEnabled = MutableStateFlow(true)
    val aiEnabled: StateFlow<Boolean> = _aiEnabled.asStateFlow()

    private var currentCursorPosition = 0

    fun joinSession(sessionId: String, userName: String) {
        _sessionId.value = sessionId
        _userName.value = userName
        repository.joinSession(sessionId, userName)
        _sessionJoined.value = true
    }

    fun onTextChanged(newText: String, oldText: String) {
        if (newText == oldText) return
        if (newText == repository.textState.value) return

        if (newText.length > oldText.length) {
            var i = 0
            while (i < oldText.length && i < newText.length && oldText[i] == newText[i]) i++
            val insertedPart = newText.substring(i, i + (newText.length - oldText.length))
            insertedPart.forEachIndexed { offset, char ->
                repository.onCharacterInserted(i + offset, char)
            }
            currentCursorPosition = i + insertedPart.length
        } else {
            var i = 0
            while (i < oldText.length && i < newText.length && oldText[i] == newText[i]) i++
            val deleteCount = oldText.length - newText.length
            repeat(deleteCount) { repository.onCharacterDeleted(i) }
            currentCursorPosition = i
        }

        if (_aiEnabled.value) {
            aiService.onTextChanged(newText, currentCursorPosition)
        }
    }

    fun onCursorMoved(index: Int) {
        currentCursorPosition = index
        repository.onCursorMoved(index)
    }

    fun acceptAiSuggestion() {
        val suggestion = aiService.acceptSuggestion() ?: return
        suggestion.forEachIndexed { offset, char ->
            repository.onCharacterInserted(currentCursorPosition + offset, char)
        }
        currentCursorPosition += suggestion.length
    }

    fun dismissAiSuggestion() { aiService.clearSuggestion() }

    fun toggleAi() {
        val newState = !_aiEnabled.value
        _aiEnabled.value = newState
        aiService.setEnabled(newState)
    }

    fun loadFileContent(content: String) {
        val currentText = repository.textState.value
        if (currentText.isNotEmpty()) {
            repeat(currentText.length) { repository.onCharacterDeleted(0) }
        }
        content.forEachIndexed { index, char ->
            repository.onCharacterInserted(index, char)
        }
    }

    override fun onCleared() {
        super.onCleared()
        aiService.clearSuggestion()
        repository.destroy()
    }
}