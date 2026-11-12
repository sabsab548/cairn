# Cairn on a Linux laptop — quick start

Cairn is a shared offline map for a group: pins, messages and positions are
exchanged over a LoRa mesh (Meshtastic radios) instead of a server. This page
gets you from "fresh repo, fresh radio, never used Meshtastic" to sending a pin
through your node.

You need: a Linux laptop, a Meshtastic node, a **data** USB cable.

> **Bluetooth: not yet.** The desktop harness talks to the node over **USB
> serial** or **Wi-Fi (TCP)**, both of which carry the same Meshtastic protocol.
> BLE from Linux is deliberately not implemented (see
> `apps/desktop/README.md`). Bluetooth is only needed *once*, to configure the
> node — and you can skip that with the USB web client below.

## 1. Java 17

```bash
sudo apt install openjdk-17-jdk      # Debian/Ubuntu
chmod +x gradlew
```

The build pins a Java 17 toolchain; a Java-21-only machine fails at build
configuration until 17 is installed.

## 2. Configure the node once (this is Meshtastic, not Cairn)

A factory-fresh node has **no region set**, so it refuses to transmit. Do this
once; the node remembers it.

**Option A — browser over USB (no phone needed):**

1. Plug the node into the laptop.
2. Open <https://client.meshtastic.org> in Chrome or Edge.
3. Connect → **Serial** → pick `/dev/ttyACM0` (or `/dev/ttyUSB0`).
4. Set **Region** to your country, leave the primary channel `LongFast`.
5. Save, then **close the tab** — it keeps the port open.

**Option B — phone app:** install *Meshtastic*, pair over Bluetooth, set the
region. Afterwards close the app, or it keeps the radio to itself.

Sanity check from a terminal (optional): `pipx install meshtastic`, then
`meshtastic --port /dev/ttyACM0 --info`.

## 3. Prove the build without hardware

```bash
./gradlew :apps:desktop:runHarness --args="--link mock"
```

The first run downloads Gradle/Kotlin dependencies (internet once). `mock` is a
fake in-process node — no radio, no permissions. Type `status`, then
`point 52.5204 13.4043 bridge`: the pin comes back (`←`) and is stored.

```
[radio] connecting
[radio] connected as Cairn Dev Node
cairn> → Pin#0@1 "bridge" @ 52.520400, 13.404300 (32 B)
cairn> ← Pin#0@1 "bridge" @ 52.520400, 13.404300
```

If that works the code is fine, and only the radio link is left.

## 4. Connect your node over USB

```bash
ls /dev/ttyACM*                      # ACM = native USB, USB = CH340/CP210x
sudo usermod -aG dialout "$USER"     # once, then log out and back in
./gradlew :apps:desktop:runHarness --args="--link serial:/dev/ttyACM0"
```

Expected within a second or two:

```
[radio] connecting
[radio] connected as YourNodeName
```

Then try `status`, `nodes`, and `point 52.5204 13.4043 bridge`.

**One node only proves the link and the handshake.** To see a pin actually be
received you need a second node (step 6).

## 5. Or over Wi-Fi

Enable Wi-Fi in the node's config (web/phone client), find its IP in your
router, then:

```bash
./gradlew :apps:desktop:runHarness --args="--link tcp:192.168.1.50"
```

Default port is 4403 (`tcp:HOST:PORT` to override). Also works against
`meshtasticd` on `localhost:4403`. No `dialout`, no cable, no permissions —
the nicest link for development.

## 6. Two nodes = a real group

Both nodes need a Meshtastic channel (same name *and* key) and a region, so
repeat step 2 for the second one. Then run one harness per machine — two
`./gradlew` runs in the same checkout wait on Gradle's project lock, so use two
laptops or a second `git clone` — with the same Cairn port and a different
author:

```bash
# machine 1
./gradlew :apps:desktop:runHarness --args="--link serial:/dev/ttyACM0 --author 1"
# machine 2
./gradlew :apps:desktop:runHarness --args="--link tcp:192.168.1.51 --author 2"
```

Send a `point` on one; the other prints `← …` and it appears in `state`.
Defaults: `--channel 0`, `--port 256`; a peer with a different value hears
nothing.

## Commands

| Command | Does |
|---|---|
| `status` | link, connection state, attached node, clock |
| `nodes` | nodes the radio has heard (`snr`, `hops`, position) |
| `state` | Cairn objects held by the sync engine |
| `point LAT LON [NAME]` | send a pin |
| `position LAT LON` | send your own position |
| `msg TEXT` | send a short message |
| `delete Pin#0` | send a tombstone |
| `help`, `quit` | — |

Objects print as `Type#counter@author`, e.g. `Pin#0@1`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Permission denied` opening the tty | `sudo usermod -aG dialout "$USER"`, log out and back in |
| No `/dev/ttyACM0` | Data cable, not a charge-only one; `dmesg \| tail`; try `/dev/ttyUSB0` |
| Drops out / port busy | Close the web client and phone app; `sudo systemctl stop ModemManager` |
| Stuck at `connecting` | Region not set, or another program holds the port |
| `not connected yet` | Wait for `[radio] connected` before sending |
| Gradle: `No matching toolchains` | Install JDK 17 (step 1) |
| `tcp:` connection refused | Wi-Fi off, wrong IP, or 4403 blocked |
| Peer sees nothing | Different channel, or different `--port` |

## Not implemented

- **BLE from the laptop** — needs BlueZ/D-Bus work, and a node accepts about
  one BLE client, so the phone and laptop would fight over the radio. Deferred;
  serial/TCP carry the same protocol behind the same `MeshtasticLink` seam.
- **Phone ↔ radio** — the Android app's BLE driver is still unfinished.
- No persistence, expiry or catch-up: `state` lives only as long as the
  harness runs.

More detail: `apps/desktop/README.md`. Design rationale:
`docs/DECISIONS.md` (ADR-007).
