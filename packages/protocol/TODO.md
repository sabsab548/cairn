# packages/protocol — open work

## Wire format

- [ ] F-32 multi-point route packing (see PROTOCOL.md "TODO (F-32)")
- [x] F-40 optional expiry field
- [x] F-41 tombstone packet kind 0x03
- [x] F-83 digest packet kind 0x04
- [x] Predecessor sentinel: author 0 is now reserved for `0x00000000`, and
      `ObjectId.fromBytes` rejects any other ID with author 0. See
      `docs/PROTOCOL.md` and `docs/DECISIONS.md` (ADR-004).

## Testing

- [x] Worst-case size assertions for every packet kind (long names, high
      lat/lon magnitude, UTF-8 multi-byte names)
- [x] Fuzz round-trip: random bytes into `decode` must never throw
      unexpected exceptions (only `IllegalArgumentException`)
- [x] Test that unknown `version` is rejected
- [x] Test that unknown `kind` is dropped silently

## API

- [ ] Consider making `Codec` an interface with a `v1` implementation so a
      future `v2` can be added without breaking callers.
- [ ] Expose a `Packet.size()` helper so the sender can decide before encoding.

## Counter (ADR-004 open question)

- [ ] Decide counter range reserved per boot to avoid reuse after reinstall.
      Proposal: on first launch after install, jump the counter to a random
      16-bit value; subsequent boots just continue.
