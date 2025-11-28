# CardsNow App: Potential Errors Analysis

 Last reviewed: 2025-11-27

 This document reflects the current code in the Android app and cardsnow-backend and removes risks already mitigated.

 ## What’s already addressed (validated in code/tests)
 - Per-room concurrency control via `Room.atomicUpdate {}` and `RoomService.executeRoomOperation()` with lock timeout.
 - Game state versioning (`GameState.version`), client-side version guard to ignore older updates.
 - Operation timeouts with graceful error (`TIMEOUT`) using `withTimeout(opTimeoutMs)`; covered by tests.
 - Per-session message rate limiting and IP-based room-create throttling.
 - Incoming payload limit and server WebSocket `maxFrameSize` set (10 KB) with explicit errors for oversized payloads; covered by tests.
 - Send retry with exponential backoff and dead-letter queue on repeated broadcast failures.
 - Input validation (player name, room code, card IDs, room settings) applied in handlers.
 - Session model with secure IDs, TTL (24h), and cleanup job; reconnection flow restores sessions.
 - Android client: jittered exponential reconnect, periodic Ping, bounded outgoing buffer (10), gated flush until first ack (SessionCreated/GameStateUpdate).

 ---

 ## Current priority risks (only what still applies)

 ### Critical
 - Presence/disconnect semantics not broadcast
   - On disconnect, server cleans up mappings but does not emit `player_left` or update player presence; UI may be stale until next event.
   - Host migration after disconnect is computed in `Room.removePlayer` but not triggered on network disconnects.
   - Action: broadcast `PlayerLeft` on disconnect and, if host changed, include `newHost`.

 - Room code brute-force and join spam
   - `join_room` is not IP-rate-limited; room codes are 4 digits (9k combos). `create_room` is throttled; `join_room` is not.
   - CORS is `anyHost()`; safe for dev but risky if left in prod.
   - Actions: increase to 6-digit codes or alphanumeric; add IP throttling for `join_room`; restrict CORS in release builds.

 - Idempotency/duplicate actions
   - No operation IDs/ack at application level; client retries or reconnect resends could duplicate effects.
   - Actions: add `opId` to client requests, track last-N per session on server for dedup; reply with explicit acks.

 - Frame/payload ceiling vs state size
   - `maxFrameSize` (10 KB) and payload limit may be exceeded by full `GameState` after deals/shuffles.
   - Actions: raise limits (e.g., 64 KB), or send more incremental updates; make limits configurable via env.

 ### High
 - Error code consistency
   - Not all `sendError` calls include `ErrorCode`; client mapping assumes codes in places.
   - Action: standardize error codes for validation, not-found, auth/authorization, conflict, and timeout paths.

 - Observability gaps
   - Only CallLogging is enabled; no metrics/tracing/correlation IDs.
   - Actions: add metrics (rooms, players, rates, failures), correlation IDs (room/session), and health/readiness endpoints.

 - Join/leave lifecycle correctness
   - Ensure host reassignment is always applied and broadcast when the previous host disconnects.

 ### Medium
 - Client connection FSM and retry policy
   - Reconnect is jittered and capped by backoff, but attempts are unbounded; no explicit connection state machine.
   - Actions: cap attempts with user prompt and clearer UI states.

 - Validation coverage gaps
   - `ValidationService.validateCardCount` exists but is not uniformly enforced; `RoomSettings` rules are minimal.
   - Actions: tighten and centralize validation usage for all handlers.

 - Test coverage breadth
   - Unit tests exist (schema, payload limits, timeouts) but lack coverage for move/recall/deal/host-migration and presence.
   - Actions: add unit/integration tests for these flows and for session cleanup.

 ### Low (longer-term)
 - Single-instance in-memory state; no persistence across restarts; no horizontal scaling.
 - Dead-letter queue has no reprocessor; messages are stored but not retried after recovery.
 - Any-host CORS still present by default; ensure release configuration tightens this.

 ---

 ## Immediate watchlist
 - Disconnect without `PlayerLeft` broadcast (user-visible stale rosters).
 - Oversized `GameState` updates tripping 10 KB frame limit.
 - Join-room brute-force attempts (consider simple telemetry/alerts now).

 ## Notes
 - The above reflects code as of 2025-11-27 in both app and backend. Items previously listed (no timeouts, no rate limiting, no retries, no session cleanup, no outgoing buffer, etc.) are resolved and therefore removed from this analysis.