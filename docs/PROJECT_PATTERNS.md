# CardsNow Project Patterns and Conventions

This document captures the agreed patterns used across the Android client and Ktor backend. Follow these when adding features to keep the codebase consistent and maintainable.

## Serialization and Wire Protocol

- Use Kotlinx Serialization with a sealed `WebSocketMessage` hierarchy on both client and server.
- Discriminator: `messageType`.
- Annotation style: `@Serializable` on every message/data class; `@SerialName("snake_case")` for each variant.
- Canonical types on the wire:
  - `ErrorType`: `TRANSIENT`, `CRITICAL`.
  - `Reconnect`: requires `sessionId`.
  - `GameStateUpdate`: carries `roomCode`, `gameState`, `players`.
- Backend Json config (server-wide):
  - `classDiscriminator = "messageType"`
  - `explicitNulls = false`
  - `encodeDefaults = true`
- Client Json config (currently): `classDiscriminator = "messageType"`.
  - Tests also use `explicitNulls = false` and `encodeDefaults = true`.
  - Recommendation: keep client Json options aligned with backend.

## Message Naming

- Message `@SerialName` values are snake_case, e.g. `join_room`, `create_room`, `player_joined`, `room_created`.
- Keep names stable; treat them as protocol keys.

## Wire vs UI Models

- Wire models live in `com.example.cardsnow.wire` (client) and `com.cardsnow.backend.models` (server).
- UI-only models live under `com.example.cardsnow.ui.model` (e.g., UI `RoomSettings`).
- Prefer alias imports when the same names exist (e.g., `import com.example.cardsnow.wire.RoomSettings as WireRoomSettings`).
- Cards on the wire include suit/rank/id; Android `resourceId` is mapped on the client side and not relied on over the wire.

## Android Client Patterns

- Logging
  - Use `android.util.Log`.
  - Guard logs with `ClientConfig.IS_DEBUG`.
  - Never use `println`.
- WebSocket Handling
  - Ktor `HttpClient(CIO)` with `WebSockets` plugin.
  - Decode incoming messages into sealed `WebSocketMessage` and use an exhaustive `when`.
  - Prefer typed messages for navigation/state updates.
    - `RoomCreated`, `SessionCreated` set `screen = "room"` and clear loading.
    - `GameStateUpdate` also sets `screen = "room"` to cover reconnect flows.
  - `Success` is for user feedback only; do not parse it for control flow.
  - Send `Reconnect` only when a `sessionId` exists.
- Errors & Success
  - `showError(message, type)` auto-dismisses for `TRANSIENT` using `ClientConfig.ERROR_AUTO_DISMISS_MS`.
  - `handleSuccess(message)` only displays a toast-like message and auto-dismisses using `ClientConfig.SUCCESS_AUTO_DISMISS_MS`.
- Constants
  - All client constants in `ClientConfig` (WS URL, timeouts, reconnect backoff/jitter, auto-dismiss, debug flag).
  - Use `ClientConfig.IS_DEBUG` instead of referencing `BuildConfig` directly.
- UI State Mapping
  - Map wire `GameState` to UI `GameState` and compute resource IDs from suit/rank.

## Backend Patterns

- Logging
  - Use SLF4J `logger` throughout. No `println`.
- Constants
  - All server constants in `ServerConfig` (rate limits, cleanup intervals, retry policy, room code range, lock timeout, session TTL).
  - Derive user-facing durations from config values (e.g., seconds = `LOCK_TIMEOUT_MS / 1000`).
- Message Handling
  - Decode messages with shared `json` configured in `plugins/Serialization.kt`.
  - Validate inputs and return `Error` with `ErrorType`.
  - `Reconnect` requires `sessionId`; restore session mapping and send current `GameStateUpdate`.
  - Broadcast typed messages (`PlayerJoined`, `PlayerLeft`, `GameStateUpdate`) instead of generic strings.

## Testing Patterns

- Add round-trip tests for every new `WebSocketMessage` variant to guarantee serializer compatibility.
- Validate protocol constraints in tests (e.g., `Reconnect` requires `sessionId`).
- Keep the test `Json` options aligned with production (discriminator + `explicitNulls=false`, `encodeDefaults=true`).

## Code Style & Quality

- Sealed classes + exhaustive `when` for message handling.
- No `println`; use platform logging only.
- Remove unused imports; keep imports organized.
- Prefer constants/config over magic numbers.
- Keep business logic unchanged when performing refactors.

## Checklists

### Adding a new WebSocket message type
- [ ] Define matching `@Serializable` data classes on server and client.
- [ ] Use `@SerialName("snake_case_name")` for the variant.
- [ ] Update server handler in `ConnectionService`.
- [ ] Update client `KtorViewModel.handleIncomingMessage` with an exhaustive branch.
- [ ] Add client round-trip test(s) and server test(s).
- [ ] Add/adjust constants in `ClientConfig`/`ServerConfig` if needed.
- [ ] Prefer typed feedback over `Success` string parsing.
- [ ] Add structured logging with relevant context.

### Adding a new constant / changing a timeout
- [ ] Put the value in `ClientConfig` or `ServerConfig`.
- [ ] Replace hardcoded mentions in user-facing messages (derive values from config).
- [ ] Add/adjust tests if behavior depends on the value.

### Modifying GameState or Player models
- [ ] Keep wire and server models aligned shape-wise.
- [ ] Update client mapping code (suit/rank to drawable mapping).
- [ ] Add/adjust tests for `GameStateUpdate`.
 
## Error Handling and Codes

- Always include an `ErrorCode` when sending server `Error` messages where a specific category applies.
- Use `ErrorType.TRANSIENT` for recoverable/input issues; use `CRITICAL` for not-found/session/auth failures.
- Derive user-facing durations and limits from `ServerConfig`/`ClientConfig` values in messages (e.g., seconds = `MS / 1000`).
- If introducing a new error category, add it to both enums (`app` and `backend`) and update client mapping.

## Presence & Lifecycle Events

- On join: broadcast `PlayerJoined(roomCode, playerName, players)` and keep roster authoritative.
- On disconnect: remove connection mappings and broadcast `PlayerLeft(roomCode, playerName, newHost)` when host changes.
- On reconnect: validate `sessionId`, rebind, and send `GameStateUpdate` (optionally a `Success`).

## Session Management

- Create sessions via `Session.create(playerName, roomCode)` only. Do not hand-roll IDs.
- Keep `sessions` (by `sessionId`) and `sessionToWebSocket` as the single sources of truth.
- Cleanup: periodically remove expired sessions by TTL; remove only mappings on disconnect to allow reconnection.

## Timeouts, Locks, and Validation

- Wrap every handler call with the timeout wrapper (e.g., `runWithTimeout`).
- For state changes: acquire `RoomService.executeRoomOperation(roomCode)` then `room.atomicUpdate { ... }` inside.
- Validate inputs at the start of each handler using `ValidationService` and return typed `Error` immediately.
- After mutating room/game state, call `room.updateLastActive()` (directly or via service method that does).

## Broadcasting & Delivery

- Use `broadcastToRoom` for multi-recipient messages (internally uses retry + dead-letter queue).
- Use direct `session.send(...)` for one-to-one replies (success/error/ack).
- When applicable, exclude the initiating session from broadcast to avoid echoing.
- Dead-letter: store on final failure; add a background reprocessor in future (see Implementation Plan).

## Payload & Frame Limits

- Keep `ServerConfig.MAX_INCOMING_MESSAGE_BYTES` ≤ `ServerConfig.WS_MAX_FRAME_BYTES`.
- If raising limits, bump both and keep client/server aligned; prefer incremental updates if full-state exceeds limits.

## Android Client Networking

- Always send via `WsClient` abstraction; do not call `WebSocketSession` directly from `ViewModel`.
- Queue outgoing messages when disconnected; cap by `ClientConfig.OUTGOING_BUFFER_MAX`, drop oldest on overflow.
- Gate flush of queued messages until receiving `SessionCreated` or `GameStateUpdate`.
- Reconnect with jittered exponential backoff; send periodic Ping while connected.
- Ignore strictly older `GameState` versions, except allow full reset states (empty deck/table/discard and empty hands).

## Logging & Observability

- Backend: use SLF4J; log INFO for lifecycle events, DEBUG for expected validation failures, ERROR with exceptions for faults.
- Include `roomCode`, `playerName`, and `sessionId` (when available) in log messages for correlation.
- Plan to add metrics (rooms, players, rates, errors, DLQ size) and HTTP health/readiness endpoints.

## Backend Plugins & Configuration

- Add server features via `plugins/*` and wire them in `Application.module()` using `configureX()` functions.
- CORS: `anyHost()` only for development; restrict origins in release builds.
- WebSockets: tune `pingPeriod`, `timeout`, and `maxFrameSize` from `ServerConfig`.

## Additional Checklists

### Adding a new backend handler
- [ ] Add a new `WebSocketMessage` type on server and client with `@SerialName("snake_case")`.
- [ ] Register the handler inside the `when` with the timeout wrapper.
- [ ] Validate inputs early using `ValidationService`.
- [ ] Perform state changes inside `RoomService.executeRoomOperation` + `room.atomicUpdate`.
- [ ] Call `roomService.updateRoom` and then broadcast via `broadcastGameStateUpdate`/`broadcastToRoom` as appropriate.
- [ ] Use `sendWithRetry` implicitly via broadcast helpers; use direct `session.send` for one-to-one replies.
- [ ] Include contextual logging (room, player, session).
- [ ] Add unit tests for happy/validation/error paths.

### Client networking changes
- [ ] Use `WsClient` and keep `KtorViewModel` free from direct session calls.
- [ ] Respect buffer limits and gating rules for outgoing messages.
- [ ] Update friendly error mapping when new `ErrorCode`s are added.
- [ ] Add unit tests around queuing, flush gating, ping, and reconnection/backoff.

## Notes

- Protocol is source of truth: client and server wire models must match exactly.
- For development, we optimize for correctness and clarity; we do not maintain backward compatibility with hypothetical old clients.
