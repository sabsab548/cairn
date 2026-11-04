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
