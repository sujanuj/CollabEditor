package com.collabedit.app.crdt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CrdtDocument(val siteId: String) {

    data class Char(
        val id: CharacterId,
        val afterId: CharacterId?,
        val value: kotlin.Char,
        var isDeleted: Boolean = false
    )

    private val chars = mutableListOf<Char>()
    private var clock = 0L
    private var suppressTextUpdates = false

    private val _textState = MutableStateFlow("")
    val textState: StateFlow<String> = _textState.asStateFlow()

    // Incremented every time a remote operation updates the document.
    // Local inserts/deletes do NOT increment this.
    // The UI observes this to know when to ignore onValueChange calls.
    private val _remoteOpCount = MutableStateFlow(0L)
    val remoteOpCount: StateFlow<Long> = _remoteOpCount.asStateFlow()

    private val _cursors = MutableStateFlow<Map<String, CursorPosition>>(emptyMap())
    val cursors: StateFlow<Map<String, CursorPosition>> = _cursors.asStateFlow()

    fun localInsert(index: Int, char: kotlin.Char): DocumentOperation.Insert {
        clock++
        val visible = getVisible()
        val afterId = if (index == 0) null else visible.getOrNull(index - 1)?.id
        val op = DocumentOperation.Insert(
            siteId = siteId,
            clock = clock,
            afterId = afterId,
            value = char
        )
        integrate(op)
        return op
    }

    fun localDelete(index: Int): DocumentOperation.Delete? {
        val visible = getVisible()
        val target = visible.getOrNull(index) ?: return null
        clock++
        val op = DocumentOperation.Delete(
            siteId = siteId,
            clock = clock,
            targetId = target.id
        )
        applyDelete(op)
        return op
    }

    fun applyRemoteOperation(op: DocumentOperation) {
        when (op) {
            is DocumentOperation.Insert -> {
                clock = maxOf(clock, op.clock) + 1
                val id = CharacterId(op.siteId, op.clock)
                if (chars.none { it.id == id }) {
                    integrate(op)
                }
            }
            is DocumentOperation.Delete -> {
                clock = maxOf(clock, op.clock) + 1
                applyDelete(op)
            }
        }
        // Increment remote counter after every remote op
        // (suppressed during batch — incremented once at end of batch instead)
        if (!suppressTextUpdates) {
            _remoteOpCount.value++
        }
    }

    /**
     * Apply a batch of operations suppressing intermediate textState
     * and remoteOpCount emissions. Both emit exactly once after all ops.
     */
    fun applyRemoteOperationsBatch(ops: List<DocumentOperation>) {
        if (ops.isEmpty()) return
        suppressTextUpdates = true
        try {
            for (op in ops) {
                applyRemoteOperation(op)
            }
        } finally {
            suppressTextUpdates = false
            updateText()
            _remoteOpCount.value++ // single increment for the whole batch
        }
    }

    private fun integrate(op: DocumentOperation.Insert) {
        val newId = CharacterId(op.siteId, op.clock)
        val newChar = Char(id = newId, afterId = op.afterId, value = op.value)

        val anchorPos = if (op.afterId == null) -1
        else chars.indexOfFirst { it.id == op.afterId }

        var pos = anchorPos + 1

        while (pos < chars.size) {
            val c = chars[pos]
            if (c.afterId != op.afterId) break
            if (c.id.clock < newId.clock) { pos++; continue }
            if (c.id.clock == newId.clock && c.id.siteId < newId.siteId) { pos++; continue }
            break
        }

        chars.add(pos, newChar)
        updateText()
    }

    private fun applyDelete(op: DocumentOperation.Delete) {
        chars.find { it.id == op.targetId }?.isDeleted = true
        updateText()
    }

    fun updateCursor(cursor: CursorPosition) {
        _cursors.value = _cursors.value.toMutableMap().apply {
            put(cursor.siteId, cursor)
        }
    }

    fun removeCursor(siteId: String) {
        _cursors.value = _cursors.value.toMutableMap().apply {
            remove(siteId)
        }
    }

    fun getCursorVisibleIndex(cursor: CursorPosition): Int {
        if (cursor.afterId == null) return 0
        val visible = getVisible()
        val idx = visible.indexOfFirst { it.id == cursor.afterId }
        return if (idx == -1) 0 else idx + 1
    }

    private fun getVisible() = chars.filter { !it.isDeleted }

    private fun updateText() {
        if (suppressTextUpdates) return
        _textState.value = getVisible().map { it.value }.joinToString("")
    }

    fun getText(): String = _textState.value

    fun getVectorClock(): Map<String, Long> {
        val vc = mutableMapOf<String, Long>()
        for (c in chars) {
            val current = vc[c.id.siteId] ?: 0L
            if (c.id.clock > current) vc[c.id.siteId] = c.id.clock
        }
        return vc
    }

    fun getFullHistory(): List<DocumentOperation> {
        val ops = mutableListOf<DocumentOperation>()
        for (c in chars) {
            ops.add(DocumentOperation.Insert(
                siteId = c.id.siteId,
                clock = c.id.clock,
                afterId = c.afterId,
                value = c.value
            ))
            if (c.isDeleted) {
                ops.add(DocumentOperation.Delete(
                    siteId = c.id.siteId,
                    clock = c.id.clock + 1,
                    targetId = c.id
                ))
            }
        }
        return ops
    }

    fun totalCharCount(): Int = chars.size
}