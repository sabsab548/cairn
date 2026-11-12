# apps/android — open work

## Missing entirely (blocker for v1)

- [x] Real Gradle module (`apps/android/app/build.gradle.kts`,
      `settings.gradle.kts` inclusion in root).
- [x] `AndroidManifest.xml` with Bluetooth permissions and foreground service.
- [x] `MainActivity` + a minimal Compose scaffold.
- [x] `RadioTransport` implementation using `BluetoothGatt` against a
      Meshtastic node. This is the highest-risk piece; see ADR-003.
- [x] `RadioTransport` interface is shared (`packages/sync`) and the
      Meshtastic protobuf layer is shared (`packages/meshtastic`, ADR-007).
- [ ] Finish the BLE driver: it is still a skeleton. Implement
      `MeshtasticLink` over GATT and wrap it in `MeshtasticRadio` instead of
      decoding `FromRadio` inline — that deletes the remaining TODO in
      `MeshtasticBleDriver.onFromRadioChanged`. Needs the CCCD write, MTU
      request and a FromRadio read loop.
- [ ] SQLite persistence via Room (`Store` interface from `packages/sync`).

## Planned module layout (suggested)

```
apps/android/
├── app/                          # the APK
├── core-ble/                     # Meshtastic BLE driver
├── core-store/                   # Room persistence
└── feature-map/                  # MapLibre integration
```

## Known risks

- Meshtastic BLE typically accepts **one** client. The app may need to
  force the user to close the official Meshtastic app. Confirm early
  (Lastenheft Open Question 1).
- Android BLE stack quirks vary wildly across vendors. Budget time.

## Bench work

- [ ] Verify BLE take-over behaviour on a real node.
- [ ] Verify re-connect after Bluetooth drop (F-05).
- [ ] Verify tile file (MBTiles) reading performance on a mid-range phone.

See `scripts/test-bench.md`.
