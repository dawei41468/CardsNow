# CardsNow Production Readiness Plan and Codebase Review

This document outlines what remains to take CardsNow to production, with specific recommendations based on the current codebase and the project patterns in `docs/PROJECT_PATTERNS.md`.

## Executive Summary

- Phase 1 and 2 are complete. Core flows work; idempotency (opId + dedup LRU) and JSON metrics are in place. Tests pass for backend and Android unit tests, including ack-correlation.
- Not yet ready for public production. The remaining P0 items are persistence/recovery, production deployment hardening (TLS/proxy/config), and abuse protection.
- P1 covers DLQ reprocessing, horizontal observability/alerting, and client connection FSM UI polish. P2 covers scale-out and protocol optimization.

---

## P0 (Must do before production)

- **Persistence & Recovery**
  - Introduce durable storage for room and game state.
  - On server restart, recover rooms to preserve active games or provide graceful shutdown semantics to drain rooms.
  - Document schema and migration plan.

- **Production deployment hardening**
  - Terminate TLS (HTTPS/WSS) at a reverse proxy (Nginx/Envoy) with proper WebSocket upgrade config.
  - Configure timeouts, keep-alive, `proxy_read_timeout` to exceed WS ping interval.
  - Set production `CORS_ALLOWED_HOSTS` and disable `anyHost`.
  - Configure server memory/CPU limits, JVM flags, and graceful shutdown hooks.

- **Abuse protection**
  - 4-digit room codes are guessable under high-rate probing. Options:
    - Increase code space (e.g., 6-digit) or add an optional join secret/token.
    - Add CAPTCHA or an additional server-side throttling layer for `join_room`.
  - Confirm IP rate limiting windows in production env and add WAF/rate-limiting at the proxy.

- **Operational configuration**
  - Parameterize and externalize all server configs via env (timeouts, limits, rate windows).
  - Provision secrets (if any) via a secure store; never commit secrets.
  - Validate `/ready` for rollout gating and cordon new WS connections on draining.

- **Logging & Privacy**
  - Ensure logs contain correlation fields (`roomCode`, `playerName`, `sessionId`) while avoiding PII.
  - Retention policy and log rotation.

---

## P1 (Shortly after launch)

- **DLQ reprocessor**
  - Background job with backoff to drain `deadLetterQueue`.
  - Alerts on stuck messages; metrics for retries and successes.

- **Observability & Alerting**
  - Use `/metrics` to drive alerts:
    - Errors per code spike, DLQ size increase, WS connections trends, message rate anomalies.
  - Add structured logging fields consistently and ensure sampling strategy for high-volume logs.

- **Client connection FSM & UX**
  - Implement explicit states (connecting/reconnecting/offline) with bounded retries and clear UI indicators.
  - Surface retry controls and exponential backoff feedback.

- **Security polish**
  - Consider optional auth for private rooms.
  - Harden CORS and origin-check beyond WebView usage.

---

## P2 (Later)

- **Horizontal scaling**
  - Shared state backend + distributed locking.
  - Sticky sessions at ingress; doc expectations for reconnection across nodes.

- **Protocol optimization**
  - Incremental updates/patches for `GameState` to reduce payloads.

- **CI/CD & Infra**
  - Containerize backend, add repeatable deployments (IaC), and implement smoke tests post-deploy.

---

## Codebase Review and Cleanup Recommendations

Aligned to `docs/PROJECT_PATTERNS.md` and current code snapshots.

### Backend (Ktor)

- **ConnectionService**
  - Idempotency helpers look good (`sendError`/`sendSuccess` include `opId` + cache ack). Keep early returns and small helpers to avoid deep nesting.
  - Centralize validation via a `ValidationService` (as per Patterns) to ensure all handlers call shared validators first.
  - Ensure all `sendError` calls set `ErrorCode` (already done) and include room/session/player context in logs.
  - Extract common player/room lookup boilerplate into small utilities to reduce duplication.

- **MetricsService**
  - Snapshot JSON schema is clear. Add comments or a README section describing metric semantics for operators.
  - Add error counters by type and code (already present). Consider rate-limited logging when error bursts happen.

- **Plugins/Configuration**
  - Verify `Serialization.kt` is configured consistently with client (discriminator + `explicitNulls=false`, `encodeDefaults=true`). If not, align or document the difference.
  - Ensure `WebSockets.kt` max frame size and timeouts match `ServerConfig` values and are documented via env.

- **Logging**
  - Use SLF4J consistently. Avoid `println`. Include correlation IDs. Consider MDC for `sessionId`/`roomCode`.

- **Testing**
  - Add handler-level tests for idempotency (duplicate `opId` returns cached ack and no re-execution).
  - Add DLQ path tests (sendWithRetry failure increments counters).

### Android Client

- **KtorViewModel**
  - Ack-correlation is integrated (`OpAckTracker`). Good separation via `WsClient` abstraction.
  - Consider splitting concerns (networking, state mapping, UI events) into smaller classes to reduce file size and improve readability.
  - Move user-facing strings (friendly errors) into resources for localization and clarity.
  - Ensure JSON config aligns with server: `classDiscriminator = "messageType"` is set; consider adding `explicitNulls=false` and `encodeDefaults=true` like server for parity.
  - Confirm we never rely on `Success` for control flow (per Patterns) — currently used only for UI toast-like messages.

- **OpAckTracker**
  - Solid minimal design. Add unit tests already done (timeout, replay). Consider exposing a small interface to mock in higher-level tests.
  - Ensure cleanup on `ViewModel.onCleared` (already done). 

- **WsClient**
  - Keep the abstraction thin; confirm all sending goes through it (no direct `WebSocketSession` calls from `ViewModel`).

- **Testing**
  - Continue to add round-trip tests for all `WebSocketMessage` variants.
  - Expand tests around reconnect flows (queue gating, replay of pending ops, and ack resolution).

### Cross-cutting cleanup

- **Consistency & Conciseness**
  - Deduplicate error mapping on the client into a small helper (e.g., `friendlyError(wsMessage)`), referenced in `handleIncomingMessage`.
  - Extract `messageOpId` helpers as extension functions for brevity.
  - Avoid unused imports and keep imports organized.

- **Static analysis & formatting**
  - Add ktlint/spotless (client and backend) and detekt (optional) to enforce style and catch issues.
  - Enable Gradle tasks to check formatting in CI.

---

## Adherence to PROJECT_PATTERNS.md (Status)

- **Serialization & Wire Protocol**: OK
  - Sealed message hierarchy present on both sides; `messageType` discriminator used. Recommendation: align `explicitNulls=false`, `encodeDefaults=true` on client.
- **Message Naming**: OK
  - `@SerialName` uses snake_case.
- **Wire vs UI Models**: OK
  - Wire lives in `wire` packages; UI models on client.
- **Android Client Patterns**: OK/Minor
  - Logs guarded by `ClientConfig.IS_DEBUG`. Consider moving friendly error strings to resources.
- **Backend Patterns**: OK/Minor
  - SLF4J logging; ensure all error paths include codes and context consistently.
- **Testing Patterns**: Partial
  - Round-trip and idempotency tests exist in part. Expand breadth across all message variants and DLQ.
- **Presence & Lifecycle**: OK
  - Hybrid presence implemented per spec.
- **Session Management**: OK
  - TTL and reconnect supported; ensure periodic cleanup job continues to run.
- **Broadcasting & Delivery**: OK/Minor
  - DLQ metrics present; add reprocessor and alerts later.
- **Payload & Frame Limits**: OK
  - 64KB enforced; keep aligned client/server.
- **Client Networking**: OK
  - Uses `WsClient`, buffering, gating, pings, and version checks.

---

## Production Rollout Checklist

- **Infrastructure**
  - [ ] Reverse proxy (TLS/WSS) configured with WS upgrade, timeouts, and CORS.
  - [ ] Server resource limits and JVM settings tuned.
  - [ ] Environment variables set for all `ServerConfig` and CORS.

- **Data**
  - [ ] Durable storage for room/game state.
  - [ ] Backup, migration plan, and data retention policy.

- **Security**
  - [ ] Abuse protections for join/create (WAF, CAPTCHA/secret, increased code space).
  - [ ] Secrets managed securely.

- **Observability**
  - [ ] Alerts on `/metrics` (errors by code, DLQ size, WS connections, message rate).
  - [ ] Log retention and correlation fields.

- **Client**
  - [ ] Connection FSM and UI polish.
  - [ ] Strings localized; error messages mapped in one helper.

- **Testing**
  - [ ] Load/soak tests for WS stability.
  - [ ] Integration tests for DLQ and idempotency on duplicate sends.

---

## Appendix: Deployment Notes

- **Nginx (example) essentials**
  - `proxy_set_header Upgrade $http_upgrade;`
  - `proxy_set_header Connection "upgrade";`
  - `proxy_read_timeout` > client ping interval (e.g., > 30s).
  - CORS allow-listing only production hosts; block others.

- **Configuration**
  - Keep server/client limits aligned (frame/payload).
  - Document env var defaults and overrides.

---

## Next Steps (Recommended Order)

1. Implement persistence and recovery for rooms/game state.
2. Deploy behind TLS proxy with production CORS and timeouts configured.
3. Add abuse protection (code space expansion or join secret + proxy rate-limit/WAF).
4. Add DLQ reprocessor and alerting on `/metrics`.
5. Add client connection FSM and polish UX for reconnect.
6. Add CI checks (ktlint/spotless/detekt), expand tests (round-trip, DLQ, idempotency).
