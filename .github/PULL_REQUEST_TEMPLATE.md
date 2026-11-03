## What this changes

## Why

## Checklist

- [ ] JVM tests pass locally (`./gradlew :packages:protocol:jvmTest :packages:sync:jvmTest`)
- [ ] I have not added any server dependency
- [ ] I have not added any account / login / telemetry
- [ ] New packets are ≤ 230 B in the worst case (test proves it)

## If this changes the wire format

- [ ] `docs/PROTOCOL.md` is updated in this PR
- [ ] Codec round-trip test added
- [ ] Size assertion added
- [ ] ADR added to `docs/DECISIONS.md` if the change is non-obvious

## If this touches BLE or tile files

- [ ] I have run the physical bench (see `scripts/test-bench.md`) and
      described the result below

Bench result:
