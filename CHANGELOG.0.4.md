# OpenGGF v0.4 Changelog

## v0.4.20260304 (Released 2026-03-04)

Analysis range: `v0.3.20260206..v0.4.20260304` on `develop` (`1790` commits, `1589` non-merge commits,
`2040` files changed, `218141` insertions, `195996` deletions).

> Note: the large deletion count reflects the package rename from `uk.co.jamesj999.sonic` to
> `com.openggf`, which deleted and recreated most source files. Net code growth is ~22,100 lines.

### Sonic 1 Expansion and Content Completion

- Added full Sonic 1 title screen pipeline and title-screen-to-level-select flow
  (`Sonic1TitleScreenManager`, loader, mappings, transition handling).
- Implemented Sonic 1 rings and lamppost/checkpoint behavior.
- Implemented Sonic 1 special stage gameplay and integration:
  - Game-agnostic special stage provider refactor.
  - `Sonic1SpecialStageManager`, renderer/background renderer/data loader, block types, and results screen.
  - Giant Ring route from normal gameplay into special stage flow.
- Introduced per-zone event coverage for Sonic 1 with zone-specific managers/events for GHZ, MZ, SYZ,
  LZ (including water events), SLZ, SBZ, and ending/FZ handling.
- Major object implementation wave for Sonic 1: `117` new object-related classes
  (`78` general objects, `23` badnik classes, `16` boss-related classes).
- Boss coverage expanded to GHZ, MZ, SYZ, LZ, SLZ, and FZ with child objects/projectiles and event integration.
- Added/finished LZ water behavior and bubble systems, including per-ROM drowning music selection.
- Added ending/outro flow updates and initial credits sequence implementation.
- Added SBZ2 post-level-end sequence.
- Fixed S1 physics regressions with test coverage (multiple passes).

### Sonic 2 Gameplay Additions

- Added Sonic 2 title screen architecture and title-screen audio regression coverage.
- Added major object coverage passes:
  - Metropolis Zone object set (`16` objects) and engine crush detection.
  - Sky Chase/Tornado object set and spawn path integration.
  - Wing Fortress object set and supporting hazards/platforms.
  - Oil Ocean object and oil-surface behavior improvements.
- Added MCZ boss implementation (`Sonic2MCZBossInstance` + falling debris support) with follow-up fixes.
- Added MTZ Boss (Obj54) with S2 boss event stubs.
- Added WFZ Boss (ObjC5) with laser platform attack cycle, plus ROM-accuracy pass (17 issues).
- Added DEZ Mecha Sonic boss (ObjAF) with full state machine, plus ROM-accuracy pass (17 issues).
- Added DEZ Death Egg Robot (ObjC7) — final S2 boss, plus ROM-accuracy pass (12 issues).
- Added Robotnik escape sequence between DEZ boss fights (ObjC6).
- Six passes of DEZ boss ROM-accuracy corrections: Silver Sonic facing direction, LED overlay,
  animation phase gating, Egg Robo collision/render priorities, Death Egg Robot child systems.
- Added `61` new Sonic 2 object-related files (`45` general objects, `14` badnik classes, `2` boss files),
  including additional SCZ/WFZ/MTZ/OOZ badnik/object coverage.
- Refactored and expanded Sonic 2 zone events (`Sonic2LevelEventManager` + per-zone event classes).
- Implemented Sonic 2 credits and ending system:
  - `EndingPhase` enum, `EndingProvider` interface, and `ENDING_CUTSCENE` GameMode.
  - `Sonic2CreditsTextRenderer`, `Sonic2CreditsMappings`, `Sonic2CreditsData` with timing constants.
  - `Sonic2EndingCutsceneManager` and `Sonic2EndingArt` with DEZ star field background rendering.
  - `Sonic2LogoFlashManager` with ROM-accurate palette strobe.
  - `Sonic2EndingProvider` wired to DEZ boss ending trigger.
  - Rewritten for ROM parity with ObjCA/ObjCC, DPLC player sprites, tornado visibility.
  - `Sonic1EndingProvider` refactored to use shared `EndingProvider` interface.
- Added demo playback functionality with enhancements and routing to objects.
- Systematic TODO resolution pass: water heights, monitor effects, distortion table, sliding spikes,
  dual collision, Yadrin spiky-top collision, water slide control lockout, LZ rumbling SFX,
  boss flag wiring to AIZ pattern animations, plus TODO/FIXME coverage tests with disassembly validation.
- Various object fixes: PointPokey positioning, MCZRotPlatforms child accumulation, signpost/screen
  locking, object loading improvements, ROM-accurate bumper/bonus block/rising pillar/diagonal spring physics.

### Super Sonic and Per-Game Physics

- Added cross-game physics abstraction:
  - `PhysicsProfile`, `PhysicsFeatureSet`, `PhysicsModifiers`, `PhysicsProvider`, and `CollisionModel`.
  - Validation tests for profile behavior, collision model differences, spindash gating, and speed capping.
- Implemented Sonic 2 Super Sonic flow:
  - Base state machine via `SuperState`/`SuperStateController`.
  - Integration into playable sprite/game loop/module plumbing.
  - ROM-based animation loading, ROM-exact palette cycling, and S2 constants wiring.
  - Invulnerability/enemy-destruction behavior and shield/power-up interaction guards.
  - Debug toggle support and Super Sonic stars object support.
- Added Sonic 3K Super Sonic controller stub/hook points for future parity work.
- Added cross-game Super Sonic delegation to S1 and S2 game modules via `CrossGameFeatureProvider`,
  including palette, audio, and renderer integration, invincibility, and S3K slope animation offset.

### Sonic 3K Bring-Up (AIZ-Focused)

- Extended Sonic 3K bootstrap/audio readiness (voice/sfx index fixes, ROM loading fixes, SoundTestApp support).
- Implemented Angel Island intro cinematic pipeline:
  - AIZ event wiring and intro state-machine objects (`AizPlaneIntroInstance`, Knuckles cutscene objects,
    emerald scatter, wave/plane/glow/booster children).
  - Intro art loading/caching and terrain swap integration.
- Added AIZ gameplay object work with parity-focused fixes:
  - Ride vines and giant ride vines.
  - Hollow tree traversal and reveal/tilemap support.
  - Multiple parity fixes (angle bytes, state retention, endianness, momentum, despawn guards) plus regressions.
- Added AIZ miniboss object set and child components.
- Added initial S3K badnik framework and first wired badnik implementations.
- Added S3K shield object implementations and fixed deferred PLC loading after AIZ intro.
- Added Sonic 3K title card manager/mappings and S3K pattern/palette animation work.
- Implemented S3K water system:
  - Game-agnostic `WaterDataProvider` and `DynamicWaterHandler` interfaces.
  - `ThresholdTableWaterHandler` for table-driven water zones.
  - `Sonic3kWaterDataProvider` with static heights, dynamic handlers, and underwater palette loading.
  - `Sonic1WaterDataProvider` migration to the new provider architecture.
  - Wired into LevelManager and S3K zone features, deprecated game-specific water loading methods.
  - Correct water threshold tables, `setMeanDirect`, zone scope, and starting heights matching ROM.
  - S3K water locked flag, shake timer, LBZ2 pipe plug handler.
  - AIZ2 Knuckles water exclusion, raise speed inheritance, `update()` overshoot fixes.
- Implemented seamless AIZ fire transition flow (`S3kSeamlessMutationExecutor`).
- AIZ miniboss cutscene and barrel shot child updates.
- Expanded AIZ scroll handler work (`SwScrlAiz`).

### PLC, Art Loading, and Tooling

- Major PLC and sprite-pattern refactor across S1/S2/S3K pipelines.
- Added/expanded PLC systems:
  - `Sonic2PlcLoader`, `Sonic2PlcArtRegistry`, and broader S3K PLC loading paths.
  - Shared sprite/mapping loader use (`S1SpriteDataLoader`, `S2SpriteDataLoader`, `S3kSpriteDataLoader`).
- Expanded ROM/disassembly tooling:
  - Object profile abstractions per game (`Sonic1ObjectProfile`, `Sonic2ObjectProfile`, `Sonic3kObjectProfile`).
  - Shared-ID handling in S3K object checklist generation.
  - PLC cross-referencing in `RomOffsetFinder`/`DisassemblySearchTool` and `ObjectDiscoveryTool`.

### Audio, Stability, and Engine Hardening

- Audio updates:
  - Music/SFX catalog refactor to enum-driven paths.
  - PSG GPGX hybrid parity work and tests.
  - S3K pitch wrapping and SFX index fixes.
  - YM2612/SMPS fixes (including SSG-EG active-count leak and loop counter bounds).
  - Thread-safety fixes in SMPS/audio backend paths and output mixing saturation safeguards.
- Engine hardening and safety:
  - ROM read synchronization and bounds checks.
  - Kosinski/resource loading safety limits.
  - Graphics cleanup fixes (resource leaks, reset-state gaps, allocation reductions).
  - Additional stability fixes across water/drowning handling, invulnerability timing, and debug movement modifiers.
- Performance passes across level/render/audio hot paths and internal debug profiling updates.
- Fixed SFX channel replacement: kill old SFX track on shared channel to prevent priority lock.
- Synth-core review fixes: resource safety, encapsulation, dead code cleanup.
- HTZ earthquake fixes: descending through floor, tile display, rising lava subtype 4 hurt behaviour.
- Consolidated duplicate sine/cosine tables to `TrigLookupTable`.
- Fixed cross-game features breaking layer switchers.
- Fixed special stage transition softlocks and S1 results fade type.

### Test and Quality Coverage

- Added `83` new test files across this range, including:
  - Sonic 1 special stage, object, badnik, boss, and routing regressions.
  - Sonic 3K AIZ intro/state timeline/hollow tree traversal parity regressions.
  - Title screen audio regression coverage.
  - PSG/YM2612 and per-game physics/profile parity checks.
- Expanded headless and subsystem-focused tests in support of object/event/audio refactors.
- Added 21 headless bug reproduction tests for 17 reported S1/S2 bugs.
- JUnit 5 migration: deleted 54 self-verifying tests, replaced with parameterized tests.
- Parallelized test execution with 8 forked JVMs.
- Test grouping by level: merged headless tests sharing the same level load into groups
  (EHZ1: 4→1, ARZ1: 3→1, CNZ1: 3→1, HTZ1: 2→1, AIZ1: 2→1, GHZ1: 6→1), eliminating 14 redundant
  level loads.
- Added TODO/FIXME coverage tests with disassembly validation.

### Cross-Game Feature Donation

Implemented cross-game feature donation system: a donor game (S2 or S3K) provides player sprites,
spindash dust, physics, palettes, and SFX while the base game (e.g. S1) handles levels, collision,
objects, and music. Enabled via `CROSS_GAME_FEATURES_ENABLED` and `CROSS_GAME_SOURCE` config keys.

- `CrossGameFeatureProvider` singleton: opens donor ROM as secondary ROM (no module detection
  side-effect), creates game-specific art loaders (`Sonic2PlayerArt`/`Sonic3kPlayerArt`,
  `Sonic2DustArt`), builds hybrid `PhysicsFeatureSet` (spindash from donor, everything else S1),
  loads donor character palette, initializes donor audio.
- `RenderContext` palette isolation: base game occupies palette lines 0-3, each donor gets its own
  block of 4 lines (4-7, 8-11, etc.) via static registry with `getOrCreateDonor()`.
  `uploadDonorPalettes()` pushes donor palettes to GPU. `getDonorContexts()` for iteration.
- `GameId` enum with `fromCode()` for type-safe donor identification.
- `RomManager.getSecondaryRom()` opens donor ROM without triggering game module detection.
- `LevelManager` art loading paths (`initPlayerSpriteArt`, `initSpindashDust`, `initTailsTails`)
  check `CrossGameFeatureProvider.isActive()` and delegate to donor art providers, attaching
  donor `RenderContext` to each `PlayerSpriteRenderer`.
- `Engine` initialization gates sidekick spawning on `GameModule.supportsSidekick()` or
  `CrossGameFeatureProvider.isActive()`, with cleanup on shutdown.
- GPU palette texture dynamically resized via `RenderContext.getTotalPaletteLines()`. All shaders
  (`shader_the_hedgehog`, `shader_tilemap`, `shader_water`, `shader_sprite_priority`,
  `shader_instanced_priority`, `shader_cnz_slots`) updated from hardcoded `/4.0` to
  `/TotalPaletteLines` uniform.
- Underwater palette derivation for donor sprites:
  - `RenderContext.deriveUnderwaterPalette()` synthesizes donor underwater colors using the base
    game's global average per-channel color shift ratio (not per-index, which would mismatch
    palette layouts across games).
  - `GraphicsManager.cacheUnderwaterPaletteTexture()` extended to populate donor palette rows
    automatically from the base game's normal-to-underwater shift.
- Donor SMPS driver config for correct SFX playback:
  - `SmpsSequencerConfig` threaded through `AudioManager.registerDonorLoader()` (4-arg overload),
    stored per donor game in `donorConfigs` map.
  - `AudioBackend.playSfxSmps()` 4-arg overload accepting explicit config; `LWJGLAudioBackend`
    uses donor config when provided, falling back to base game config.
  - `CrossGameFeatureProvider.initializeDonorAudio()` passes `donorProfile.getSequencerConfig()`.
- Donor audio overlay in `AudioManager`: `donorLoaders`, `donorDacData`, `donorSoundBindings` maps;
  `playSfx()` falls through to donor path when base game sound map has no entry.
- S3K Tails tail appendage support: `CrossGameFeatureProvider.hasSeparateTailsTailArt()` and
  `loadTailsTailArt()` delegate to donor's `Sonic3kPlayerArt` for separate Obj05 tail art.
  `LevelManager.initTailsTails()` checks donor game module when cross-game is active, selecting
  correct art loading path and `ANI_SELECTION_S3K` animation tables.
- SFX re-trigger fix in `SmpsDriver`: re-triggering the same SFX ID now replaces the old sequencer
  instead of competing for the same FM/PSG channels (prevents priority lock ping-pong with S1/S2
  jump SFX priority 0x80).
- Tests: `TestRenderContext` (9 tests covering palette isolation, line allocation, reset,
  underwater palette derivation), `TestDonorAudioRouting` (donor SFX routing and sequencer config),
  `TestGameId`, `TestHybridPhysicsFeatureSet`, `TestSidekickGating`.

### Master Title Screen

- Implemented `MasterTitleScreen` (404 lines): engine-wide title screen displayed on startup before
  entering game-specific title flow. PNG-based background, animated clouds, title emblem, and game
  selection text rendered via `TexturedQuadRenderer` and `PixelFont`.
- New rendering infrastructure: `PngTextureLoader` (85 lines), `TexturedQuadRenderer` (139 lines),
  `PixelFont` (144 lines), `shader_rgba_texture` vertex/fragment shaders.
- Configurable via `TITLE_SCREEN_ON_STARTUP` config key (default: enabled).

### Sonic 1 Fixes and Improvements

- Fixed Sonic spawning 5px underneath terrain on level reset by restoring standing radii in
  `AbstractPlayableSprite` respawn path (ROM: `Obj01_Init` unconditionally sets `y_radius=$13`).
- Object collision fixes: `ObjectManager` solid overlap test now always uses `airHalfHeight`
  matching ROM behaviour (d3 is overwritten by playerYRadius before read). Added
  `Sonic1ButtonObjectInstance` and `Sonic1MzBrickObjectInstance` collision support.
  `TestHeadlessSonic1ObjectCollision` (291 lines) regression test added.
- Fixed edge balance mode for S1 (single balance state, force face edge) while preserving S2's
  4-state extended balance. `PhysicsFeatureSet.extendedEdgeBalance` gates behaviour.
  `TestEdgeBalance` (91 lines) and `TestHeadlessSonic1EdgeBalance` (369 lines) added.
- Fixed MZ2 push block: longer blocks no longer get pushed "out of the way" when Sonic pushes them
  against walls. `SolidContact` improvements. `TestHeadlessMZ2PushBlockGap` (132 lines) added.
- SBZ fixes: Flamethrower positioning corrected for vflip/hflip variants. StomperDoor objects fixed.
  Junction now locks the player correctly. SBZ3 water oscillation implemented.
- LZ fixes: Wind tunnels now play correct player animation. Breakable poles play correct animation.
  Water splash effect implemented (`Sonic1SplashObjectInstance`).
- Demo playback now sent to objects (`AbstractPlayableSprite` demo input routing).
- Push stability fixes for solid objects. `TestHeadlessSonic1PushStability` (220 lines) added.
- Outro/credits improvements (`Sonic1CreditsManager`, `FadeManager` enhancements).
- `TestSbz1CreditsDemoBug` (162 lines) and `TestS1FlamethrowerObjectRendering` (58 lines) added.
- S1 "fast" mode SMPS sequencer support.
- S1 outro improvements: disable control on outro, change 'back to main menu' key.
- S1 ending sequence flowers fix.
- S1 object collision fixes.

### Sonic 2 Fixes

- Fixed badnik palette lines (Spiny now uses palette line 1 matching `make_art_tile`), signpost
  frame order corrected to match `obj0D_a.asm` ROM mapping order, CPZ stair block / MTZ platform
  art sheet rebuilt with hand-crafted mappings (ROM mappings reference level art tiles).
- Swinging platform art loading fix for non-S2 games.
- S2 ending cutscene parity: DEZ white fade (not black), star field background, pilot visibility,
  BG scroll compensation, DPLC player sprites, tornado visibility, falling timing.
- Prevented DEZ Robot despawn during defeat ending sequence.
- Fixed DEZ boss visual and collision issues (multiple passes).
- Fixed S2 credits visual accuracy: ROM-correct font, mappings, and player detection.
- Fixed S2 `Sonic2LevelEventManager` zone constants alignment with `ZoneRegistry`.

### Physics and Collision Fixes

- Fixed solid object edge jitter: `SolidContacts` snaps player to resolved edge on static solids
  to prevent subpixel accumulation. Push-driven objects opt in to ROM-style subpixel preservation
  via `SolidObjectProvider.preservesEdgeSubpixelMotion()`.
- S1 slope crest sensor guard: prefer floor-class probe over wall-class probe at crest transitions,
  preventing one-frame wall/air mode flips.
  `TestHeadlessStaticObjectPushStability` (208 lines) and
  `TestSonic1GhzSlopeTopDiagnostic` (519 lines) added.
- Sonic no longer jumps if the player holds jump while airborne via a non-jump (spring, slope
  launch, etc.).
- Various physics tweaks aimed at S1: physics modifiers cleanup, `FadeManager` fade-to-black
  transitions no longer flash back to "off" briefly before fade-in begins.
- Fixed results screen rendering issue for both S1 and S2.

### Package Rename

- Renamed root package from `uk.co.jamesj999.sonic` to `com.openggf` across the entire codebase.
  All source files, test files, and references updated.

### Profile-Driven Level Loading

- Introduced `LevelInitProfile` abstraction with `InitStep` and `StaticFixup` primitives for
  declarative, ROM-aligned level loading.
- Implemented per-game profiles (`Sonic1LevelInitProfile`, `Sonic2LevelInitProfile`,
  `Sonic3kLevelInitProfile`) with 13 finer-grained ROM-aligned steps each.
- `LevelLoadContext` provides shared state across load steps.
- `LevelManager.loadLevel()` routed through profile steps; old fallback path removed.
- Per-step timing and logging for load diagnostics.
- Profile-driven teardown and per-test reset replaces `TestEnvironment` and `GameContext.forTesting()`.
- `CHARACTER_APPEAR` phase uses `Map_Sonic`/`Map_Tails` Float2 animation.

### Testability Refactor

- `GameContext` holder with `production()` and `forTesting()` factories for singleton lifecycle.
- `SharedLevel` for reusable level loading across test classes.
- `HeadlessTestFixture` builder pattern for test setup, with 14 test classes converted.
- `TestEnvironment.resetAll()` delegates to `GameContext.forTesting()` for consistent teardown.

### Docs and Planning

- Added release-planning/implementation docs for unified level events, Super Sonic, and AIZ intro work.
- Added cross-game donation fixes design doc and implementation plan.
- Added `docs/CONFIGURATION.md` with full config key reference.
- Expanded disassembly/reference and skill documentation used for parity-driven object/boss implementation workflows.
- Added DEZ boss fixes design and implementation plans.
- Added Sonic 2 credits and ending sequence design and implementation plans.
- Added cross-game Super Sonic design and implementation plan.
- Added S3K water system design and implementation plan.
- Added testability improvement design (GameContext + HeadlessTestFixture) and implementation plan.
- Added headless test level grouping design and implementation plan.
- Added profile-driven level loading plans (Phase 3 and Phase 4).
- Added ending parallax background design and implementation plan.
- Added level editor design and implementation plan.
- Added ROM-driven init profiles design and implementation plan.
