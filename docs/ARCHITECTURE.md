# Architecture

## Layer diagram

```
┌─────────────────────────────────────┐
│ UI (map, pin/route edit, messages)  │   apps/*
├─────────────────────────────────────┤
│ Application / state layer           │   apps/*
├─────────────────────────────────────┤
│ ⭐ SyncEngine (pure logic)             │   packages/sync      (CI-tested)
│ - dedupe (F-80)                     │
│ - latest-wins (F-81)                │
│ - pending refs (F-82)               │
│ - tombstones (F-41)                 │
│ - priority queue (F-84)             │
├─────────────────────────────────────┤
│ ⭐ Protocol codec (pure logic)         │   packages/protocol  (CI-tested)
│ - encode/decode Point, Message      │
│ - ObjectId                          │
│ - size assertions (N-05)            │
├─────────────────────────────────────┤
│ RadioTransport interface            │   defined in packages/sync
│ - connect(channelKey)               │   implemented per platform
│ - send(ByteArray)                   │
│ - onReceive(ByteArray)              │
│ - onStateChanged(...)               │
├─────────────────────────────────────┤
│ BLE driver (native)                 │   apps/android (BluetoothGatt)
│                                     │   apps/ios (CoreBluetooth)
├─────────────────────────────────────┤
│ Persistence (SQLite)                │   apps/*  (Room / SQLite.swift)
└─────────────────────────────────────┘
```

**Rule:** everything above the `RadioTransport` line is pure logic and MUST
be tested in CI on JVM. Everything below the line is platform-specific and
is exercised only on the physical test bench.

## Why this split (see ADR-001 and ADR-003)

- ~60% of the project's logical complexity lives in codec + sync.
- That code is 100% testable on JVM without a device.
- BLE reliability is the biggest *unknown*, but the codec and sync bugs are
  the biggest *known* bug surface. Freezing the codec first buys us the
  largest possible safety margin per hour spent.

## Data model summary

- **Object ID** (4 B): `[type 1B][author 1B][counter 2B]`
- **Point**: ID, predecessor ID (nullable), flags, lat/lon, optional expiry, short name.
- **Message**: ID, sender, timestamp, optional ref to an object, text.
- **Tombstone** (F-41): a Point/Message with `deleted` flag set. *(TODO)*

Full byte layout: `docs/PROTOCOL.md`.

## What "works fully offline" means

- Map tiles are a file on the phone (MBTiles / PMTiles, see F-71).
- No network calls anywhere. The app must not even try DNS.
- No accounts, no telemetry (N-02).
