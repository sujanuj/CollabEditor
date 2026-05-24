package com.collabedit.app.crdt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Converts DocumentOperations to/from JSON strings.
 *
 * We need this because WebSockets send text — so every operation
 * gets serialized to JSON before sending, and deserialized on receipt.
 *
 * Example JSON for an Insert:
 * {
 *   "type": "INSERT",
 *   "operationId": "abc-123",
 *   "siteId": "user-A",
 *   "clock": 5,
 *   "afterId": { "siteId": "user-B", "clock": 3 },
 *   "value": "H",
 *   "timestamp": 1716000000000
 * }
 */
object OperationSerializer {

    private val gson = Gson()

    // -------------------------------------------------------------------------
    // SERIALIZE — operation → JSON string (for sending over WebSocket)
    // -------------------------------------------------------------------------

    fun serialize(op: DocumentOperation): String {
        val json = JsonObject()
        when (op) {
            is DocumentOperation.Insert -> {
                json.addProperty("type", "INSERT")
                json.addProperty("operationId", op.operationId)
                json.addProperty("siteId", op.siteId)
                json.addProperty("clock", op.clock)
                json.addProperty("value", op.value.toString())
                json.addProperty("timestamp", op.timestamp)
                if (op.afterId != null) {
                    val afterIdJson = JsonObject()
                    afterIdJson.addProperty("siteId", op.afterId.siteId)
                    afterIdJson.addProperty("clock", op.afterId.clock)
                    json.add("afterId", afterIdJson)
                } else {
                    json.add("afterId", null)
                }
            }
            is DocumentOperation.Delete -> {
                json.addProperty("type", "DELETE")
                json.addProperty("operationId", op.operationId)
                json.addProperty("siteId", op.siteId)
                json.addProperty("clock", op.clock)
                json.addProperty("timestamp", op.timestamp)
                val targetIdJson = JsonObject()
                targetIdJson.addProperty("siteId", op.targetId.siteId)
                targetIdJson.addProperty("clock", op.targetId.clock)
                json.add("targetId", targetIdJson)
            }
        }
        return gson.toJson(json)
    }

    fun serializeCursor(cursor: CursorPosition): String {
        val json = JsonObject()
        json.addProperty("type", "CURSOR")
        json.addProperty("siteId", cursor.siteId)
        json.addProperty("userColor", cursor.userColor)
        json.addProperty("userName", cursor.userName)
        if (cursor.afterId != null) {
            val afterIdJson = JsonObject()
            afterIdJson.addProperty("siteId", cursor.afterId.siteId)
            afterIdJson.addProperty("clock", cursor.afterId.clock)
            json.add("afterId", afterIdJson)
        } else {
            json.add("afterId", null)
        }
        return gson.toJson(json)
    }

    // -------------------------------------------------------------------------
    // DESERIALIZE — JSON string → operation (for receiving over WebSocket)
    // -------------------------------------------------------------------------

    fun deserialize(jsonString: String): DocumentOperation? {
        return try {
            val json = JsonParser.parseString(jsonString).asJsonObject
            when (val type = json.get("type").asString) {
                "INSERT" -> {
                    val afterIdJson = json.get("afterId")
                    val afterId = if (afterIdJson == null || afterIdJson.isJsonNull) null
                    else CharacterId(
                        siteId = afterIdJson.asJsonObject.get("siteId").asString,
                        clock = afterIdJson.asJsonObject.get("clock").asLong
                    )
                    DocumentOperation.Insert(
                        operationId = json.get("operationId").asString,
                        siteId = json.get("siteId").asString,
                        clock = json.get("clock").asLong,
                        afterId = afterId,
                        value = json.get("value").asString[0],
                        timestamp = json.get("timestamp").asLong
                    )
                }
                "DELETE" -> {
                    val targetIdJson = json.get("targetId").asJsonObject
                    DocumentOperation.Delete(
                        operationId = json.get("operationId").asString,
                        siteId = json.get("siteId").asString,
                        clock = json.get("clock").asLong,
                        targetId = CharacterId(
                            siteId = targetIdJson.get("siteId").asString,
                            clock = targetIdJson.get("clock").asLong
                        ),
                        timestamp = json.get("timestamp").asLong
                    )
                }
                else -> null.also {
                    android.util.Log.w("OperationSerializer", "Unknown op type: $type")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OperationSerializer", "Failed to deserialize: $jsonString", e)
            null
        }
    }

    fun deserializeCursor(jsonString: String): CursorPosition? {
        return try {
            val json = JsonParser.parseString(jsonString).asJsonObject
            if (json.get("type").asString != "CURSOR") return null
            val afterIdJson = json.get("afterId")
            val afterId = if (afterIdJson == null || afterIdJson.isJsonNull) null
            else CharacterId(
                siteId = afterIdJson.asJsonObject.get("siteId").asString,
                clock = afterIdJson.asJsonObject.get("clock").asLong
            )
            CursorPosition(
                siteId = json.get("siteId").asString,
                afterId = afterId,
                userColor = json.get("userColor").asString,
                userName = json.get("userName").asString
            )
        } catch (e: Exception) {
            android.util.Log.e("OperationSerializer", "Failed to deserialize cursor", e)
            null
        }
    }
}