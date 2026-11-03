# Physical test bench

Everything BLE / iOS-background / tile-file is validated here, not in CI.
This is the checklist that must pass before every release tag.

## Hardware

- 3 × phones (at least 1 iOS, at least 2 Android)
- 3 × Meshtastic nodes, same model, same firmware version
- 1 × copy of the official Meshtastic app on each phone (for the
  BLE-coexistence test)
- 1 × MBTiles file of the test area (approx. 50–500 MB is realistic)
- No WiFi, no cellular — airplane mode on, Bluetooth on

## Firmware

Record the exact firmware version here before each run:

    firmware: …
    node model: …
    date: …

## Manual checklist (Lastenheft §12)

1. [ ] All three phones: positions visible to each other (F-10)
2. [ ] Drop pin on phone A → visible on B and C (F-20)
3. [ ] Edit pin on A → edit visible on B and C (F-22)
4. [ ] Delete pin on A → disappears on B and C (F-41)
5. [ ] Draw route on A → visible on B and C with direction (F-30, F-36)
6. [ ] Edit a route point on A → visible on B and C (F-33)
7. [ ] Mark a route closed → becomes an area (F-34)
8. [ ] Message from A → arrives on B and C (F-50)
9. [ ] Emergency button on A → prominent banner on B and C (F-60, F-61)
10. [ ] Switch phone C off, do steps 2–8 on A and B, switch C on again
        → C catches up (F-83, only if implemented)

## BLE coexistence (Lastenheft Open Question 1)

The official Meshtastic app and Cairn both want the node's BLE connection.
Test all four combinations and record the result:

| Official app state | Cairn state  | Works? |
|---|---|---|
| Not installed       | connected    |  |
| Foreground          | tries to connect |  |
| Background          | tries to connect |  |
| Force-closed        | connected    |  |

If only one client works, Cairn must clearly instruct the user to close
the official app. Add a screenshot of that instruction to the release notes.

## Regression log

Keep a short log here of each bench run and what changed:

    ## 2026-11-03 — bootstrap (M0)
    Codec + sync unit tests only; no app, no BLE. Nothing to bench.
