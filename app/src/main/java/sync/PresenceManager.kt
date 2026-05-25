package com.collabedit.app.sync

import com.collabedit.app.crdt.CursorPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks which users are currently in the session and their cursor positions.
 * The UI observes this to render colored cursor indicators.
 */
class PresenceManager {

    data class UserPresence(
        val siteId: String,
        val userName: String,
        val userColor: String,
        val cursorIndex: Int = 0,
        val isActive: Boolean = true
    )

    private val _users = MutableStateFlow<Map<String, UserPresence>>(emptyMap())
    val users: StateFlow<Map<String, UserPresence>> = _users.asStateFlow()

    fun userJoined(siteId: String, userName: String, userColor: String) {
        _users.value = _users.value.toMutableMap().apply {
            put(siteId, UserPresence(siteId, userName, userColor))
        }
    }

    fun userLeft(siteId: String) {
        _users.value = _users.value.toMutableMap().apply {
            remove(siteId)
        }
    }

    fun updateCursor(siteId: String, cursorIndex: Int) {
        val existing = _users.value[siteId] ?: return
        _users.value = _users.value.toMutableMap().apply {
            put(siteId, existing.copy(cursorIndex = cursorIndex))
        }
    }

    fun getUser(siteId: String): UserPresence? = _users.value[siteId]

    fun activeUsers(): List<UserPresence> = _users.value.values.toList()

    fun clear() {
        _users.value = emptyMap()
    }

    // Predefined colors for users — cycles through these
    companion object {
        val USER_COLORS = listOf(
            "#FF5733", "#33A8FF", "#33FF57", "#FF33A8",
            "#A833FF", "#FFD433", "#33FFF0", "#FF8333"
        )

        fun colorForIndex(index: Int): String =
            USER_COLORS[index % USER_COLORS.size]
    }
}