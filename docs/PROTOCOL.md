# Cairn wire protocol — draft v0.1

**Status:** draft. Unknown fields MUST be skipped (forward compat). Any
change to this file MUST ship in the same PR as a codec change and a test.

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
  - `0x03` Tombstone (F-41) — **TODO, not in v0.1**
  - `0x04` Digest (F-83) — **TODO, not in v0.1**

Unknown `kind` → drop silently.

## Object ID (4 bytes)

    [ type 1B ][ author 1B ][ counter 2B big-endian ]

Type tags:

    0x00  user position
    0x01  route point
    0x02  pin
    0x03  message

`author` is 8 random bits assigned once per install (see ADR-004).
`counter` is per-author, monotonically increasing, wraps at 2^16.

## Flags (1 byte)

    bit 0  closed      (route is a polygon / area)
    bit 1  deleted     (tombstone)
    bit 2  emergency   (F-60)
    bit 3..7 reserved — send as 0, ignore on receive

## Point body

    [ id 4B ][ predecessor 4B ][ flags 1B ][ latE7 4B ][ lonE7 4B ][ nameLen 1B ][ name N B ]

- `predecessor`: `0x00000000` means standalone pin or first route point
  (F-30/F-35). Otherwise the ID of the point this one chains from.
- `latE7` / `lonE7`: signed 32-bit, degrees × 10^7.
- `name`: UTF-8, 0–40 bytes.

Fixed size before `name`: 18 B. Worst case with a 40-byte name: **58 B**.
That leaves 172 B of the 230 B payload free for multi-point packing.

**TODO (F-32):** multi-point route packing. Proposed: a Route packet carries
a `count` byte and delta-coded points (2×2 B per point after the first).
Not implemented yet — see `packages/protocol/TODO.md`.

**TODO (F-40):** optional expiry field (4 B). Not implemented yet.

## Message body

    [ id 4B ][ sender 1B ][ ts 4B ][ ref 4B ][ textLen 1B ][ text N B ]

- `ts`: seconds since `2026-01-01T00:00:00Z`. 32 bits ≈ 136 years.
- `ref`: `0x00000000` = no reference, else an Object ID (F-52).
- `text`: UTF-8, 1–180 bytes.

Worst case: 4+1+4+4+1+180 = **194 B**. Fits, with headroom.

## Airtime budget (F-84)

Not on the wire. Sender-side policy. Order: **position → message →
geometry**. See `packages/sync/TODO.md`.

## Versioning

- Incompatible change → bump `version`.
- Backwards-compatible addition → use a reserved flag bit, or add a new `kind`.
- The reference codec MUST reject unknown `version` and drop unknown `kind`.
