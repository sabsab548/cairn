# Contributing to Cairn

Thanks for wanting to help. Cairn is small on purpose — please keep it
that way.

## Ground rules

- **No servers.** If your change needs a server of any kind, it's out of
  scope for v1. See ADR-001 and the Lastenheft's Non-goals.
- **Airtime is holy.** Every feature must justify its bytes. A point packet
  is 18 B before the name; the whole LoRa payload is 230 B. If you don't
  know the size of your change, write a test.
- **No accounts.** Join = channel key. Don't add identity or roles.
- **Protocol changes are big.** Read `docs/PROTOCOL.md` and open an issue
  *before* the PR. A protocol change requires:
  1. A doc change in `docs/PROTOCOL.md`
  2. A codec change in `packages/protocol`
  3. Round-trip and size tests
  4. A note in `docs/DECISIONS.md` if it's not obvious

## Branching

Trunk-based (ADR-002). `main` is releasable. Feature branches are short-lived:

    feat/…  fix/…  proto/…  docs/…

PR titles follow [Conventional Commits](https://www.conventionalcommits.org/).

## Local checks (same as CI)

```bash
./gradlew :packages:protocol:jvmTest :packages:sync:jvmTest
```

## What we cannot test in CI

BLE, iOS background behaviour, tile-file reading — all that is on the
physical test bench. See `docs/TESTING.md` and `scripts/test-bench.md`.

## Code style

Kotlin: standard Kotlin style, 4-space indent, no wildcard imports.
Markdown: 100-char soft wrap, no trailing whitespace.
