# CardsNow App: Comprehensive Implementation Plan

## Overview
This plan focuses on what remains after the mitigations already implemented and validated in code/tests. It aligns with the updated risks in `POTENTIAL_ERRORS_ANALYSIS.md`.

## Baseline implemented (verified)
- Room-level locking and operation queueing with timeout (`Room.atomicUpdate`, `RoomService.executeRoomOperation`).
- State versioning and client guard against older updates.
- Operation timeouts with `TIMEOUT` error and tests.
- Message rate limiting per session and `create_room` IP throttling.
- Payload and frame size limits with explicit errors and tests.
- Send retry + dead-letter queue on repeated failures.
- Validation for names, room codes, card IDs, room settings.
- Secure sessions with TTL, cleanup job, and reconnection flow.
- Android client: jittered exponential reconnect, Ping, bounded outgoing buffer, ack-gated flush.

---

## Phase 1: Critical (ship next)
Focus: Correctness and abuse resistance.

- Presence and host-migration on disconnect
  - Backend: On WebSocket disconnect, remove player from room, reassign host if needed, and broadcast `player_left(roomCode, playerName, newHost)`.
  - Client: Update roster and host flag immediately on event.
  - Acceptance: Disconnect a player → others receive `player_left`; if host leaves, `newHost` set and UI reflects change.

- Join hardening and basic security
  - Increase room code complexity (e.g., 6-digit) and update `ValidationService`, UI input, and server constants.
  - Add IP throttling to `join_room` similar to `create_room`.
  - Restrict CORS in release builds (keep `anyHost()` only in dev).
  - Acceptance: Old 4-digit codes rejected; join attempts rate-limited; release build blocks non-whitelisted origins.

- Idempotency and acknowledgements
  - Add `opId` to client requests; server maintains per-session LRU to deduplicate.
  - Success/Error responses echo `opId`; client clears queued op upon ack.
  - Acceptance: Replayed requests don’t duplicate effects; client UI never double-executes on reconnect.

- Payload/frame safety
  - Raise limits to 64KB via `ServerConfig` and env overrides; audit largest `GameState` payloads.
  - Optional: Begin shaping incremental updates for large changes (non-blocking spike).
  - Acceptance: Full-state updates do not exceed configured ceilings in typical 4-player scenarios.

---

## Phase 2: High (stability and visibility)

- Error code standardization
  - Ensure all `sendError` paths set specific `ErrorCode` values (VALIDATION, NOT_FOUND, AUTHZ, CONFLICT, TIMEOUT, UNKNOWN).
  - Client maps codes to friendly messages and recovery suggestions.
  - Acceptance: 100% of error responses include `code`; client-side mapping table has no fallbacks for expected errors.

- Observability and health
  - Add lightweight metrics (rooms, players, message rates, error counts, dead-letter size) and correlation IDs (room/session).
  - Implement `/health` and `/ready` HTTP endpoints.
  - Acceptance: Metrics exposed; dashboards show trends; health checks integrate with local runner.

- Lifecycle correctness
  - Tests for join/leave, host migration, and reconnection restoring presence state.
  - Acceptance: Automated tests cover presence and host migration flows.

---

## Phase 3: Medium (UX and correctness)

- Client connection FSM
  - Explicit states: connecting, connected, reconnecting, offline; cap retries and surface retry UI.
  - Acceptance: UI shows accurate connection state; retries bounded with user control.

- Validation coverage tightening
  - Enforce `validateCardCount` and expand `RoomSettings` rules; centralize validation calls.
  - Acceptance: Invalid inputs rejected consistently with `VALIDATION` code.

- Test coverage expansion
  - Add unit/integration tests for move/recall/deal edge cases, dead-letter behavior, and session cleanup.
  - Acceptance: New tests pass and reproduce fixed issues.

---

## Phase 4: Long-term (scale and resilience)

- Persistence and recovery
  - Introduce durable storage for rooms/game state; implement recovery on restart.
  - Acceptance: Restart preserves active rooms; data model migrations documented.

- Horizontal scaling
  - Shared state backend and distributed locking; sticky sessions at ingress.
  - Acceptance: Two server instances handle a single room correctly under load.

- Dead-letter reprocessor
  - Background job to retry DLQ messages with backoff; alert if stuck.
  - Acceptance: DLQ drains when recipients reconnect; metrics reflect retries.

- Incremental update protocol
  - Reduce `GameState` payloads via diffs or event streams; versioned schema.
  - Acceptance: Average bytes/update reduced significantly without correctness loss.

---

## Testing strategy
- Unit: validation, handler error paths, locking contention timeouts, idempotency dedup store, presence broadcast.
- Integration: 4-player flows (deal/play/move/recall), disconnect/reconnect, join/leave spam protections.
- Load: message rate limiting, memory stability over long sessions, DLQ behavior.

## Rollout and config
- Feature flags for presence broadcast and idempotency.
- Environment-configurable limits (frame/payload, rate windows, retries).
- Release gating with staged rollout.

## Current status and next steps
- Baseline protections are in place as listed above.
- Next up: Phase 1 tasks, starting with presence/host-migration on disconnect and join hardening.

---

Last Updated: 2025-11-27