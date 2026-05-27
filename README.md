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
│   │   └── EditorRepository.kt        ← Single source of truth
│   │
│   ├── github/                        ← GitHub integration
│   │   ├── GitHubAuthManager.kt       ← OAuth 2.0 flow
│   │   ├── GitHubRepository.kt        ← REST API client
│   │   └── GitHubScreen.kt            ← File browser UI
│   │
│   ├── ai/                            ← AI suggestions
│   │   └── AiCompletionService.kt     ← Claude API integration
│   │
│   └── ui/                            ← Jetpack Compose screens
│       └── editor/
│           ├── CodeEditorScreen.kt    ← Main editor UI
│           ├── EditorViewModel.kt     ← State + Flow management
│           └── SessionScreen.kt       ← Create/join sessions
│
└── server/                            ← Ktor backend (separate repo)
    ├── Application.kt                 ← Entry point
    ├── SessionManager.kt              ← Active session tracking
    └── Routes.kt                      ← WebSocket route handlers
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
        if (c.afterId != op.afterId) break
        if (c.id.clock < newId.clock) { pos++; continue }
        if (c.id.clock == newId.clock && c.id.siteId < newId.siteId) { pos++; continue }
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

---

## Tech stack

| Concern | Technology | Why |
|---|---|---|
| Language | Kotlin | Null safety, coroutines, sealed classes |
| UI | Jetpack Compose + Material 3 | Declarative, reactive, modern Android |
| Real-time sync | Ktor WebSockets | Lightweight, Kotlin-native |
| Async | Kotlin Coroutines + Flow | Structured concurrency, no callback hell |
| Dependency injection | Hilt | Compile-time verified DI graph |
| Sync algorithm | Sequence CRDT (Logoot-inspired) | No central sequencer, provably convergent |
| AI suggestions | Anthropic Claude API (claude-sonnet-4-5) | Context-aware inline completions |
| VCS integration | GitHub REST API + OAuth 2.0 | Pull/push files directly from the app |

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

## Key engineering decisions

**Why CRDT over Operational Transformation?**
OT requires a central server to sequence concurrent operations — every edit must round-trip
to the server before being applied. CRDT convergence is a mathematical property of the data
structure itself, so edits can be applied locally and merged later without coordination.

**Why Logoot-style over RGA (Replicated Growable Array)?**
Logoot assigns fractional position identifiers to characters, making it straightforward to
implement the sibling-ordering rule using just a Lamport clock and siteId. RGA uses a
linked list approach that requires more complex tombstone management.

**Why Ktor over Firebase?**
Firebase would have hidden all the interesting distributed systems work behind a library.
Building the sync layer with Ktor WebSockets required explicit design of the session
protocol, history replay, and vector clock exchange — exactly the kind of work that
matters at companies like Microsoft and Apple.

---

## What I would change at scale

This is designed as a portfolio project running on a single Ktor server.
In a production system at Microsoft or Apple scale:

1. **Shard sessions by document ID** across multiple server nodes using consistent hashing
2. **Add a persistent message bus** (Kafka) between shards to guarantee op delivery with at-least-once semantics
3. **Replace in-memory session state** with a distributed cache (Redis) so server restarts don't lose active sessions
4. **Add causal consistency enforcement** — buffer ops that arrive before their causal dependencies
5. **Implement garbage collection** for tombstoned characters using a stable snapshot protocol

The client-side CRDT merge logic would require zero changes — that's the elegance of the approach.

---

## Build and run

**Prerequisites:** Android Studio Panda (2025.3.x), JDK 17

```bash
# Clone both repos
git clone https://github.com/sujanuj/CollabEditor
git clone https://github.com/sujanuj/CollabEditorServer

# Terminal 1 — start the sync server
cd CollabEditorServer
./gradlew run

# Terminal 2 — verify server is up
curl http://localhost:8080/health

# Android Studio — open CollabEditor, press Run
# Use the same Session ID on both emulators to collaborate
```

---

## Project status

| Phase | Description | Status |
|---|---|---|
| 1 | CRDT engine + 10 unit tests |  Complete |
| 2 | Ktor WebSocket sync server |  Complete |
| 3 | Android WebSocket sync client |  Complete |
| 4 | Jetpack Compose editor UI |  Complete |
| 5 | GitHub OAuth + file browser |  Complete |
| 6 | AI code suggestions (Claude API) |  Complete |

---

## Author

**Sujan Uppalli Jayadevappa**
MS Software Engineering — Arizona State University

Built as a portfolio project targeting engineering roles at Microsoft, Apple, Amazon, and Tesla.
Designed to demonstrate distributed systems depth, Kotlin expertise, and mobile architecture
judgment beyond what typical Android portfolio projects show.

- Android app: [github.com/sujanuj/CollabEditor](https://github.com/sujanuj/CollabEditor)
- Sync server: [github.com/sujanuj/CollabEditorServer](https://github.com/sujanuj/CollabEditorServer)
