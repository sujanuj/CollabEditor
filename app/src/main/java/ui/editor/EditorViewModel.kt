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

    // Flag to prevent processing remote updates as local edits
    private var isApplyingRemoteUpdate = false

    fun joinSession(sessionId: String, userName: String) {
        _sessionId.value = sessionId
        _userName.value = userName
        repository.joinSession(sessionId, userName)
        _sessionJoined.value = true
    }

    fun onTextChanged(newText: String, oldText: String) {
        // If this change came from a remote update, ignore it
        if (isApplyingRemoteUpdate) return
        // If lengths are equal, no structural change
        if (newText == oldText) return

        if (newText.length > oldText.length) {
            // Find first difference
            var i = 0
            while (i < oldText.length && i < newText.length && oldText[i] == newText[i]) i++
            // Insert each new character
            val insertedPart = newText.substring(i, i + (newText.length - oldText.length))
            insertedPart.forEachIndexed { offset, char ->
                repository.onCharacterInserted(i + offset, char)
            }
        } else {
            // Find first difference
            var i = 0
            while (i < oldText.length && i < newText.length && oldText[i] == newText[i]) i++
            // Delete each removed character
            val deleteCount = oldText.length - newText.length
            repeat(deleteCount) {
                repository.onCharacterDeleted(i)
            }
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