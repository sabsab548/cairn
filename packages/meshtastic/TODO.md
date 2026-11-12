# packages/meshtastic — open work

Shared, platform-independent Meshtastic link layer (ADR-007). It moves Cairn
packets through a Meshtastic node's app port and implements `RadioTransport`;
the platform-specific part (socket, tty, GATT) lives in the apps behind
`MeshtasticLink`.

## Known gaps

- [ ] **Android wiring.** `MeshtasticBleDriver` still decodes `FromRadio`
      inline (it is a skeleton). Rewrite it as a `MeshtasticLink` over GATT and
      wrap it in `MeshtasticRadio`, like the desktop links do. No framing is
      needed over BLE: one write/notification is one message.
- [ ] **BLE link (desktop).** BlueZ/D-Bus implementation of `MeshtasticLink`.
      Deferred on purpose: a node accepts ~one BLE client and CI cannot test it.
- [ ] **Routing / ACK handling (F-84).** `send()` always broadcasts with
      `want_ack = false`. When priority/throttle lands, decide whether unicast
      + `want_ack` is worth the airtime.
- [ ] **Channel selection by name.** `--channel` is an index. Reading the
      channel list (`FromRadio.channel`, field 10, currently skipped) would let
      the user pick by name and warn when the index is wrong.
- [ ] **Encrypted payloads.** The client cannot read them, and correctly drops
      them. If a future Meshtastic API exposes the channel key, revisit.
- [ ] **`FromRadio.Other` is logged, not modelled.** Module config, channel and
      queue status are skipped on purpose. Model only what the UI needs.

## Tests

`jvmTest` covers protobuf primitives, every message round trip, unknown-field
skipping, stream framing/resync, the handshake (including the own-node-info
fallback and a wrong nonce) and port filtering. Real radios are exercised from
`apps/desktop` on the bench; see `scripts/test-bench.md`.

## Maintenance

Field numbers are hand-pinned at the bottom of `MeshProto.kt`. When upstream
`meshtastic/protobufs` changes a message we actually use, update the constant
**and** the `// field numbers` block together, and add a round-trip test.
