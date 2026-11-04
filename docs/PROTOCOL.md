# Cairn wire protocol — draft v0.1

**Status:** draft. Unknown fields MUST be skipped (forward compat). Any
change to this file MUST ship in the same PR as a codec change and a test.

> 2026-11-04: packets gained a **version stamp** (F-81). Nothing had shipped,
> so the layouts changed in place and `version` stays `0x01`. See ADR-006.

## Transport

Cairn packets travel as the payload of a Meshtastic **private application
port** in the range 256–511 (F-02). The exact port is chosen at join time
by the group and configurable (F-03). Cairn does **not** use the built-in
`POSITION_APP` / `TEXT_MESSAGE_APP` ports — those belong to the firmware.

## Packet envelope

One Cairn packet = one Meshtastic payload. **Hard limit: 230 bytes** (N-05).

    +---------+--------+-----------------+
    | version |  kind  |     body …      |
    |  1 B    |  1 B   |    ≤ 228 B      |
    +---------+--------+-----------------+

- `version` = `0x01`. Bump on incompatible changes. Reject unknown versions.
- `kind`:
  - `0x01` Point
  - `0x02` Message
  - `0x03` Tombstone (F-41)
  - `0x04` Digest (F-83)

Unknown `kind` → drop silently.

## Object ID (4 bytes)

    [ type 1B ][ author 1B ][ counter 2B big-endian ]

Type tags:

    0x00  user position
    0x01  route point
    0x02  pin
    0x03  message

`author` is 8 random bits assigned once per install (see ADR-004).
**`author = 0` is reserved** for the nil ID `0x00000000`; real objects use
`1..255`, and the codec rejects any other ID with author 0.
`counter` is per-author, monotonically increasing, wraps at 2^16.

## Flags (1 byte)

    bit 0  closed      (route is a polygon / area)
    bit 1  deleted     (reserved — deletion travels as a Tombstone packet, F-41)
    bit 2  emergency   (F-60)
    bit 3  hasExpiry   (F-40 — optional 4 B expiry field after name)
    bit 4..7 reserved — send as 0, ignore on receive

## Version stamp (F-81)

Every object packet carries a 6-byte hybrid logical clock stamp:

    [ sec 4B big-endian ][ count 2B big-endian ]

- `sec`: the sender's time, **seconds since 2026-01-01T00:00:00Z** — the same
  epoch a message's `ts` uses.
- `count`: a logical counter that breaks same-second ties. It is bumped when a
  writer produces two stamps in the same second, and when it hears a peer's
  stamp. It never overflows into a tie: it carries into `sec` instead.

Ordering is lexicographic on `(sec, count)`. Two peers that hold the same set
of versions therefore always agree on the winner, whatever the delivery order.
A pure wall clock is not enough — two edits in the same second would tie.

Equal stamps are broken by author ID, then by content hash, so the order is
total (see `docs/DECISIONS.md` ADR-006). Implemented by
`org.cairn.protocol.Hlc` and `HybridClock`.

## Point body

    [ id 4B ][ predecessor 4B ][ flags 1B ][ version 6B ][ latE7 4B ][ lonE7 4B ][ nameLen 1B ][ name N B ][ expiry 4B? ]

- `predecessor`: `0x00000000` (the nil ID) means standalone pin or first
  route point (F-30/F-35). Otherwise the ID of the point this one chains
  from. The nil ID cannot collide with a real object because author 0 is
  reserved (see "Object ID" above).
- `version`: see "Version stamp" above.
- `latE7` / `lonE7`: signed 32-bit, degrees × 10^7.
- `name`: UTF-8, 0–40 bytes.

Fixed size before `name`: 26 B (including the 2-byte envelope). Worst case
with a 40-byte name: **66 B**; with the optional expiry field: **70 B**. That
leaves 160 B of the 230 B payload free for multi-point packing.

**F-32:** multi-point route packing — not implemented yet.
Proposed: a Route packet carries a `count` byte and delta-coded points
(2×2 B per point after the first). See `packages/protocol/TODO.md`.

## Message body

    [ id 4B ][ sender 1B ][ version 6B ][ ref 4B ][ textLen 1B ][ text N B ]

- `version.sec`: seconds since `2026-01-01T00:00:00Z`. 32 bits ≈ 136 years,
  and it doubles as the displayed send time (F-51). `version.count` is 0 in
  practice — messages are never edited.
- `ref`: `0x00000000` = no reference, else an Object ID (F-52).
- `text`: UTF-8, 1–180 bytes.

Worst case: 2+4+1+6+4+1+180 = **198 B**. Fits, with headroom.

## Tombstone body (F-41)

    [ id 4B ][ target 4B ][ version 6B ]

16 B. The tombstone is **not** an object of its own: `version` competes with
the target's own version. A tombstone with a higher stamp deletes the target
and cannot be undone by a stale copy that an offline phone still holds; a
later edit with a higher stamp undeletes it. `id` only identifies the
sender's tombstone (its author byte is what breaks delete/edit ties).

A peer that receives a tombstone for an object it never had MUST keep the
stamp as a floor, so the object cannot be resurrected later.

## Digest body (F-83)

    [ id 4B ][ filterLen 1B ][ filterBytes N B ]

- `filterLen`: number of bytes in the filter (0–40).
- `filterBytes`: the digest payload (currently a Bloom filter, per
  `packages/sync/TODO.md`).

Fixed overhead: 7 B (including the 2-byte envelope). Filter capped at
**40 B** (≤ 230 B total packet).

## Airtime budget (F-84)

Not on the wire. Sender-side policy. Order: **position → message →
geometry**. See `packages/sync/TODO.md`.

## Versioning

- Incompatible change → bump `version`.
- Backwards-compatible addition → use a reserved flag bit, or add a new `kind`.
- The reference codec MUST reject unknown `version` and drop unknown `kind`.
