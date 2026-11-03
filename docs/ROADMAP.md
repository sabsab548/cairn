# Roadmap to v1

Milestones are vertical slices. Each one ends with a bench run of the
three-phone acceptance test (Lastenheft §12).

## M0 — Repo skeleton ✅ (this bootstrap)

- [x] Community health files
- [x] ADRs 001–005
- [x] Protocol spec v0.1
- [x] Kotlin modules compile, JVM tests run
- [x] CI runs those tests

## M1 — Codec complete

- [ ] Multi-point route packing (F-32)
- [ ] Expiry field (F-40)
- [ ] Tombstone packet (F-41)
- [ ] Size tests for every packet kind, worst case
- [ ] `docs/PROTOCOL.md` updated to match

## M2 — Sync engine complete

- [ ] Pending refs resolved and emitted (F-82)
- [ ] Expiry sweep (F-40)
- [ ] Tombstone propagation (F-41)
- [ ] Priority queue / throttle (F-84)
- [ ] Catch-up digest (F-83, simplest version)

## M3 — First end-to-end app

- [ ] Android app: connect to node, show own position (F-01, F-10)
- [ ] iOS app: ditto
- [ ] Bench: positions of two phones visible to each other

## M4 — Pins, routes, messages

- [ ] Pin create / edit / delete (F-20–F-22)
- [ ] Route draw / edit / delete (F-30–F-33)
- [ ] Messages (F-50–F-51)

## M5 — Safety + offline map

- [ ] Emergency button (F-60, F-61)
- [ ] MBTiles/PMTiles import + rendering (F-70, F-71)
- [ ] Measure / bearing / coord formats (F-72, F-73)

## M6 — Release

- [ ] Full acceptance test on the bench passes
- [ ] AGPL text in LICENSE
- [ ] Signed Android release, TestFlight iOS build
- [ ] README status → alpha

## Beyond v1

See Lastenheft §10 (tasks, photos, elevation, plugins, GPX/KML, CoT bridge).
