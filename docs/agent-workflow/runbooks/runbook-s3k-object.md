# Runbook: Implement an S3K Object or Badnik

**Primary runbook.** S3K object work has the most hidden integration steps and the highest chance of late art/PLC/lifecycle/trace failures. Read this fully before writing code.

Companion skill: [`.agents/skills/s3k-implement-object/SKILL.md`](../../../.agents/skills/s3k-implement-object/SKILL.md) (+ mirrored `.claude/skills/...`) and its [`rom-pitfalls.md`](../../../.agents/skills/s3k-implement-object/rom-pitfalls.md). This runbook is the operational checklist; the skill is the reference.

---

## 0. S3K-specific hazards — read first

These five rules are the most common sources of wasted work on S3K objects.

### a. Zone-set resolution (S3kZoneSet S3KL/SKL)
S3K uses **two object pointer tables** that remap many IDs by zone. The same object ID can mean different things in different zones.

- `S3kZoneSet.S3KL` — zones 0-6 (AIZ, HCZ, MGZ, CNZ, FBZ, ICZ, LBZ).
- `S3kZoneSet.SKL` — zones 7-13 (MHZ, SOZ, LRZ, SSZ, DEZ, DDZ).
- Resolve the zone set: `S3kZoneSet.forZone(int zone)` in `src/main/java/com/openggf/game/sonic3k/constants/S3kZoneSet.java`.
- Resolve the canonical object name for an ID + zone set: `Sonic3kObjectRegistry.getPrimaryName(int objectId, S3kZoneSet zoneSet)` in `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`.
- In a factory, branch on `getCurrentZoneSet()` and `currentRomZoneId()` (NOT on zone name). A factory for an ID shared across both tables must dispatch to the right instance class per zone set.

### b. S&K-side ROM addresses (with a rare S3 fallback)
The locked-on ROM has an S&K half (`< 0x200000`) and an S3 half (`>= 0x200000`) with byte-identical shared assets. The S3KL/SKL runtime references the S&K half for the overwhelming majority of assets.

- Prefer the `sonic3k.asm` address for every offset in `Sonic3kConstants.java`.
- When `RomOffsetFinder` returns both `sonic3k.asm` and `s3.asm` hits for a label, **pick `sonic3k.asm`**.
- If only `s3.asm` hits come back, first re-search with label variants (zone-specific prefix/suffix, `Levels/{ZONE}/` paths). **But don't loop forever:** depending on who implemented an object and when, some S3K objects reference S3-half (`s3.asm`) assets directly. If there is genuinely no S&K equivalent, that `s3.asm` reference is the one the object uses — use it after verifying the object's code points there. Rare edge case, not the default.

### c. ROM-only runtime assets
Object art, mappings, DPLCs, animation scripts, and PLC bytes come from the user ROM via the loader (`Sonic3kObjectArt`, `Sonic3kPlcArtRegistry`). Never read asset bytes from `docs/skdisasm/`. The disassembly is for labels and offsets only.

### d. Center-coordinate semantics
For any player interaction, use `getCentreX()`/`getCentreY()` (ROM `x_pos`/`y_pos`), never top-left `getX()`/`getY()` (rendering corner, ~19px vertical offset). For native playable-sprite position writes use `NativePositionOps` (`src/main/java/com/openggf/sprites/NativePositionOps.java`).

### e. Injected ObjectServices rules
Objects receive `ObjectServices` at construction via `ObjectManager` (ThreadLocal). In object code:
- Call `services()` (or `tryServices()`), never `getInstance()`.
- Never call `services()` from a constructor — defer to `update()` or a lazy `ensureInitialized()` pattern.
- Spawn children with `spawnChild(() -> new Child(...))` (FindNextFreeObj) or `spawnFreeChild(...)` (FindFreeObj), never raw `ObjectManager.addDynamicObject(...)`.

---

## 1. Discovery commands

`RomOffsetFinder` lives at `src/main/java/com/openggf/tools/disasm/RomOffsetFinder.java`. **Always** pass `--game s3k`. The S3K ROM path is `-Ds3k.rom.path=s3k.gen` for tests; the tool reads the ROM from the working directory.

```powershell
# Find the object's code routine / label
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search <Obj_Label>" -q

# Find an art / mapping / DPLC label's ROM offset (verify it is from sonic3k.asm)
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k find <ArtNem_Label>" -q

# Verify a candidate offset decompresses cleanly
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k verify <Label>" -q

# Inspect the zone's PLC list
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k plc <ZonePlcLabel>" -q

# Enumerate object IDs/names per zone set (composite "objectId:name" keys)
mvn exec:java "-Dexec.mainClass=com.openggf.tools.ObjectDiscoveryTool" "-Dexec.args=--game s3k" -q
```

**Compression type is in the label suffix** (e.g. `AIZ1_8x8_Primary_KosM`) — the tool auto-infers; no file extension needed.

See [`.agents/skills/s3k-disasm-guide/SKILL.md`](../../../.agents/skills/s3k-disasm-guide/SKILL.md) for the full command catalog and label conventions.

---

## 2. Files to inspect (read before touching)

| File | Why |
|------|-----|
| `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java` | Factory registration; zone-set dispatch patterns; `getPrimaryName`. |
| `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java` | Object ID constants (S3KL names canonical; comments document SKL duality). |
| `src/main/java/com/openggf/game/sonic3k/constants/S3kZoneSet.java` | Zone-set enum + `forZone`. |
| `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kZoneIds.java` | Zone id constants for factory branching. |
| `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java` | ROM offsets (S&K-side only). |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java` | Build sprite sheets from ROM. |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java` | Per-zone/act art manifest: `StandaloneArtEntry` / `LevelArtEntry` / `getPlan(zone, act)`. |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java` | Art key string constants. |
| `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java` | Base lifecycle, `services()`, `spawnChild`, on-screen checks. |
| `src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java` | Badnik base (`updateMovement`, `getCollisionSizeIndex`). |
| `src/main/java/com/openggf/game/sonic3k/objects/badniks/AbstractS3kBadnikInstance.java` | S3K badnik base (renderer key, collision/priority, S3K render-flag facing). |
| `src/main/java/com/openggf/level/objects/ObjectServices.java` | Full service surface. |
| An existing similar object under `src/main/java/com/openggf/game/sonic3k/objects/` | Closest working example to mirror. |

Reusable helpers (do NOT reimplement): `SubpixelMotion`, `PatrolMovementHelper`, `PlatformBobHelper`, `SpringBounceHelper`, `DestructionEffects` (all in `src/main/java/com/openggf/level/objects/`).

Behavior contracts: `ObjectControlState` (`src/main/java/com/openggf/sprites/playable/ObjectControlState.java`), `ObjectPlayerQuery` / `ObjectPlayerParticipationPolicy`, `NativePositionOps`, `ObjectLifetimeOps` (all in `src/main/java/com/openggf/level/objects/` except NativePositionOps).

---

## 3. Files likely touched

- **New:** `src/main/java/com/openggf/game/sonic3k/objects/<Name>ObjectInstance.java` (or `.../objects/badniks/<Name>Instance.java`).
- `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java` — add the ID constant if missing.
- `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java` — register the factory (with zone-set dispatch if the ID is shared).
- If art is involved:
  - `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java` — add S&K-side ROM offsets.
  - `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java` — add art key.
  - `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java` — add `StandaloneArtEntry`/`LevelArtEntry` to the right zone/act plan. (See [`runbook-rom-art-mappings-plc.md`](runbook-rom-art-mappings-plc.md).)
- **New test:** `src/test/java/com/openggf/game/sonic3k/objects/Test<Name>...java`.
- Docs: `CHANGELOG.md`, possibly `docs/S3K_KNOWN_DISCREPANCIES.md`.

---

## 4. Implementation pattern (guard-safe defaults)

- Extend `AbstractObjectInstance` (generic object) or `AbstractS3kBadnikInstance` (badnik).
- Constructor: only assign fields from `spawn` (`spawn.x()`, `spawn.y()`, `spawn.subtype()`, `spawn.renderFlags()`). **No `services()`.**
- First-frame setup: lazy `ensureInitialized()` guarded by a boolean, called at the top of `update()`.
- Player interaction: `ObjectPlayerQuery` + a participation policy; compare with `getCentreX()/getCentreY()`.
- Native position writes: `NativePositionOps.writeXPosPreserveSubpixel(...)` etc.
- Destruction: prefer `ObjectLifetimeOps` / `DestructionEffects` / the canonical lifecycle profile, not raw `setDestroyed(true)`.
- Movement: reuse `SubpixelMotion` / `PatrolMovementHelper` rather than hand-rolling 16:8 math.
- Children: `spawnChild(...)` / `spawnFreeChild(...)`.

---

## 5. Required guard tests + focused regression tests

Run after every change (PowerShell — quote the selector):

```powershell
# Guard tests that S3K object work most often trips:
mvn "-Dtest=TestObjectServicesMigrationGuard" test            # no getInstance() in object code
mvn "-Dtest=TestNoServicesInObjectConstructors" test          # no services() in constructors
mvn "-Dtest=TestConstructionContextGuard" test                # new-call sites wrap construction context
mvn "-Dtest=TestSonic3kPlcArtRegistry" test                   # if art/mapping/PLC touched
mvn "-Dtest=TestPatternSpriteRendererCorruptionGuard" test    # if art/mapping touched

# Keep-green S3K baseline (CLAUDE.md):
mvn "-Dtest=TestS3kAiz1SkipHeadless" test
mvn "-Dtest=TestSonic3kLevelLoading" test
mvn "-Dtest=TestSonic3kBootstrapResolver" test
mvn "-Dtest=TestSonic3kDecodingUtils" test

# Your new focused test:
mvn "-Dtest=Test<Name>..." test "-Ds3k.rom.path=s3k.gen"
```

Tests must be JUnit 5 / Jupiter only (no `org.junit.*`). Prefer `@ExtendWith(SingletonResetExtension.class)` / `@FullReset` and `HeadlessTestRunner`. `StubObjectServices` is the test double for `ObjectServices`.

---

## 6. Common failure signatures → fix

| Signature | Cause | Fix |
|-----------|-------|-----|
| `TestObjectServicesMigrationGuard` fails: "must access runtime dependencies through ObjectServices" | `getInstance()` in object code | Replace with `services()`. |
| `TestNoServicesInObjectConstructors` fails | `services()` called in constructor or via `addDynamicObject(new X())` | Defer to `update()`/lazy init; use `spawnChild(() -> new X())`. |
| `TestConstructionContextGuard` fails: "MISSING CONSTRUCTION CONTEXT" | `new Child()` (whose ctor calls `services()`) outside a wrapped site | Use `spawnChild`/`spawnFreeChild`, or wrap with `setConstructionContext(services())` / `clearConstructionContext()`. |
| `TestSonic3kPlcArtRegistry` fails: missing entry / bad palette / tile OOB / pieceCount>80 | Wrong/missing PLC art entry, wrong palette line, mapping offset wrong half | Correct the `getPlan` entry; re-verify the offset is `sonic3k.asm`. |
| `TestPatternSpriteRendererCorruptionGuard` fails / on-screen sprite garbled | Mapping addr points to wrong data (often S3 half) | Re-find with `--game s3k`; verify offset; check tile base + palette from `make_art_tile`. |
| `maxChunkPatternIndex > patternCount` log | Known limitation (dynamic art/PLC parity incomplete) | Note in PR; only fix if it blocks your slice. |
| Object spawns in wrong zone / wrong behavior | Zone-set not resolved in factory | Branch on `getCurrentZoneSet()` + `currentRomZoneId()`. |

---

## 7. ROM / disassembly citation expectations

- Cite the exact `sonic3k.asm` label and ROM offset for every constant you add (e.g. `// Obj_Foo at sonic3k.asm:NNNNN -> 0x1XXXXX, verified RomOffsetFinder --game s3k verify Obj_Foo`).
- Explicitly note when a label also exists in `s3.asm` and that you chose the S&K-side address.
- Never cite `docs/skdisasm/` as the source of runtime asset bytes — only as the source of labels/offsets.

---

## 8. Documentation & commit-trailer obligations

- `CHANGELOG.md` + `Changelog: updated` (engine `src/main/` change). A bare `Changelog: n/a` on a `feat`/`fix` touching `src/main/` is rejected.
- `docs/S3K_KNOWN_DISCREPANCIES.md` + `S3K-Known-Discrepancies: updated` if you introduce/resolve an S3K parity gap.
- If a trace fix revealed a reusable pitfall: update `.agents/skills/s3k-implement-object/rom-pitfalls.md` AND the mirrored `.claude/skills/s3k-implement-object/rom-pitfalls.md`, set `Skills: updated`.
- Fill all trailers (`Guide`, `Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`) with `updated` or `n/a`. Do not `--no-verify`.
- Run `git config core.hooksPath .githooks` once if you commit without building first.
