# Phase 4 Common Refactoring — Design Spec

**Date:** 2026-03-21
**Branch:** `feature/ai-common-utility-refactors`
**Builds on:** Phases 1-3 (PatternDecompressor, LazyMappingHolder, FboHelper, AnimationTimer, AbstractLevel, AbstractSmpsLoader, PaletteLoader, CommonSpriteDataLoader, DestructionEffects, PatrolMovementHelper, SpringBounceHelper, DebugRenderContext migration, isOnScreen migration)

## Goal

Extract remaining cross-game duplication into shared utilities, base classes, and behavioral helpers. Reduces ~207 files of duplicated code across 25 identified patterns, organized into 5 dependency-ordered phases.

## Non-Goals

- Changing game behavior or physics
- Adding new features or engine capabilities
- Refactoring code that isn't duplicated (no speculative cleanup)
- Updating skills/documentation (separate follow-up task)

---

## Phase 1: Migrations to Existing Infrastructure

### 1a. Promote `SubpixelMotion` to Neutral Package

**Current state:** `SubpixelMotion` and its `State` inner class live in `com.openggf.game.sonic3k.objects`. 33 S1/S2 files inline the identical 8-line 16:8 fixed-point motion math:

```java
int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
xPos24 += xVelocity;
yPos24 += yVelocity;
currentX = xPos24 >> 8;
currentY = yPos24 >> 8;
xSubpixel = xPos24 & 0xFF;
ySubpixel = yPos24 & 0xFF;
```

**Change:**
- Move `SubpixelMotion` from `com.openggf.game.sonic3k.objects` to `com.openggf.level.objects`
- Update existing S3K importers (3-4 files)
- Migrate ~33 S1/S2 files: replace inline math with `SubpixelMotion.State` field + `moveSprite()`/`moveSprite2()` calls

**Affected files (S1):** `Sonic1BuzzBomberMissileInstance`, `Sonic1CrabmeatProjectileInstance`, `Sonic1BombShrapnelInstance`, `Sonic1CannonballInstance`, `Sonic1CaterkillerBodyInstance`, `Sonic1NewtronMissileInstance`, `Sonic1BombBadnikInstance`, `Sonic1MovingBlockObjectInstance`, `Sonic1PlatformObjectInstance`, `Sonic1GirderBlockObjectInstance`, `Sonic1LabyrinthBlockObjectInstance`, plus ~8 more S1 objects.

**Affected files (S2):** `BadnikProjectileInstance`, `SolFireballObjectInstance`, `WallTurretShotInstance`, `GrounderRockProjectile`, `SlicerPincerInstance`, `RexonHeadObjectInstance`, `AquisBadnikInstance`, `GrabberBadnikInstance`, `NebulaBadnikInstance`, `SpikerBadnikInstance`, `ShellcrackerClawInstance`, `TurtloidBadnikInstance`, `CrawltonBadnikInstance`, `CollapsingPlatformObjectInstance`, `ConveyorObjectInstance`.

### 1b. Migrate to Inherited `getRenderer(key)`

**Current state:** `AbstractObjectInstance` already provides `protected PatternSpriteRenderer getRenderer(String key)` which performs the null-check chain (get ObjectRenderManager → null check → get renderer → ready check). Many older S1/S2 files still use the verbose 5-line form calling `LevelManager.getInstance().getObjectRenderManager()` directly.

**Scope:** Only migrate files where `appendRenderCommands()` uses the 5-line pattern to get a single renderer and draw frames. Files that call other methods on `ObjectRenderManager` (beyond `getRenderer()`) are out of scope. Estimated ~40-60 files in the simple-migration category; the exact count will be determined during implementation by grepping for the pattern.

**Change:** Replace the verbose pattern with `getRenderer(key)` in each qualifying file's `appendRenderCommands()`. Pure mechanical migration, no behavioral change.

### 1c. Relocate `AbstractBadnikInstance` to Neutral Package

**Current state:** `AbstractBadnikInstance` lives in `com.openggf.game.sonic2.objects.badniks` but is used by all S1 badniks (12 files) and all S2 badniks. It hardcodes `Sonic2Sfx.EXPLOSION.id` for the destruction SFX.

**Change:**
- Move `AbstractBadnikInstance` to `com.openggf.level.objects`
- Accept `DestructionConfig` via constructor (already exists from Phase 3 — `Sonic1DestructionConfig` demonstrates the pattern)
- Each game's badnik factory supplies the game-specific `DestructionConfig` with the correct explosion SFX ID
- Clean up field shadowing: `AbstractBadnikInstance` declares `protected boolean destroyed` which shadows `AbstractObjectInstance`'s `private boolean destroyed` (with `setDestroyed()`/`isDestroyed()`). Remove the local field and use only the parent's accessors.
- Update all ~30 imports across S1 and S2 badniks

**Note:** `AbstractS3kBadnikInstance` extends `AbstractObjectInstance` directly, NOT `AbstractBadnikInstance`. S3K badniks are unaffected by this move. Migrating S3K badniks to use the relocated `AbstractBadnikInstance` is out of scope.

---

## Phase 2: New Base Methods on `AbstractObjectInstance`

### 2a. `buildSpawnAt(int x, int y)`

**Current state:** Many files construct identical `ObjectSpawn` instances for dynamic position tracking. The `getSpawn()` override appears in ~115 files and `refreshDynamicSpawn()` in ~45 (with significant overlap). Not all construct the spawn identically — focus on files using the standard 7-arg pattern:

```java
new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(),
    spawn.renderFlags(), spawn.respawnTracked(), spawn.rawYWord())
```

This appears in two patterns:
- `getSpawn()` overrides (~30 files) — return a fresh spawn at current position
- `refreshDynamicSpawn()` private methods (~45 files) — cached guard + construct

**Change:**
- Add `protected ObjectSpawn buildSpawnAt(int x, int y)` to `AbstractObjectInstance`
- `getSpawn()` overrides collapse to `return buildSpawnAt(currentX, currentY)`
- `refreshDynamicSpawn()` methods use `dynamicSpawn = buildSpawnAt(x, y)` (guard logic stays local)

### 2b. `isPlayerRiding()`

**Current state:** 15+ platform/block files declare private `isPlayerRiding()` or `isStanding()` methods with identical implementation.

**Change:**
- Add `protected boolean isPlayerRiding()` to `AbstractObjectInstance`
- Implementation: `LevelManager lm = LevelManager.getInstance(); if (lm != null && lm.getObjectManager() != null) return lm.getObjectManager().isAnyPlayerRiding(this); return false;`
- Delete 15+ private copies across S1/S2/S3K platform objects

---

## Phase 3: Infrastructure Base Classes

### 3a. `AbstractZoneScrollHandler`

**Current state:** ~20 scroll handler classes each declare `minScrollOffset`, `maxScrollOffset`, `vscrollFactorBG` fields, a `trackOffset(short, short)` method (or inline equivalent), reset logic at top of `update()`, and three getter methods — all functionally identical. Handlers live in three packages: `level.scroll` (S2, ~10 files), `game.sonic1.scroll` (S1, ~8 files), and `game.sonic3k.scroll` (S3K, ~3 files).

**Change:**
- New `AbstractZoneScrollHandler` abstract class implementing `ZoneScrollHandler`
- Contains: fields, `resetScrollTracking()`, `trackOffset(short fgScroll, short bgScroll)`, `getMinScrollOffset()`, `getMaxScrollOffset()`, `getVscrollFactorBG()`
- ~20 concrete handlers extend it, call `resetScrollTracking()` at top of `update()`
- Each handler deletes its private fields, `trackOffset()`, and getter methods
- Note: some handlers (e.g., `SwScrlArz`, `SwScrlScz`) inline the tracking logic rather than using a named `trackOffset()` method — these need the inline code replaced with calls to the inherited `trackOffset()`

**Package:** `com.openggf.level.scroll` (concrete S1/S3K handlers stay in their game-specific packages with cross-package imports, consistent with existing conventions)

### 3b. `AbstractZoneRegistry`

**Current state:** 3 game zone registries share identical implementations of `getZoneCount()`, `getActCount()`, `getZoneName()`, `getLevelDataForZone()`, `getAllZones()`.

**Change:**
- New abstract base holding `zones` list and `ZONE_NAMES` array
- Provides the 5 shared methods
- S3K subclass overrides `getMusicId()` for per-act music (2D array vs 1D)
- Each game subclass provides data arrays via constructor or abstract method

**Package:** `com.openggf.game` (alongside `GameModule`)

### 3c. `AbstractLevelInitProfile` Core Steps

**Current state:** The existing `AbstractLevelInitProfile` base class exists but 3 game-specific subclasses each build identical 12-step `InitStep` lists with the same lambda bodies and the same try/catch `UncheckedIOException` wrapping.

**Change:**
- Add `protected List<InitStep> buildCoreSteps(LevelLoadContext ctx)` to `AbstractLevelInitProfile`
- Add `protected static InitStep ioStep(String name, String desc, IORunnable action)` helper that wraps `try { action.run(); } catch (IOException e) { throw new UncheckedIOException(e); }`
- Each game's `levelLoadSteps()` calls `buildCoreSteps(ctx)` then appends game-specific overrides
- `IORunnable` is a `@FunctionalInterface` with `void run() throws IOException`

### 3d. `AbstractObjectRegistry`

**Current state:** 3 game object registries share identical `ensureLoaded()`, `create()`, `defaultFactory`, `factories` map, and `loaded` flag patterns.

**Change:**
- New abstract base with `factories` map, `loaded` flag, `defaultFactory` lambda
- Provides `ensureLoaded()`, `create(ObjectSpawn)` (matching the `ObjectRegistry` interface), `registerFactory(int, ObjectFactory)`
- Provides no-op default for `reportCoverage(List<ObjectSpawn>)` (S1/S3K have no-ops; S2 overrides with its coverage tracking)
- `getPrimaryName(int)` left abstract (S2 uses `namesById` map, S1/S3K use different approaches)
- Each game subclass implements `registerDefaultFactories()` and `getPrimaryName()`

**Package:** `com.openggf.level.objects`

### 3e. `AniPlcScriptState` + `AniPlcParser`

**Current state:** `Sonic2PatternAnimator.ScriptState` and `Sonic3kPatternAnimator.ScriptState` are byte-for-byte identical (the S3K version has a comment acknowledging this). Both animators also duplicate the binary parser loop (`parseList`/`parseAniPlc`) and `loadArtPatterns()`. **S1's `Sonic1PatternAnimator` is NOT affected** — it uses a completely different hardcoded approach (per-zone `AnimHandler` inner classes, no `ScriptState`).

**Change:**
- Extract `AniPlcScriptState` to `com.openggf.level.animation` with fields: `globalDuration`, `destTileIndex`, `frameTileIds`, `frameDurations`, `tilesPerFrame`, `artPatterns`, `timer`, `frameIndex`; methods: `tick()`, `prime()`, `applyFrame()`, `requiredPatternCount()`
- Extract `AniPlcParser` with `static List<AniPlcScriptState> parseScripts(RomByteReader reader, int addr)` and `static Pattern[][] loadArtPatterns(RomByteReader reader, int artAddr, int tilesPerFrame, int[] frameTileIds)`
- Delete private `readU32BE()` copies — use `reader.readU32BE()` directly
- `Sonic2PatternAnimator` and `Sonic3kPatternAnimator` delegate to shared classes
- Also extract `ensurePatternCapacity()` and `primeScripts()` loops (identical in both) into the shared `AniPlcParser`

### 3f. `AbstractAudioProfile`

**Current state:** 3 game audio profiles share `handleSystemCommand()` logic (identical between S1 and S3K) and structural method patterns.

**Change:**
- New abstract base with `handleSystemCommand()` helper that accepts command IDs and a `Runnable` or parameterized callback for the fade-out action (S3K passes explicit fade parameters `(0x28, 6)` while S1 uses no-arg `fadeOutMusic()`, so the fade action must be game-configurable — not just the command ID)
- Shared `SOUND_MAP` initialization pattern as a protected builder method
- Each game profile extends, providing command IDs, fade behavior, and game-specific overrides

**Package:** `com.openggf.audio`

### 3g. `AbstractSoundTestCatalog`

**Current state:** 3 game sound test catalogs are structurally identical, differing only in which static maps they reference.

**Change:**
- New abstract base or static factory: takes `titleMap`, `sfxNames`, `defaultSongId`, `sfxBase`, `sfxMax`, `gameName`
- Provides all 6 interface method implementations from supplied data
- Each game catalog becomes a thin wrapper passing its constants

**Package:** `com.openggf.audio` (alongside the interface)

### 3h. `ParallaxShaderProgram` Extends `ShaderProgram`

**Current state:** `ParallaxShaderProgram` reimplements the entire GL shader program lifecycle (create, link, attach, detach, delete, use/stop, cleanup, `uniformsCached` guard) instead of extending `ShaderProgram` like all other shader subclasses.

**Change:**
- Make `ParallaxShaderProgram` extend `ShaderProgram`
- Delete duplicated lifecycle code (~50 lines): `programId` field, `uniformsCached` guard, `glCreateProgram`/`glAttachShader`/`glLinkProgram`/`glDetachShader`/`glDeleteShader` sequence, `use()`/`stop()`, `cleanup()`
- Reconcile uniform caching: override `cacheUniformLocations()` to call `super.cacheUniformLocations()` then cache parallax-specific uniforms, or call the parent's `getUniformLocation()` if available
- Keep parallax-specific uniform setters and rendering logic
- Constructor calls `super(vertexShaderPath, fragmentShaderPath)`

---

## Phase 4: Behavioral Object Base Classes

### 4a. `AbstractProjectileInstance`

**Current state:** 19+ projectile classes across all 3 games implement identical motion+gravity+off-screen-destroy+HURT-collision logic.

**Change:**
- New base in `com.openggf.level.objects`
- Extends `AbstractObjectInstance` (which provides `getX()`/`getY()` but not `currentX`/`currentY` fields — the projectile base declares its own position fields for subpixel tracking)
- Fields: `currentX`, `currentY`, `xVelocity`, `yVelocity`, `SubpixelMotion.State`, `gravity`, `collisionSizeIndex`
- Concrete `update()`: apply `SubpixelMotion`, check `isOnScreen(margin)`, `setDestroyed(true)` if off-screen
- Implements `TouchResponseProvider` with `0x80 | sizeIndex`
- Abstract: `getArtKey()`, `getMappingFrame()` for rendering
- S1 missile classes, S2 `BadnikProjectileInstance`, S3K `S3kBadnikProjectileInstance` extend or are replaced

### 4b. `AbstractSpikeObjectInstance`

**Current state:** S2 `SpikeObjectInstance` and S3K `Sonic3kSpikeObjectInstance` share identical retract constants, dimension tables, and 6+ methods.

**Change:**
- New shared base with: `SPIKE_RETRACT_STEP`, `SPIKE_RETRACT_MAX`, `SPIKE_RETRACT_DELAY`, `WIDTH_PIXELS[]`, `Y_RADIUS[]`, `moveSpikesDelay()`, `moveSpikesVertical()`, `moveSpikesHorizontal()`, `shouldHurt()`, `isSideways()`, `isUpsideDown()`, `getEntryValue()`, `getSolidParams()`
- S3K subclass overrides `moveSpikes()` to add push-mode (behavior 3)
- S1 spikes excluded (different subtype encoding)

**Package:** `com.openggf.level.objects`

### 4c. `AbstractMonitorObjectInstance`

**Current state:** 3 game monitor classes share identical icon-rise physics constants and state machine.

**Change:**
- New base with: `ICON_INITIAL_VELOCITY = -0x300`, `ICON_RISE_ACCEL = 0x18`, `ICON_WAIT_FRAMES = 0x1D`, `BROKEN_FRAME = 0x0B`
- Contains `iconVelY`/`iconSubY` fields and `updateIcon()` method
- Abstract `applyPowerup(AbstractPlayableSprite player)` for game-specific power-ups
- Each game subclass provides power-up set and renderer key

**Package:** `com.openggf.level.objects`

### 4d. `AbstractPointsObjectInstance`

**Current state:** S1 and S2 points objects share identical physics (`INITIAL_Y_VEL = -0x300`, `GRAVITY = 0x18`, destroy when `yVel >= 0`).

**Change:**
- New base with physics constants and `update()` implementation
- Abstract `getFrameForScore(int score)` for game-specific score→frame mapping
- S1 and S2 extend with their frame tables

**Package:** `com.openggf.level.objects`

### 4e. `PlatformBobHelper`

**Current state:** S1, S2, and S3K platform objects implement identical sine-based standing-nudge displacement.

**Change:**
- New utility class (not a base class) in `com.openggf.level.objects`
- Constructor: `PlatformBobHelper(int stepSize, int maxAngle, int amplitudeShift)`
- `update(boolean isStanding)`: increment/decrement angle toward max/0
- `getOffset()`: returns sine displacement
- Replaces identical nudge/bob logic in 3 platform objects

### 4f. `GravityDebrisChild`

**Current state:** 7+ fragment/debris child classes share identical gravity+motion+off-screen-destroy logic.

**Change:**
- New base in `com.openggf.level.objects` with `SubpixelMotion.State`, configurable gravity (default `0x18`), initial velocity from constructor
- Concrete `update()`: apply motion, destroy when off-screen
- Abstract `appendRenderCommands()` for game-specific rendering
- `AizRockFragmentChild`, `RockDebrisChild`, `Sonic1BombShrapnelInstance` extend

---

## Phase 5: Low-Priority Cleanup

### 5a. `SmpsSequencerConfig` Default Constants

Move `TEMPO_MOD_BASE`, `FM_CHANNEL_ORDER`, `PSG_CHANNEL_ORDER` to `SmpsSequencerConfig` as static defaults. 3 per-game config classes reference instead of redeclare.

### 5b. Consolidate `loadArtTiles()` into `CommonSpriteDataLoader`

Move identical single-line delegation from `S1SpriteDataLoader.loadArtTiles()` and `S3kSpriteDataLoader.loadArtTiles()` to `CommonSpriteDataLoader.loadArtTiles()`.

### 5c. Shared `FULLSCREEN_VERTEX_SHADER` Constant

Move from `ParallaxShaderProgram`, `TilemapShaderProgram`, and `GraphicsManager` (which has its own `FULLSCREEN_VERTEX_SHADER_PATH` copy) to `ShaderProgram` as `protected static final`. All three reference `ShaderProgram.FULLSCREEN_VERTEX_SHADER`.

---

## Constraints

- **No behavioral changes.** All refactoring is structural — tests must pass identically before and after.
- **Phase ordering.** Each phase depends only on prior phases. Within a phase, items are independent.
- **Test strategy.** `mvn test` after each phase. No new tests needed (these are structural extractions).
- **Package conventions.** Game-agnostic code goes in `com.openggf.level.objects`, `com.openggf.level.animation`, `com.openggf.level.scroll`, `com.openggf.audio`, or `com.openggf.game`. Game-specific code stays in `com.openggf.game.{sonic1,sonic2,sonic3k}`.

## Summary

| Phase | New Classes | Files Modified | Lines Removed (est.) |
|-------|-------------|---------------|---------------------|
| 1. Migrations | 0 (1 move) | ~75 | ~300 |
| 2. Base methods | 0 (2 additions) | ~60 | ~200 |
| 3. Infra bases | 8 new classes | ~35 | ~400 |
| 4. Behavioral bases | 6 new classes | ~25 | ~350 |
| 5. Cleanup | 0 | ~10 | ~30 |
| **Total** | **14 new classes** | **~205** | **~1,280** |
