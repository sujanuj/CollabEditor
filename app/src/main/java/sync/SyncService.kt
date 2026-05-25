package com.collabedit.app.sync

import android.util.Log
import com.collabedit.app.crdt.CrdtDocument
import com.collabedit.app.crdt.DocumentOperation
import com.collabedit.app.crdt.OperationSerializer
import com.collabedit.app.crdt.CursorPosition
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "SyncService"

class SyncService(
    private val document: CrdtDocument,
    private val serverUrl: String = "ws://10.0.2.2:8080"
) {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocketSession: DefaultWebSocketSession? = null
    private val offlineQueue = mutableListOf<String>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<SyncEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

    private var currentSessionId: String? = null
    private var currentSiteId: String? = null
    private var currentUserName: String? = null
    private var currentUserColor: String? = null

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    sealed class SyncEvent {
        data class UserJoined(val siteId: String, val userName: String, val userColor: String) : SyncEvent()
        data class UserLeft(val siteId: String) : SyncEvent()
        data class HistoryReceived(val operationCount: Int) : SyncEvent()
        data class Error(val message: String) : SyncEvent()
        object Connected : SyncEvent()
        object Disconnected : SyncEvent()
    }

    fun connect(sessionId: String, siteId: String, userName: String, userColor: String) {
        currentSessionId = sessionId
        currentSiteId = siteId
        currentUserName = userName
        currentUserColor = userColor
        scope.launch { connectWithRetry() }
    }

    private suspend fun connectWithRetry() {
        while (scope.isActive) {
            _connectionState.value = ConnectionState.CONNECTING
            try {
                Log.d(TAG, "Connecting...")
                startWebSocketSession()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
            }
            _connectionState.value = ConnectionState.RECONNECTING
            Log.d(TAG, "Retrying in 3s...")
            delay(3000L)
        }
    }

    private suspend fun startWebSocketSession() {
        val client = HttpClient(OkHttp) {
            install(WebSockets)
        }

        try {
            val sessionId = currentSessionId ?: return
            val siteId = currentSiteId ?: return
            val userName = currentUserName ?: return
            val userColor = currentUserColor ?: return

            client.webSocket("$serverUrl/session/$sessionId") {
                webSocketSession = this
                _connectionState.value = ConnectionState.CONNECTED
                _events.emit(SyncEvent.Connected)
                Log.d(TAG, "CONNECTED to session $sessionId as $userName")

                val joinMsg = JsonObject().apply {
                    addProperty("type", "JOIN")
                    addProperty("siteId", siteId)
                    addProperty("userName", userName)
                    addProperty("userColor", userColor)
                }
                send(Frame.Text(gson.toJson(joinMsg)))
                flushOfflineQueue()

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    handleIncomingMessage(frame.readText(), siteId)
                }
            }
        } finally {
            client.close()
            webSocketSession = null
        }
    }

    private suspend fun handleIncomingMessage(rawMessage: String, mySiteId: String) {
        try {
            val json = JsonParser.parseString(rawMessage).asJsonObject
            when (val type = json.get("type")?.asString) {

                "HISTORY" -> {
                    val operations = json.getAsJsonArray("operations")
                    var count = 0
                    operations?.forEach { element ->
                        val op = OperationSerializer.deserialize(element.asString)
                        if (op != null) {
                            document.applyRemoteOperation(op)
                            count++
                        }
                    }
                    val clientCount = json.get("clientCount")?.asInt ?: 0
                    Log.d(TAG, "History: $count ops applied. Session has $clientCount client(s)")
                    _events.emit(SyncEvent.HistoryReceived(count))
                }

                "INSERT", "DELETE" -> {
                    val op = OperationSerializer.deserialize(rawMessage)
                    if (op != null) document.applyRemoteOperation(op)
                }

                "CURSOR" -> {
                    val cursor = OperationSerializer.deserializeCursor(rawMessage)
                    if (cursor != null) document.updateCursor(cursor)
                }

                "JOIN" -> {
                    val joinedSiteId = json.get("siteId").asString
                    val joinedUserName = json.get("userName").asString
                    val joinedUserColor = json.get("userColor").asString
                    // Only emit if it's someone else joining — we already added ourselves locally
                    if (joinedSiteId != mySiteId) {
                        Log.d(TAG, "Remote user joined: $joinedUserName ($joinedSiteId)")
                        _events.emit(SyncEvent.UserJoined(joinedSiteId, joinedUserName, joinedUserColor))
                    }
                }

                "LEAVE" -> {
                    val leftSiteId = json.get("siteId").asString
                    document.removeCursor(leftSiteId)
                    _events.emit(SyncEvent.UserLeft(leftSiteId))
                    Log.d(TAG, "$leftSiteId left the session")
                }

                "SYNC_RESPONSE" -> {
                    json.getAsJsonArray("operations")?.forEach { element ->
                        val op = OperationSerializer.deserialize(element.asString)
                        if (op != null) document.applyRemoteOperation(op)
                    }
                }

                else -> Log.w(TAG, "Unknown message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Message error: ${e.message}")
        }
    }

    suspend fun sendInsert(op: DocumentOperation.Insert) =
        sendOrQueue(OperationSerializer.serialize(op))

    suspend fun sendDelete(op: DocumentOperation.Delete) =
        sendOrQueue(OperationSerializer.serialize(op))

    suspend fun sendCursor(cursor: CursorPosition) {
        try {
            webSocketSession?.send(Frame.Text(OperationSerializer.serializeCursor(cursor)))
        } catch (e: Exception) {
            Log.w(TAG, "Cursor send failed: ${e.message}")
        }
    }

    private suspend fun sendOrQueue(json: String) {
        val session = webSocketSession
        if (session != null && _connectionState.value == ConnectionState.CONNECTED) {
            try {
                session.send(Frame.Text(json))
            } catch (e: Exception) {
                Log.w(TAG, "Send failed, queuing: ${e.message}")
                offlineQueue.add(json)
            }
        } else {
            Log.d(TAG, "Offline — queuing operation")
            offlineQueue.add(json)
        }
    }

    private suspend fun flushOfflineQueue() {
        val session = webSocketSession ?: return
        val queued = offlineQueue.toList()
        offlineQueue.clear()
        Log.d(TAG, "Flushing ${queued.size} queued operations")
        queued.forEach { json ->
            try { session.send(Frame.Text(json)) }
            catch (e: Exception) { offlineQueue.add(json) }
        }
    }

    fun disconnect() {
        scope.launch {
            webSocketSession?.close()
            webSocketSession = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    fun destroy() { scope.cancel() }
}