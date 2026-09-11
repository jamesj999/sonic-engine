# Runbook: Implement an S1 or S2 Object or Badnik

For Sonic 1 and Sonic 2 objects/badniks. S1/S2 have a **single** object pointer table (no zone-set duality) and use standalone ROM addresses, so they avoid the biggest S3K hazards — but the object-service, center-coordinate, ROM-only-asset, and guard-test rules are identical.

Companion skills:
- S1: [`.agents/skills/s1-implement-object/SKILL.md`](../../../.agents/skills/s1-implement-object/SKILL.md), [`.agents/skills/s1disasm-guide/SKILL.md`](../../../.agents/skills/s1disasm-guide/SKILL.md)
- S2: [`.agents/skills/s2-implement-object/SKILL.md`](../../../.agents/skills/s2-implement-object/SKILL.md) (+ [`rom-pitfalls.md`](../../../.agents/skills/s2-implement-object/rom-pitfalls.md)), [`.agents/skills/s2disasm-guide/SKILL.md`](../../../.agents/skills/s2disasm-guide/SKILL.md)

---

## 1. Discovery commands

`RomOffsetFinder` (`src/main/java/com/openggf/tools/disasm/RomOffsetFinder.java`). Pass `--game s1` or `--game s2`.

> S1 caution: addresses derived from s1disasm labels frequently do NOT match the compiled REV01 ROM. Verify against actual ROM bytes (see verified address tables in the project memory / `Sonic1Constants.java`).

```powershell
# S2 example
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s2 search Obj_<Name>" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s2 find ArtNem_<Name>" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s2 verify <Label>" -q

# S1 example
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s1 search Obj<NN>" -q

# Enumerate object IDs/names
mvn exec:java "-Dexec.mainClass=com.openggf.tools.ObjectDiscoveryTool" "-Dexec.args=--game s2" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.ObjectDiscoveryTool" "-Dexec.args=--game s1" -q
```

---

## 2. Files to inspect

| Concern | S1 | S2 |
|---------|----|----|
| Registry | `src/main/java/com/openggf/game/sonic1/objects/Sonic1ObjectRegistry.java` | `src/main/java/com/openggf/game/sonic2/objects/Sonic2ObjectRegistry.java` |
| Object IDs | `src/main/java/com/openggf/game/sonic1/constants/Sonic1ObjectIds.java` | `src/main/java/com/openggf/game/sonic2/constants/Sonic2ObjectIds.java` |
| ROM offsets | `src/main/java/com/openggf/game/sonic1/constants/Sonic1Constants.java` | `src/main/java/com/openggf/game/sonic2/constants/Sonic2Constants.java` |
| Object art | `src/main/java/com/openggf/game/sonic1/Sonic1ObjectArt.java` | `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArt.java` |

Shared base/helpers: `src/main/java/com/openggf/level/objects/` (`AbstractObjectInstance`, `AbstractBadnikInstance`, `SubpixelMotion`, `PatrolMovementHelper`, `DestructionEffects`, `ObjectLifetimeOps`, `ObjectPlayerQuery`), plus `src/main/java/com/openggf/sprites/NativePositionOps.java`.

---

## 3. Files likely touched

- **New:** `src/main/java/com/openggf/game/sonic{1,2}/objects/<Name>ObjectInstance.java`.
- Add ID to `Sonic{1,2}ObjectIds.java`.
- Register factory in `Sonic{1,2}ObjectRegistry.java`.
- Art: add ROM offset to `Sonic{1,2}Constants.java`; add loader method in `Sonic{1,2}ObjectArt.java`.
  - **S2:** prefer `S2SpriteDataLoader.loadMappingFrames(reader, mappingAddr)` (add mapping addr to `Sonic2Constants.java`); `loadMappingFramesWithTileOffset()` for VRAM tile adjustment.
  - **S1:** use `Sonic1ObjectArt.buildArtSheet(artAddr, mappings, palette, bankSize)` for Nemesis art; `S1SpriteDataLoader.loadMappingFrames(reader, mappingAddr)` for ROM-parsed mappings (5-byte pieces). Many S1 mappings are inline `spritePiece` macros, so hardcoded mappings with `buildArtSheet()` are common.
- **New test:** `src/test/java/com/openggf/game/sonic{1,2}/objects/Test<Name>...java`.
- Docs: `CHANGELOG.md`, possibly `docs/status/known-discrepancies.md`.

---

## 4. Implementation pattern (same guard-safe defaults as S3K)

- Extend `AbstractObjectInstance` / `AbstractBadnikInstance`.
- Constructor assigns from `spawn` only — **no `services()`**.
- Lazy `ensureInitialized()` at top of `update()`.
- Center coordinates (`getCentreX/Y`) for player interaction; `NativePositionOps` for native writes.
- `spawnChild` / `spawnFreeChild` for children; `ObjectLifetimeOps` / `DestructionEffects` for destruction.
- Per-game differences use the smallest accurate owner from `docs/architecture/per-game-rule-placement.md`, never `if (gameId == ...)`.

---

## 5. Required guard tests + focused regression tests

```powershell
mvn "-Dtest=TestObjectServicesMigrationGuard" test
mvn "-Dtest=TestNoServicesInObjectConstructors" test
mvn "-Dtest=TestConstructionContextGuard" test
mvn "-Dtest=Test<Name>..." test     # your new focused test (JUnit 5 only)
```

Use `HeadlessTestRunner`, `@FullReset`/`SingletonResetExtension`, and `StubObjectServices`. `TestRomLogic` is skipped when ROM is absent.

---

## 6. Common failure signatures → fix

| Signature | Fix |
|-----------|-----|
| `TestObjectServicesMigrationGuard` fails | Replace `getInstance()` with `services()`. |
| `TestNoServicesInObjectConstructors` / `TestConstructionContextGuard` fail | Defer `services()` to `update()`; use `spawnChild`/`spawnFreeChild` or wrap construction context. |
| Sprite garbled / wrong tiles | Mapping/art offset wrong; for S1 verify against ROM bytes, not just label. Check VDP column-major tile order and h/v-flip rules. |
| Collision feels offset by ~19px | Used `getX/getY` instead of `getCentreX/getCentreY`. |
| Per-game `if/else` flagged in review | Move divergence to the smallest accurate owner from `docs/architecture/per-game-rule-placement.md`. |

---

## 7. ROM / disassembly citation expectations

- Cite the exact label and verified ROM offset for each constant added.
- **S1:** state how the address was verified against the compiled ROM (pattern/cross-ref), since label-derived offsets are unreliable.
- Never read runtime asset bytes from `docs/s1disasm/` or `docs/s2disasm/` — labels/offsets only.

---

## 8. Documentation & commit-trailer obligations

- `CHANGELOG.md` + `Changelog: updated` for engine changes.
- `docs/status/known-discrepancies.md` + `Known-Discrepancies: updated` if you add/resolve an intentional divergence.
- Pitfall reuse → update `.agents/skills/s{1,2}-implement-object/rom-pitfalls.md` and the mirrored `.claude/skills/...`, set `Skills: updated`.
- Fill all trailers with `updated` or `n/a`; never `--no-verify`.
