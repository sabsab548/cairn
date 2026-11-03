# Top-level TODO

This file is the project-wide open-work board. Per-area TODOs live in the
folder they belong to:

- `packages/protocol/TODO.md`
- `packages/sync/TODO.md`
- `apps/android/TODO.md`
- `apps/ios/TODO.md`

## Not written yet (blockers before v1)

- [ ] `docs/Lastenheft.md` — copy the requirements doc into the repo
- [ ] Full verbatim AGPL-3.0 text into `LICENSE`
- [ ] `MAINTAINERS` file (needed by SECURITY.md / CODE_OF_CONDUCT.md)
- [ ] Root `gradle/` wrapper (gradlew, gradle-wrapper.jar, properties)
- [ ] `apps/android` real module (see its TODO.md)
- [ ] `apps/ios` real Xcode project (see its TODO.md)

## Decisions still open

See `docs/DECISIONS.md`, in particular:
- ADR-004 (author-ID collision policy) is only "Proposed"
- Open questions 1–5 from the Lastenheft

## Pre-release checklist

- [ ] Physical test bench green (see `scripts/test-bench.md`)
- [ ] README status line says "alpha"
- [ ] Full AGPL text in LICENSE
- [ ] Release workflow does something (see `.github/workflows/release.yml`)
