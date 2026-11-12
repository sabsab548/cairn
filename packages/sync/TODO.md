# packages/sync — open work

## Still missing from the state machine

- [x] F-82 pending refs: objects waiting on an unseen predecessor are queued
      and resolved when it arrives, in both arrival orders. A *deleted*
      predecessor resolves them as `orphaned` instead (the route splits).
- [ ] F-40 expiry: `SyncEngine.sweep(nowMs)` to grey-then-remove expired
      points. The wire field exists (`expirySec`).
- [ ] F-41 tombstones: ingest works (a tombstone is a version of its target and
      leaves a version floor). Still missing: **re-broadcast** to peers who
      were offline, so the delete reaches them without them asking.
- [ ] F-83 catch-up: produce and consume a "here is what I have" digest.
      Recommended MVP: Bloom filter over received IDs, ≤ 40 bytes,
      piggybacked on the next outgoing packet. See
      `docs/DECISIONS.md` (add a new ADR when you pick one).
      Note: `ingestDigest` is still the pre-F-81 placeholder.
- [ ] F-84 priority / throttle: separate module (`Priority.kt`) that
      enforces "position → message → geometry" and a max packet rate.

## Design gaps

- [x] `RadioTransport` interface defined in `commonMain` (`RadioTransport.kt`),
      so the sync engine never imports BLE types. Implementations:
      `MeshtasticRadio` in `packages/meshtastic` (shared protobuf layer) and the
      Android GATT driver; the desktop harness adds serial/TCP links below it.
- [ ] Clocks: conflict resolution no longer needs one (the stamp is in the
      packet, ADR-006). The apps still need a physical clock for
      `HybridClock.physicalNowSec` (seconds since 2026-01-01), for F-40 expiry
      and to feed `SyncEngine.now` (staleness / priority). Call
      `HybridClock.observe` on every accepted inbound packet, or a device
      whose clock runs behind will keep minting stale stamps.
- [ ] Skew warning: if an inbound stamp is far ahead of local time, tell the
      user. Never clamp the stamp — clamping breaks convergence.
- [ ] Persistence interface not yet defined. SQLite-backed on both
      platforms; `SyncEngine` should take a `Store` interface. Deleted
      objects must persist too: `Stored.packet == null` is the version floor.

## Testing

- [x] Dedupe: same packet twice = one event.
- [x] Latest-wins: newer packet on same ID replaces older.
- [x] Ordering: any permutation of the same versions converges to the same
      state.
- [x] Pending refs resolve in both arrival orders, and a deleted predecessor
      orphans its waiters instead of leaving them pending.
- [ ] (once F-40 lands) expiry sweep, including "an expired object does not
      come back".
