# apps/desktop — Cairn dev harness

A command-line version of Cairn for Linux/PC. It speaks the **real Meshtastic
link protocol** to a radio over USB serial or TCP, feeds the same Cairn codec
and sync engine the phones use, and prints what happens.

This is a developer tool, not a product UI. It exists so the radio link, the
protobuf layer and the sync engine can be exercised from a laptop instead of
only on a phone + a bench.

```
link (serial / TCP / mock)
   └─ packages/meshtastic   ToRadio/FromRadio protobuf + 0x94 0xC3 framing
        └─ MeshtasticRadio  RadioTransport: app-port packets ⇄ Cairn packets
             └─ packages/sync      SyncEngine (dedupe, latest-wins, …)
```

See `docs/DECISIONS.md` (ADR-007) for why the Meshtastic layer is shared KMP
code and why the desktop link is serial/TCP rather than BLE.

## Quick start — no hardware

```bash
./gradlew :apps:desktop:runHarness --args="--link mock"
```

`mock` is an in-process fake node: it answers `want_config` with a small node
DB and echoes anything you send back as if a peer had transmitted it. The whole
pipeline (framing → protobuf → Cairn codec → sync engine) is exercised, with no
radio and no permissions.

At the `cairn>` prompt:

```
status                    connection state and attached node
nodes                     nodes the radio knows about
state                     Cairn objects held by the sync engine
point 52.5204 13.4043 bridge      send a pin (F-20)
position 52.5204 13.4043          send your own position (F-10)
msg rendezvous at the ridge       send a group message (F-50)
delete Pin#3@7                    send a tombstone (F-41)
quit
```

Object IDs print as `Type#counter@author`, e.g. `Pin#3@7`.

> Piped stdin is delivered to the process in one burst by Gradle, so the mock's
> latency can outrun a scripted `point …`. Type interactively, or wait for
> `state connected` first. The automated tests use the same mock with an
> explicit barrier instead.

## Connecting a real radio

Both ends must use the **same Meshtastic channel** (the channel key is
configured on the node, not by this app) and the **same Cairn port**
(`--port`, default `256`).

### USB serial (simplest on Linux)

1. Plug the node in. It shows up as `/dev/ttyACM0` (some boards:
   `/dev/ttyUSB0`):

   ```bash
   ls /dev/ttyACM*
   ```

2. Make sure your user can open it. On Debian/Ubuntu:

   ```bash
   sudo usermod -aG dialout "$USER"
   ```

   Log out and back in (or `newgrp dialout`), otherwise you get
   `Permission denied`.

3. Run:

   ```bash
   ./gradlew :apps:desktop:runHarness --args="--link serial:/dev/ttyACM0"
   ```

The harness puts the tty into raw mode with `stty` and sends the 32-byte
`0xC3` wake-up burst the reference client uses. `serial:/dev/ttyUSB0:9600`
overrides the baud rate (default 115200; irrelevant for most USB-CDC boards).

### TCP (Wi-Fi node or `meshtasticd`)

Meshtastic nodes with Wi-Fi enabled expose the same API on port **4403**:

```bash
./gradlew :apps:desktop:runHarness --args="--link tcp:192.168.1.50"
```

An explicit port works too: `tcp:host:4403`. This is the nicest link for
development: no permissions, no tty, survives a harness restart. It also works
against a simulated node:

```bash
meshtasticd            # or the Docker image
./gradlew :apps:desktop:runHarness --args="--link tcp:localhost:4403"
```

### Options

```
--link tcp:HOST[:PORT]        Meshtastic TCP API (default 4403)
--link serial:DEVICE[:BAUD]   USB serial (default 115200)
--link mock                   in-process fake node
--channel N                   Meshtastic channel index (default 0)
--port N                      Cairn app port, 256..511 (default 256)
--author N                    Cairn author ID, 1..255 (default random)
--help
```

Cairn traffic is broadcast on the selected channel index, on the app port
(F-02). Every peer in the group must agree on both.

## Why BLE is not here (yet)

BLE on Linux means talking to BlueZ over D-Bus, plus a library that is not in
the dependency set, and it cannot be verified in CI. A Meshtastic node also
accepts roughly **one** BLE client, so a PC harness and the phone would fight
over the radio. Serial and TCP have none of those problems and use the same
protocol.

The seam is already in place: implement `MeshtasticLink` on top of a GATT
connection and hand it to `MeshtasticRadio`. Unlike the stream links it needs
no framing — one GATT write/notification is one message. The UUIDs (from the
Android driver) are:

| Purpose    | UUID |
|------------|------|
| service    | `6ba1b218-15a8-461f-9269-48f14e47f3a8` |
| FromRadio  | `8ba2cc2d-6200-4875-919e-b7249cc5619a` |
| ToRadio    | `f75c76d2-129e-4dad-a1dd-7866124401e7` |
| FromNum    | `ed9da18c-a800-4f66-a670-aa7547e34453` |

Until then, use the phone app for BLE.

## Tests

```bash
./gradlew :packages:meshtastic:jvmTest :apps:desktop:desktopTest
```

- `packages/meshtastic` — protobuf primitives, message encode/decode, stream
  framing, handshake and port filtering (`MeshtasticRadioTest`).
- `apps/desktop` — `LinkSpec` parsing, a real `TcpLink` against a local
  `ServerSocket`, and the full mock pipeline.

Nothing in CI opens a serial port or touches Bluetooth; those paths are
`SerialLink` and the future BLE link, validated on the bench
(`scripts/test-bench.md`).

## Status of the hardware paths

- `mock` — fully covered by automated tests.
- `tcp:` — the framing and socket handling are covered by a test against a
  local server; the same code path is what a Wi-Fi node or `meshtasticd`
  speaks.
- `serial:` — implemented (raw tty via `stty`, wake burst, reader thread) but
  only a real node can prove it. If it misbehaves, check the device name, the
  `dialout` group, and that no other program (e.g. the phone app or
  `meshtastic --port`) holds the tty.
