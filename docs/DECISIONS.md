# Architecture Decision Records

Short, dated, immutable once *Accepted*. Supersede by adding a new ADR.

---

## ADR-001 — Monorepo with a shared KMP protocol core

**Date:** 2026-11-03 · **Status:** Accepted

### Context
N-04 requires Android + iOS apps that must exchange identical bytes over
LoRa. Two independent codecs in two repos drift silently — and the wire
format is exactly the part where drift hurts most.

### Decision
Single repo. Two pure Kotlin Multiplatform modules (`protocol`, `sync`) with
zero platform dependencies. Two thin native apps.

### Consequences
- ✅ Wire codec and sync logic are JVM-tested on every PR.
- ✅ A protocol change is one PR in one place.
- ❌ iOS engineers must read Kotlin. Acceptable: the modules are small,
  heavily commented, and iOS consumes the compiled framework.

---

## ADR-002 — Trunk-based development

**Date:** 2026-11-03 · **Status:** Accepted

`main` is always releasable. Short-lived branches, PR required, CI green,
one approving review. No develop/release/hotfix branch hierarchy.

---

## ADR-003 — Protocol layer is pure logic, transport is native

**Date:** 2026-11-03 · **Status:** Accepted

BLE is deeply platform-specific (Android GATT + MTU, iOS CoreBluetooth +
state restoration). Trying to unify BLE in KMP blocks the iOS engineer.

### Decision
- `packages/protocol` and `packages/sync` contain **zero** BLE code.
- Each app implements a `RadioTransport` interface natively.
- CI never touches BLE. BLE is validated on the physical bench.

### Consequences
- ✅ CI runs in seconds and is deterministic.
- ❌ The most likely breakage (pairing / reconnect) is not covered by CI.
  Mitigation: bench run before every release tag.

---

## ADR-004 — Author-ID collision policy

**Date:** 2026-11-03 · **Status:** Proposed (answers Open Question 2)

### Context
Object IDs carry an 8-bit author ID. In small groups (3–10) random
collisions are unlikely but not impossible.

### Proposal
- Each install picks a random author ID in `1..255` at first launch,
  persisted. `0` is reserved for the nil object ID `0x00000000` used for
  "no predecessor" / "no reference", so the sentinel can never collide
  with a real object.
- On ingest, if the author byte matches ours but the counter was not issued
  by us, log a warning and show a small "ID conflict" banner.
- "Re-roll my author ID" button in settings. Existing objects keep their
  baked-in author byte; only new ones use the new value.

### Open
- Should the app reserve a counter range per boot to avoid counter reuse
  after a reinstall? See `packages/protocol/TODO.md`.

---

## ADR-005 — License split

**Date:** 2026-11-03 · **Status:** Accepted

- Apps: **AGPL-3.0-or-later**
- Protocol spec (`docs/PROTOCOL.md`): **CC-BY-4.0**
- Reference codec + sync engine: **MIT**

Rationale: AGPL keeps derivative *apps* open; MIT on the reference codec
encourages third-party tooling and alternative frontends (N-09).

---

## ADR-006 — Latest-wins ordering (F-81)

**Date:** 2026-11-04 · **Status:** Accepted

### Context
F-81 only says "for the same ID, the newest version wins" and never defines
*newest*. The first implementation used the **local receive timestamp**, which
is not latest-wins but last-to-arrive-wins:

- two peers that heard the same two versions in opposite order kept
  different versions (permanent divergence),
- a neighbour relaying an old copy made the map revert,
- two versions landing in the same millisecond dropped one of them,
- a replayed old packet won (see `SECURITY.md`).

Edits reuse the object's ID (`F-31` makes the route ID its head point's ID, and
predecessor/reference fields point at IDs), so the ID cannot distinguish two
versions of the same object. A version has to be carried explicitly.

### Decision

1. **Identity on edit.** An edit keeps the `ObjectId` and carries a new
   version stamp. The alternative — a fresh ID per edit plus a "supersedes"
   pointer — was rejected: it costs a packet per edit over LoRa and breaks the
   predecessor chains F-82/F-32 depend on.
2. **The stamp is a hybrid logical clock**, 6 bytes: physical seconds since
   2026-01-01 plus a 16-bit counter. Ordering is lexicographic. This makes the
   order **total**, so two peers holding the same versions always agree
   regardless of delivery order.
3. **Ties** (same stamp) are broken by author ID, then by content hash. The
   last resort only matters if one writer emits two different contents with
   one stamp — a bug or an author-ID collision (ADR-004) — but it still leaves
   every peer with the same answer.
4. **Any member may edit any object.** There is no ownership: §9 of the
   Lastenheft defines trust as "whoever holds the channel key". This is what
   makes a cross-author clock necessary instead of a per-author counter.
5. **A tombstone is a version of its target**, not an object of its own. If it
   wins, the payload is dropped and only `(id, stamp, author)` is kept as a
   **floor**. A stale copy cannot resurrect the object; a newer edit can
   undelete it.
6. **Pending ≠ deleted.** F-82 keeps a point queued while its predecessor is
   unknown. If the predecessor is *deleted*, the waiter is not pending any
   more: it becomes the head of a new segment, i.e. **the route splits in
   two**. Deleting a point in a chain stored as back-pointers breaks exactly
   one link, so a split costs one tombstone and no other packet changes.
7. **Everything editable.** Positions, pins and route points are all
   `PointPacket`, so one rule covers them. Messages stay immutable (a message's
   stamp is its send time); digest packets are not objects.
8. **Never clamp a stamp** that is far in the future. Clamping is
   non-monotonic and breaks convergence. Surface "a peer's clock is ~N minutes
   ahead" instead.

### Rejected alternatives

| Option | Why not |
|---|---|
| Local receive time (previous code) | Does not converge; replays win |
| Per-author counter (Lamport) | Converges without any clock, but "newest" becomes meaningless: a peer that never saw v4 edits from v2 and silently loses to a version it never knew |
| Plain wall clock, seconds | Same-second edits tie, so an author's own second edit can lose to their first |
| Immutable objects, delete + recreate | Kill the clock entirely, but a moved route point forces rewriting the whole downstream tail (O(n) packets, visible duplicate segments on peers) and two routes can never be linked |
| Explicit link/edge records | Contradicts F-30 (pointer lives in the point) and creates two sources of truth |

### Consequences

- ✅ Maps converge. "Newest" means "the sender's clock says later", which is
  what users expect, and it now holds regardless of arrival order.
- ✅ Replay/rollback is **bounded** rather than free: anything at or below the
  per-object floor is dropped.
- ❌ +6 B on every point (worst case 60 → 66 B, 70 B with expiry), +2 B on
  messages (198 B) and tombstones (16 B). Well inside the 230 B limit.
- ❌ A phone with a badly wrong clock still wins concurrent edits within its
  skew window. HLC does not detect skew; only the app-layer warning can.
- ❌ No `version` byte bump — only because nothing had shipped. Any later layout
  change bumps `version`.

### Not in v1

- **Group epoch / bulk clear.** Attractive as *the* cheap bulk delete (one
  packet instead of N tombstones), but anyone with the key can wipe the whole
  crew's map, partitions make "cleared" ambiguous, and the epoch itself needs
  ordering on the same axis. For v1, map clutter is answered by F-40 expiry, a
  fresh operation by a new channel (F-90), and "hide everything from before
  today" by a purely local filter.
- **Author-only writes for user positions** (`type 0x00`): a position is a
  claim about a person, so a peer overwriting it is odd. In practice IDs are
  per-author, so this only bites on an author-ID collision. Not enforced yet.

---

## ADR-007 — Desktop harness and a shared Meshtastic link layer

**Date:** 2026-11-12 · **Status:** Accepted

### Context

Development was gated on a phone plus a paired radio, which makes the
highest-risk code (the radio link) the hardest to iterate on. ADR-003 put the
BLE driver below the `RadioTransport` line and kept everything above it pure,
but the Android driver still said "TODO: decode Meshtastic FromRadio protobuf":
the codec existed, the Meshtastic envelope did not.

A PC can reach a Meshtastic node over USB serial or TCP/4403, both of which
speak the exact same `ToRadio`/`FromRadio` protobuf stream that BLE does.

### Decision

1. **One shared Meshtastic layer** (`packages/meshtastic`, pure KMP): the
   subset of the protobuf messages Cairn needs, the `0x94 0xC3` stream framing
   and `MeshtasticRadio : RadioTransport`. No protobuf runtime, no BLE types;
   unknown fields are skipped so firmware updates do not break us.
2. **Desktop links are TCP and USB serial, not BLE.** BLE on Linux needs
   BlueZ/D-Bus, a node accepts ~one BLE client, and none of it is testable in
   CI. Serial/TCP have no permissions (beyond `dialout`), no exclusivity, and
   the identical protocol on the wire. BLE becomes a `MeshtasticLink`
   implementation later; the seam is already there.
3. **The desktop app is a CLI harness**, on a `jvm("desktop")` target
   (the layout Compose Desktop uses) so a UI can be added without moving
   source sets.
4. **Cairn's app port defaults to 256** (Meshtastic `PRIVATE_APP`, inside
   F-02's 256–511 range) and is configurable (`--port`); every peer must agree.

### Consequences

- ✅ The whole pipeline except the socket/tty itself is JVM-tested: protobuf
  round-trips, framing resync, handshake, port filtering and the sync engine.
- ✅ Android gets the protobuf layer for free; the driver only has to move
  bytes over GATT. ADR-003 still holds: no BLE in shared code.
- ✅ A laptop with a USB cable is now a development environment; `--link mock`
  needs no hardware at all.
- ❌ BLE behaviour (pairing, reconnect, MTU) still cannot be exercised from a
  PC. That remains bench-only, as ADR-003 already accepted.
- ❌ Hand-rolled protobuf is a maintenance surface. It is small, pinned to
  upstream field numbers and covered by tests, but it must be updated by hand
  when a needed message changes.
