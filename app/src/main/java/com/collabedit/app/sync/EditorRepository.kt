package com.collabedit.app.sync

import android.util.Log
import com.collabedit.app.crdt.CrdtDocument
import com.collabedit.app.crdt.CursorPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "EditorRepository"

class EditorRepository(private val scope: CoroutineScope) {

    val siteId: String = UUID.randomUUID().toString().take(8)
    val document = CrdtDocument(siteId)
    val presenceManager = PresenceManager()
    private val syncService = SyncService(document)

    val textState: StateFlow<String> = document.textState
    val remoteOpCount: StateFlow<Long> = document.remoteOpCount
    val cursors = document.cursors
    val connectionState = syncService.connectionState
    val users = presenceManager.users

    init {
        scope.launch {
            syncService.events.collect { event ->
                when (event) {
                    is SyncService.SyncEvent.UserJoined -> {
                        if (event.siteId != siteId) {
                            presenceManager.userJoined(event.siteId, event.userName, event.userColor)
                            Log.d(TAG, "${event.userName} joined")
                        }
                    }
                    is SyncService.SyncEvent.UserLeft -> {
                        presenceManager.userLeft(event.siteId)
                        Log.d(TAG, "${event.siteId} left")
                    }
                    is SyncService.SyncEvent.HistoryReceived -> {
                        Log.d(TAG, "History received: ${event.operationCount} ops")
                    }
                    is SyncService.SyncEvent.Connected -> Log.d(TAG, "Connected to server")
                    is SyncService.SyncEvent.Disconnected -> Log.d(TAG, "Disconnected from server")
                    is SyncService.SyncEvent.Error -> Log.e(TAG, "Sync error: ${event.message}")
                }
            }
        }
    }

    fun joinSession(sessionId: String, userName: String) {
        val userColor = PresenceManager.colorForIndex(presenceManager.activeUsers().size)
        presenceManager.userJoined(siteId, userName, userColor)
        syncService.connect(sessionId, siteId, userName, userColor)
        Log.d(TAG, "Joining session $sessionId as $userName ($siteId)")
    }

    fun onCharacterInserted(index: Int, char: Char) {
        val op = document.localInsert(index, char)
        scope.launch(Dispatchers.IO) { syncService.sendInsert(op) }
    }

    fun onCharacterDeleted(index: Int) {
        val op = document.localDelete(index) ?: return
        scope.launch(Dispatchers.IO) { syncService.sendDelete(op) }
    }

    fun onCursorMoved(index: Int) {
        val cursor = CursorPosition(
            siteId = siteId,
            afterId = null,
            userColor = presenceManager.getUser(siteId)?.userColor ?: "#FF5733",
            userName = presenceManager.getUser(siteId)?.userName ?: "User"
        )
        scope.launch(Dispatchers.IO) { syncService.sendCursor(cursor) }
    }

    fun disconnect() {
        syncService.disconnect()
        presenceManager.clear()
    }

    fun destroy() { syncService.destroy() }
}