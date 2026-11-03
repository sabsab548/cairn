# Cairn (working title)

**Serverless group map over a LoRa mesh.**

Cairn turns a group of phones + Meshtastic radios into a shared offline map:
positions, pins, routes, areas, short messages. No server, no account, no
cloud. Join a group with a channel key, done.

- **Status:** pre-alpha. Nothing usable yet. See `TODO.md` and
  `docs/ROADMAP.md` for where we are.
- **Scope of v1:** see `docs/ARCHITECTURE.md` and the Lastenheft
  (kept outside this repo for now — move it into `docs/Lastenheft.md`).

## Why not X?

- **ATAK** works but is heavy (sideload, plugins, server).
- **Flaresat** works but is a commercial product with its own cloud.
- **Official Meshtastic app** shows nodes and chat, but no shared pins /
  routes / areas.

Cairn is the small open-source answer for that gap.

## Hardware & setup assumptions

- One Meshtastic node per phone, connected over Bluetooth LE.
- Airtime is the bottleneck; every design decision follows from that.
- The Meshtastic channel key **is** the only trust boundary. Anyone with the
  key is a full peer. There are no roles, no certificates, no per-user
  identity. See `SECURITY.md`.

## Repo layout

```
packages/protocol/   wire codec + object IDs     (KMP, MIT)
packages/sync/       sync engine state machine   (KMP, MIT)
apps/android/        Android app                 (Kotlin/Compose, AGPL-3.0)
apps/ios/            iOS app                     (Swift, AGPL-3.0)
docs/                spec, architecture, ADRs, testing, roadmap
```

## Building (once apps are wired)

```bash
./gradlew :packages:protocol:jvmTest :packages:sync:jvmTest
./gradlew :apps:android:assembleDebug       # TODO: see apps/android/TODO.md
```

## License

See `LICENSE` and `docs/DECISIONS.md` (ADR-005). Apps AGPL-3.0-or-later,
packages MIT, protocol spec CC-BY-4.0.

## Contributing

Read `CONTRIBUTING.md`. If you change the wire format, read
`docs/PROTOCOL.md` first and update both `docs/PROTOCOL.md` and the codec
together in the same PR.
