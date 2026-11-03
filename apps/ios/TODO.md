# apps/ios — open work

## Missing entirely (blocker for v1)

- [ ] Xcode project / workspace.
- [ ] Swift package consuming `packages/protocol` and `packages/sync` as
      `.xcframework`.
- [ ] `CoreBluetooth` implementation of `RadioTransport`.
- [ ] Tile file reader (MBTiles / PMTiles) — likely `MapLibre Native` for
      rendering plus a small SQLite reader.
- [ ] Persistence (SQLite.swift or GRDB) behind the `Store` interface.

## Known risks

- **iOS background limits.** CoreBluetooth connections can be suspended
  when the app is backgrounded. Keeping the app foreground may be required
  in v1 — document this clearly to users.
- **Sideloading / distribution.** Free Apple developer accounts can't ship
  test builds easily; decide distribution model early.

## Bench work

- [ ] Verify the app stays connected with the screen off (or document
      that it cannot).
- [ ] Verify re-connect after Bluetooth drop (F-05).

See `scripts/test-bench.md`.
