# Runbook: Add a Gameplay Level Mutation

For editing level tile data from gameplay code (terrain modifiers, breakable walls, collapsing floors, dynamic layout changes). The one rule that matters most: **gameplay-path tile edits must route through `ZoneLayoutMutationPipeline` / a `LevelMutationSurface` — never a direct `getMap().setValue(...)`.**

---

## 0. Why the routing rule exists

All level mutations must flow through one place so the rewind framework's copy-on-write epoch (`AbstractLevel.snapshotEpoch` + `cowEnsureWritable`) can intercept them and keep rewind snapshots isolated from later live edits. Direct `getMap().setValue(...)` bypasses this and is rejected by `TestNoDirectMapMutationsInGameplay`.

Exempt (allowlisted) callers: editor commands (`PlaceBlockCommand`, `DeriveChunkFromPatternsCommand`, `DeriveBlockFromChunksCommand`) and initial layout decoders (e.g. `Sonic3kLevel.java`). Gameplay code under `game/sonic1|2|3k` and `level/objects` is NOT exempt.

---

## 1. Discovery

No ROM discovery is usually needed — this is engine plumbing. Inspect:

| File | Role |
|------|------|
| `src/main/java/com/openggf/game/mutation/ZoneLayoutMutationPipeline.java` | Deterministic queued/immediate layout edits + redraw sequencing. The gameplay entry point. |
| `src/main/java/com/openggf/game/mutation/LevelMutationSurface.java` | Mutation surface abstraction. |
| `src/main/java/com/openggf/level/objects/ObjectServices.java` | `services().zoneLayoutMutationPipeline()` accessor for object code. |
| Terminology (CLAUDE.md): Pattern = 8x8, Chunk = 16x16, Block = 128x128. | |

Per-frame dirty-region processing happens in `LevelFrameStep.processDirtyRegions()` (at the `com.openggf` package root, not under `level`).

---

## 2. Files likely touched

- Your object/event class under `src/main/java/com/openggf/game/sonic{1,2,3k}/...` or `src/main/java/com/openggf/level/objects/...`.
- No edit to the pipeline itself in the common case — you call into it.

---

## 3. Implementation pattern

From object code:

```java
// CORRECT — routes through the pipeline (rewind-safe):
services().zoneLayoutMutationPipeline().mutate(/* queued or immediate layout edit */ ...);

// WRONG — direct map mutation, rejected by TestNoDirectMapMutationsInGameplay:
// services().currentLevel().getMap().setValue(...);
```

Choose queued vs immediate mutation per the redraw timing you need. Center-coordinate and `ObjectServices` rules from the object runbooks still apply (no `getInstance()`, no `services()` in constructors).

---

## 4. Required guard tests + focused regression tests

```powershell
mvn "-Dtest=TestNoDirectMapMutationsInGameplay" test    # routing enforcement
mvn "-Dtest=Test<YourFeature>..." test                   # your focused test (JUnit 5 only)
```

If the mutation is rewind-relevant, also exercise the relevant rewind tests for your area. Tests JUnit 5 / Jupiter only.

---

## 5. Common failure signatures → fix

| Signature | Cause | Fix |
|-----------|-------|-----|
| `TestNoDirectMapMutationsInGameplay`: "Direct getMap().setValue() calls found in gameplay paths" | Bypassed the pipeline | Replace with `services().zoneLayoutMutationPipeline().mutate(...)`. |
| Rewind shows stale/over-written tiles | Mutation didn't go through CoW epoch | Route through the pipeline so `cowEnsureWritable` runs. |
| Edit visible but not redrawn | Dirty region not queued | Use the pipeline's redraw sequencing; confirm `LevelFrameStep.processDirtyRegions()` runs. |

---

## 6. ROM / disassembly citation expectations

If the mutation models a ROM behavior (e.g. a collapsing platform's tile swap), cite the routine label and the ROM-state condition that triggers it. Do not branch on zone id/name — model the triggering object/event state.

---

## 7. Documentation & commit-trailer obligations

- `CHANGELOG.md` + `Changelog: updated` for the engine change.
- `docs/status/known-discrepancies.md` / `docs/S3K_KNOWN_DISCREPANCIES.md` + matching trailer if behavior diverges from ROM.
- Fill all trailers (`Guide`, `Agent-Docs`, `Configuration-Docs`, `Skills`) with `updated` or `n/a`; never `--no-verify`.
