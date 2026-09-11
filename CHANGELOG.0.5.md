# OpenGGF v0.5 Changelog

## v0.5.20260411 (Released 2026-04-11)

Analysis range: `v0.4.20260304..v0.5.20260411` on `develop` (`2479` commits, `2298` non-merge commits,
`1588` files changed, `477351` insertions, `28266` deletions). Net code growth is ~449,100 lines.

A primarily architectural release. The engine internals have been restructured to prepare for level
editor support, safe runtime teardown, and future multi-instance play-testing. Sonic 3 & Knuckles
gameplay coverage has advanced significantly across Angel Island and Hydrocity: the AIZ2 Flying
Battery bombing sequence, AIZ2 end boss, post-boss capsule/cutscene flow, AIZ-to-HCZ transition,
HCZ1 miniboss, and HCZ1-to-HCZ2 transition are now represented, alongside all three S3K bonus-stage
families in active implementation.

### Architectural Overhaul: Two-Tier Service Architecture

The engine's object model has been fundamentally restructured from direct singleton access to a
two-tier dependency injection pattern.

- **GameServices** (global tier): facade over ROM, graphics, audio, camera, level, fade, and
  configuration singletons. Accessed anywhere via `GameServices.camera()`, `GameServices.audio()`, etc.
- **ObjectServices** (context tier): injected into every game object at spawn time via
  `ObjectManager`. Provides camera, game state, zone features, sidekick access, and level queries
  scoped to the object's lifecycle. Accessed via `services()` within any `AbstractObjectInstance`.
- **ThreadLocal construction context**: `ObjectServices` is available during object construction
  without requiring constructor parameters, via a `ThreadLocal` injection pattern.
- **Migration scope**: 105 Sonic 2 object files, 50 Sonic 1 object files, 25 Sonic 3K object files,
  and 6 game-agnostic base classes migrated from `getInstance()` / `LevelManager.getInstance()` to
  `services()` or `GameServices` as appropriate. All singleton `.getInstance()` calls removed from
  object classes.
- **NoOp sentinels**: null-returning provider methods replaced with NoOp sentinel objects across
  the provider interfaces (zone features, physics, water, scroll handlers), eliminating null checks
  throughout the object layer.
- **GameId enum**: replaced string-based game identification with type-safe `GameId` enum throughout
  `CrossGameFeatureProvider` and module detection.

### Architectural Overhaul: GameRuntime and Singleton Lifecycle

- **GameRuntime**: introduced `GameRuntime` as the explicit owner of all mutable gameplay state.
  `ObjectServices` is backed by the runtime instance rather than global singletons.
- **resetState() lifecycle**: all singletons (`Camera`, `RomManager`, `GraphicsManager`,
  `AudioManager`, `CollisionSystem`, `CrossGameFeatureProvider`, `DebugOverlayManager`,
  `SonicConfigurationService`, `Sonic1ConveyorState`, `Sonic1SwitchManager`,
  `TerrainCollisionManager`) now implement `resetState()` for clean teardown without destroying
  the singleton instance. `resetInstance()` deprecated across the board.
- **Generation counter**: `GameContext` tracks a generation counter for stale reference detection.
- **SingletonResetExtension**: JUnit 5 extension with `@FullReset` annotation for automated
  per-test singleton reset, replacing manual `resetInstance()` boilerplate across 35+ test classes.
- **GameRuntime lifecycle wired into test harness**: `resetPerTest()` now creates/destroys
  `GameRuntime` for CI stability.

### Architectural Overhaul: LevelManager Decomposition

`LevelManager` (previously the largest class in the engine) has been broken into focused components:

- **LevelTilemapManager**: extracted ~18 methods and ~22 fields for tilemap rendering, chunk
  lookup, and tile-level queries.
- **LevelTransitionCoordinator**: extracted ~43 methods and ~25 fields for act transitions,
  seamless level mutation, title cards, results screens, and game mode flow.
- **LevelDebugRenderer**: extracted ~12 methods and ~14 fields for collision overlay, sensor
  display, camera bounds, and other debug visualizations.
- **LevelGeometry** and **LevelDebugContext** records introduced as data carriers between the
  decomposed components.
- Game-specific art dispatching extracted from `LevelManager` into per-game modules.

### Architectural Overhaul: Cross-Game Abstraction Hardening

Systematic removal of game-specific coupling from the engine core:

- **PlayableEntity interface**: extracted from `AbstractPlayableSprite` to decouple `level.objects`
  from `sprites.playable`. Includes `isOnObject()`, `getAnimationId()`, and all methods needed
  by game objects to interact with the player.
- **PowerUpSpawner interface**: breaks `sprites.playable` dependency on `level.objects` for
  monitor/power-up spawning.
- **DamageCause**, **GroundMode**, **ShieldType** relocated from `sprites.playable` to `game`
  package for cross-game reuse.
- **SecondaryAbility enum**: replaced `instanceof Tails` checks throughout the codebase.
- **CanonicalAnimation enum** and **AnimationTranslator**: cross-game animation vocabulary
  enabling bidirectional sprite donation between any pair of games.
- **DonorCapabilities interface**: each `GameModule` declares its donation capabilities (S1, S2,
  S3K all implemented), replacing hardcoded branches in `CrossGameFeatureProvider`.
- S1 wired as donor for forward donation into S2/S3K games.
- CNZ slot machine renderer moved to `ZoneFeatureProvider`; seamless mutation moved to
  `GameModule`; Tails tail art loading moved to `GameModule`; sidekick zone suppression moved
  from hardcoded S2 IDs to `GameModule`.
- 11 cross-game classes relocated from `sonic2` to generic packages; 5 cross-game dependency
  classes decoupled.

### Common Code Extraction (Phase 1-5)

A systematic 5-phase refactoring pass eliminated structural duplication across all three games:

- **Phase 1 — Common utilities**: `SubpixelMotion` (16.16 fixed-point gravity helpers),
  `AnimationTimer` (cyclic frame animation), `FboHelper` (centralised FBO creation),
  `PatternDecompressor.nemesis()` (eliminated private Nemesis copies), `refreshDynamicSpawn()`
  extracted into `AbstractObjectInstance`, `isOnScreen(margin)` guard migrated across all objects,
  `buildSpawnAt()` helper and `getRenderer()` helper inherited by all object classes.
- **Phase 2 — Base class extraction**: `AbstractMonitorObjectInstance`, `AbstractSpikeObjectInstance`
  (S2/S3K), `AbstractProjectileInstance` (S1 missiles), `AbstractPointsObjectInstance`,
  `AbstractFallingFragment` (collapsing platforms), `AbstractSoundTestCatalog`,
  `AbstractAudioProfile`, `AbstractObjectRegistry`, `AbstractZoneRegistry`,
  `AbstractZoneScrollHandler` (~20 scroll handlers migrated), `AbstractLevelInitProfile`
  (with `buildCoreSteps()`), `AniPlcScriptState` and `AniPlcParser` extracted from pattern
  animators.
- **Phase 3 — Behavior helpers**: S1 badnik migration to shared destruction config, shared ring/object
  placement record parsers, shared title card sprite rendering utility, shared S1 Eggman boss
  methods extracted into base class.
- **Phase 4 — Gravity and debris**: `GravityDebrisChild`, `PlatformBobHelper` (3 platform objects
  migrated), `ObjectFall()` method in `SubpixelMotion`.
- **Phase 5 — Cleanup**: shared constants, `loadArtTiles` path, shader path standardization,
  `ParallaxShaderProgram` extends `ShaderProgram` (deleted lifecycle duplication).
- **Debug render migration**: all S1, S2, and S3K objects migrated from legacy
  `appendDebug`/`appendLine` API to `DebugRenderContext`.

### MutableLevel (Level Editor Foundation)

- **MutableLevel**: a new level data abstraction supporting snapshot, mutation, and dirty-region
  tracking. Wraps the read-only level data and provides `setChunkDesc()`, `getGridSide()`,
  `saveState()`/`restoreState()` for undo/redo support.
- **Block**: added `saveState()`/`restoreState()` and `setChunkDesc()` for chunk-level mutation.
- **Dirty-region processing pipeline**: `processDirtyRegions()` wired into `LevelFrameStep` for
  efficient per-frame GPU updates of only the modified tile regions.
- MutableLevel preserves game-specific overrides and `ringSpriteSheet` across mutations.
- Round-trip and integration tests verify snapshot fidelity and mutation correctness.

### Sonic 3 & Knuckles Expansion

#### Knuckles: Playable Character

Knuckles is now a fully playable character with his complete S3K ability set, working natively
in S3K and via cross-game donation into S1 and S2.

- **Glide state machine**: glide activation on jump re-press with ROM-accurate turn physics
  (sine/cosine velocity from `doubleJumpProperty` angle, gravity balance). Direct mapping frame
  control using `RawAni_Knuckles_GlideTurn` table (frames 0xC0–0xC4).
- **Floor landing and sliding**: flat surfaces enter sliding state with deceleration (0x20/frame
  while jump held, matching ROM's `.continueSliding` routine). Slide follows terrain via
  `ObjectTerrainUtils` floor probing, snapping Y position to surface with correct angle. Ledge
  detection enters fall state when floor distance >= 14.
- **Wall grab and climbing**: wall grab with climbing animation cycling (frames 0xB7–0xBC every
  4 frames). Ledge climb using `Knuckles_ClimbLedge_Frames` table (4 keyframes with x/y deltas).
  Wall jump away with facing flip and normal jump animation.
- **Fall-from-glide landing**: ROM-accurate crouch pose with 15-frame `move_lock`.
- **ROM-accurate jump height**: `PhysicsProfile.SONIC_3K_KNUCKLES` with jump velocity 0x600
  (vs Sonic's 0x680), water jump 0x300 (vs 0x380), matching `Knux_Jump` in disassembly.
- **Shield ability gating**: fire/lightning/bubble shield abilities gated to Sonic only per ROM;
  Knuckles gets passive shield protection with glide as his secondary ability. Bubble shield
  bounce correctly suppressed on glide landing (gates on `SecondaryAbility.INSTA_SHIELD`, not
  `doubleJumpFlag` value).
- **Knuckles palette**: `Pal_Knuckles` (0x0A8AFC) loaded for both native S3K and cross-game
  donation. Cross-game palette fix ensures correct palette is loaded based on character config.
- **Life icon art**: `ArtNem_KnucklesLifeIcon` (0x190E4C) with character-specific rendering.
- **Sound effects**: GRAB and GLIDE_LAND SFX registered in S3K audio profile.
- **Character detection fix**: `Sonic3kLevelEventManager.getPlayerCharacter()` now resolves from
  config (was hardcoded to `SONIC_AND_TAILS`), enabling all character-gated object behaviour.
- **AIZ rock breaking**: knucklesOnly rocks (subtype bit 7) now trigger on airborne side contacts
  (jumping/gliding into them), not just grounded push.

#### S2 Cross-Game Knuckles Support

- **Lock-on palette**: "Knuckles in Sonic 2" palette loaded from S3K ROM at 0x060BEA. Only
  indices 2–5 differ from S2's `Pal_SonicTails` (Knuckles' reds vs Sonic's blues); title cards,
  badniks, and rings are unaffected. HUD text index 4 tweaked (green→orange) for readability.
- **Lives icon**: `ArtNem_KnucklesLifeIcon` decompressed from S3K donor ROM with pixel index
  remap from S3K palette layout to S2-compatible layout (`S3K_TO_S2_PALETTE_REMAP`).
- **HUD rendering**: lives name tiles use palette 0 (no flash cycling) when donor art is active,
  via `livesNameUsesIconPalette` flag in `HudRenderManager`.
- **Palette utility**: `Palette.mergeColorsFrom()` added for targeted color range copying.

#### Title Screen

- Full S3 title screen implemented with 6-phase state machine: SEGA logo with palette fade,
  12-frame Sonic morphing animation, white flash transition, and interactive menu with banner
  bounce physics, sprite animations, and menu selection.
- ROM data loading for 7 Kosinski art sets, 4 Nemesis sprite sets, 14 Enigma plane mappings.
- Hardcoded sprite mapping frames for banner, &Knuckles text, menu text, Sonic finger/wink,
  and Tails plane sprites (`Sonic3kTitleScreenMappings`).
- FadeManager transition fix: title screen exit now renders fade-to-black internally to avoid
  `GameLoop`/`UiRenderPipeline` FadeManager instance mismatch after `RuntimeManager` migration.

#### Level Select Screen

- ROM-accurate S3K level select matching the S3 disassembly menu infrastructure.
- `Sonic3kLevelSelectConstants`: data tables (level order, mark table, switch table, icon table,
  zone text, mapping offsets) from `s3.asm` with S&K zones replacing disabled/competition entries.
- `Sonic3kLevelSelectDataLoader`: loads Nemesis art (font, menu box, icons), Enigma mappings
  (screen layout, background, icons), uncompressed SONICMILES animation art, and palettes from ROM.
  Builds screen layout in-memory with S3K zone names via the LEVELSELECT codepage.
- `Sonic3kLevelSelectManager`: two-layer rendering (Plane B SONICMILES background + Plane A
  foreground), input navigation with disabled-entry skipping, sound test (0x00–0xFF), selection
  highlight, and zone icons.

#### Special Stage Character Support

- S3K Blue Ball special stages now dynamically resolve `PlayerCharacter` from config: Sonic &
  Tails (with AI sidekick), Sonic alone, Tails alone (with spinning tails appendage), and
  Knuckles (with correct palette patch to colors 8–15 per ROM's `Pal_SStage_Knux`).

#### AIZ Object Lifecycle Fixes

- **Vine dismount**: suppressed stale jump press on release to prevent immediate insta-shield
  (Sonic) or glide (Knuckles) activation. Added edge detection so holding jump from the vine-
  reaching jump doesn't cause immediate dismount.
- **Vine respawn**: removed self-destruct cull checks from both vine objects. The vine's coarse
  range was narrower than the Placement window, causing permanent respawn prevention via the
  `destroyedInWindow` latch.
- **Collapsing platform respawn**: removed `markRemembered()` call — ROM uses
  `Delete_Current_Sprite` (allows respawn), not `Remember_Sprite`. Platforms now correctly
  respawn when the player scrolls away and returns.
- **Breakable boulders**: preserved rolling state when smashing AIZ/LRZ rocks from the side,
  matching `SolidObjectFull` behaviour.
- **Special stage return**: restored saved centre coordinates correctly on Blue Ball exit,
  preventing the player from being embedded in the floor after returning from the big ring.
- **Results screen spawn path**: signpost flow now uses `spawnChild()` for the results object,
  preserving `ObjectServices` context and fixing the end-of-act bubble monitor crash.

#### AIZ Miniboss Completion
- AIZ miniboss defeat flow fully implemented: `S3kBossDefeatSignpostFlow` reusable sequence,
  staggered explosions with `S3kBossExplosionController`, per-explosion `sfx_Explode`.
- Knuckles napalm attack: `AizMinibossNapalmController` and `AizMinibossNapalmProjectile` with
  launch/drop/explode lifecycle, gated to Knuckles-only appearance.
- AIZ2 dynamic resize state machine for correct camera boundaries during miniboss spawn.

#### AIZ2 Boss and Transition Progress
- AIZ2 Flying Battery bombing sequence implemented with battleship overlay rendering, ship-relative
  bomb placement, explosion children, background tree spawners, and object-art loading for the
  bombership / small Robotnik craft frames.
- AIZ2 end boss implemented with Robotnik ship/head overlays, arm/propeller/flame/bomb/smoke child
  systems, camera scripting, boss state flow, and regression coverage for ship bomb timing.
- Post-boss capsule/cutscene flow now includes the AIZ2 egg capsule release path and handoff toward
  the Hydrocity transition. Follow-up fixes restore AIZ transition zone-feature state and prevent
  bombership art regressions after act-transition reinitialization.

#### Signpost and Results Screen
- `S3kSignpostInstance` with 5-state machine (idle/spin/slowdown/sparkle/done), stub and sparkle
  children, `PLC_EndSignStuff` art loading from ROM.
- `S3kHiddenMonitorInstance` with signpost interaction.
- Results screen: full state machine with tally, element system rendering, art loading from ROM,
  act display via `Apparent_act`, exit timing, control lock, victory pose, and Tails-specific
  victory animation.
- End-of-level flag and `endOfLevelActive` state wired through defeat flow.

#### Blue Ball Special Stages (WIP)
- Blue Ball special stage implemented (work in progress): gameplay, rendering, HUD, banner,
  ring animation, emerald collection, exit sequence.
- `SSEntryRing` art, animation, and special stage entry sequence from giant rings.
- Special stage results screen with art loading.
- Tails P2 support in special stages with tails sprite and delayed jump.
- Player returns to big ring location after special stage (not checkpoint).

#### S3K Bonus Stages: Slots, Gumball, and Glowing Sphere (WIP)
- `Sonic3kBonusStageCoordinator` now implements the S3K ring-threshold selection formula and
  zone/music routing for the three lock-on bonus stages: Slots, Glowing Sphere (Pachinko), and
  Gumball. StarPost bonus-star entry and saved-state return are wired into the S3K bonus-stage
  lifecycle.
- Bonus-stage title card support added to `Sonic3kTitleCardManager` and mappings, including the
  dedicated `BONUS STAGE` layout and bonus-specific fade timing.
- **Gumball stage bring-up:** `GumballMachineObjectInstance`, `GumballItemObjectInstance`, and
  `GumballTriangleBumperObjectInstance` implemented with ROM-driven machine state, dispenser /
  container / exit-trigger child chains, machine Y drift and slot tracking, subtype-specific item
  behavior, spring bounce/crumble parity, shield persistence, sidekick safety, and dedicated
  `SwScrlGumball` scrolling.
- **Glowing Sphere / Pachinko bring-up:** `PachinkoFlipperObjectInstance`,
  `PachinkoTriangleBumperObjectInstance`, `PachinkoBumperObjectInstance`,
  `PachinkoPlatformObjectInstance`, `PachinkoItemOrbObjectInstance`,
  `PachinkoMagnetOrbObjectInstance`, and `PachinkoEnergyTrapObjectInstance` implemented, with
  stage entry/return flow, top-exit handling, and dedicated `SwScrlPachinko` scrolling.
- **Zone animation support:** `Sonic3kPatternAnimator` and `Sonic3kPaletteCycler` now cover the
  bonus-stage-specific Gumball direct-DMA tile animation plus Pachinko animated tiles, DMA-driven
  background strips, and palette cycling.
- **Render-path parity for the gumball machine:** per-piece VDP priority from ROM mapping data,
  SAT-style sprite-mask post-processing, and replay-role metadata now preserve the intended glass /
  shell / interior pile layering for mixed-priority machine frames.
- Pachinko energy trap bootstrap now stays persistent like the ROM object, keeps its spawned
  column/beam children alive until scripted teardown, and force-releases players from competing
  magnet orbs before trap capture. Capture now zeros X/Y/G speed and cleanly holds the player on
  the beam.
- Bonus-stage title card exit no longer freezes the pachinko trap update loop. Persistent power-up
  re-registration now clears stale object slots before `ObjectManager` rebuilds, preventing slot
  aliasing during bonus-stage entry and post-title-card resume.
- **Slot Machine stage bring-up:** `S3kSlotRomData`, `S3kSlotStageController`,
  `S3kSlotStageState`, `S3kSlotCollisionSystem`, `S3kSlotPlayerRuntime`,
  `S3kSlotOptionCycleSystem`, `S3kSlotPrizeCalculator`, and reward/cage object runtime wiring now
  cover ROM table loading, rotating-stage movement, projected ground/air physics, grid collision,
  tile interactions, reel option cycling, match detection, cage capture/release, interpolated ring
  and spike rewards, exit wind-down/fade, and slot-specific sound effects.
- **Slot Machine rendering:** `S3kSlotLayoutRenderer`, `S3kSlotLayoutAnimator`,
  `S3kSlotMachineRenderer`, `S3kSlotMachinePanelAnimator`, `S3kSlotMachineDisplayState`,
  `SwScrlSlots`, and `shader_s3k_slots.glsl` implement layout animation, palette cycling,
  goal/peppermint/reel display updates, background row refresh, debug visibility, and FG glass /
  player priority ordering.
- **Slot Machine remediation:** state ownership was moved into the slot runtime with `ObjectManager`
  rendering for cage/reward objects, preserved special collision bits across probes, authoritative
  follow-up state, persistent wall animation state, capture-cycle restart coverage, and fixes for
  player swap focus, title-card bootstrap, runtime ownership, launch physics, spike reward ring
  drain, reel display, and exit fade.
- Added regression coverage for coordinator lifecycle, bonus title card mappings/flow, gumball
  machine drift and priority diagnostics, sprite-mask helper consumption and replay ordering,
  pachinko palette/pattern animation, slot ROM data, slot collision/player/runtime/rendering/reward
  systems, registry wiring, and live trap/orb/title-card integration.

#### Per-Character Physics
- Per-character physics profiles for Sonic, Tails, and Knuckles (speed, acceleration, jump height).
- Super spindash speed table and slope sprite selection fixes.
- Ducking while moving at slow speeds (S3K-specific behavior).

#### Palette and Visual Systems
- Palette cycling implemented for all remaining zones: HCZ, CNZ, ICZ, LBZ, LRZ, BPZ, CGZ, EMZ
  (plus existing AIZ).
- Per-frame palette mutation system for AIZ1 hollow tree reveal (`palette[2][15]`).
- AIZ fire curtain overlay with cached BG descriptors and fire palette fixes, looping linger and
  graceful scroll-off.
- Heat haze deformation applied to AIZ2 background layer.
- HUD text loaded from ROM; digit rendering uses mapping frames (not tile indices).

#### New Objects and Badniks
- `BreakableWall` (0x0D), `CorkFloor`, `FloatingPlatform`, `CaterkillerJr` (with body segment
  despawn), `AutoSpin`, `Falling Log`, `InvisibleBlock`, `StarPost`, `TwistedRamp`,
  `AIZCollapsingLogBridge` (0x2C), `AIZSpikedLog` (0x2E), `AIZFlippingBridge` (0x2B), and the
  zone-specific `Button` object (0x33).
- HCZ expansion: water surface, water rush sequence (`HCZBreakableBarObjectInstance`,
  `HCZWaterRushObjectInstance`, `HCZWaterWallObjectInstance`, `HCZWaterTunnelHandler`),
  `HCZConveyorBeltObjectInstance`, `HCZCGZFanObjectInstance`, `HCZHandLauncherObjectInstance`,
  `HCZLargeFanObjectInstance`, `HCZBlockObjectInstance`, `HCZConveyorSpikeObjectInstance`,
  `HCZTwistingLoopObjectInstance`, `HczMinibossInstance`, and `DoorObjectInstance` for HCZ/CNZ/DEZ.
- Additional S3K objects and badniks: `CollapsingBridgeObjectInstance`, `BubblerObjectInstance`,
  `Sonic3kInvisibleHurtBlockHObjectInstance`, `MegaChopperBadnikInstance`,
  `PoindexterBadnikInstance`, `BlastoidBadnikInstance`, `BuggernautBadnikInstance` /
  `BuggernautBabyInstance`, and `TurboSpikerBadnikInstance`.
- `Sonic3kLevelTriggerManager` added for AIZ trigger state such as boss-driven burn activation.
- All zone badnik entries populated in `Sonic3kPlcArtRegistry`.
- Initial badnik implementations wired into object system.
- **Badnik destruction effects**: destroying S3K badniks now spawns animals and floating points
  popups, matching S1/S2 behavior. Zone-specific animal pairs loaded from ROM per
  `PLCLoad_Animals_Index` (all 13 zones mapped). Enemy score art parsed from `Map_EnemyScore`
  (shared `ArtNem_EnemyPtsStarPost` blob). `Sonic3kPointsObjectInstance` provides S3K-specific
  score-to-frame mapping.

#### Spindash Dust
- **S3K spindash dust**: implemented native `SpindashDustArtProvider` for Sonic 3&K. Art loaded
  from ROM (`ArtUnc_DashDust` at 0x18A604, `Map_DashDust` at 0x18DF4, `DPLC_DashSplashDrown` at
  0x18EE2). Uses virtual pattern base 0x34000 to avoid collision with ring tiles in the atlas.
- **Multi-character dust isolation**: sidekick dust renderers now get isolated DPLC banks
  (shifted into `SIDEKICK_PATTERN_BASE + 0x2000` range), preventing atlas corruption when
  multiple characters spindash simultaneously.

#### Invincibility Stars
- **S3K invincibility stars**: `Sonic3kInvincibilityStarsObjectInstance` implements ROM-accurate
  Obj_Invincibility (sonic3k.asm:33751) with 1 parent group + 3 trailing child groups.
  Each group renders 2 sub-sprites at opposite positions on a 32-entry circular orbit table
  (`byte_189A0`). Children trail via `PlayableEntity.getCentreX/Y(framesAgo)` at 3/6/9 frames
  behind the player; parent orbits fast (9 entries/frame), children orbit slow (1 entry/frame).
  Rotation reverses when facing left. Art loaded from ROM (`ArtUnc_Invincibility` at 0x18A204,
  `Map_Invincibility` at 0x018AEA). `DefaultPowerUpSpawner` branches on `Sonic3kGameModule`
  to create the S3K variant; S1/S2 `InvincibilityStarsObjectInstance` remains unchanged.

#### Audio
- Music tempo scaling and all-spheres SFX fix.
- Ring collection sound alternates left/right channels.
- Correct SFX: `sfx_Death` for normal hurt (not `sfx_SpikeHit`), jump SFX fix.
- S3K tumble frame base corrected to `0x31` (not S2's `0x5F`).

#### Miscellaneous S3K Fixes
- VDP priority bit correctly extracted in S3K sprite mapping loader.
- Collapsing platforms stay solid during fragment phase (S2/S3K).
- Shield re-registration after act transition; StarPost bonus stage routing fix.
- AIZ1 level bounds use normal `LevelSizes` entry.
- Prevented OOM in S3K DPLC frame loading by parsing only 1P entries (combined mapping table fix).
- SONIC art address corrected; camera bounds restored after transition.
- Lightning shield sparks rendered directly instead of via DPLC.
- Save/restore `Dynamic_resize_routine` across big ring special stage transitions (ROM: `Saved2_dynamic_resize_routine`). Without this, the resize state machine restarted from routine 0 on return, rapidly re-processing boundary thresholds and causing incorrect camera locks in AIZ Act 2.
- Title card showed wrong act after AIZ mid-act fire transition. Death or special stage return displayed "Act 2" instead of "Act 1" because the engine lacked the ROM's `Apparent_zone_and_act` variable. Added `apparentAct` tracking to `LevelManager`: seamless transitions (fire) only update `currentAct`, normal act changes update both, title card requests read `apparentAct`. Results screen exit sets `apparentAct = 1` matching ROM's `move.b #1,(Apparent_act).w`.
- AIZ2 water incorrectly enabled for Knuckles on level select load. `LevelManager.initWater()` hardcoded `SONIC_AND_TAILS` instead of resolving the actual player character from the level event manager. ROM `CheckLevelForWater` (sonic3k.asm:9754-9759) gates AIZ2 water on `Player_mode` and `Apparent_zone_and_act`, disabling it for Knuckles on direct load but enabling it during seamless AIZ1→AIZ2 transitions. Both cases now handled correctly via a `seamlessTransition` flag threaded through `WaterDataProvider`.

### Insta-Shield Implementation

Full S3K insta-shield ability implemented with ROM parity:
- ROM constants, art key, and art loading (including cross-game donation path).
- Activation via `tryShieldAbility()` with character gating (Sonic only, not Tails/Knuckles).
- Hitbox expansion in `TouchResponses` for the active insta-shield frames.
- Persistent `InstaShieldObjectInstance` lifecycle (survives level transitions).
- DPLC cache invalidation on seamless level transitions.
- Lazy art initialization to handle sprite-before-level-load ordering.
- Half-arc animation bug fix (prevented double-update per frame).

### Multi-Sidekick System

- Comma-separated sidekick config enables spawning multiple sidekicks (e.g. `"sonic,tails"`).
- `SidekickRespawnStrategy` interface extracted with `TailsRespawnStrategy` and per-character
  `requiresPhysics()` (Sonic walk-in vs Tails fly-in).
- Parallel sidekick respawn via effective leader reference.
- Virtual pattern ID range validation in `PatternAtlas` for safe multi-bank allocation.
- Sidekick DPLC banks placed in dedicated `0x30000+` range, capped at `0x800` limit with bank
  sharing on overflow.
- Sidekick rendered behind main player to match VDP sprite priority order.
- Leader reference preserved across `reset()` — sidekicks no longer become permanently idle.
- Directional input maintained during approach phase.
- Slot reclamation added to `PatternAtlas` for efficient VRAM management.

#### S3K Sidekick Knuckles Fixes

- **VRAM isolation**: every sidekick now unconditionally gets its own isolated pattern bank in the
  `SIDEKICK_PATTERN_BASE` (0x38000) range, eliminating sprite corruption when characters share
  the same ART_TILE base (Knuckles and Sonic both use 0x0680 in S3K). Removed the name-based
  `computeVramSlots` optimization that missed this collision.
- **Palette isolation**: per-sidekick `RenderContext` palette blocks loaded via
  `PlayerSpriteArtProvider.loadCharacterPalette()`. When a sidekick uses a different palette
  than the main character (e.g. Knuckles' `Pal_Knuckles` vs Sonic's `Pal_SonicTails`), a
  dedicated palette context is created so the sidekick renders with correct colors. Propagated
  to spindash dust and Tails tail appendage sub-renderers.
- **Knuckles glide-in respawn**: `KnucklesRespawnStrategy.requiresPhysics()` now returns
  `true` during the drop phase so the physics pipeline applies gravity. Previously Knuckles
  would hang in mid-air after the glide because `SpriteManager` skipped physics for all
  `APPROACHING` strategies. `GLIDE_DROP` animation set during the glide approach phase.
- **Palette texture resize safety**: `GraphicsManager.cachePaletteTexture()` now preserves
  existing palette data when the texture grows to accommodate new contexts, preventing level
  palette corruption on resize.

#### S3K Zone Bring-Up Skill System

A 7-skill agentic system for systematic, per-zone implementation of S3K visual and behavioural
features (events, parallax, animated tiles, palette cycling). Designed for agent-driven analysis
of the disassembly followed by parallel feature implementation across worktrees.

- **s3k-zone-analysis**: reads the S3K disassembly and produces a structured zone feature spec
  covering events, parallax, animated tiles, palette cycling, notable objects, and cross-cutting
  concerns. Includes Phase 4 shared state trace for cross-category dependency detection (VRAM
  ownership conflicts, palette mutation vs cycling overlaps, event flag gating).
- **s3k-zone-events**: implements `Sonic3kZoneEvents` subclasses porting `Dynamic_Resize` routines
  from the disassembly — camera locks, boss arenas, cutscenes, act transitions, palette mutations.
- **s3k-animated-tiles**: implements AniPLC script triggers in `Sonic3kPatternAnimator` with
  zone-specific gating conditions and dynamic art overrides.
- **s3k-palette-cycling**: implements or validates `AnPal` handlers in `Sonic3kPaletteCycler` using
  the counter/step/limit pattern. Supports both new implementation and validation of existing zones.
- **s3k-parallax** *(updated)*: now accepts a zone analysis spec as optional input to accelerate
  deform routine discovery.
- **s3k-zone-bring-up**: orchestrator that dispatches zone analysis, parallel feature agents in
  worktrees, merge reconciliation, build verification, and validation.
- **s3k-zone-validate**: visual validation via stable-retro reference screenshots compared against
  engine output using agent image recognition (feature presence, not pixel-perfect diffing).
- All skills published in dual format (`.claude/skills/` + `.agent/skills/`) for agent-agnostic use.
- YAML frontmatter standardised across all 20 `.claude/skills/` and 8 `.agent/skills/` files.
- HCZ zone analysis spec produced as smoke test (`docs/architecture/research/s3k-zones/hcz-analysis.md`).
- AIZ zone analysis cross-validated against engine implementation: events and palette cycling
  matched byte-for-byte; parallax matched 13/14 checks; animated tiles revealed 3 cross-category
  gating omissions that motivated the Phase 4 shared state trace addition.

### Tails AI Improvements

- Comprehensive Tails CPU AI rework:
  - WFZ/DEZ/SCZ now suppress the CPU sidekick in gameplay and rendering.
  - Tails switches to FLYING when Sonic dies instead of despawning.
  - Respawn uses ROM's 64-frame gate plus A/B/C/Start bypass, blocking on object-control,
    air, roll-jump, underwater, and prevent-respawn conditions.
  - Manual P2 override for gameplay and special stages.
  - PANIC mode reworked to use `move_lock` + frame-counter timing.
  - Flying/despawn reworked with on-screen checks, water clamp, exact landing criteria.
  - Boss/event updates wired for EHZ2, HTZ2, MCZ2, CNZ2, CPZ2, ARZ2, and MTZ3.
  - Special-stage Tails uses its own replay buffer + P2 takeover path.

### Rendering Pipeline Improvements

- **PatternAtlas slot reclamation**: freed VRAM slots can be reused by new pattern uploads.
- **Batched DPLC atlas updates**: `DynamicPatternBank` batches multiple pattern updates per frame
  instead of individual uploads.
- **Virtual pattern ID validation**: range checks prevent silent VRAM corruption from out-of-bounds
  pattern references.
- **FboHelper**: centralised FBO creation utility, migrated 4 renderer files.
- **writeQuad()** extracted from `BatchedPatternRenderer` for reuse.
- Fail-fast on shader compilation/linking errors with GL resource cleanup.

### Logging and Error Handling

- 22 `e.printStackTrace()` calls migrated to structured `java.util.logging`.
- 28 swallowed exceptions in S3K code replaced with `LOG.fine()`.
- Production `System.out.println` calls replaced with `LOGGER.fine()`.
- Remaining logging gaps fixed across 6 files.

### Performance

- Batched DPLC atlas updates in `DynamicPatternBank`.
- Cached `LevelManager` reference in `DefaultObjectServices` (eliminates per-call singleton lookup).
- Per-frame `ObjectSpawn` allocation eliminated in `AbstractBadnikInstance`.
- Pre-allocated debug overlay lists, collision/sensor/camera bounds command lists.
- Reduced per-frame allocations in collision, rendering, and audio hot paths.
- Batched glyph rendering for debug text.

### BizHawk Trace Replay Testing

A new automated accuracy verification system that records per-frame physics state from the real ROM
running in BizHawk emulator, then replays the same inputs through the engine and compares every
field frame-by-frame.

- **Lua trace recorder** (`tools/bizhawk/`): BizHawk Lua script that captures player position,
  speed, angle, ground mode, air/rolling flags, and controller input every frame during a BK2
  movie playback. Outputs `metadata.json`, `physics.csv`, and `aux_state.jsonl`.
- **stable-retro trace recorder** (`tools/retro/`): cross-platform Python equivalent of the
  BizHawk Lua recorder, using stable-retro (Genesis Plus GX) for headless emulation. Produces
  byte-identical output format (same CSV, JSONL, and metadata.json) consumed by the same Java
  test infrastructure. Supports stable-retro BK2 replay, BizHawk BK2 parsing, savestate boot,
  and credits demo recording. Enables trace generation on macOS and Linux without BizHawk.
  Verified byte-for-byte output match against BizHawk reference traces for first 2100+ frames
  of GHZ1 before GPGX version-specific lag frames diverge the runs.
- **stable-retro BK2 alignment** (`--bk2-offset`): replays BizHawk BK2 movies through
  stable-retro by shifting BK2 inputs to the emulator's gameplay start frame. Handles GPGX
  byte-swap, exact-0x0C game_mode detection, and `|system|P1|P2|` BK2 group parsing.
- **Lag frame handling in credits demo tests**: `AbstractCreditsDemoTraceReplayTest` now
  detects lag frames (identical physics state on consecutive frames with non-zero speed) and
  skips both engine physics and demo input advancement on those frames. Reduced MZ2 credits
  divergences from 28 errors/131 warnings to 10 errors/57 warnings. Remaining errors are
  genuine engine divergences (missed bounces, slope collision, object timing).
- **Trace replay test infrastructure** (`tests.trace` package): `TraceData` loader, `TraceFrame`
  parser, `TraceBinder` per-frame comparator with configurable tolerances, `DivergenceReport`
  with JSON output and context windows, lag frame detection for VBlank sync.
- **`AbstractTraceReplayTest`**: base class for trace replay tests with graceful skip when ROM,
  BK2, or trace data files are absent. Subclasses only specify game/zone/act/path.
- **First trace: S1 GHZ1** full-run recording (3,905 frames): passes with 0 errors, 6 warnings.
- **Second trace: S1 MZ1** full-run recording (7,936 frames): baseline added with
  `TestS1Mz1TraceReplay`, regenerated GHZ1 traces, and ROM-verified zone/act metadata.
- **Recorder/diagnostics upgrades**: trace format now captures subpixel position, player routine,
  camera state, ring count, raw status, `v_framecount`, `standOnObj`, slot dumps, routine-change
  events, and ObjPosLoad cursor state for direct ROM/engine comparison.
- **Engine-side context windows**: divergence reports now include ROM and engine routine/object
  diagnostics, riding-object context, and placement cursor counters to narrow parity failures.
- **Buzz Bomber proximity fix**: removed overcorrecting player position prediction from the
  proximity detection check. The engine's 1-frame late spawn (pre-camera X vs ROM's post-camera X)
  and the pre-physics player position naturally cancel, placing the Buzz Bomber at the correct
  stop position without prediction.
- **Post-camera object placement sync**: `LevelFrameStep` now runs a post-camera placement
  catch-up pass after the camera update, closing the spawn timing gap when the camera crosses a
  chunk boundary between object placement (step 2) and camera update (step 5).
- **Placement parity narrowing**: S1 `out_of_range` timing, dormant-spawn handling, and
  ObjPosLoad callback groundwork reduced the remaining MZ1 investigation to a terrain /
  solid-contact parity problem rather than cursor drift.

#### Physics Accuracy Fixes (discovered via trace replay)

- **16:16 fixed-point subpixel positions**: `AbstractSprite.move()` upgraded from 16:8 to 16:16
  fixed-point arithmetic, matching the ROM's 32-bit `move.l obX(a0),d2` / `asl.l #8,d0` /
  `add.l d0,d2` position update. `xSubpixel`/`ySubpixel` widened from `byte` to `short`.
  `setX()`/`setY()` no longer zero the subpixel fraction (ROM's `move.w` to x_pos doesn't
  touch x_sub). Collision adjustments use new `shiftX()`/`shiftY()` to preserve subpixel.
- **GroundMode enum order fix**: `LEFTWALL` and `RIGHTWALL` were swapped; corrected to match
  ROM's quadrant assignment (0x40 = LEFTWALL, 0xC0 = RIGHTWALL).
- **CalcRoomInFront probe quadrant**: wall probe now uses `anglePosQuadrant()` (asymmetric
  rounding matching ROM's AnglePos dispatch) instead of `(angle+0x20)&0xC0`. Fixes false wall
  detections at steep slope angles (e.g. rotated angle 0xA0).
- **CalcRoomInFront 32-bit prediction**: probe prediction uses full 16-bit subpixel, matching
  ROM's 32-bit position arithmetic.
- **Air collision landing split**: separated `doTerrainCollisionAirDirect()` for movement
  quadrants 0x40/0xC0 (land immediately when floor detected, no speed threshold) from
  quadrant 0x00 (speed-dependent threshold). Matches ROM's per-quadrant landing logic.
- **Double ground mode update**: second `updateGroundMode()` after `selectSensorWithAngle()`
  uses the new angle from terrain probes, matching ROM's end-of-frame ground mode calculation.
- **Arithmetic right shift for air drag**: `xSpeed / 32` changed to `xSpeed >> 5` to match
  68000's `asr.w #5,d1` which rounds toward negative infinity (Java `/` truncates toward zero).
- **Jump transition defers air physics**: on jump, air physics are deferred to the next frame
  (ROM's `addq.l #4,sp` pops the return address, skipping the rest of ground movement).
  `sprite.setOnObject(false)` now called before jump to match `bclr #sta_onObj`.
- **BCC carry flag parity**: spindash release speed clamp `gSpeed > 0` changed to `gSpeed >= 0`
  to match 68000's carry flag behavior (carry SET on unsigned overflow, BCC NOT taken for zero).
- **`groundWallCollisionEnabled` feature flag**: new `PhysicsFeatureSet` field. S1 does not
  call CalcRoomInFront during ground movement (no equivalent in `Sonic_MdNormal`); S2/S3K do.
- **Air-control superspeed preservation**: S3K now preserves airborne speeds already above
  `topSpeed` after ramps and springs, while S1/S2 retain the original hard cap. `TwistedRamp`
  tumble frames now remain visible while rolling.

#### Object System Fixes (discovered via trace replay)

- **Deterministic object iteration order**: active objects now sorted by spawn X position,
  matching ROM's slot-order correlation with spawn-window entry order.
- **Touch response timing**: `runTouchResponsesForPlayer()` extracted and called during the
  player physics tick (after `handleMovement()`, before solid contacts), matching ROM's
  ReactToItem timing within Sonic's ExecuteObjects slot.
- **S1 UNIFIED collision model in SpriteManager**: pre-movement solid pass skipped for S1
  (ROM processes all solid objects after Sonic's movement); post-movement pass with
  `postMovement=true` disables velocity classification adjustment.
- **SolidContacts post-movement parameter**: `updateSolidContacts()` gains `postMovement` and
  `deferSideToPostMovement` flags to support the S1/S2 collision timing difference.
- **ROM-accurate `out_of_range` semantics**: `AbstractObjectInstance.isInRange()` now matches the
  ROM's chunk-aligned X-only range check with 16-bit wraparound, and S1 now performs
  out-of-range deletion during object execution rather than before it.
- **Dormant spawn tracking**: objects deleted by S1 `out_of_range` stay dormant between ObjPosLoad
  cursors until the cursor naturally re-processes them, preventing premature or missing reloads
  during camera backtracking.
- **Standing/contact parity fixes**: `MvSonicOnPtfm` now uses `groundHalfHeight` (`d3`) for
  standing Y, HURT touch responses now remain continuous after invulnerability expires, and
  staircase / MTZ platform / nut / button / elevator contact state now uses ROM-style boolean
  latches instead of diverging frame counters.

### Object Lifecycle Safety

- Removed constructor-time `services()` usage from 38 object classes; all affected objects now
  lazily initialize renderer and service-dependent state after `ObjectServices` injection.
- `TestNoServicesInObjectConstructors` now hard-fails constructor-time service access, unsafe
  `addDynamicObject(new X(...))` patterns, and pre-registration method calls that transitively
  depend on injected services.
- Sonic 1 lava geysers now defer initialization until first update, preventing pre-registration
  crashes; the lavafall third piece also no longer cascade-spawns infinite children.

### Sonic 1 Fixes

- Drowning visuals: breathing air bubble animation frames and countdown number positioning corrected.
- LZ credits demo spike collision fix and frame tick ordering unification.
- Yadrin top-hit behaviour and underwater palette/animation fixes for LZ.
- Minor LZ fix for jumping while sliding.
- GHZ bridge collision fix with corresponding tests.
- Monitor collision fix (particularly when in a tree).
- Bubble breathing now uses the fallback animation chain correctly, so grabbing an air bubble shows
  the intended breathing animation instead of preserving the rolling/spinning pose.
- SLZ staircase activation now uses ROM-style per-frame contact latches and has dedicated headless
  regression coverage.
- Bubble makers, push blocks, and related S1 objects now use ROM-accurate range semantics; spike
  standing dimensions now match the ROM's `d2`/`d3` values, including sideways spike extension.

### Sonic 2 Fixes

- HTZ water configuration corrected (Hill Top Zone no longer reports water).
- Collapsing platforms in MCZ stay solid during fragment phase.
- Special stage results screen decoupled from object system.
- S2/S3K collapsing platforms remain solid during fragment phase.
- CPZ staircase, MTZ platforms, nuts, buttons, and elevators now use boolean contact latches
  instead of frame-counter comparisons, fixing activation regressions during title cards and
  multi-sprite updates.
- Invincibility stars (Obj35) rewritten to match s2disasm: star 0 orbits at player's current
  position with fast rotation ($12/frame), stars 1-3 trail behind via position history buffer
  (3/6/9 frames behind) with slow rotation ($02/frame). Each star renders 2 sub-sprites at
  180 degrees apart. Corrected orbit offset table (7 entries had wrong X values), animation
  tables (parent uses byte_1DB82; trailing stars use per-star primary/secondary tables), and
  direction-aware rotation (angle negated when facing left).
- RNG parity paths tightened through shared `GameRng` coverage and `Sonic2Rng` regression tests,
  including CNZ slot-machine consumers and S2 object/boss call sites.

### Cross-Game Feature Donation Enhancements

- S1 wired as donor for forward donation into S2/S3K (previously only S2/S3K could donate).
- `DonorCapabilities` interface replaces hardcoded game-specific branches.
- `CanonicalAnimation` enum provides a game-neutral animation vocabulary for cross-game translation.
- `AnimationTranslator` handles bidirectional profile translation between any pair of games.
- Spindash speed table sourced from donor `PhysicsFeatureSet`.
- Cross-game art keys promoted to `ObjectArtKeys` for game-agnostic constant references.
- Import leak cleanup: removed cross-game S2 animation ID imports from game-agnostic sidekick code.

### Test and Quality

- `SingletonResetExtension` and `@FullReset` for automated per-test singleton lifecycle.
- `GameRuntime` lifecycle wired into 35 test classes with optimized Surefire configuration.
- Multi-sidekick integration smoke tests.
- Insta-shield test suite: gating, hitbox expansion, and visual frame-by-frame capture.
- MutableLevel round-trip and integration tests.
- S3K results screen tally mechanics unit tests.
- S3K registry coverage tests for all zones.
- Per-character respawn strategy unit tests.
- Migration guard scanner for detecting `getInstance()` / `GameServices` violations in object code.
- Annotated guard tests for services() migration completeness.
- AudioManager.resetState() field-clearing verification.
- Added `TestS1Mz1TraceReplay`, `TestSonic1StaircaseActivation`, `TestAbstractObjectInstanceRange`,
  and expanded lava geyser / constructor-safety guard coverage.
- Fixed 7 test failures caused by leaked runtime state: updated S3K Knuckles physics assertion
  to expect `SONIC_3K_KNUCKLES` profile (jump=0x600), saved/restored `RuntimeManager` in render
  tests, guarded teardown camera calls with null checks, used `destroyForReinit()` for
  `TestGraphicsManagerHeadless`.

#### Test Suite Cleanup

Systematic audit and remediation of the test suite. Net result: +34 passing tests, 36→0 skipped,
no new failures.

- **Stale @Ignored stubs replaced with real tests**: `TestTodo14` (PlayerCharacter ordinals),
  `TestTodo13` (19 SBZ/FZ event routine tests), `TestTodo17` (boss flag gating), `TestTodo19`
  (rock debris table parity), `TestTodo34` (water slide chunk detection).
- **Broken live tests fixed**: `TestTodo3` (MonitorType reflection instead of test-local enum copy),
  `TestTodo37` (ROM-vs-engine constant parity via reflection).
- **Dead test files deleted**: 8 fully-@Ignored TestTodo stubs for unimplemented features (Yadrin
  spiky-top, Knuckles monitor, Super transform, rock width, rock push, ChopChop bubbles, control
  lockout, SBZ2 transition); 8 zero-assertion diagnostic dumps; `TestTodo29` (SCALE no-op).
- **Low-value tests pruned**: constant-equals-itself assertions (Knuckles cutscene timers, emerald
  scatter constants), ROM-only checks with no engine cross-reference (angle table size, CNZ
  romDataPresent), duplicate coverage (edge balance constants, water provider hasWater), test
  infrastructure self-tests (SharedLevel, InitStep fields).
- **Test uplifts**: `TestTodo1` cross-references ROM water heights against `Sonic2WaterDataProvider`;
  `TestTodo31` adds real end-game zone boundary assertions; 7 S3K palette cycling test files (AIZ2,
  CNZ, EMZ, HCZ, ICZ, LBZ, LRZ) strengthened from "color changed" to specific RGB value assertions
  using `Sonic3kPaletteCycler` with StubLevel; water data provider tests deduplicated between
  provider and handler files.
- **Integration gaps closed**: removed blocked @Ignored stubs from `TestGameLoop` (special stage
  mode) and `TestTodo4` (MCZ boss collision boxes); removed reference-file-dependent test from
  `TestSonic3kVoiceData`; removed diagnostic dump stubs from `TestS3kSonicSpriteDiag`.

### Documentation

- Comprehensive user guide for three audiences (players, developers, contributors).
- OpenSMPSDeck music tracker design spec and implementation plan.
- Rendering pipeline improvements spec and plan.
- Unified execution roadmap and Phase 0+1 implementation plan.
- GameRuntime architecture spec and implementation plan.
- Two-tier service architecture design spec and implementation plan.
- MutableLevel (Phase 3) spec and implementation plan.
- Insta-shield design spec and implementation plan.
- Multi-sidekick daisy chain design spec and implementation plan.
- Cross-game bidirectional animation donation design spec and implementation plan.
- Game-specific leak fixes spec and plan.
- Services migration cleanup design spec and implementation plan.
- Architectural fixes design spec, implementation plan, and review passes.
- Singleton lifecycle documentation.
- Phase 4 common refactoring design spec (5 phases, 25 patterns) and implementation plan (21 tasks).
- Virtual pattern IDs and multi-sidekick system documented in AGENTS.md.
- Known discrepancies documentation for multi-sidekick rendering.
- Added the `s1-trace-replay` skill and refreshed skill descriptions for the parity-driven
  object/boss/disassembly workflow docs.
