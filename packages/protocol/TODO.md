# packages/protocol — open work

## Wire format

- [ ] F-32 multi-point route packing (see PROTOCOL.md "TODO (F-32)")
- [ ] F-40 optional expiry field
- [ ] F-41 tombstone packet kind 0x03
- [ ] F-83 digest packet kind 0x04
- [ ] Predecessor == `0x00000000` sentinel collides with author=0/counter=0.
      Pick a cleaner "no predecessor" marker and migrate the codec. See
      the corresponding comment in `Codec.kt`.

## Testing

- [ ] Worst-case size assertions for every packet kind (long names, high
      lat/lon magnitude, UTF-8 multi-byte names)
- [ ] Fuzz round-trip: random bytes into `decode` must never throw
      unexpected exceptions (only `IllegalArgumentException`)
- [ ] Test that unknown `version` is rejected
- [ ] Test that unknown `kind` is dropped silently

## API

- [ ] Consider making `Codec` an interface with a `v1` implementation so a
      future `v2` can be added without breaking callers.
- [ ] Expose a `Packet.size()` helper so the sender can decide before encoding.

## Counter (ADR-004 open question)

- [ ] Decide counter range reserved per boot to avoid reuse after reinstall.
      Proposal: on first launch after install, jump the counter to a random
      16-bit value; subsequent boots just continue.
