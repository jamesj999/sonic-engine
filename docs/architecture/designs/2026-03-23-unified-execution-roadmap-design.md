# Unified Execution Roadmap — Design Spec

**Date:** 2026-03-23
**Branch:** `feature/ai-common-utility-refactors`
**Target merge:** `develop`
**Status:** Draft

## Overview

Unified roadmap for all remaining architectural, code quality, and feature work on this branch. Consolidates findings from a comprehensive architectural review against the audit of 18 existing plans (10 architectural, 8 feature). Completed plans are retired. Partially-complete plans and net-new items are organized into 5 dependency-ordered phases.

**Note on plan file checkboxes:** Many existing plan files have unchecked (`- [ ]`) checkboxes despite their tasks being committed. Git commit history is the source of truth for completion status, not checkbox state.

## Current State

**Branch:** 387 commits ahead of master, 129 ahead of develop, 0 behind develop. Clean fast-forward merge path to develop.

### Completed Plans (Retired — No Further Work)

| Plan | Tasks | Key Commits |
|------|-------|-------------|
| Phase 3 Cycle Breaking | 6/6 | PlayableEntity, PowerUpSpawner, GroundMode/ShieldType extraction |
| Services Migration Cleanup | 10/10 | ObjectServices expansion, 180+ files migrated |
| Critical & High Fixes | 15/15 | All C/H severity items resolved |
| Architectural Fixes | 8/8 | Art dispatching, scroll handlers, swallowed exceptions, singleton reset |
| Singleton Coupling Reduction | 6/6 | GameServices expansion, ObjectServices interface |
| Per-Character Respawn | 5/5 | Sonic/Tails/Knuckles strategies |
| Cross-Game Animation Donation | Complete | CanonicalAnimation, AnimationTranslator, DonorCapabilities, per-game mappings |
| Phase 1 Singleton Lifecycle | ~10/12 | AudioManager verified, S1 state wrappers, GameContext counter, test migration, SingletonResetExtension |
| LevelManager Decomposition | 5/5 | LevelGeometry, LevelDebugRenderer, LevelTransitionCoordinator, LevelTilemapManager all extracted |

### LevelManager Post-Decomposition Status

All four planned extractions are committed. However, LevelManager is currently **3,629 lines** — other feature work (sidekick management, S3K support, water system, act transitions) added lines back after the decomposition. The original 5,068-line starting point was reduced, but the current state warrants a second decomposition pass in this roadmap.

### Genuinely Remaining Work

**From existing plans (verified against commits):**
- Phase 1: DebugOverlayManager `resetState()`, SonicConfigurationService test isolation (2 tasks)
- Phase 4 Common Refactoring: Phases 2-5 (18 tasks, defer bulk to future cycle)
- S3K Palette Cycling: 6 remaining zones (ICZ, LBZ, LRZ, BPZ, CGZ, EMZ)
- AIZ1 Miniboss Signpost: ~6 remaining tasks
- S3K Insta Shield: ~4 remaining tasks
- S3K Results Screen / Special Stage Results: ~6 remaining tasks
- Multi-Sidekick Chain: integration tests, docs

**Net-new from architectural review (no existing plan):**
- Production `System.out.println` / `e.printStackTrace()` cleanup
- `WaterSystem.getInstance()` synchronization
- Dead `CollisionSystem` flags removal
- Deprecated `SmpsSequencerConfig` constructor removal
- Shader compilation fail-fast
- PatternAtlas slot reclamation
- `DefaultObjectServices` LevelManager caching
- DPLC batch atlas updates
- `GraphicsManager` decomposition
- `GameModule.getGameId()` → abstract
- `CrossGameFeatureProvider` string → `GameId` enum
- NoOp sentinel unification for 3 providers
- `TouchResponses` deduplication
- `BatchedPatternRenderer` `writeQuad()` extraction
- Audio lock contention mitigation (theoretical — not observed, lower priority)
- LevelManager second-pass reduction

### Deferred to Future Cycle

- **Phase 5 Remaining Refactoring** (8 tasks) — aspirational extraction work, no blockers
- **Phase 4 Common Refactoring Phases 2-5** (18 tasks) — incremental utility migrations, cherry-pick applicable items into Phase 4 when touching related S3K files
- **Level editor branch** (`feature/ai-level-editor`) — separate integration cycle after develop merge
- **Diagnostic test cleanup** — converting assertion-free tests to real tests or separate Maven profile

## Non-Goals

- Full dependency injection framework
- Breaking the `physics ↔ sprites` cycle (natural coupling)
- Refactoring PlayableSpriteMovement or GameLoop (separate efforts)
- Changing ROM-accurate physics or collision behavior
- Any work on the level editor branch

---

## Phase 0: Code Hygiene Quick Wins

**Goal:** Eliminate production debug output, fix inconsistent error handling, remove dead code. All items are independent and parallelizable.

**Estimated scope:** ~15 files modified, no architectural changes.

### 0-1: Replace production System.out.println with LOGGER

Three production code sites write to stdout during normal gameplay:

- `ObjectManager.java:~1543` — `System.out.println(">>> SONIC HURT by: ...")` on every player hurt. Replace with `LOG.fine()`.
- `BatchedPatternRenderer.java:~377-380` — `System.out.println` guarded by `debugFrameCounter < 10`. Remove or replace with `LOGGER.fine()`.
- `BatchedPatternRenderer.java:~648-655` — `System.out.println` guarded by `executeDebugCounter < 5`. Remove or replace with `LOGGER.fine()`.

### 0-2: Migrate e.printStackTrace() to structured logging

22 call sites use `e.printStackTrace()` bypassing the JUL logging framework:

- `SonicConfigurationService.java` (lines ~56, ~158) — `LOGGER.log(Level.WARNING, "...", e)`
- `Sonic1SmpsLoader.java` (lines ~93, ~138, ~233, ~275) — `LOGGER.log(Level.SEVERE, "...", e)`
- `Sonic2SmpsLoader.java` (lines ~310, ~453, ~482, ~549, ~746) — `LOGGER.log(Level.SEVERE, "...", e)`
- `TitleScreenDataLoader.java` (line ~158) — `LOGGER.log(Level.WARNING, "...", e)`
- `ShaderProgram.java` / `ShaderLoader.java` — see also 0-6
- `AbstractPlayableSprite.java:~623-627` — contradictory `catch + LOGGER.fine + e.printStackTrace() + rethrow`. Remove the `e.printStackTrace()`; the rethrow surfaces the exception.

Grep for `e.printStackTrace()` and `System.err.println` to find all sites.

### 0-3: Synchronize WaterSystem.getInstance()

`WaterSystem.getInstance()` at `WaterSystem.java:~184` is the only core singleton without `synchronized`. Add `synchronized` to match every other manager singleton. One-line change.

**Note:** Some peripheral singletons (`Sonic1ConveyorState`, `Sonic1SwitchManager`, zone registries) also lack `synchronized` but are only accessed from the game loop thread. WaterSystem is the only one in the core manager tier that breaks the pattern.

### 0-4: Remove dead CollisionSystem flags

`unifiedPipelineEnabled` and `shadowModeEnabled` flags (lines ~34, ~37) are never set to `true`. `postResolutionAdjustments()` computes headroom but never acts on it.

Remove the two boolean fields and their setters. Remove the no-op headroom computation. Keep the `postResolutionAdjustments()` method shell if it has other callers, otherwise remove it.

### 0-5: Remove deprecated SmpsSequencerConfig constructors

Three constructors marked `@Deprecated(forRemoval = true)` (lines ~133, ~182, ~198) silently use S2 defaults for S3K-specific fields. Delete them and migrate any remaining callers to the Builder.

### 0-6: Add shader compilation fail-fast

`ShaderProgram` writes compilation/link failures to `System.err` but does not throw. Subsequent `use()` calls silently bind a broken or zero program.

After the compile or link error log, throw `RuntimeException("Shader compilation failed: " + infoLog)`. Converts silent render failure into a loud, immediately diagnosable crash at startup.

### 0-7: Fix stale comments and minor hygiene

- `Ym2612Chip.java:~436` — stale comment says "Disabled for testing" but field is `true`. Fix comment.
- `SonicConfiguration.java:~34` — `SCALE` entry Javadoc reads "TODO: Work out what this does". Document it or remove the TODO.

### 0-8: Remaining singleton lifecycle gaps

Two gaps from the original Phase 1 plan:

- **DebugOverlayManager**: Has no `resetState()`. Evaluate whether it holds per-level state (active overlays, toggle state). If so, add `resetState()` and include in `perTestResetSteps()`. If stateless, document why no reset is needed.
- **SonicConfigurationService**: Has no `resetState()` or `resetToDefaults()`. Tests that modify config values can bleed. Add a reset mechanism and wire into `TestEnvironment.resetAll()`.

---

## Phase 1: Provider Pattern & Module Cleanup

**Goal:** Standardize the GameModule provider pattern, fix feature flag leaks, and optimize hot-path service lookups. Independent of Phase 0.

### 1-1: Unify null-returning providers to NoOp sentinels

Three mandatory-abstract methods return `null` from S1 and S3K instead of using the NoOp sentinel pattern:

Convert to default methods:
- `getRomOffsetProvider()` → default returns `NoOpRomOffsetProvider.INSTANCE`
- `getDebugModeProvider()` → default returns `NoOpDebugModeProvider.INSTANCE`
- `getZoneArtProvider()` → default returns `NoOpZoneArtProvider.INSTANCE`

Create three NoOp implementations. Remove forced overrides from `Sonic1GameModule` and `Sonic3kGameModule`. Audit and remove null checks at call sites.

### 1-2: Make GameModule.getGameId() abstract

Default method contains a string switch over magic literals. Make `getGameId()` abstract. All three modules already return the correct `GameId`. Remove the string switch.

### 1-3: Replace CrossGameFeatureProvider string comparisons with GameId enum

Five `"s3k".equalsIgnoreCase(donorGameId)` string comparisons. Change parameter type from `String` to `GameId`. Use `switch(donorGameId)` with enum cases.

### 1-4: Cache LevelManager reference in DefaultObjectServices

Every method calls `LevelManager.getInstance()` — 8+ synchronized singleton lookups per player per frame.

Add `private final LevelManager levelManager` field set in constructor. `DefaultObjectServices` is created once per `ObjectManager` construction (once per level load), so the cached reference is valid for the services instance lifetime.

### 1-5: TouchResponses update/updateSidekick deduplication

~150 lines of near-duplicate collision loop code. Extract `private void processTouch(AbstractPlayableSprite player, boolean isSidekick)`. Both public methods delegate to it.

### 1-6: Sonic2GameModule lazy-init documentation

Audit all `get*Provider()` methods. For stateless providers, cache lazily. For stateful providers that need fresh state per level, add a clarifying comment. No behavior change — documentation only.

---

## Phase 2: LevelManager & GraphicsManager Reduction

**Goal:** Second-pass reduction of the two largest god classes. LevelManager is 3,629 lines after the first decomposition round. GraphicsManager is 1,394 lines with zero internal delegation.

### 2-1: LevelManager second-pass assessment

Read the current LevelManager and identify the next extraction candidates. The first pass extracted rendering (~LevelTilemapManager), debug (~LevelDebugRenderer), and transitions (~LevelTransitionCoordinator). Likely candidates for a second pass:
- Sidekick management (~200+ lines added since first decomposition)
- Water surface management (water level tracking, waterline rendering)
- HUD initialization and ring sheet setup
- Pattern caching coordination

Target: identify 2-3 extractions that bring the class below 2,800 lines.

### 2-2: GraphicsManager decomposition

Extract three focused classes:
- `PaletteTextureManager` — palette texture caching, underwater palette, palette upload buffer, waterline state
- `ShaderInventory` — 8 shader program fields, loading, uniform caching lifecycle
- `CnzSlotsRendererHolder` — lazy-init CNZ slot machine renderer

GraphicsManager retains: singleton lifecycle, pattern atlas delegation, render command queue, batch orchestration, viewport, FBO coordination. Target: ~800 lines.

### 2-3: PatternAtlas slot reclamation

`removeEntry()` clears lookup but never resets `nextSlot`, permanently leaking physical slots.

Add `freeSlots` list to `AtlasPage`. `removeEntry()` records freed slot. `allocateSlot()` checks `freeSlots` before incrementing. Log `SEVERE` on true exhaustion. Add test for cache → remove → re-cache cycle.

### 2-4: Batch DPLC atlas updates

`DynamicPatternBank.applyRequests()` triggers 10-30 individual `glTexSubImage2D` per animation frame change.

Wrap in `patternAtlas.beginBatch()` / `endBatch()`. The batch mode accumulates dirty regions in the `cpuPixels` buffer and flushes a single `glTexSubImage2D` covering the dirty extent. Requires extending `PatternAtlas` batch mode to track the min/max modified slot for efficient partial upload.

### 2-5: BatchedPatternRenderer writeQuad extraction

`addPattern`, `addStripPattern`, `addShadowPattern` each have ~40 lines of identical vertex construction. Extract `writeQuad()` helper. Net reduction: ~80 lines.

---

## Phase 3: Graphics & Audio Polish

**Goal:** Lower-priority improvements to the rendering and audio subsystems. Can overlap with Phase 4 since they touch different packages.

### 3-1: Audio lock contention mitigation

**Status: Theoretical concern.** `SmpsDriver.read()` holds `sequencersLock` for the entire buffer fill (~21ms). No contention has been observed in practice, but the design means SFX triggers block until buffer fill completes.

**Design:** Snapshot the sequencer list under the lock, then release before the render loop. New sequencers added during rendering are picked up on the next buffer fill. Lock hold time drops from ~21ms to microseconds. Tradeoff: newly triggered SFX may be delayed by one additional buffer period — acceptable.

**Priority:** Low. Implement only if audio stutter is observed, or if Phase 4 work runs ahead of schedule.

### 3-2: Deprecated SmpsSequencerConfig constructor cleanup verification

After 0-5 removes the deprecated constructors, verify no test or loader regressed to using old constructor patterns. Run the audio test suite specifically.

---

## Phase 4: S3K Feature Completion

**Goal:** Finish all partially-complete S3K features. When touching S3K object files, opportunistically apply common refactoring cherry-picks (SubpixelMotion migration, inherited `getRenderer()`, `isOnScreen()`).

### 4-1: S3K Palette Cycling — 6 remaining zones

**Detailed plan:** `docs/architecture/plans/2026-03-14-s3k-palette-cycling-all-zones.md`

Remaining: ICZ, LBZ, LRZ, BPZ, CGZ, EMZ. Pattern established by HCZ/CNZ implementations. Each zone: ROM constant verification via RomOffsetFinder, cycle class in `Sonic3kPaletteCycler`, registration, and a test.

### 4-2: S3K Insta Shield completion

**Detailed plan:** `docs/architecture/plans/2026-03-18-s3k-insta-shield.md`

Remaining: `TestInstaShieldGating` unit test, `PlayableSpriteMovement.tryShieldAbility()` integration verification, expanding hitbox visual rendering, touch response expansion during active frames.

### 4-3: S3K Results Screen completion

**Detailed plan:** `docs/architecture/plans/2026-03-16-s3k-results-screen.md`

Remaining: Full state machine verification (tally counting, bonus calculation), integration into level event flow, end-of-results transition (next act or special stage).

### 4-4: S3K Special Stage Results completion

**Detailed plan:** `docs/architecture/plans/2026-03-17-s3k-special-stage-results.md`

Remaining: Tally unit test, full integration test with special stage → results → gameplay transition.

### 4-5: AIZ1 Miniboss Signpost remaining

**Detailed plan:** `docs/architecture/plans/2026-03-16-aiz1-miniboss-signpost.md`

Remaining: Knuckles napalm attack controller/projectile (verify — commit `00e678ec3` added `AizMinibossNapalmProjectile`), signpost art/mapping DPLC loading, integration testing. Verify scope of remaining work before implementing.

### 4-6: Multi-Sidekick Chain completion

**Detailed plan:** `docs/architecture/plans/2026-03-19-multi-sidekick-chain.md`

Remaining: Integration tests, documentation update. Core functionality is merged and working.

### 4-7: Common refactoring cherry-picks

While touching S3K object files for 4-1 through 4-6, opportunistically apply:
- Migrate inline SubpixelMotion math → `SubpixelMotion.State`
- Migrate verbose `getObjectRenderManager()` patterns → inherited `getRenderer(key)`
- Migrate inline `isOnScreen` checks → inherited `isOnScreen(margin)`

Only apply to files already being modified. Do not do bulk migration passes.

---

## Phase Dependency Graph

```
Phase 0 (Hygiene) ----\
                       +--- soft: minimize file conflicts
Phase 1 (Providers) ---/
                                    Phase 3 (Graphics/Audio Polish)
Phase 2 (God Class Reduction) ---->        |
                                           v
                                    Phase 4 (S3K Features)
```

- **Phase 0 and 1 are independent** — can run in parallel. The Phase 0→1 dependency is soft (minimize merge conflicts on overlapping files, not a logical dependency).
- **Phase 2 is independent** of Phases 0 and 1 — touches different subsystems (LevelManager, GraphicsManager, PatternAtlas).
- **Phase 3 is independent** of everything — audio and rendering polish.
- **Phase 4 (S3K features) can begin as soon as Phase 0 is complete** (stable singletons). It can overlap with Phases 1-3 since S3K features primarily touch `game/sonic3k/` code, not shared infrastructure.
- **Maximum parallelism:** All four phases can run concurrently with care about file overlap.

## Merge Strategy

All work stays on `feature/ai-common-utility-refactors`. Each phase produces compilable, test-passing commits. After Phase 4 completion:
1. Run full `mvn test` verification
2. Merge to `develop`

## Success Criteria

- `mvn test` passes after each phase
- LevelManager reduced below 2,800 lines (from current 3,629)
- GraphicsManager reduced to ~800 lines (from current 1,394)
- Zero `System.out.println` in production code
- Zero `e.printStackTrace()` in production code
- All core manager singletons have consistent `synchronized getInstance()` + `resetState()`
- No game-name string comparisons in shared code
- PatternAtlas supports slot reclamation
- All 3 null-returning GameModule providers converted to NoOp sentinels
