# OpenGGF v0.3 Changelog

## v0.3.20260206

366 commits, 541 files changed, ~99,000 lines added.

### Multi-Game Architecture

- Complete engine refactor to support multiple Sonic games through a provider-based abstraction layer
  - `GameModule` interface defines 15+ provider methods for all game-specific behaviour
  - `GameModuleRegistry` singleton holds the active game module
  - `RomDetectionService` auto-detects ROM type via registered `RomDetector` implementations
- New provider interfaces: `ZoneRegistry`, `ObjectRegistry`, `ObjectArtProvider`, `ZoneArtProvider`,
  `ScrollHandlerProvider`, `ZoneFeatureProvider`, `RomOffsetProvider`, `SpecialStageProvider`,
  `BonusStageProvider`, `DebugModeProvider`, `DebugOverlayProvider`, `TitleCardProvider`,
  `LevelEventProvider`, `ResultsScreen`, `MiniGameProvider`
- `GameServices` facade for centralised access to `gameState()`, `timers()`, `rom()`, `debugOverlay()`
- NoOp implementations for optional providers (`NoOpBonusStageProvider`, `NoOpSpecialStageProvider`, etc.)
- Sonic 2 fully migrated to provider architecture (`Sonic2GameModule` and all provider implementations)
- `Sonic2Constants.java` expanded by 663+ lines of ROM offset constants
- `Sonic2ObjectIds.java` expanded with 118 new object type ID constants

### Tails (Miles Prower) - Playable Character

- `Tails.java` playable sprite: shorter height (30px vs Sonic's 32px), adjusted sensor offsets (±15px vs ±19px), otherwise identical physics
- `TailsCpuController.java` ROM-accurate AI follower with 5-state machine: `INIT`, `NORMAL` (input replay), `FLYING` (helicopter chase), `PANIC` (spindash escape), `SPAWNING` (respawn wait)
- Input replay system: Tails replays Sonic's recorded inputs from 17 frames ago via position/status history buffer
- AI overrides: direction correction when >16px off, forced jumps when Sonic is 32+ pixels above, spindash escape every 128 frames when stuck >120 frames
- Despawn after 300 frames off-screen, respawn 192 pixels above Sonic when safe
- `TailsTailsController.java` (Obj05): separate rotating tails animation with 10 states (Blank, Swish, Flick, Directional, Spindash, Skidding, Pushing, Hanging)
- Art loaded from ROM at `0x64320` (uncompressed, `0xB8C0` bytes) with separate mappings and reversed mappings
- Configurable via `SIDEKICK_CHARACTER_CODE` in config.json: `"tails"` (default), `""` to disable, `"sonic"` for Sonic clone
- Can be spawned as main player character or as CPU-controlled sidekick
- Flying mode bypasses normal physics, using direct position updates for aerial chase
- Per-player riding state: solid object contacts refactored to `IdentityHashMap` so Sonic and Tails can independently ride different platforms (13 files updated)
- Test: `TestTailsCpuController` covering state transitions, input replay, distance gating, despawn/respawn

### Sonic 1 Initial Support (23 new files, 3,729 lines)

- ROM auto-detection via `Sonic1RomDetector` (header-based)
- `Sonic1.java` game entry point with level loading and data decompression from S1 ROM
- `Sonic1Level.java` implementing S1-specific level data format (different structure from S2)
- `Sonic1ZoneRegistry` covering all 7 zones: Green Hill, Marble, Spring Yard, Labyrinth, Star Light, Scrap Brain, Final
- `Sonic1Constants.java` with verified ROM addresses for S1 REV01
- `Sonic1PlayerArt.java` loading player sprites with S1-specific mapping format
- Parallax scroll handlers for all 7 zones (`SwScrlGhz`, `SwScrlMz`, `SwScrlSyz`, `SwScrlLz`, `SwScrlSlz`, `SwScrlSbz`, `SwScrlFz`)
- `Sonic1PatternAnimator` for S1 tile animation scripts (waterfall, flowers, lava, conveyors)
- `Sonic1PaletteCycler` for S1 zone-specific palette cycling
- `Sonic1AudioProfile` and `Sonic1SmpsData` for S1 ROM audio playback via SMPS driver
- `Sonic1ObjectRegistry` and `Sonic1ObjectPlacement` stubs for S1 object format parsing
- `Sonic1LevelSelectManager` (394 lines): 21-item vertical menu with zone/act selection, wrap-around navigation, sound test
- `Sonic1LevelSelectDataLoader` and `Sonic1LevelSelectConstants` for ROM-based graphics and layout
- `LevelSelectProvider` interface extracted for game-agnostic level select support
- `Sonic1TitleCardManager` (468 lines), `Sonic1TitleCardMappings` (306 lines): S1-specific title card rendering
- `Sonic1ObjectArtProvider` for S1 HUD rendering (life icons, ring display)
- Tests: `TestGhzChunkDiagnostic` (GHZ chunk loading), `Sonic1PlayerArtTest` (player sprite loading)

### Physics Engine

#### Core Physics Rewrite
- Complete physics rewrite in `PlayableSpriteMovement` (1,814 lines, replacing 1,134-line predecessor)
- Movement modes now explicitly mirror ROM state machine: `Obj01_MdNormal` (ground walking), `Obj01_MdRoll` (ground rolling), `Obj01_MdAir`/`MdJump` (airborne)
- ROM-accurate slope resistance/repulsion formulas with correct angle offset (0x20) and mask (0xC0)
- Slope repel minimum speed threshold (0x280) matching ROM `Sonic_SlopeRepel`
- Rolling physics: dedicated roll deceleration (0x20), controlled roll constants, minimum start roll speed gating
- Spindash fully reimplemented using ROM speed table (`s2.asm:37294`) indexed by `spindash_counter >> 8`
- Spindash counter charging/decay logic matching ROM `Sonic_UpdateSpindash`
- Fixed subpixel accuracy: subpixels were not being used correctly in velocity/position calculations
- Near-apex air drag implemented (when -1024 <= ySpeed < 0), matching ROM `Sonic_MdJump` behaviour
- Upward velocity cap added at -0xFC0
- Roll height adjustment fixed (5px to 10px) for all roll-mode transitions, preventing visual "fall" on transition
- ROM-identical angle/quadrant selection table in `TrigLookupTable` (256 entries from `misc/angles.bin`)
- `calcAngle()` method exactly matching ROM `CalcAngle` routine (s2.asm:4033-4076)
- Jump angle calculation and slope angle assist/repel gating adjustments

#### Player Mechanics
- Pinball mode flag (`pinballMode`) preventing rolling from being cleared on landing; gives boost instead of stopping at speed 0. Used by CNZ tubes, blue balls, launcher springs. Preserved through launcher spring bounces
- Ledge balance animation with 4 balance states matching ROM (BALANCE through BALANCE4) based on proximity and facing direction (s2.asm:36246-36373)
- Look up/down delay counter (`lookDelayCounter`) matching ROM `Sonic_Look_delay_counter` timing
- Spring control lock fixed to only apply when grounded (was incorrectly locking controls in air)
- Run animation starts the moment left/right are pressed
- Three distinct control lock types matching ROM: `objectControlled` (blocks all input), `moveLocked` (blocks directional but allows jump), `springing` (blocks grounded directional)
- Signpost walk-off fix: control lock no longer cancels forced input, allowing Sonic to properly walk off-screen after act end
- Position history buffer (64 entries) for camera lag and spindash compensation

#### Collision System
- New unified `CollisionSystem` (214 lines) orchestrating a 3-phase pipeline:
  1. Terrain probes (ground/ceiling/wall sensors via `TerrainCollisionManager`)
  2. Solid object resolution (platforms, moving solids via `ObjectManager.SolidContacts`)
  3. Post-resolution adjustments (ground mode, headroom checks)
- Supports trace recording via `CollisionTrace` interface for debugging and testing
- `GroundSensor` rewrite (437 lines): separated vertical scanning (floor/ceiling) from horizontal scanning (walls), ROM-accurate negative metric handling, full-tile edge detection with previous-tile lookback, horizontal wall scanning with regress/extend states
- Collision order fix: solid objects now processed before terrain, preventing objects from being overridden
- Sensor adjustment timing changed to earlier in the tick
- Collision path reset on level switch to prevent falling through levels on wrong layer
- Ceiling collision improvements: better ceiling sensors on walls/ceilings, angle-based landing detection, ceiling mode (0x80) correctly adjusts only Y velocity
- Wall pushing fix
- Solid object landing now resets ground mode and angle (matching solid tile landing)
- New `ObjectTerrainUtils` (296 lines) for game object terrain collision (floor, ceiling, left wall, right wall), mirroring ROM `ObjCheckFloorDist`

### Camera

- Complete vertical scroll rewrite matching ROM behaviour:
  - Y position bias system (`Camera_Y_pos_bias`) with default value of 96
  - Look up bias (200) with gradual 2px/frame increment
  - Look down bias (8) with gradual 2px/frame decrement
  - Bias easing back to default at 2px/frame
  - Grounded scroll speed cap: 2px (looking), 6px (normal), 16px (fast, inertia >= 0x800)
- Airborne camera uses +/-32px window around current bias matching ROM `ScrollVerti` airborne path
- Horizontal scroll delay (`horizScrollDelayFrames`) replaces old `framesBehind` system. Matches ROM where `ScrollHoriz` checks `Horiz_scroll_delay_val` but `ScrollVerti` does not
- Rolling height compensation: camera subtracts 5px from Y delta when rolling (1px for Tails)
- Spindash camera fixed to use horizontal scroll delay rather than full camera freeze
- Screen shake system: `shakeOffsetX`/`shakeOffsetY` with `getXWithShake()`/`getYWithShake()` for rendering (used by HTZ earthquake)
- Boundary clamping to `minX`/`minY` (was only clamping to 0)
- Full freeze (death/cutscenes) now separate from horizontal scroll delay

### Water System

- Complete `WaterSystem` (462 lines): water level loaded from ROM at correct height, water oscillation in CPZ2 via `OscillationManager`, water surface sprites rendering in front of solid tiles
- `WaterSurfaceManager` (282 lines) for surface sprite management
- Water surface sprites appear for CPZ2, ARZ1, and ARZ2
- Water entry/exit detection based on player centre Y vs water surface
- Underwater physics: speed halving on water entry (xSpeed/2, ySpeed/4), halved acceleration/deceleration/max speed, corrected jump height, corrected hurt gravity and launch amount
- `DrowningController` (289 lines): 30-second air timer with frame-accurate countdown, warning chimes at air levels 25/20/15, drowning countdown music at air level 12, countdown number bubbles (5/4/3/2/1/0), breathing bubble spawning, music restart on water exit or air replenishment, air bubble collection with 35-frame control lock
- Water collision aligned with oscillating visual position in CPZ2
- HUD text no longer turns red on water levels
- Special Stage results no longer overwrite water surface sprite

### Boss Fights

#### Boss Framework (game-agnostic)
- `AbstractBossInstance` (530 lines) base class: hit points, invincibility frames, state machine, defeat sequences, camera locking, explosion cascades
- `AbstractBossChild` (109 lines) base class for multi-component boss sub-objects
- `BossChildComponent` interface (45 lines) and `BossStateContext` (72 lines) for shared state
- `BossExplosionObjectInstance` for shared boss explosion effects
- `CameraBounds` for boss arena camera locking

#### Implemented Bosses
- **EHZ Boss** (Drill Car, Obj56) - `Sonic2EHZBossInstance` with 6 child components: ground vehicle, propeller, spike drill, vehicle top, wheels, animations helper
- **CPZ Boss** (Water Dropper, Obj5D) - `Sonic2CPZBossInstance` with 14 child components: container (extend, floor), dripper, falling parts, flame, gunk hazard, pipes (pump, segment), pump, Robotnik sprite, smoke puffs, animations helper
- **HTZ Boss** (Lava Flamethrower, Obj52) - `Sonic2HTZBossInstance` with flamethrower, lava ball projectiles, smoke particles. Lava bubble spawned on ground impact
- **CNZ Boss** (Electricity, Obj51) - `Sonic2CNZBossInstance` with electric ball projectiles, animations helper
- **ARZ Boss** (Hammer/Arrow, Obj89) - `Sonic2ARZBossInstance` with arrow projectiles, eye tracking component, destructible pillars
- **Egg Prison** (Obj3E) - End-of-act capsule with button, animal escape sequence, and destruction

### Badniks (15+ New Enemies)

#### CPZ
- **Spiny** (Obj A5) - Wall-crawling spike enemy
- **Spiny on Wall** (Obj A6) - Ceiling variant
- **Grabber** (Obj A7) - Descends to capture player

#### ARZ
- **ChopChop** (Obj 91) - Piranha fish that lunges at player
- **Whisp** (Obj 8C) - Floating dragonfly enemy
- **Grounder** (Obj 8D/8E) - Mole that hides behind breakable wall, throws rock projectiles
  - GrounderWallInstance (Obj 8F) - Breakable wall
  - GrounderRockProjectile (Obj 90) - Rock projectiles

#### HTZ
- **Rexon** (Obj 94/96) - Multi-segment lava-dwelling serpent
  - RexonHeadObjectInstance (Obj 97) - Shootable head segment
- **Sol** (Obj 95) - Fireball-shooting enemy with SolFireballObjectInstance
- **Spiker** (Obj 92) - Drill badnik with SpikerDrillObjectInstance (Obj 93) projectile

#### CNZ
- **Crawl** (Obj C8) - Bouncing boxing glove enemy

#### MCZ
- **Crawlton** (Obj 9E) - Snake that lunges with trailing body segments
- **Flasher** (Obj A3) - Firefly that flashes invulnerability

#### Badnik Framework
- Enhanced `AbstractBadnikInstance` base class
- Improved `AnimalObjectInstance` escape behaviour
- Enhanced `BadnikProjectileInstance` framework
- `PointsObjectInstance` moved to objects package (score popup display)

### Game Objects (50+ New)

#### Platforms and Moving Objects
- **SwingingPlatformObjectInstance** (Obj15) - Chain-suspended pendulum platform (OOZ, ARZ, MCZ)
- **SwingingPformObjectInstance** (Obj82) - ARZ swinging vine platform
- **CPZPlatformObjectInstance** (Obj19) - CPZ rotating/moving platforms
- **ARZPlatformObjectInstance** (Obj18) - ARZ-specific platform
- **MTZPlatformObjectInstance** (Obj6B) - Multi-purpose platform with 12 movement subtypes
- **SidewaysPformObjectInstance** (Obj7A) - CPZ/MCZ horizontal moving platform
- **MCZRotPformsObjectInstance** (Obj6A) - MCZ wooden crate / MTZ rotating platforms
- **ARZRotPformsObjectInstance** (Obj83) - 3 platforms orbiting centre
- **CollapsingPlatformObjectInstance** (Obj1F) - OOZ/MCZ/ARZ collapsing platform
- **SeesawObjectInstance** (Obj14) + **SeesawBallObjectInstance** - HTZ catapult seesaw with ball physics
- **HTZLiftObjectInstance** (Obj16) - HTZ zipline/diagonal lift
- **ElevatorObjectInstance** (ObjD5) - CNZ vertical moving elevator
- **CNZBigBlockObjectInstance** (ObjD4) - CNZ 64x64 oscillating platform
- **CNZRectBlocksObjectInstance** (ObjD2) - CNZ flashing "caterpillar" blocks
- **InvisibleBlockObjectInstance** (Obj74) - Invisible solid block

#### Hazards and Traps
- **RisingLavaObjectInstance** (Obj30) - HTZ invisible solid lava platform during earthquakes
- **LavaMarkerObjectInstance** (Obj31) - HTZ/MTZ invisible lava hazard collision zone
- **LavaBubbleObjectInstance** (Obj20) - Lava bubble visual effects
- **SmashableGroundObjectInstance** (Obj2F) - HTZ breakable rock platform
- **FallingPillarObjectInstance** (Obj23) - ARZ pillar that drops lower section
- **RisingPillarObjectInstance** (Obj2B) - ARZ pillar that rises and launches player
- **ArrowShooterObjectInstance** (Obj22) + **ArrowProjectileInstance** - ARZ arrow shooter trap
- **StomperObjectInstance** (Obj2A) - MCZ ceiling crusher
- **MCZBrickObjectInstance** (Obj75) - MCZ pushable/breakable brick
- **SlidingSpikesObjectInstance** (Obj76) - MCZ spike block sliding from wall
- **TippingFloorObjectInstance** (Obj0B) - CPZ tipping floor
- **BreakableBlockObjectInstance** (Obj32) - CPZ metal blocks / HTZ breakable rocks
- **BlueBallsObjectInstance** (Obj1D) - CPZ chemical droplet hazard
- **BombPrizeObjectInstance** (ObjD3) - CNZ slot machine bomb/spike penalty

#### Interactive Objects
- **SpringboardObjectInstance** (Obj40) - Pressure/lever spring (CPZ, ARZ, MCZ)
- **SpringHelper** - Shared spring velocity calculations
- **SpeedBoosterObjectInstance** (Obj1B) - CPZ/CNZ speed booster pad
- **ForcedSpinObjectInstance** (Obj84) - CNZ/HTZ forced spin (pinball mode trigger)
- **LauncherSpringObjectInstance** (Obj85) - CNZ pressure launcher spring
- **PipeExitSpringObjectInstance** (Obj7B) - CPZ warp tube exit spring
- **BarrierObjectInstance** (Obj2D) - One-way rising barrier (CPZ/HTZ/MTZ/ARZ/DEZ)
- **EggPrisonObjectInstance** (Obj3E) - End-of-act capsule with button, animal escape, destruction
- **SkidDustObjectInstance** - Skid dust particles
- **SplashObjectInstance** - Water splash effect

#### CPZ-Specific Objects
- **CPZSpinTubeObjectInstance** (Obj1E, 895 lines) - Full tube transport system
- **CPZStaircaseObjectInstance** (Obj78) - 4-piece triggered elevator platform
- **CPZPylonObjectInstance** (Obj7C) - Decorative background pylon

#### CNZ-Specific Objects
- **BumperObjectInstance** (Obj44) - Standard round bumper
- **HexBumperObjectInstance** (ObjD7) - Hexagonal bumper
- **BonusBlockObjectInstance** (ObjD8) - Drop target / bonus block (colour-changing, scoring)
- **FlipperObjectInstance** (Obj86) - Pinball flipper
- **CNZConveyorBeltObjectInstance** (Obj72) - Invisible velocity conveyor zone
- **PointPokeyObjectInstance** (ObjD6) - Cage that captures player and awards points
- **RingPrizeObjectInstance** (ObjDC) - Slot machine ring reward
- **CNZBumperManager** (574 lines) - Full bumper system with ROM-accurate bounce physics, 6 bumper types
- **CNZSlotMachineManager** (608 lines) + **CNZSlotMachineRenderer** (549 lines) - Complete slot machine system

#### ARZ-Specific Objects
- **BubbleGeneratorObjectInstance** (Obj24) - Spawns breathable bubbles underwater
- **BubbleObjectInstance** / **BreathingBubbleInstance** - Rising and breathable air bubbles
- **LeavesGeneratorObjectInstance** (Obj2C) + **LeafParticleObjectInstance** - Falling leaves on contact

#### MCZ-Specific Objects
- **VineSwitchObjectInstance** (Obj7F) - Pull switch triggering ButtonVine
- **MovingVineObjectInstance** (Obj80) - Vine pulley transport
- **MCZDrawbridgeObjectInstance** (Obj81) - Rotatable drawbridge triggered by VineSwitch
- **ButtonVineTriggerManager** - MCZ-specific vine routing

### Zone Improvements

#### Emerald Hill Zone (EHZ)
- Full boss fight (Act 2) with multi-component child objects
- Art used as base for HTZ overlay system

#### Chemical Plant Zone (CPZ)
- Full water implementation with oscillation in CPZ2
- Cycling palette implementation (water shimmer, chemical bubbles)
- Spin tubes, staircase platforms, blue balls, speed boosters, breakable blocks
- Spiny, Grabber, and Crawl badniks
- Full boss fight with multi-component gunk dropper
- Multiple collision and positioning fixes (tubes, staircases, platforms, blue balls)

#### Aquatic Ruin Zone (ARZ)
- Water surface sprites for ARZ1 and ARZ2
- Arrow shooters, swinging platforms, rotating platforms, rising/falling pillars
- ChopChop, Whisp, and Grounder badniks
- Leaves generator and leaf particle objects
- Full boss fight with hammer, arrows, and destructible pillars
- Collision fix: boss no longer attackable from the floor

#### Casino Night Zone (CNZ)
- Full bumper system with 6 bumper types and ROM-accurate bounce physics
- Complete slot machine system with shader rendering
- Flipper system (multiple rounds of fixes)
- New parallax scroll handler
- Conveyor belts, elevators, big blocks, rect blocks, point pokey, bonus blocks
- Crawl badnik
- Full boss fight with electric balls
- Physics fix: slopes no longer get stuck

#### Hill Top Zone (HTZ)
- Level resource overlay system: loads EHZ base data with HTZ-specific pattern overlays at byte offset 0x3F80 and block overlays at 0x0980. Shared chunks and collision indices
- Full earthquake system: dual architecture with `Camera_BG_Y_offset` (224-320) for BG vertical scroll and `SwScrl_RippleData` (0-3px) for screen jitter
- Earthquake trigger coordinates: Act 1 camera X >= 0x1800, Y >= 0x400; Act 2 camera X >= 0x14C0
- Rising lava with invisible solid platform, lava markers, lava bubble effects
- HTZ dynamic art loaded from ROM instead of disassembly files
- New parallax scroll handler with correct BG rendering
- Seesaws with ball physics, smashable ground, lifts, launcher springs, barriers
- Rexon, Sol, and Spiker badniks
- Full boss fight with lava flamethrower

#### Mystic Cave Zone (MCZ)
- Crawlton and Flasher badniks
- Bricks, drawbridges, vine switches, moving vines, rotating platforms, stompers, sliding spikes

#### Sky Chase Zone (SCZ)
- `SwScrlScz` (207 lines): ROM-accurate scroll handler with Tornado-driven camera movement
- BG X advances at 0.5px/frame via 16.16 fixed-point accumulator, BG Y always 0
- Act 1 phase system: fly right → descend → resume right, triggered by camera position thresholds

#### Oil Ocean Zone (OOZ)
- Full multi-layer parallax background with oil surface effects (`SwScrlOoz`, 395 lines)

#### Metropolis Zone (MTZ)
- MTZ platform with 12 movement subtypes

#### General Level System
- `LevelEventManager` massively expanded (+1,026 lines): dynamic camera boundaries, boss arenas, zone-specific event triggers, HTZ earthquake coordination
- `OscillationManager` extracted into proper abstraction (drives water oscillation, platform cycles)
- `ParallaxManager` expanded (+249 lines) with enhanced scroll offset calculations
- `BackgroundRenderer` reworked (+324 lines)
- Palette cycling system rewritten: `Sonic2PaletteCycler` (578 lines) with per-zone scripts from ROM
- Tile animation system rewritten: `Sonic2PatternAnimator` (343 lines) with ROM-based scripts
- `Sonic2LevelAnimationManager` consolidating both pattern animation and palette cycling

### Level Resource Overlay System (New)

- `LevelResourcePlan` (221 lines) - Declarative resource loading with overlay composition
- `LoadOp` (49 lines) - Individual load operations with ROM address, compression type, destination offset
- `ResourceLoader` (175 lines) - Performs loading with copy-on-write overlay pattern
- `CompressionType` enum - Nemesis, Kosinski, Enigma, Saxman, Uncompressed
- `Sonic2LevelResourcePlans` (108 lines) - Factory for zone-specific resource plans
- Overlays never mutate cached data (copy-on-write pattern)
- Tests: `LevelResourceOverlayTest` (333 lines)

### Graphics and Rendering

#### Backend Migration
- Complete migration from JOGL to LWJGL for both graphics and audio backends (multiple commits)
- GLFW window management replaces previous windowing system
- Initially tried OpenGL 4.1 core profile, settled on OpenGL 2.1 compatibility profile for broader hardware support
- Fixed shader loading when packaged as JAR
- DPI-aware window scaling via `GLFW_SCALE_TO_MONITOR`

#### GPU Rendering Pipeline
- **Pattern atlas system**: all 8x8 tile patterns uploaded to a single GPU texture (`PatternAtlas`, 326 lines) with multi-atlas fallback and buffer pooling
- **GPU tilemap renderer** (`TilemapGpuRenderer`, 198 lines): dedicated `TilemapShaderProgram` (172 lines) and `TilemapTexture` (78 lines) for GPU-side tile lookup. Covers background, water, and foreground layers. Configurable fallback to CPU rendering
- **Instanced sprite batching** (`InstancedPatternRenderer`, 696 lines): per-instance attributes with `glDrawArraysInstanced`. Enabled by default when supported, automatic fallback to existing batcher. Includes instanced water shader sync
- **Shared fullscreen quad VBO** (`QuadRenderer`, 50 lines): replaces all immediate-mode fullscreen quads across tilemap, parallax, fade, and special stage renderers
- **Priority rendering** (`TilePriorityFBO`, 177 lines): framebuffer object for tile priority bit rendering, enabling correct sprite-behind-tile ordering via GPU
- **Pattern lookup buffer** (`PatternLookupBuffer`, 70 lines): GPU-side pattern index lookup for tilemap shader

#### New Shaders
- `shader_tilemap.glsl` (132 lines) - GPU tilemap lookup and rendering
- `shader_water.glsl` (105 lines) - Water surface effects with palette-based tinting
- `shader_instanced.vert` (27 lines) - Instanced sprite vertex shader
- `shader_instanced_priority.glsl` (93 lines) - Instanced rendering with priority bit
- `shader_sprite_priority.glsl` (91 lines) - Sprite-behind-tile priority rendering
- `shader_cnz_slots.glsl` (131 lines) - CNZ slot machine display
- `shader_debug_text.frag`/`shader_debug_text.vert` (69 lines) - Debug text glyph rendering
- `shader_debug_color.vert`, `shader_basic.vert`, `shader_fullscreen.vert` - Utility shaders

#### UI Render Pipeline
- `UiRenderPipeline` (104 lines): ordered rendering phases (Scene, HUD Overlay, Fade pass)
- `RenderPhase` enum, `RenderCommand` interface, `RenderOrderRecorder` for testing

#### Debug Overlay Rendering
- Batched glyph rendering using GPU-accelerated glyph atlas texture
- Multi-size fonts with smooth anti-aliased outlines
- Proper viewport-space projection and DPI scaling
- Crisp texture filtering, correct Y-flip orientation
- Glyph atlas size increased to 1024x1024
- Bold font for SMALL and MEDIUM debug text sizes, capped at 32pt maximum
- `DebugPrimitiveRenderer` (72 lines) with `DebugColorShaderProgram` for collision/sensor overlays
- Collision overlay accessible via backtick key

#### Other Rendering Changes
- `FadeManager` rewritten for LWJGL compatibility
- `ScreenshotCapture` (231 lines) for visual regression testing
- Slot machine rendering moved from CPU to shader
- VBO sprite rendering

### Audio Engine

#### YM2612 FM Synthesis
- Complete rewrite based on Genesis-Plus-GX (GPGX) reference: SIN_HBITS/ENV_HBITS changed from 12 to 10, LFO changed from 1024-step sine to 128-step inverted triangle, TL table restructured, output clipping changed to asymmetric GPGX-style (+8191/-8192)
- `ENV_QUIET` threshold: when envelope exceeds threshold, operator output forced to 0, causing feedback buffer to naturally decay (matching real hardware)
- SSG-EG (SSG envelope generator) support
- Phase generator detune overflow matching Nemesis-verified real hardware behaviour (DT_BITS = 17)
- Internal sample rate output: YM2612 can output at CLOCK/144 (~53267 Hz) with proper band-limited resampling via `BlipDeltaBuffer` (330 lines) and `BlipResampler` (200 lines)
- Fixed operator routing order and TL position in voice format
- Fixed voice format parsing that was causing corruption and muted instruments
- Fixed low output volume
- Multiple rounds of accuracy improvements (5+ commits)

#### PSG (SN76489)
- New Experimental (Off by Default) PSG implementation with anti-aliasing
- `PsgChipGPGX` (378 lines) added as alternate implementation based on Genesis-Plus-GX (reference for future noise channel work)
- Clock divider fixed to 32.0, Noise Mode 3 corrected
- Clock speed and period calculation fixed
- Extensive spindash release SFX fixes (PSG modulation, note-off, tone bleed, noise channel)
- Default to original PSG after noise channel issues, keeping GPGX as reference

#### SMPS Driver
- Fixed frequency wrapping for high notes
- Fixed E7 command handling
- Fixed octave shifts during modulation/detune
- Fixed fill/gate time logic causing audio desync
- Fixed missing noise channel
- Refactored driver locking to prevent concurrent modification between SFX and music
- `SmpsSequencerConfig` abstraction: configurable per-game (Sonic 1 vs Sonic 2 differences in instrument loading, pitch offsets, noise channel handling)
- Sonic 1 SMPS driver accuracy fixes:
  - PSG envelope 1-based indexing: S1 `subq.w #1,d0` before table lookup; VoiceIndex=0 means no envelope
  - FM voice operator order conversion: S1 (Op4,Op3,Op2,Op1) swapped to engine's S2 format (Op4,Op2,Op3,Op1) on load
  - PC-relative pointer addressing: S1 F6/F7/F8 commands use `dc.w loc-*-1` offsets vs S2 absolute Z80 addresses
  - TIMEOUT tempo mode: S1 uses countdown-based tempo (extend durations on wrap) vs S2 accumulator overflow
  - PSG base note: S1 PSGSetFreq subtracts 0x81 (table starts at C), so `getPsgBaseNoteOffset()` returns 0
  - SFX tempo bypass: S1 SFX have normalTempo=0; skip duration extension in TIMEOUT mode when sfxMode=true
  - First-frame tempo processing matching S1 DOTEMPO behaviour

#### Sound Effects and Music
- Fixed extra life music restore (multiple playbacks no longer break original music)
- Fixed SFX-over-music priority and channel management
- Spindash release SFX: extensive multi-commit effort (14+ commits) fixing looping, noise timing, tone bleed, modulation enable, artifact prevention, overlapping playback. Invalid FM transpose value patched
- Level select: music fade on transitions, ring sound removed, double-fade fixed
- Gloop sound toggle moved from Z80 driver to `BlueBallsObjectInstance`

#### Audio Backend
- Migrated from JOAL to LWJGL OpenAL (`LWJGLAudioBackend`, 427 lines). Includes `WavDecoder` for WAV file support
- Fixed audio quality degradation in LWJGL backend
- Audio latency reduced to 16ms (one frame)
- Window minimize/restore handling: pauses audio so music doesn't play in background

#### Audio Performance
- Eliminated per-sample allocations in VirtualSynthesizer, SmpsSequencer, SmpsDriver scratch buffers
- Audio engine performance optimisations verified via regression tests

### Manager Consolidation Refactor

#### ObjectManager
- `ObjectManager.java` grew from ~200 to ~1,917 lines, absorbing 4 removed managers as inner classes:
  - `ObjectManager.Placement` (was `ObjectPlacementManager`) - Spawn windowing, remembered objects
  - `ObjectManager.SolidContacts` (was `SolidObjectManager`, -455 lines) - Riding, landing, ceiling, side collision
  - `ObjectManager.TouchResponses` (was `TouchResponseManager`, -195 lines) - Enemy bounce, hurt, category detection
  - `ObjectManager.PlaneSwitchers` (was `PlaneSwitcherManager`, -143 lines) - Plane switching logic

#### RingManager
- Consolidated from 3 separate managers as inner classes:
  - `RingManager.RingPlacement` (was `RingPlacementManager`, -93 lines) - Collection state, sparkle animation
  - `RingManager.RingRenderer` (was `RingRenderManager`, -114 lines) - Ring rendering with cached patterns
  - `RingManager.LostRingPool` (was `LostRingManager`, -304 lines) - Lost ring physics, object pooling

#### PlayableSprite Controller
- `PlayableSpriteController` (38 lines) coordinator owned by `AbstractPlayableSprite`:
  - `PlayableSpriteMovement` (1,814 lines, replaces `PlayableSpriteMovementManager`)
  - `PlayableSpriteAnimation` (renamed from `PlayableSpriteAnimationManager`)
  - `SpindashDustController` (renamed from `SpindashDustManager`)
  - `DrowningController` (289 lines, new)
- Removed `SpriteCollisionManager` (-131 lines)

#### CollisionSystem
- `CollisionSystem` (214 lines) unifying terrain probes and solid object collision
- `CollisionTrace` (40 lines), `RecordingCollisionTrace` (121 lines), `NoOpCollisionTrace` (25 lines), `CollisionEvent` (44 lines)

#### Animation System
- `Sonic2LevelAnimationManager` consolidating `AnimatedPatternManager` and `AnimatedPaletteManager`
- `Sonic2PatternAnimator` renamed from `Sonic2AnimatedPatternManager`
- `Sonic2PaletteCycler` (578 lines) replacing `Sonic2PaletteCycleManager` (-143 lines)

### Object System Framework

- `ObjectArtKeys` - Game-agnostic art key constants
- `MultiPieceSolidProvider` interface for objects with multiple solid collision pieces
- `SlopedSolidProvider` interface for sloped solid objects
- Enhanced `AbstractObjectInstance` (+55 lines), `ObjectInstance` interface (+34 lines)
- `ObjectRenderManager` significantly enhanced rendering pipeline
- `ObjectArtData` enhanced art loading (+169 lines)
- `HudRenderManager` enhanced HUD rendering (+155 lines)
- `SolidObjectProvider` extended interface
- Game-specific art loading pattern: `Sonic2ObjectArt`, `Sonic2ObjectArtProvider`, `Sonic2ObjectArtKeys`
- LayerSwitcher (Obj03) handled by PlaneSwitchers subsystem, not as rendered object

### Level Select

- `LevelSelectManager` (762 lines) - Full level select screen with keyboard navigation, zone/act selection, music playback
- `LevelSelectDataLoader` (485 lines) - Loads graphics, fonts, and preview images from ROM
- `LevelSelectConstants` (240 lines) - ROM addresses and layout data
- Palette loaded from ROM
- Menu background: `MenuBackgroundAnimator`, `MenuBackgroundDataLoader`, `MenuBackgroundRenderer` (292 lines total)
- Sound test integration via shared `Sonic2SoundTestCatalog`
- Configurable via `LEVEL_SELECT_ENABLED` config key
- Palette reset on returning to level select from gameplay
- Music fade on level select transitions

### Testing Infrastructure

#### HeadlessTestRunner
- `HeadlessTestRunner` (137 lines): physics/collision integration tests without OpenGL context
- `stepFrame(up, down, left, right, jump)` to simulate one frame with input
- `stepIdleFrames(n)` for stepping multiple idle frames
- Calls `Camera.updatePosition()`, `LevelEventManager.update()`, `ParallaxManager.update()` each frame

#### Physics and Collision Tests
- `TestHeadlessWallCollision` (133 lines) - Ground collision and walking physics
- `TestPlayableSpriteMovement` (1,483 lines) - Comprehensive movement physics tests
- `CollisionSystemTest` (460 lines) - Unified collision pipeline
- `WaterPhysicsTest` (250 lines) - Underwater physics
- `WaterSystemTest` (178 lines) - Water level system

#### Zone-Specific Tests
- `TestCNZCeilingStateExit` (403 lines), `TestCNZFlipperLaunch` (190 lines), `TestCNZForcedSpinTunnel` (193 lines), `SwScrlCnzTest` (454 lines)
- `TestHTZBossArtPalette`, `TestHTZBossChildObjects` (181 lines), `TestHTZBossEventRoutine9`, `TestHTZBossTouchResponse` (133 lines)
- `TestHTZInvisibleWallBug` (731 lines), `TestHTZRisingLavaDisassemblyParity` (115 lines), `TestSwScrlHtzEarthquakeMode` (86 lines), `TestHtzSpringLoop` (197 lines)
- `SwScrlOozTest` (487 lines), `TestOozAnimation` (248 lines), `TestPaletteCycling` (101 lines)

#### Visual Regression Tests
- `VisualRegressionTest` (393 lines) - Screenshot comparison testing
- `VisualReferenceGenerator` (265 lines) - Generate reference screenshots
- `ScreenshotCapture` (231 lines) - Headless screenshot capture
- Reference images for EHZ, CPZ, CNZ, HTZ, MCZ

#### Audio Regression Tests
- `AudioRegressionTest` (370 lines) - Audio output comparison
- `AudioReferenceGenerator` (302 lines) - Generate reference audio
- `AudioBenchmark` (109 lines) - Audio performance benchmarks
- Reference WAVs for EHZ/CPZ/HTZ music, jump/ring/spring/spindash SFX
- `TestSmpsSequencerInstrumentLoading` - SMPS instrument loading verification

#### Other Tests
- `TestSignpostWalkOff` - Signpost walk-off regression test
- `TestTailsCpuController` - Tails AI state machine and input replay
- `TestObjectManagerLifecycle` (108 lines), `TestObjectPlacementManager` (40 lines), `TestSolidObjectManager` (148 lines)
- `BossStateContextTest` (220 lines), `FadeManagerTest` (535 lines), `RenderOrderTest` (181 lines)
- `PatternAtlasFallbackTest` (33 lines), `TestSpriteManagerRender` (211 lines)
- `LevelResourceOverlayTest` (333 lines)

#### Test Annotation Framework
- `@RequiresRom(SonicGame.SONIC_1)` annotation for tests needing a real ROM file
- `@RequiresGameModule(SonicGame.SONIC_1)` annotation for tests needing a game module without ROM
- `RequiresRomRule` JUnit rule with per-game ROM resolution and auto-detection
- `SonicGame` enum: `SONIC_1`, `SONIC_2`, `SONIC_3K`
- `RomCache` for shared ROM instances across test classes

### Performance Optimisations

- Pre-allocated command lists in `LevelManager` (collisionCommands, sensorCommands, cameraBoundsCommands) using `.clear()` instead of `new ArrayList<>()` each frame
- ObjectManager and SpriteManager pre-bucketing with dirty flag to avoid re-sorting every frame
- `Sonic2SpecialStageRenderer` PatternDesc reuse instead of per-frame allocation
- Lost ring object pooling to reduce allocations during ring scatter
- Reduced per-frame allocations in collision, rendering, and audio hot paths
- Debug overlay buffer reuse
- Per-sample allocation elimination in audio synthesis pipeline
- `PerformanceProfiler` with memory stats (GC and allocation timers), Ctrl+P copies all stats to clipboard
- General memory allocation reduction passes across the engine

### GraalVM Native Build Support

- GraalVM Native Image plugin configuration in `pom.xml`
- GitHub Actions release workflow (`.github/workflows/release.yml`, 128 lines) and CI workflow
- GraalVM configuration files: `native-image.properties`, `reflect-config.json`, `resource-config.json`, `jni-config.json`
- LWJGL migration (both graphics and audio) as prerequisite for GraalVM compatibility
- `run.cmd` for Windows execution

### Tooling

#### RomOffsetFinder Enhancements
- `verify <label>` command - Verifies calculated offset against actual ROM data
- `verify-batch [type]` command - Batch verify all items of a type (shows [OK], [!!] mismatch, [??] not found)
- `export <type> [prefix]` command - Export verified offsets as Java constants
- Offset validation for searched items
- Multi-game support via `--game` flag: `--game s1`, `--game s2` (default), `--game s3k`
- `GameProfile` with per-game anchor offsets, label prefixes, ROM filenames, and disasm paths
- Auto-detection from disassembly path (`s1disasm` → S1, `skdisasm` → S3K)
- Expanded anchor offsets in `RomOffsetCalculator` for improved accuracy
- `CompressionTestTool` auto-detect compression type at offset
- Kosinski Moduled (KosM) decompression support in `KosinskiReader.decompressModuled()` — container format wrapping multiple standard Kosinski modules with 16-byte aligned padding, used extensively by Sonic 3&K art assets
- Palette macro parsing support from disassembly

#### New Tools
- `WaterHeightFinder` (114 lines) - Finds water height data in ROM
- `AudioSfxExporter` (261 lines) - Exports SFX audio data
- `SoundTestApp` refactored to use shared `Sonic2SoundTestCatalog` with channel mute/solo support

#### Claude Skills
- `.claude/skills/implement-object/SKILL.md` (410 lines) - Guided object implementation workflow
- `.claude/skills/implement-boss/skill.md` (332 lines) - Boss implementation workflow
- `.claude/skills/s2disasm-guide/skill.md` (302 lines) - Disassembly reference guide

### Other Changes

- Per-game ROM configuration: `SONIC_1_ROM`, `SONIC_2_ROM`, `SONIC_3K_ROM` config keys with `DEFAULT_ROM` selector; `ROM_FILENAME` removed entirely
- Pause functionality (default key: Enter) and frame step when paused (default key: Q)
- `ConfigMigrationService` (95 lines) for evolving configuration format
- `GameStateManager` (104 lines) for score, lives, emeralds state management
- Window minimize/restore: pauses audio and rendering to prevent background playback and catch-up
- `docs/status/known-discrepancies.md` (161 lines) documenting intentional divergences from original ROM
- Old markdown docs archived to `docs/archive/`
- `AGENTS.md` and `CLAUDE.md` extensively updated with architecture documentation

---
