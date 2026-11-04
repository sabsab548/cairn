# Security policy

## Reporting

Send a private email to the address listed in `MAINTAINERS` (TODO: create
this file when the project goes public). Do **not** open a public issue.

## Threat model

Cairn is **not** a secure messenger. Be explicit about what it does not do:

- **Trust = channel key.** Anyone with the Meshtastic channel key can
  inject, edit, or delete objects on the mesh. There are no per-user
  identities and no roles.
- **Default keys are public.** A Meshtastic channel using the well-known
  default key offers no protection. The app should warn the user; this is
  tracked in `docs/ROADMAP.md`.
- **Traffic analysis is possible.** Positions, pin drops and message
  metadata are visible to anyone with the channel key.
- **Replay/rollback is bounded, not prevented.** A malicious peer can
  re-broadcast old state, but every object carries a version stamp and the sync
  engine keeps a floor per object (ADR-006), so anything older than what you
  already accepted is dropped. It cannot be prevented outright: an attacker
  who stamps a *future* version can still overwrite an object. Mitigation is
  future work (see `docs/ROADMAP.md`).
- **No forward secrecy.**

Use Cairn only in a group you already trust. Do not use it where an
adversary with a radio can attack you.
