# Testing

## Two kinds of tests

### 1. CI tests — every PR, no devices

Everything that can be pure logic is pure logic, and lives in
`packages/protocol` / `packages/sync`. On every PR:

- Round-trip encode/decode for every packet kind
- Exact size assertions per packet kind (worst case pinned to PROTOCOL.md)
- HLC: ordering, same-second counter, monotonicity (`HlcTest`)
- Sync engine: dedupe, latest-wins, pending refs, tombstones
- Convergence: the same versions in **every** delivery order give the same
  state (see ADR-006 — this is the test that makes latest-wins real)
- Android compile (once the module exists)
- iOS framework link (once the module exists)

That is what `.github/workflows/pr-checks.yml` runs.

### 2. Bench tests — before every release tag, real hardware

Everything BLE / iOS background / tile-file. See `scripts/test-bench.md`
for the concrete physical setup and the manual checklist. These are **not**
in CI. Do not try to put them in CI; you will spend more time fighting
self-hosted runners than writing features.

## The three-phone acceptance test (Lastenheft §12)

1. Three phones, each with its own Meshtastic node, no internet:
   all three see each other's positions on an offline map.
2. A pin dropped on phone A appears on B and C.
3. A route drawn on A appears on B and C with direction; editable and
   deletable.
4. A text message from A arrives on B and C.
5. A phone switched off during 1–4 catches up when it reconnects
   (only if F-83 is implemented).

Run this on the bench before every release tag.

## What you *can* test without hardware

- `./gradlew :packages:protocol:jvmTest`
- `./gradlew :packages:sync:jvmTest`

If your feature is not covered by one of those, ask yourself whether it
could be, before giving up and reaching for a device.
