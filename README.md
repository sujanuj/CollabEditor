# CollabEditor

> A production-grade, real-time collaborative code editor for Android — built from scratch
> as a portfolio project targeting engineering roles at Microsoft, Apple, Amazon, and Tesla.

---

## Overview

CollabEditor lets multiple developers edit the same code file simultaneously on Android,
with changes appearing on every connected device in real time — no conflicts, no data loss,
even when devices go offline and reconnect.

The core is a custom **sequence CRDT** (Conflict-free Replicated Data Type) implemented in
pure Kotlin — the same class of algorithm that powers Google Docs, Figma, and Notion.
Building it from scratch, rather than using a library, demonstrates deep understanding of
distributed systems fundamentals.

---

## Why this project

Most collaborative editing apps either:
- Require a central server to sequence operations (Operational Transformation — used by early Google Docs), or
- Use third-party sync libraries (Firebase Realtime Database, Liveblocks, etc.)

CollabEditor uses neither. The CRDT engine guarantees convergence mathematically,
meaning any two devices that receive the same set of operations — in any order,
with any delay — will always end up with identical documents. No central sequencer needed.

This has direct relevance to:
- **Microsoft** — VS Code Live Share uses the same CRDT-based model
- **Apple** — iCloud collaborative documents (Pages, Notes) use CvRDTs
- **Amazon** — distributed systems and offline-first sync are core to AWS and Alexa

---

## Architecture

The project is structured in four clean layers, each with a single responsibility:

```
CollabEditor/
│
├── app/src/main/java/com/collabedit/app/
│   │
│   ├── crdt/                          ← Pure Kotlin, zero Android dependencies
│   │   ├── CrdtDocument.kt            ← Core merge engine (Logoot-style CRDT)
│   │   ├── DocumentOperation.kt       ← Sealed class: Insert | Delete | Cursor
│   │   ├── VectorClock.kt             ← Distributed clock for offline sync
│   │   └── OperationSerializer.kt     ← JSON ↔ operation (WebSocket transport)
│   │
│   ├── sync/                          ← Network layer
│   │   ├── SyncService.kt             ← Ktor WebSocket client
│   │   ├── PresenceManager.kt         ← Real-time cursor broadcast
│   │   └── OfflineQueue.kt            ← Op queue, survives process death
│   │
│   ├── data/                          ← Persistence layer
│   │   ├── OperationLog.kt            ← Room database for local op history
│   │   └── DocumentRepository.kt      ← Single source of truth
│   │
│   └── ui/                            ← Jetpack Compose screens
│       ├── editor/
│       │   ├── CodeEditorScreen.kt    ← Main editor with syntax highlighting
│       │   ├── EditorViewModel.kt     ← State + Flow management
│       │   └── CursorOverlay.kt       ← Animated remote cursors
│       └── session/
│           └── SessionScreen.kt       ← Create/join sessions
│
└── server/                            ← Ktor backend (separate module)
    ├── Application.kt                 ← Entry point
    ├── SessionManager.kt              ← Active session tracking
    ├── SyncRoutes.kt                  ← WebSocket route handlers
    └── PresenceBroadcaster.kt         ← Fan-out cursor positions
```

---

## How the CRDT works

Every character in the document has a **globally unique identity** — a `(siteId, clock)` pair
where `siteId` is the device that created it and `clock` is a Lamport timestamp.

When two users insert at the same position simultaneously:

```
User A (clock=3): insert 'B' after 'A'
User B (clock=3): insert 'X' after 'A'    ← same anchor, same clock
```

The `integrate()` function resolves this deterministically using a priority rule — no
server round-trip, no user intervention. Both devices apply both operations in any order
and always converge to the same result.

```kotlin
// From CrdtDocument.kt — the core merge logic
private fun integrate(op: DocumentOperation.Insert) {
    val newId = CharacterId(op.siteId, op.clock)
    val anchorPos = if (op.afterId == null) -1
                    else chars.indexOfFirst { it.id == op.afterId }
    var pos = anchorPos + 1

    while (pos < chars.size) {
        val c = chars[pos]
        if (c.afterId != op.afterId) break          // left the sibling group
        if (c.id.clock > newId.clock) { pos++; continue }  // higher clock = goes left
        if (c.id.clock == newId.clock && c.id.siteId > newId.siteId) {
            pos++; continue                         // tiebreaker: higher siteId = left
        }
        break
    }
    chars.add(pos, newChar)
}
```

Deleted characters are **tombstoned** (marked `isDeleted = true`) rather than removed,
so operations referencing them still resolve correctly during offline sync replay.

---

## Offline sync

When a device reconnects after being offline:

1. It sends its **vector clock** to the server — a map of `siteId → highestClockSeen`
2. The server computes the diff and returns only the operations the client missed
3. The client replays those operations through the CRDT engine
4. Because the CRDT is commutative and idempotent, the result is always correct

```kotlin
// VectorClock.kt
fun missingOpsForServer(
    serverClock: Map<String, Long>,
    allLocalOps: List<DocumentOperation>
): List<DocumentOperation> {
    return allLocalOps
        .filter { op -> op.clock > (serverClock[op.siteId] ?: 0L) }
        .sortedBy { it.clock }   // replay in causal order
}
```

---

## Tech stack

| Concern | Technology | Why |
|---|---|---|
| Language | Kotlin | Null safety, coroutines, sealed classes |
| UI | Jetpack Compose + Material 3 | Declarative, reactive, modern Android |
| Real-time sync | Ktor WebSockets | Lightweight, Kotlin-native |
| Offline storage | Room | Structured local op log with type safety |
| Dependency injection | Hilt | Compile-time verified DI graph |
| Async | Kotlin Coroutines + Flow | Structured concurrency, no callback hell |
| Sync algorithm | Sequence CRDT (Logoot-inspired) | No central sequencer, provably convergent |
| AI suggestions | Anthropic Claude API | Context-aware inline completions |
| VCS integration | GitHub REST API + OAuth | Pull/push files directly from the app |

---

## CRDT correctness — unit test coverage

The merge engine is verified with 10 unit tests covering every convergence guarantee:

| Test | What it proves |
|---|---|
| `basicInsertWorks` | Sequential inserts produce correct text |
| `insertAtBeginning` | Prepending characters works correctly |
| `insertInMiddle` | Cursor-position inserts are correct |
| `deleteWorks` | Tombstone deletion works |
| `deleteOnEmptyReturnsNull` | Safe boundary handling |
| `twoUsersConverge` | Remote op application produces identical state |
| `concurrentInsertsConverge` | Simultaneous edits at same position resolve deterministically |
| `operationsAreIdempotent` | Applying same op twice has no effect |
| `vectorClockTracksOps` | Clock advances correctly per operation |
| `offlineSyncConverges` | Offline edits merge correctly on reconnect |

Run them:

```bash
./gradlew test
```

Expected output:

```
BUILD SUCCESSFUL in 2s
10 tests completed, 0 failed
```

---

## What I would change at scale

This is designed as a portfolio project running on a single Ktor server.
In a production system at Microsoft or Apple scale, I would:

1. **Shard sessions by document ID** across multiple server nodes using consistent hashing
2. **Add a persistent message bus** (Kafka) between shards to guarantee op delivery with at-least-once semantics
3. **Replace in-memory session state** with a distributed cache (Redis) so server restarts don't lose active sessions
4. **Add causal consistency enforcement** — buffer ops that arrive before their causal dependencies
5. **Implement garbage collection** for tombstoned characters using a stable snapshot protocol

The client-side CRDT merge logic would require zero changes — that's the elegance of the approach.

---

## Build and run

**Prerequisites:** Android Studio Panda (2025.3.x), JDK 17, macOS with Xcode

```bash
# Clone
git clone https://github.com/sujanuj/CollabEditor
cd CollabEditor

# Run unit tests
./gradlew test

# Install on emulator or device
./gradlew installDebug

# Start the sync server (separate terminal)
cd server
./gradlew run
```

---

## Project status

| Phase | Description | Status |
|---|---|---|
| 1 | CRDT engine + unit tests |  Complete |
| 2 | Ktor WebSocket sync server |  In progress |
| 3 | Android sync client |  Planned |
| 4 | Jetpack Compose editor UI |  Planned |
| 5 | GitHub OAuth integration |  Planned |
| 6 | AI code suggestions (Claude API) |  Planned |

---

## Author

**Sujan Uppalli Jayadevappa**
MS Software Engineering — Arizona State University

Built as a portfolio project targeting engineering roles at Microsoft, Apple, Amazon, and Tesla.
Designed to demonstrate distributed systems depth, Kotlin expertise, and mobile architecture
judgment beyond what typical Android portfolio projects show.
