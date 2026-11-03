# packages/sync — open work

## Still missing from the state machine

- [ ] F-82 pending refs: currently received objects waiting on an unseen
      predecessor are recorded in `pendingRefs` but never resolved.
      Implement the "when the target arrives, emit events for everything
      that was waiting on it" step.
- [ ] F-40 expiry: `SyncEngine.sweep(nowMs)` to grey-then-remove expired
      points. Needs an expiry field on the wire first — see
      `packages/protocol/TODO.md`.
- [ ] F-41 tombstones: ingest a "deleted" packet, mark the target deleted,
      and ensure re-broadcast to peers who were offline. Only possible once
      the tombstone packet kind exists on the wire.
- [ ] F-83 catch-up: produce and consume a "here is what I have" digest.
      Recommended MVP: Bloom filter over received IDs, ≤ 40 bytes,
      piggybacked on the next outgoing packet. See
      `docs/DECISIONS.md` (add a new ADR when you pick one).
- [ ] F-84 priority / throttle: separate module (`Priority.kt`) that
      enforces "position → message → geometry" and a max packet rate.

## Design gaps

- [ ] `RadioTransport` interface not yet defined. Suggestion: keep it tiny —
      `connect`, `send`, `onReceive`, `onState`. Define it in `commonMain`
      so the sync engine never imports BLE types.
- [ ] Clock injection: `now: () -> Long` is fine for tests, but the apps
      need to pass a real monotonic clock AND a real wall clock (for
      F-40 expiry and F-51 message timestamps).
- [ ] Persistence interface not yet defined. SQLite-backed on both
      platforms; `SyncEngine` should take a `Store` interface.

## Testing

- [ ] Dedupe: same packet twice = one event.
- [ ] Latest-wins: newer packet on same ID replaces older.
- [ ] Ordering: out-of-order arrival does not corrupt state.
- [ ] (once implemented) pending refs resolve in both arrival orders.
