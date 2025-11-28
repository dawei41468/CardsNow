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
- Presence with 30s grace and host migration on disconnect (hybrid presence: mark offline, remove after grace if not reconnected) with tests.
- Join hardening: `join_room` IP throttling; kept 4-digit room codes (1000–9999) by design.
- CORS restricted in release builds via config; permissive only in dev or when explicitly allowed.
- Frame/payload limits raised to 64KB and enforced client/server.
- Android client: jittered exponential reconnect, Ping, bounded outgoing buffer, ack-gated flush.

---

## Phase 1: Critical (COMPLETED)
Focus: Correctness and abuse resistance.

- Presence and host-migration on disconnect (Hybrid with 30s grace)
  - Backend: On WebSocket disconnect, mark player offline and broadcast presence via `GameStateUpdate`. If not reconnected within grace, remove, reassign host if needed, and broadcast `player_left(roomCode, playerName, newHost)`.
  - Client: Roster reflects presence and host changes when events/states arrive.
  - Acceptance: Disconnect → presence shows offline; after grace with no reconnect → `player_left` broadcast; if host leaves, `newHost` set.

- Join hardening and basic security
  - Kept room codes at 4 digits (1000–9999) per product choice.
  - Added IP throttling to `join_room` similar to `create_room`.
  - Restricted CORS in release builds (permitted only for allowed hosts; dev can use anyHost()).
  - Acceptance: 4-digit codes accepted; join attempts rate-limited; release build blocks non-whitelisted origins.

- Payload/frame safety
  - Limits raised to 64KB via `ServerConfig`; enforced on inbound payload and WS frames.
  - Acceptance: Full-state updates fit within configured limits in typical scenarios.

---

## Phase 2: High (stability and visibility) (COMPLETED)

- [1] Error code standardization (COMPLETED)
  - Ensure all `sendError` paths set specific `ErrorCode` values (VALIDATION, NOT_FOUND, AUTHZ, CONFLICT, TIMEOUT, INVALID_FORMAT, RATE_LIMITED, PAYLOAD_TOO_LARGE, UNKNOWN).
  - Client maps codes to friendly messages and recovery suggestions.
  - Acceptance: 100% of error responses include `code`; client-side mapping table has no fallbacks for expected errors.

- [2] Health and readiness endpoints (COMPLETED)
  - Add `/health` and `/ready` HTTP endpoints with lightweight checks.
  - Acceptance: Health endpoints return 200 OK; basic readiness/health checks validated.

- [3] Observability metrics (COMPLETED)
  - Expose JSON metrics at `/metrics` with: `rooms_total`, `players_total`, `ws_connections_current`, `ws_sessions_known`, `messages_total`, `messages_per_minute`, `errors_total_by_code`, `dlq_messages_total`, `dlq_rooms`.
  - Acceptance: `/metrics` returns expected snapshot; fields validated.

- [4] Idempotency and acknowledgements (COMPLETED)
  - Add `opId` to client requests; server maintains per-session dedup LRU.
  - Success/Error responses echo `opId`; client clears queued op upon ack.
  - Acceptance: Replayed requests don’t duplicate effects; client UI never double-executes on reconnect.

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
- Phase 2 is completed (error code standardization, health/readiness endpoints, JSON metrics, idempotency/acks).
- Next up (Phase 3 sequence):
  1. Client connection FSM
  2. Validation coverage tightening
  3. Test coverage expansion

---

Last Updated: 2025-11-28