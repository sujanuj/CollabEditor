package com.collabedit.app.crdt

import java.util.UUID

/**
 * Represents a unique position in the document.
 * Every character has one of these — it never changes even when
 * other characters are inserted or deleted around it.
 */
data class CharacterId(
    val siteId: String,   // which device/user created this character
    val clock: Long       // logical timestamp (Lamport clock)
) : Comparable<CharacterId> {

    // When two characters are inserted at the same position at the same time,
    // we use this to decide which one comes first — deterministically.
    override fun compareTo(other: CharacterId): Int {
        val clockCompare = this.clock.compareTo(other.clock)
        return if (clockCompare != 0) clockCompare
        else this.siteId.compareTo(other.siteId)
    }

    companion object {
        // A special sentinel ID that represents "the beginning of the document"
        val ROOT = CharacterId(siteId = "ROOT", clock = 0L)
    }
}

/**
 * A single character in the CRDT document.
 * Once created, its id and value never change — only isDeleted can flip to true.
 */
data class CrdtCharacter(
    val id: CharacterId,
    val value: Char,
    var isDeleted: Boolean = false  // "tombstoned" — hidden but kept for sync
)

/**
 * The two types of operations that can happen in the editor.
 * Every keystroke produces exactly one of these.
 */
sealed class DocumentOperation {

    /**
     * Insert a character into the document.
     *
     * @param operationId  unique ID for this operation itself
     * @param siteId       which user/device is inserting
     * @param clock        Lamport timestamp at time of insert
     * @param afterId      insert AFTER this character (null = insert at very beginning)
     * @param value        the actual character being inserted
     * @param timestamp    real wall-clock time (for display purposes only)
     */
    data class Insert(
        val operationId: String = UUID.randomUUID().toString(),
        val siteId: String,
        val clock: Long,
        val afterId: CharacterId?,
        val value: Char,
        val timestamp: Long = System.currentTimeMillis()
    ) : DocumentOperation()

    /**
     * Delete (tombstone) a character from the document.
     *
     * @param operationId  unique ID for this operation
     * @param siteId       which user/device is deleting
     * @param clock        Lamport timestamp at time of delete
     * @param targetId     the ID of the character to delete
     * @param timestamp    real wall-clock time
     */
    data class Delete(
        val operationId: String = UUID.randomUUID().toString(),
        val siteId: String,
        val clock: Long,
        val targetId: CharacterId,
        val timestamp: Long = System.currentTimeMillis()
    ) : DocumentOperation()
}

/**
 * Cursor position — sent over WebSocket so other users can
 * see where your cursor is in real time. Not stored permanently.
 */
data class CursorPosition(
    val siteId: String,
    val afterId: CharacterId?,   // cursor is positioned after this character
    val userColor: String,       // hex color like "#FF5733"
    val userName: String
)