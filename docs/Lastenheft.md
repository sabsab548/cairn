# Lastenheft: Cairn (working title)

**Serverless group map over LoRa mesh**
Version 0.1 (draft) · 2026-11-03

Priorities: **M** = Muss (must), **S** = Soll (should), **K** = Kann (may)

---

## 1. Background (Ausgangslage)

Groups that work off-grid (hiking, SAR volunteers, festivals, events) already carry Meshtastic radios. The official Meshtastic app shows nodes and chat, but not shared pins, routes or areas. ATAK covers that, but its setup is heavy (sideloading, plugins, TAK roles, server). Flaresat is easy to set up, but it is a commercial product with its own cloud and accounts.

## 2. Goal (Zielsetzung)

An open-source mobile app in which every phone is a full participant. Phones talk to their own LoRa radio and exchange positions, pins, routes, areas and messages over the mesh.

- **No server.** No hub, no cloud, no relay operated by the project.
- **No account.** Joining a group needs only a channel key.
- **Works fully offline** once map tiles are on the phone.

### Non-goals

- Replacing ATAK/TAK or reproducing its server side (accounts, certificates, missions, federation).
- Internet sync, remote viewers, cloud backup.
- Transfer of photos, files or video.

## 3. Users and scenarios

| Scenario | Description |
|---|---|
| Hiking group | 3–10 people, no cell coverage; everyone sees everyone, a shared route and camp pins |
| Volunteer SAR / event crew | Search areas as polygons, hazards as pins, an emergency button |
| Festival | Cell network overloaded; meeting points and positions over LoRa |

**Reference scenario / acceptance test:** three phones, each paired with its own node, share positions; one user draws a route and a pin; the other two see both without any internet connection.

## 4. System context

```
Phone A ──BLE── Node A ~~LoRa~~ Node B ──BLE── Phone B
                     ~~LoRa~~ Node C ──BLE── Phone C
```

Each phone holds a complete local copy of all map objects. The radios are the only link between users.

## 5. Functional requirements

### 5.1 Radio connection

| ID | Requirement | Prio |
|---|---|---|
| F-01 | The app connects to a Meshtastic node over Bluetooth LE | M |
| F-02 | The app sends and receives its own data on a private application port in Meshtastic's private range (256–511) | M |
| F-03 | The user can choose which Meshtastic channel is used | M |
| F-04 | The app shows connection state and node battery level | S |
| F-05 | Automatic reconnect after a Bluetooth drop | S |

### 5.2 Positions and members

| ID | Requirement | Prio |
|---|---|---|
| F-10 | Show own position and the positions of all group members on the map, taken from Meshtastic position packets | M |
| F-11 | Each member has a callsign and a team colour, set once | S |
| F-12 | Members without a recent position are shown greyed out (stale handling, see F-40) | S |

### 5.3 Points and pins

| ID | Requirement | Prio |
|---|---|---|
| F-20 | Create a pin by tapping the map, with a name | M |
| F-21 | Optional description and pin type | S |
| F-22 | Edit and delete a pin; the change reaches all members | M |
| F-23 | A pin fits into **one** LoRa packet | M |

### 5.4 Routes and areas

| ID | Requirement | Prio |
|---|---|---|
| F-30 | A route is a chain of points; each point carries a pointer to its predecessor, so the direction is part of the data | M |
| F-31 | The route ID is the ID of its first point | M |
| F-32 | Several points may be packed into one LoRa packet | M |
| F-33 | Edit, move and delete a route point; the change reaches all members | M |
| F-34 | A route can be marked **closed**, which makes it an area (polygon) | S |
| F-35 | A route may start at a pin or at a user position | K |
| F-36 | Show direction of travel on the route | S |

### 5.5 Messages

| ID | Requirement | Prio |
|---|---|---|
| F-50 | Send and receive short text messages to the whole group | M |
| F-51 | Message list with sender and time | M |
| F-52 | A message can reference a map object; tapping it flies the map there | K |

### 5.6 Safety

| ID | Requirement | Prio |
|---|---|---|
| F-60 | **Emergency button:** one tap sends a high-priority point with the user's position and an emergency flag | S |
| F-61 | Incoming emergencies are shown prominently on every phone | S |

### 5.7 Lifetime of objects

| ID | Requirement | Prio |
|---|---|---|
| F-40 | Every point carries an optional expiry time; after it passes, the object turns grey and later disappears | S |
| F-41 | Deleted objects are carried as a "deleted" marker so the deletion reaches phones that were offline | M |

### 5.8 Map and tools

| ID | Requirement | Prio |
|---|---|---|
| F-70 | Display an open map layer (OSM-based) from a **tile file stored on the phone** | M |
| F-71 | The user can import a tile file (e.g. MBTiles/PMTiles) | M |
| F-72 | Measure distance and compass bearing between two points | S |
| F-73 | Coordinate display formats: decimal lat/lon, UTM, MGRS | S |

### 5.9 Synchronisation and reliability

| ID | Requirement | Prio |
|---|---|---|
| F-80 | Every object has a unique ID; duplicate packets are ignored | M |
| F-81 | For the same ID, the newest version wins | M |
| F-82 | An object that references a point not yet received is kept pending and drawn once the point arrives | M |
| F-83 | A phone that reconnects or joins late can obtain the objects it is missing (e.g. a "here is what I have" exchange or periodic re-sending by neighbours) | S |
| F-84 | Airtime is limited: send rate is throttled and the priority is positions, then messages, then geometry | S |

### 5.10 Joining a group

| ID | Requirement | Prio |
|---|---|---|
| F-90 | A group is defined by a Meshtastic channel (name + key) | M |
| F-91 | Join by scanning a QR code or importing a channel link | S |

## 6. Data model (proposal, not final)

**Object ID** (about 4–5 bytes): type tag + short author ID + counter.
The type tag tells whether the point is a **user position**, a **route point** or a **pin**.

**Point packet:**

| Field | Note |
|---|---|
| ID | see above |
| Predecessor ID | empty for the first point of a route and for single pins |
| Latitude / longitude | compact integers, delta-coded when packed |
| Flags | closed, deleted, emergency |
| Expiry | optional |
| Name | optional, short |

**Message packet:** sender, time, text, optional reference to an object ID.

## 7. Non-functional requirements

| ID | Requirement | Prio |
|---|---|---|
| N-01 | **No server** of any kind is required or operated by the project | M |
| N-02 | **No account, no login, no telemetry** | M |
| N-03 | Fully usable without internet after tiles are on the phone | M |
| N-04 | Native app for Android and iOS (Bluetooth access needs it) | M |
| N-05 | Every packet fits the LoRa payload limit of about 230 bytes | M |
| N-06 | All data stays on the phone; export/backup is a local file only | M |
| N-07 | Open-source licence; proposal: open protocol specification + AGPL for the apps | M |
| N-08 | Reasonable battery use; the app must not poll the radio needlessly | S |
| N-09 | Documented wire format so other apps can interoperate | S |

## 8. Constraints and risks

- **LoRa bandwidth** is small. Long routes, large polygons and many members are limited by airtime.
- **Bluetooth:** a node usually accepts one client at a time. The app may have to replace the official Meshtastic app on that phone while it runs. *To be verified early.*
- **iOS background limits** can suspend Bluetooth connections. Keeping the app in the foreground may be required.
- **Safari has no Web Bluetooth**, so a pure web app is not possible.
- **Meshtastic firmware** changes over time; the radio layer should stay thin.

## 9. Security

- The Meshtastic channel key is the only protection. Whoever has the key is trusted equally; there are no individual identities or roles.
- A channel that uses the default key is public, and the app should warn about it.
- This limitation must be stated clearly in the project description.

## 10. Later ideas (not in version 1)

- Tasks and casualty-evacuation forms
- Photos, files, video (not possible over LoRa; would need another transport)
- Elevation, line of sight, 3D terrain
- Plugins and sensors (drones, cameras, etc.)
- Import/export of GPX and KML
- CoT import/export as a bridge to ATAK/iTAK
- Further radio platforms beyond Meshtastic (e.g. MeshCore)

## 11. Open questions

1. Can the app share the Bluetooth connection with the official Meshtastic app, or does it need to own it?
2. How long should the short author ID be, and how are collisions avoided in a small group?
3. Which catch-up method (F-83) is simplest and good enough?
4. How are offline tile files distributed to the group (shared file, download beforehand)?
5. Which UI framework allows one codebase for Android and iOS with reliable Bluetooth LE?

## 12. Acceptance criteria for version 1

1. Three phones with three nodes, no internet: all see each other's positions on an offline map.
2. A pin dropped on one phone appears on the other two.
3. A route drawn on one phone appears on the others, with direction, and can be edited and deleted.
4. A text message sent by one phone arrives on the other two.
5. A phone that was switched off during these steps shows the same state after it reconnects (if F-83 is implemented).
