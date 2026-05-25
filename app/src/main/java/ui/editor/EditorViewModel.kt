package com.collabedit.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.collabedit.app.sync.EditorRepository
import com.collabedit.app.sync.PresenceManager
import com.collabedit.app.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EditorViewModel : ViewModel() {

    private val repository = EditorRepository(viewModelScope)

    val text: StateFlow<String> = repository.textState
    val connectionState: StateFlow<SyncService.ConnectionState> = repository.connectionState
    val users: StateFlow<Map<String, PresenceManager.UserPresence>> = repository.users

    private val _sessionJoined = MutableStateFlow(false)
    val sessionJoined: StateFlow<Boolean> = _sessionJoined.asStateFlow()

    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _sessionId = MutableStateFlow("")
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private var isApplyingRemoteUpdate = false

    fun joinSession(sessionId: String, userName: String) {
        _sessionId.value = sessionId
        _userName.value = userName
        repository.joinSession(sessionId, userName)
        _sessionJoined.value = true
    }

    fun onTextChanged(newText: String, oldText: String) {
        if (isApplyingRemoteUpdate) return
        if (newText == oldText) return

        if (newText.length > oldText.length) {
            var i = 0
            while (i < oldText.length && i < newText.length && oldText[i] == newText[i]) i++
            val insertedPart = newText.substring(i, i + (newText.length - oldText.length))
            insertedPart.forEachIndexed { offset, char ->
                repository.onCharacterInserted(i + offset, char)
            }
        } else {
            var i = 0
            while (i < oldText.length && i < newText.length && oldText[i] == newText[i]) i++
            val deleteCount = oldText.length - newText.length
            repeat(deleteCount) {
                repository.onCharacterDeleted(i)
            }
        }
    }

    /**
     * Load a file from GitHub into the editor.
     * Clears existing content and inserts the file content.
     * All collaborators see the file load in real time.
     */
    fun loadFileContent(content: String) {
        val currentText = repository.textState.value

        // Delete all existing characters from the end backwards
        if (currentText.isNotEmpty()) {
            repeat(currentText.length) {
                repository.onCharacterDeleted(0)
            }
        }

        // Insert the file content character by character
        content.forEachIndexed { index, char ->
            repository.onCharacterInserted(index, char)
        }
    }

    fun onCursorMoved(index: Int) {
        repository.onCursorMoved(index)
    }

    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}