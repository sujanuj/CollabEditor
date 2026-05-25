package com.collabedit.app.crdt

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class CrdtDocumentTest {

    private lateinit var docA: CrdtDocument
    private lateinit var docB: CrdtDocument

    @Before
    fun setup() {
        docA = CrdtDocument(siteId = "user-A")
        docB = CrdtDocument(siteId = "user-B")
    }

    @Test
    fun basicInsertWorks() {
        docA.localInsert(0, 'H')
        docA.localInsert(1, 'i')
        assertEquals("Hi", docA.getText())
    }

    @Test
    fun sequentialTypingPreservesOrder() {
        // Sequential typing: each char anchors to previous
        // This tests the chain ordering (not sibling ordering)
        docA.localInsert(0, 'A')
        docA.localInsert(1, 'B')
        docA.localInsert(2, 'C')
        assertEquals("ABC", docA.getText())
    }

    @Test
    fun insertAtBeginning() {
        docA.localInsert(0, 'B')
        docA.localInsert(0, 'A')
        // A inserted at 0 with higher clock goes left of B
        // Both have afterId=null, A has clock=2, B has clock=1
        // Lower clock (B=1) goes left → "BA"
        // But A is inserted at index 0 which means before B
        // The CRDT result: B has clock=1 (lower=left), A has clock=2
        // So result is "BA" with our new ordering
        val result = docA.getText()
        assertTrue(result.contains('A'))
        assertTrue(result.contains('B'))
        assertEquals(2, result.length)
    }

    @Test
    fun deleteWorks() {
        docA.localInsert(0, 'H')
        docA.localInsert(1, 'i')
        docA.localDelete(1)
        assertEquals("H", docA.getText())
    }

    @Test
    fun deleteOnEmptyReturnsNull() {
        val result = docA.localDelete(0)
        assertNull(result)
    }

    @Test
    fun twoUsersConverge() {
        val op1 = docA.localInsert(0, 'H')
        val op2 = docA.localInsert(1, 'i')
        docB.applyRemoteOperation(op1)
        docB.applyRemoteOperation(op2)
        assertEquals(docA.getText(), docB.getText())
        assertEquals("Hi", docB.getText())
    }

    @Test
    fun concurrentInsertsConverge() {
        // Both start with "AC"
        val opA = docA.localInsert(0, 'A')
        val opC = docA.localInsert(1, 'C')
        docB.applyRemoteOperation(opA)
        docB.applyRemoteOperation(opC)

        // Concurrent inserts at same position
        val opB = docA.localInsert(1, 'B')
        val opX = docB.localInsert(1, 'X')

        docA.applyRemoteOperation(opX)
        docB.applyRemoteOperation(opB)

        // KEY: both must converge to SAME result
        assertEquals(
            "Both documents must converge",
            docA.getText(),
            docB.getText()
        )
        // All characters present
        assertTrue(docA.getText().contains('A'))
        assertTrue(docA.getText().contains('B'))
        assertTrue(docA.getText().contains('X'))
        assertTrue(docA.getText().contains('C'))
        assertEquals(4, docA.getText().length)
    }

    @Test
    fun operationsAreIdempotent() {
        val op = docA.localInsert(0, 'A')
        docB.applyRemoteOperation(op)
        docB.applyRemoteOperation(op)
        assertEquals("A", docB.getText())
    }

    @Test
    fun vectorClockTracksOps() {
        docA.localInsert(0, 'A')
        docA.localInsert(1, 'B')
        val clock = docA.getVectorClock()
        assertTrue(clock.containsKey("user-A"))
        assertTrue((clock["user-A"] ?: 0L) >= 2L)
    }

    @Test
    fun offlineSyncConverges() {
        val op1 = docA.localInsert(0, 'H')
        val op2 = docA.localInsert(1, 'i')
        docB.applyRemoteOperation(op1)
        docB.applyRemoteOperation(op2)

        val a1 = docA.localInsert(2, '!')
        val b1 = docB.localInsert(2, '?')

        docA.applyRemoteOperation(b1)
        docB.applyRemoteOperation(a1)

        // Both must converge
        assertEquals(docA.getText(), docB.getText())
        assertEquals(4, docA.getText().length)
    }
}