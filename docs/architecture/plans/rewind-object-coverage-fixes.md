# Rewind Coverage — Prioritized Fix Campaign Plan

_Generated 2026-06-17 from the committed coverage-baseline.txt (594 uncovered classes / 1705 gap keys). Companion to the Phase-1 audit; see docs/status/rewind-round-trip-gaps.md (round-trip probe) and the architecture spec._

I have all the data needed. The objectRef gaps split 23 player-ref (easy) / 19 object-child-ref (risky). The recreate gaps are 584 classes but most are Phase-1 layout over-approximations (false positives per the analyzer Javadoc). Here is the plan.

---

# Rewind Object-Coverage Fix Plan — Multi-Batch Campaign

**Source of truth:** `src/test/resources/rewind/coverage-baseline.txt` (1705 gap keys, 594 classes), Phase-1 report from `RewindCoverageAnalyzer` / `TestRewindCoverageGuard`. Design: `docs/architecture/designs/2026-06-17-rewind-object-coverage-architecture-design.md`. Precedent: `docs/S3K_KNOWN_DISCREPANCIES.md` "AIZ2 Boss Rewind: Transient Combat/Cosmetic Children Restored".

> Note on input scope: the "~0 objects" figure in the brief was a placeholder; the real dropped/uncovered set is the committed baseline. Counts below are derived from it.

## 1. Headline counts

| Metric | Count |
|---|---|
| **Distinct uncovered classes** | **594** |
| **Total gap keys** | **1705** |
| By bucket — `#recreate` (no recreate path / silent drop) | 584 |
| By bucket — `#finalScalar` (un-final or constructor-feed) | 1079 |
| By bucket — `#objectRef` (capture as stable id) | 42 |
| Classes with a `#recreate` gap | 584 |
| Classes already recreatable, only field/ref gaps left | 10 |

Per game (distinct classes): **S1 122, S2 192, S3K 271, shared `level.objects` 9.**

`#objectRef` split — **23 player-refs** (low effort: capture as player-slot id, the `deferredPlayerBoundCodec`/player-slot pattern) vs **19 object-child/sibling/parent refs** (risky: relink-by-id).

**Significance buckets** (route-impact, per CLAUDE.md S3K release priority AIZ→HCZ then CNZ/MGZ/ICZ/MHZ/LBZ):
- **Gameplay-critical (visible mid-rewind, on the release slice):** S3K bosses + their children, S3K traversal/capture objects (cannons, cylinders, vines, parachutes, freezer), grab badniks. ~95 classes.
- **High (active S3K route, non-boss):** CNZ/MGZ/ICZ/MHZ/LBZ zone objects, S3K projectiles/debris, monitors, springs, starposts.
- **Medium (S1/S2 polish, off release slice):** S1 + S2 zone objects/badniks/bosses.
- **Low / accept-drop:** short-lived cosmetic effects that respawn within a frame (see §3).

**Effort buckets:**
- **Low:** un-final-only classes (recreate already covered, 10) + `exactSpawnCodec` self-contained drops + player-ref captures (23). Mechanical, no relink.
- **Medium:** `exactSpawnCodec` + several un-final fields; single parent-relink children.
- **High:** sibling-relink children, multi-part bosses, cutscene controllers with child trees.

---

## 2. Prioritized batches (each = one PR-sized reviewed unit)

Ordered: gameplay-critical first, then low-effort-first within a tier. Each batch lands its objects **plus the shared registry/codec edit** that covers them together. `recommendedFix` per class uses: **`exactSpawnCodec`** (self-contained dynamic recreate), **`un-final`** (make non-spawn `final` scalars non-final so `GenericFieldCapturer` reapplies), **`player-ref-id`** (capture player as slot id), **`parent-relink`** / **`sibling-relink`** (find-live-by-id in spawn order), **`accept-drop`** (KD note, §3).

### Tier A — Gameplay-critical (S3K release slice, visible mid-rewind)

**Batch A1 — S3K player-capture traversal objects (LOW/MED).** These freeze/hold the player; a dropped capture mid-rewind strands the player. All player-refs → `player-ref-id`.
- `CnzCannonInstance` — player-ref-id (`capturedPlayer`, `releasedPlayer`) + un-final fire state
- `CnzCylinderInstance` — player-ref-id (`releasedJumpSolidSkipPlayer`) + un-final
- `MhzStickyVineObjectInstance` — player-ref-id (`capturedPlayer`) + un-final swing state
- `MhzMushroomParachuteObjectInstance` — player-ref-id (`grabbedPlayer`, `nativeP2GrabbedPlayer`) + un-final
- `PachinkoEnergyTrapObjectInstance` / `PachinkoFlipperObjectInstance` — player-ref-id (`capturedPlayer` / `lockedPlayer`)
- `IczFreezerObjectInstance` — `lastCaptureCloud` is an **object-ref** → `parent-relink` (find live cloud child); + un-final
- `PachinkoEnergyTrapObjectInstance` + the bumper/cork family below if same registry file

**Batch A2 — CNZ end-boss family (HIGH, multi-part).** Gameplay-critical, multi-part → careful recon (§4).
- `CnzEndBossInstance` — `exactSpawnCodec` + un-final + `endCannon` object-ref → `parent-relink`
- `CnzCannonInstance` (if boss-cannon variant) — `parent-relink`
- `CnzWaterLevelCorkFloorInstance` (`corkFloor` ref) + `CorkFloorObjectInstance` (`rollingBreakPlayer`) — `parent-relink` + `player-ref-id`

**Batch A3 — ICZ end-boss family (HIGH).**
- `IczEndBossInstance` — `exactSpawnCodec` + un-final + `bossSnowdustEmitter` → `parent-relink`
- `IczEndBossEggCapsuleInstance` — `exactSpawnCodec` + un-final

**Batch A4 — MHZ end-boss family (HIGH, largest child tree — 12 classes).** §4 risky. Mirror the AIZ2 precedent exactly.
- `MhzEndBossInstance` — `exactSpawnCodec` + un-final
- Children `MhzEndBossRobotnikHeadChild`, `MhzEndBossWeatherMachineChild`, `MhzEndBossWeatherVisualChild`, `MhzEndBossVisualChild`, `MhzEndBossSpikeChild`, `MhzEndBossHitProxyChild`, `MhzEndBossArenaHelperInstance`, `MhzEndBossRobotnikShipFlameInstance`, `MhzEndBossPaletteFadeController` — `exactSpawnCodec` + `parent-relink` to live `MhzEndBossInstance`; un-final non-spawn scalars
- `MhzEndBossDefeatFragmentChild` — `exactSpawnCodec` self-contained (carries world pos) → accept-drop candidate (§3)
- `MhzEndBossEggCapsuleInstance` — `exactSpawnCodec` + un-final

**Batch A5 — HCZ end-boss family (HIGH — 9 classes, primary AIZ→HCZ slice end).** §4 risky.
- `HczEndBossInstance` — `exactSpawnCodec` + un-final
- `HczEndBossRobotnikShip`, `HczEndBossTurbine`, `HczEndBossBlade`, `HczEndBossWaterColumn`, `HczEndBossGeyserCutscene` — `exactSpawnCodec` + `parent-relink`
- `HczEndBossBladeWaterChute`, `HczEndBossBladeSplash` — `sibling-relink` (need live blade) — §4
- `HczEndBossEggCapsuleInstance` — `exactSpawnCodec` + un-final

**Batch A6 — S3K grab/capture badniks (MED).**
- `MegaChopperBadnikInstance` — `player-ref-id` (`capturedPlayer`, `pendingMainPlayer`, `pendingSidekickPlayer`)
- `SpikerBadnikInstance` — `player-ref-id` (`pendingLaunchPlayer`)
- `ClamerObjectInstance` — `springChildSlot` object-ref → `parent-relink`
- `SnaleBlasterBadnikInstance` — `cover` object-ref → `parent-relink`

### Tier B — High (active S3K route, non-boss)

**Batch B1 — S3K CNZ zone objects (MED).** `CnzBumperObjectInstance` (player-ref-id ×2), `CnzCutsceneButtonInstance`/`Cnz2CutsceneButtonInstance` (`spawnedFlash` → parent-relink), `CnzLightsFlashChildInstance` (`exactSpawnCodec`/accept-drop §3), `CnzMinibossDebrisChild` (`exactSpawnCodec` self-contained → accept-drop §3).

**Batch B2 — S3K MGZ/LBZ zone objects (MED/HIGH).** `LbzMinibossInstance` (`knucklesFightParent` → parent-relink, §4), `LbzTubeElevatorInstance` (`overlayChild` → parent-relink), `Mgz2ResultsScreenObjectInstance`/`S3kResultsScreenObjectInstance`/`Mgz2ResultsScreenObjectInstance` (`playerRef` → player-ref-id), `MgzEndBossDefeatDebrisChild` (`exactSpawnCodec` → accept-drop §3), `MgzHeadTriggerProjectileInstance` (`exactSpawnCodec`).

**Batch B3 — S3K monitors / starposts / springs (LOW/MED).** `Sonic3kMonitorObjectInstance` (`monitorContentsSlot` → parent-relink), `Sonic3kStarPostObjectInstance` + `Sonic3kStarPostStarChild` + `Sonic3kStarPostBonusStarChild` (`exactSpawnCodec` + un-final), `Sonic3kSpringObjectInstance` (un-final), `S3kSlotRingRewardObjectInstance` (`exactSpawnCodec`).

**Batch B4 — S3K cutscene-Knuckles family (HIGH, object-ref heavy).** §4. `CutsceneKnucklesMhz1Instance`/`Mhz1CutsceneButtonInstance` (`parentButton`/`spawnedKnuckles` → parent-relink), `CutsceneKnucklesCnz2AInstance`/`CutsceneKnuxCnz2WallInstance` (`blockingWall`/`owner` → parent-relink), `CutsceneKnucklesLbz1CollapseChild`/`CutsceneKnucklesLbz1ThrownBomb`/`CutsceneKnucklesRockChild` (`exactSpawnCodec`).

### Tier C — Medium (S2 polish, off release slice)

**Batch C1 — S2 CPZ boss family (HIGH, 14 parts).** §4. All `CPZBoss*` (`exactSpawnCodec` + `parent-relink` to `Sonic2CPZBossInstance`; un-final). Land with `Sonic2CPZBossInstance` itself.
**Batch C2 — S2 EHZ boss family.** `EHZBoss*` + `Sonic2EHZBossInstance` — `exactSpawnCodec` + parent-relink.
**Batch C3 — S2 ARZ/CNZ/HTZ/MCZ/MTZ/WFZ/DEZ bosses** (one batch per zone family) — `exactSpawnCodec` + parent-relink; `Sonic2MechaSonicInstance` `targetingSensor` → parent-relink; `MCZFallingDebrisInstance`/`HTZBossSmokeParticle`/`CPZBossSmokePuff`/`LavaBubbleObjectInstance` → accept-drop §3.
**Batch C4 — S2 player-ref/projectile objects.** `SpringboardObjectInstance` (`launchPlayer`→player-ref-id), `BreakablePlatingObjectInstance`/`RivetObjectInstance` (`lastNativeMainPlayer`→player-ref-id), `SwingingPlatformObjectInstance`/`TornadoObjectInstance` (`displayChild`/`thrusterFollowerChild`→parent-relink), `GrabberBadnikInstance` (`grabbedPlayer`/`pendingGrabPlayer`→player-ref-id). Projectiles `ArrowProjectileInstance`/`GrounderRockProjectile`/`HtzFireProjectileObjectInstance`/`BadnikProjectileInstance`/`BombPrizeObjectInstance` → `exactSpawnCodec`.

### Tier D — Medium (S1 polish, off release slice)

**Batch D1 — S1 boss families** (FZ/GHZ/SYZ/SLZ/LZ/MZ/Scrap), one batch per boss: `exactSpawnCodec` + parent-relink. **Batch D2 — S1 player-ref objects** `Sonic1BumperObjectInstance` (`pendingTouchedPlayer`), `Sonic1EggPrisonObjectInstance` (`lastPlayer`), `Sonic1TeleporterObjectInstance` (`controlledPlayer`) → player-ref-id. **Batch D3 — S1 projectiles/badnik shrapnel** `Sonic1Bomb*`, `Sonic1BuzzBomberMissile*`, `Sonic1CrabmeatProjectileInstance` → `exactSpawnCodec`; `Sonic1RingFlashObjectInstance`/`Sonic1SplashObjectInstance` → accept-drop §3. **Batches D4..Dn — S1/S2/S3K un-final-only zone objects** (the bulk of the 1079 finalScalar gaps; group by registry file/zone, pure `un-final`, mechanical, no codec needed where recreate is already layout-covered).

---

## 3. Accept-drop (known-discrepancy) set — NO codec, documented note

These are short-lived, **self-contained cosmetic effects that re-emit within ~1 frame** of forward re-simulation; restoring them buys nothing and a 1-frame visual catch-up is within the design's stated non-goal tolerance. Add a `@RewindTransient` + a `KNOWN_DISCREPANCIES.md` / `S3K_KNOWN_DISCREPANCIES.md` note **mirroring the AIZ2 transient-children precedent** (same section style), and add their gap keys to the coverage baseline so the guard stays green with an auditable reason.

- **S3K:** `CnzLightsFlashChildInstance`, `CnzMinibossDebrisChild`, `MgzEndBossDefeatDebrisChild`, `MhzEndBossDefeatFragmentChild`, `RockDebrisChild`, `S3kBossExplosionChild`, `S3kSignpostSparkleChild`, `HCZWaterSplashObjectInstance`, `LightningSparkObjectInstance`, `Sonic3kSSEntryFlashObjectInstance`.
- **S2:** `CPZBossSmokePuff`, `HTZBossSmokeParticle`, `MCZFallingDebrisInstance`, `LavaBubbleObjectInstance`, `SuperSonicStarsObjectInstance`.
- **S1:** `Sonic1RingFlashObjectInstance`, `Sonic1SplashObjectInstance`, `Sonic1ExplosionItemObjectInstance`.

> Gate per candidate: drop it only if a capture→remove→restore round-trip shows it re-emits identically within 1 frame **and** it owns no player-ref / no gameplay-affecting state. Anything that holds the player, blocks terrain, or carries score → it must get a codec, not a drop.

---

## 4. Genuinely-risky — require careful recon before coding

These have sibling-relink or multi-part boss trees where spawn-order relink can dangle (the precise fragility the identity model in §3.2 of the design is meant to replace). Recon each: capture spawn order, confirm parent-before-child, decide relink-by-id vs. nearest-spawn-position fallback, and add the round-trip + object-set-diff assertion.

- **Sibling-relink children:** `HczEndBossBladeWaterChute`/`HczEndBossBladeSplash` (need live blade), any MHZ child needing a sibling not just the boss, S2 `EHZBoss*`/`CPZBoss*` chained segments (`CPZBossPipeSegment`, `CPZBossContainerExtend`).
- **Multi-part bosses:** A2 CNZ, A3 ICZ, A4 MHZ (12 parts), A5 HCZ (9 parts), C1 CPZ (14 parts) — recreate boss + all children as one occurrence; ensure the boss re-spawns children from its routine (not constructor) so restore + re-sim don't double-spawn (the AIZ2 boss precedent).
- **Cutscene controllers with child trees / owner back-refs:** `LbzMinibossInstance` (`knucklesFightParent`), B4 Cutscene-Knuckles family (`owner`/`blockingWall`/`parentButton` cross-refs), `Aiz2BossEndSequenceController`-style controllers.
- **Object-ref into a slot, not a child:** `Sonic3kMonitorObjectInstance.monitorContentsSlot`, `ClamerObjectInstance.springChildSlot` — confirm the slot target is itself captured/recreated before relink.

---

## 5. Per-batch execution contract (applies to every batch)

Each batch follows the established codec pattern from `ObjectRewindDynamicCodecs` / the per-game registry `DYNAMIC_REWIND_CODECS`: **`exactSpawnCodec` for self-contained dynamics; find-live-parent/sibling relink in spawn order (nearest-captured-spawn-position tiebreak, null-drop on absent target) for child refs; player-slot-id capture for player refs; un-final the non-spawn `final` scalars so `GenericFieldCapturer` reapplies them.** Then add a capture→mutate→restore **round-trip test** (per the AIZ2 `TestAiz2TransientChildRewind` pattern, ROM-gated where needed) asserting recreate + relink + mid-state (not spawn defaults), and **remove the batch's gap keys from `src/test/resources/rewind/coverage-baseline.txt`** (accept-drop entries stay, annotated) so `TestRewindCoverageGuard` ratchets down. Keep must-keep-green (`TestRewindParityAgainstTrace`, `TestRewindDeterminismAuditor`, `TestS3kAiz1SkipHeadless`, S3K loading) passing each batch.

**Suggested order to start:** A1 → A6 (low-effort player-refs + self-contained, fast wins on the release slice) → A2/A3 (smaller bosses) → A4/A5 (large boss trees, after the identity/relink pattern is proven) → B/C/D. Defer the bulk un-final-only batches (D4+) to run in parallel since they need no codec and no relink.

Key files each batch touches: per-game `*ObjectRegistry.java` (`DYNAMIC_REWIND_CODECS`), the object classes (un-final fields), `ObjectRewindDynamicCodecs.java` (shared helpers if a new relink shape is needed), `src/test/resources/rewind/coverage-baseline.txt`, and the matching `*_KNOWN_DISCREPANCIES.md` for accept-drop entries.