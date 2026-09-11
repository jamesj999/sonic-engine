# ROM-Driven Initialization Profiles Design

## Motivation

The engine's singleton initialization order is currently managed by an 8-phase manual
sequence in `GameContext.forTesting()`. This is fragile — adding a new manager requires
understanding implicit transitive dependencies, and the ordering doesn't map to any
documented ROM behavior.

Each Mega Drive Sonic game has a specific, well-documented level initialization sequence
(the `Level:` routine in the disassembly). These sequences differ meaningfully between
games in both the steps performed and their ordering. The engine's initialization
infrastructure should be shaped by these ROM sequences, not the other way around.

## Core Abstraction

```java
public record InitStep(
    String name,            // e.g. "LoadZoneTiles"
    String romRoutine,      // e.g. "s2.asm:Level, line 4934"
    Runnable action
) {}

public interface LevelInitProfile {
    /** Ordered steps for entering a level (title card through control unlock) */
    List<InitStep> levelLoadSteps();

    /** Ordered steps for tearing down a level before the next load */
    List<InitStep> levelTeardownSteps();
}
```

Each `GameModule` returns its `LevelInitProfile`. The engine executes steps in declared
order — no topological sorting, no dependency resolution. **The disassembly IS the
dependency graph.** The profile's job is to faithfully transcribe it.

## Key Differences Between Games

| Aspect | S1 | S2 | S3K |
|--------|----|----|-----|
| **Title card art** | Direct Nemesis decompress (no queue) | PLC queue system | 3 queues: Nemesis + Kosinski + KosinskiM |
| **Level data loading** | Single-phase (`LevelDataLoad`) | Separate calls (tiles, blocks, collision) | Two-phase: async KosM art queue, then sync blocks/chunks |
| **Collision model** | Single index (UNIFIED) | Dual primary+secondary (DUAL_PATH) | Dual + non-interleaved/interleaved flag |
| **Water palette timing** | Loaded during init | Loaded during init | Loaded AFTER first object frame |
| **Player spawn timing** | Before object placement | Before object placement | AFTER game state init, with cutscene setup |
| **Level_started_flag** | Set after title card exit | Set after title card exit | Set BEFORE first object frame |
| **Zone setup dispatch** | None | CPZ pylon, OOZ oil, CNZ bumpers | Full per-zone LevelSetupArray with plane drawing |
| **Decompression queues** | 0 (direct only) | 1 (PLC) | 3 (Nemesis PLC + Kosinski + KosinskiM) |

---

## Sonic 2 Level Init Profile

Source: `s2.asm`, `Level:` routine (line 4753).

Each step maps a ROM routine to an engine operation. The "Status" column indicates
current engine implementation state.

### Phase A: Pre-Title-Card Setup

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 1 | `bset #GameModeFlag_TitleCard,(Game_Mode).w` | s2.asm:4754 | Set loading flag on game loop | Needs wrapper |
| 2 | `PlaySound MusID_FadeOut` | s2.asm:4757-4758 | `AudioManager::fadeOutMusic()` | Exists |
| 3 | `ClearPLC` | s2.asm:4760 | PLC queue clear (PLC queue not fully modeled) | Needs impl |
| 4 | `Pal_FadeToBlack` | s2.asm:4761 | `FadeManager::startFadeToBlack()` | Exists |
| 5 | `ClearScreen` | s2.asm:4765 | N/A (no `GraphicsManager.clearScreen()` exists; OpenGL clears implicitly each frame) | Needs impl |
| 6 | `LoadTitleCard` | s2.asm:4766 | Title card PLC queue load (queues art for deferred DMA) | Partial (title card renders but not via PLC queue) |
| 7 | Zero `Level_frame_counter` | s2.asm:4768-4769 | `LevelManager.frameCounter = 0` (done inside `loadCurrentLevel()`) | Exists |
| 8 | Load zone PLC from `LevelArtPointers[0]` | s2.asm:4770-4784 | Zone PLC queue load | Needs impl (PLC queue not fully modeled) |
| 9 | `LoadPLC PLCID_Std2` | s2.asm:4786-4787 | Standard PLC queue load | Needs impl |
| 10 | `Level_SetPlayerMode` | s2.asm:4788 | Copy Player_option to Player_mode (`PlayerCharacter` enum via `GameModule`) | Exists |
| 11 | Load character life icon PLC | s2.asm:4789-4800 | Life icon art load (Miles/Tails vs Sonic) | Needs impl |

### Phase B: RAM/VDP/Hardware Setup

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 12 | `clearRAM` of display lists, object RAM, misc variables | s2.asm:4802-4813 | `SpriteManager::clearAllSprites()`, `ObjectManager::reset(int)` | Exists |
| 13 | Water flag check (CPZ2, ARZ, HPZ) | s2.asm:4815-4824 | `WaterSystem::loadForLevel()` / `ZoneFeatureProvider::hasWater()` | Exists |
| 14 | VDP register configuration ($8B03 line scroll, nametable bases, sprite table, scroll size) | s2.asm:4826-4834 | Implicit (OpenGL, not VDP registers) | N/A |
| 15 | Debug mode activation check (C/A button held + cheat entered) | s2.asm:4835-4844 | `DebugModeProvider` interface (via `GameModule::getDebugModeProvider()`) | Exists |
| 16 | H-INT counter ($8ADF 1P, $8A6B 2P), 2P interlace mode | s2.asm:4845-4852 | N/A (no hardware H-INT) | Skip |
| 17 | Init VDP command buffer (clear DMA queue) | s2.asm:4853-4854 | N/A (no DMA queue in OpenGL) | Skip |
| 18 | Water init: enable H-INT, load `WaterHeight` table, set `Water_Level_1/2/3`, clear routine/flags | s2.asm:4855-4873 | `WaterSystem::loadForLevel(Rom, int, int, List)` | Exists |

### Phase C: Palette & Music

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 19 | `PalLoad_Now PalID_BGND` (main level palette) | s2.asm:4876-4877 | Done during level construction in `Sonic2Level::loadPalettes()` | Exists |
| 20 | `PalLoad_Water_Now` (zone-specific: HPZ/CPZ/ARZ) + lamppost restore | s2.asm:4878-4892 | `WaterSystem::loadForLevel()` loads underwater palettes | Exists |
| 21 | `PlayMusic MusicList[zone]` (or `MusicList2` for 2P) | s2.asm:4894-4907 | `AudioManager::playMusic(int)` (called from `LevelManager::loadLevel()`) | Exists |

### Phase D: Title Card Animation

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 22 | Spawn title card: write `ObjID_TitleCard` to `TitleCard+id` | s2.asm:4908 | `TitleCardProvider::initialize(int, int)` (via `TitleCardManager::initialize()`) | Exists |
| 23 | Title card loop: VBla wait (VintID_TitleCard), `RunObjects`, `BuildSprites`, `RunPLC_RAM` until zone name X reaches target AND PLC buffer empty | s2.asm:4910-4920 | `TitleCardProvider::update()` + `TitleCardProvider::shouldReleaseControl()` loop in `GameLoop` | Exists |
| 24 | `Hud_Base` (draw score/timer/ring count base graphics) after VBla wait | s2.asm:4921-4923 | `HudRenderManager::draw()` | Exists |
| 25 | `PalLoad_ForFade PalID_BGND` (load target palette for upcoming fade-in) | s2.asm:4925-4926 | `FadeManager::startFadeFromBlack()` handles palette fade-in internally | Exists |

### Phase E: Level Geometry & Background

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 26 | `LevelSizeLoad` (camera min/max X/Y from `LevelSize` table, start positions, scroll flags cleared) | s2.asm:4927 | Done during level construction in `Sonic2Level::loadBoundaries()` + `Camera` min/max set in `LevelManager::loadCurrentLevel()` | Exists |
| 27 | `DeformBgLayer` (initial BG deformation / parallax) | s2.asm:4928 | `ParallaxManager::initZone(int, int, int, int)` | Exists |
| 28 | Clear `Vscroll_Factor_FG`, set P2 to -screen_height | s2.asm:4929-4930 | Part of `Camera` init (implicit in `Camera::updatePosition(true)`) | Needs wrapper |
| 29 | Clear `Horiz_Scroll_Buf` | s2.asm:4932 | Part of `ParallaxManager::resetState()` | Exists |
| 30 | `LoadZoneTiles` (Kosinski decompress 8x8 art to Chunk_Table, DMA to VRAM; HTZ/WFZ overlays applied) | s2.asm:4934 | Done during level construction in `Sonic2Level::loadPatterns()` / `ResourceLoader` | Exists |
| 31 | `loadZoneBlockMaps` (16x16 chunk mappings + 128x128 block map decompression) | s2.asm:4935 | Done during level construction in `Sonic2Level::loadBlocks()` + `Sonic2Level::loadChunks()` | Exists |
| 32 | `LoadAnimatedBlocks` (init animated tile scripts) | s2.asm:4936 | `LevelManager::initAnimatedPatterns()` creates `Sonic2LevelAnimationManager` | Exists |
| 33 | `DrawInitialBG` (render initial background plane) | s2.asm:4937 | No explicit equivalent; BG is rendered each frame by `LevelManager` rendering pipeline | N/A (engine draws BG every frame) |

### Phase F: Collision

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 34 | `ConvertCollisionArray` (convert raw collision data to engine format) | s2.asm:4938 | Done during level construction in `Sonic2Level::loadSolidTiles()` (tile heights/widths/angles loaded from ROM) | Exists |
| 35 | `LoadCollisionIndexes` (TWO collision pointers per zone: primary + secondary — DUAL_PATH model) | s2.asm:4939 | Done during level construction in `Sonic2Level::loadChunks(Rom, int, int)` (primary + alt collision indices embedded per chunk) | Exists |

### Phase G: Player & Object Spawning

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 36 | `WaterEffects` (initial water processing) | s2.asm:4940 | `WaterSystem::update()` | Exists |
| 37 | `InitPlayers` (spawn Sonic and/or Tails based on `Player_mode`, spawn spindash dust objects) | s2.asm:4941 | Player sprites created via `SpriteManager` during `GameLoop` init; player state reset in `LevelManager::loadCurrentLevel()` | Exists |
| 38 | Clear/lock controls: zero logical+physical, `Control_Locked=1` both players, `Level_started_flag=0` | s2.asm:4942-4948 | `AbstractPlayableSprite::setControlLocked(true)` + `Camera::setLevelStarted(false)` | Exists |
| 39 | Water surface objects: if water zone, spawn two `ObjID_WaterSurface` at X offsets $60/$120 | s2.asm:4950-4955 | `Sonic2ZoneFeatureProvider::initZoneFeatures()` calls `initWaterSurfaceManager()` | Exists |
| 40 | Zone-specific objects: CPZ → `ObjID_CPZPylon`; OOZ → `ObjID_Oil` | s2.asm:4957-4963 | `Sonic2ZoneFeatureProvider::initZoneFeatures()` calls `initCPZPylon()` for CPZ; `OilSurfaceManager` for OOZ | Partial (CPZ pylon exists; OOZ oil exists) |

### Phase H: Game State Init

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 41 | Clear game state: if no lamppost, clear rings/time/lives. Always clear: shields, invincibility, speed shoes, `Time_Over`, restart, frame counter, teleport, rings collected, monitors broken, loser time | s2.asm:4965-4994 | `GameStateManager::resetSession()` + player state reset in `LevelManager::loadCurrentLevel()` | Exists |
| 42 | `OscillateNumInit` (initialize oscillation table) | s2.asm:4995 | `OscillationManager::reset()` | Exists |
| 43 | Set HUD update flags: score, rings, timer (both players) | s2.asm:4996-4999 | `HudRenderManager::draw()` redraws each frame (no dirty flags needed in OpenGL) | N/A (engine redraws HUD every frame) |

### Phase I: First Frame

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 44 | `ObjectsManager` (initial object placement via spawn windowing) | s2.asm:5000 | `ObjectManager::update()` handles spawn windowing (initial placement on first call after `reset(int)`) | Exists |
| 45 | `RingsManager` (initial ring placement) | s2.asm:5001 | `RingManager::update()` handles ring placement (initial placement on first call after `reset(int)`) | Exists |
| 46 | `SpecialCNZBumpers` (CNZ bumper initial placement) | s2.asm:5002 | `CNZBumperManager::update()` (initialized via `Sonic2ZoneFeatureProvider::initZoneFeatures()`) | Exists |
| 47 | `RunObjects` (execute one frame of all objects) | s2.asm:5003 | `SpriteManager::update(InputHandler)` (processes all sprites including objects) | Exists |
| 48 | `BuildSprites` (build VDP sprite table) | s2.asm:5004 | Implicit in engine rendering pipeline (GPU renders directly) | N/A (rendering model differs) |
| 49 | `AniArt_Load` (first frame of animated art) | s2.asm:5005 | `Sonic2LevelAnimationManager::update()` (via `LevelManager` per-frame call) | Exists |
| 50 | `SetLevelEndType` (signpost vs boss act determination) | s2.asm:5006 | No equivalent; act-end behavior determined at runtime by `Sonic2LevelEventManager` | Needs impl |

### Phase J: Final Setup & Transition

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 51 | Load demo data: set demo button index, press counter, timer | s2.asm:5007-5038 | Demo system init | Partial |
| 52 | `PalLoad_Water_ForFade` (if water zone: load water palette for fade transition) | s2.asm:5040-5050 | Water palette fade handled by `WaterSystem` + `FadeManager` | Exists |
| 53 | Start title card exit: set leave flag, advance left part routine | s2.asm:5052-5054 | `TitleCardProvider::shouldReleaseControl()` triggers exit phase | Exists |
| 54 | Title card exit loop: wait until title card background object fully unloaded | s2.asm:5056-5062 | `TitleCardProvider::isComplete()` checked by `GameLoop` to exit title card mode | Exists |
| 55 | Set title card name/zone/act fade timers ($2D-frame delays) | s2.asm:5064-5072 | Internal to `TitleCardManager` state machine (TEXT_WAIT / TEXT_EXIT phases) | Exists |
| 56 | Unlock controls: `Control_Locked=0`, `Level_started_flag=1` | s2.asm:5073-5075 | `AbstractPlayableSprite::setControlLocked(false)` + `Camera::setLevelStarted(true)` | Exists |
| 57 | `bclr #GameModeFlag_TitleCard,(Game_Mode).w` — enter main loop | s2.asm:5078 | `GameLoop` transitions from `GameMode.TITLE_CARD` to `GameMode.LEVEL` | Exists |

### S2 Coverage Summary

- **Fully implemented:** 38/57 steps
- **Needs wrapper/integration:** 3 steps (loading flag, scroll clear, SetLevelEndType)
- **Hardware-specific skip / N/A:** 7 steps (VDP registers, H-INT, DMA queue, ClearScreen, DrawInitialBG, BuildSprites, HUD dirty flags)
- **Needs impl (PLC system):** 4 steps (ClearPLC, zone PLC load, standard PLC load, life icon PLC)
- **Partial:** 3 steps (title card PLC, zone-specific objects, demo system)

---

## Sonic 1 Level Init Profile

Source: `sonic.asm`, `GM_Level:` routine (line 2955).

S1 is the simplest initialization — no PLC queue for title card, single collision model,
no 2P support, water only in Labyrinth Zone.

### Phase A: Pre-Title-Card Setup

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 1 | `bset #7,(v_gamemode).w` (set pre-level loading flag) | sonic.asm:2956 | Set loading flag on game loop | Needs wrapper |
| 2 | `QueueSound2 bgm_Fade` (fade out music; skipped for ending demos) | sonic.asm:2960 | `AudioManager::fadeOutMusic()` | Exists |
| 3 | `ClearPLC` (clear pattern load cue queue) | sonic.asm:2963 | PLC queue clear | Needs impl |
| 4 | `PaletteFadeOut` (fade palette to black) | sonic.asm:2964 | `FadeManager::startFadeToBlack(onComplete)` | Exists |
| 5 | Direct `NemDec` of `Nem_TitleCard` into VRAM with ints disabled (NOT via PLC — direct Nemesis decompression) | sonic.asm:2968-2971 | Title card art: direct Nemesis decompress in `Sonic1TitleCardManager::loadArt()` (called from `initialize()`) | Exists |
| 6 | Load zone PLC (1st): read `LevelHeaders` table byte 0 for PLC ID, call `AddPLC` | sonic.asm:2972-2980 | `Sonic1::loadLevel()` reads `LevelHeaders`, calls `readPatternLoadCues(plcId)` | Exists |
| 7 | Load standard PLC (2nd): `AddPLC plcid_Main2` (shared HUD/ring/monitor patterns) | sonic.asm:2983-2984 | `Sonic1::loadLevel()` calls `readPatternLoadCues(plc2Id)` | Exists |

### Phase B: RAM Clear & VDP Setup

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 8 | `clearRAM` of object space, misc variables, level variables, timing/screen variables | sonic.asm:2987-2990 | `SpriteManager::clearAllSprites()`, `ObjectManager::reset(cameraX)` | Exists |
| 9 | `ClearScreen` (clear VDP planes with ints disabled) | sonic.asm:2993 | N/A (implicit in OpenGL frame clear; no `GraphicsManager.clearScreen()` exists) | N/A |
| 10 | VDP register config: line scroll mode ($8B03), plane A/B nametable bases, sprite table base, 64-cell scroll, 8-color mode, background color (palette line 2, color 0) | sonic.asm:2994-3001 | Implicit (OpenGL, not VDP) | N/A |

### Phase C: Water Check (LZ Only)

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 11 | Water check: if zone == Labyrinth Zone, enable H-INT, load water heights from `WaterHeight` table, set `Water_routine`, `Water_fullscreen_flag`, `Water_flag=1` | sonic.asm:3004-3018 | `WaterSystem::loadForLevelS1(rom, zoneId, actId)` called via `Sonic1ZoneFeatureProvider::initZoneFeatures()` — S1: LZ + SBZ3 only | Exists |

### Phase D: Palette & Music

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 12 | Set H-INT counter ($8ADF — every 224th scanline) | sonic.asm:3002-3003 | N/A (no hardware H-INT) | Skip |
| 13 | `PalLoad palid_Sonic` (load Sonic character palette line) | sonic.asm:3023-3024 | `Sonic1Level` constructor: `loadPalettes(rom, sonicPaletteId, levelPaletteId)` applies Sonic palette entry | Exists (inside constructor) |
| 14 | Load underwater palette (LZ only): `PalLoad_Fade_Water` for LZ or SBZ3 palette | sonic.asm:3025-3034 | `WaterSystem::loadForLevelS1()` handles water palette setup | Exists |
| 15 | Play level music: look up `MusicList` by zone, handle SBZ3/FZ music overrides, `QueueSound1` | sonic.asm:3039-3056 | `AudioManager::playMusic(musicId)` — `Sonic1::getMusicId(levelIdx)` handles zone-to-music mapping with SBZ3/FZ overrides | Exists |

### Phase E: Title Card Animation

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 16 | Spawn title card: write `id_TitleCard` to `v_titlecard` | sonic.asm:3057 | `Sonic1TitleCardManager::initialize(zoneIndex, actIndex)` — creates elements and loads art | Exists |
| 17 | Title card animation loop: VBla wait, `ExecuteObjects`, `BuildSprites`, `RunPLC`, until title card X reaches target AND PLC buffer empty | sonic.asm:3059-3069 | `Sonic1TitleCardManager::update()` drives state machine (SLIDE_IN → DISPLAY → SLIDE_OUT → COMPLETE) | Exists |

### Phase F: HUD & Palette Fade Target

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 18 | `Hud_Base` (draw score/timer/ring count base graphics) | sonic.asm:3070 | `HudRenderManager` renders HUD overlay; base graphics drawn as part of render loop | Exists |
| 19 | `PalLoad_Fade palid_Sonic` (load target palette for upcoming fade-in) | sonic.asm:3073-3074 | Palette already loaded in `Sonic1Level` constructor (`loadPalettes`); fade target set implicitly | Exists (implicit) |

### Phase G: Level Geometry & Data Load

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 20 | `LevelSizeLoad` (camera min/max X/Y from `LevelSize` table, start positions from `StartLocArray`) | sonic.asm:3075 | `Sonic1::loadBoundaries(zone, act)` reads `LEVEL_SIZE_ARRAY_ADDR` (12 bytes/entry); boundaries passed to `Sonic1Level` constructor | Exists |
| 21 | `DeformLayers` (initial BG deformation / parallax) | sonic.asm:3076 | `ParallaxManager::initZone(zoneId, actId, cameraX, cameraY)` | Exists |
| 22 | `bset #2,(v_fg_scroll_flags).w` (force FG redraw on first frame) | sonic.asm:3077 | `LevelManager.foregroundTilemapDirty = true` (set in `loadLevel()`) | Exists (implicit) |
| 23 | `LevelDataLoad`: decompress 16x16 chunks (EniDec), 128x128 blocks (KosDec), `LevelLayoutLoad` (load FG/BG layout), load zone palette, load 2nd PLC — all in one call | sonic.asm:3078 | `Sonic1Level` constructor: `loadPatterns(rom, patternCues)`, `loadChunks(rom, chunksAddr, collisionIndexAddr)`, `loadBlocks(rom, blocksAddr)`, `loadMap(rom, fgAddr, bgAddr)` | Exists (split into separate calls inside constructor) |
| 24 | `LoadTilesFromStart` (render initial screen of tiles to VRAM) | sonic.asm:3079 | N/A — no `LevelManager.drawInitialTiles()` exists; initial tile rendering happens on first frame via dirty tilemap flags | N/A (implicit via dirty flags) |

### Phase H: Collision

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 25 | `ConvertCollisionArray` (convert raw collision data to engine format) | sonic.asm:3080 | Loaded in `Sonic1Level` constructor: `loadSolidTiles(rom, heightsAddr, widthsAddr, anglesAddr)` | Exists (inside constructor) |
| 26 | `ColIndexLoad` (SINGLE collision pointer per zone — UNIFIED model, no secondary path) | sonic.asm:3081 | Loaded in `Sonic1Level` constructor: `loadChunks(rom, chunksAddr, collisionIndexAddr)` applies single collision index per chunk | Exists (inside constructor) |

### Phase I: Water & Player Spawn

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 27 | `LZWaterFeatures` (initial water processing for LZ) | sonic.asm:3082 | `WaterSystem::update()` called via `Sonic1ZoneFeatureProvider::update()` | Exists |
| 28 | Spawn Sonic: write `id_SonicPlayer` to `v_player` | sonic.asm:3083 | Player sprite creation via `LevelManager::resetPlayerState()` → `AbstractPlayableSprite::resetState()` | Exists |
| 29 | Spawn HUD object: write `id_HUD` to `v_hud` (skipped for demos) | sonic.asm:3086 | `LevelManager::loadLevel()` creates `LevelGamestate` via `gameModule.createLevelState()`; HUD rendered by `HudRenderManager` | Exists |
| 30 | Debug mode check: enable debug if A held and cheat entered | sonic.asm:3088-3093 | `GameLoop` checks `DEBUG_MODE_KEY` config via `InputHandler::isKeyPressed()`, sets `AbstractPlayableSprite::setDebugMode(boolean)` | Exists |
| 31 | Clear controller input: zero `v_jpadhold1`, `v_jpadhold2` | sonic.asm:3096-3097 | No explicit equivalent — input state managed per-frame by GLFW polling in `SpriteManager::update(InputHandler)` | N/A (implicit) |
| 32 | Water surface objects (LZ only): spawn two `id_WaterSurface` at X offsets $60, $120 | sonic.asm:3098-3103 | `Sonic1WaterSurfaceManager` constructor (called from `Sonic1ZoneFeatureProvider::initZoneFeatures()`) — no separate `spawnSurface()` method; initialization happens in constructor | Exists (via constructor) |

### Phase J: Object Placement & First Frame

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 33 | `ObjPosLoad` (initial object placement from level layout) — NOTE: happens BEFORE game state clear in S1 | sonic.asm:3106 | `ObjectManager::reset(cameraX)` triggers `Placement::reset()` for initial window; `ObjectManager::update()` streams objects | Exists |
| 34 | `ExecuteObjects` (run one frame of all objects) | sonic.asm:3107 | `ObjectManager::update(cameraX, player, sidekick, frameCounter)` | Exists |
| 35 | `BuildSprites` (build VDP sprite table) | sonic.asm:3108 | `SpriteManager::update(InputHandler)` handles sprite processing; rendering via `ObjectManager` render buckets | Exists |

### Phase K: Game State & Oscillation

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 36 | Clear/initialize game state: if no lamppost, clear rings/time/lives counter. Always clear: shields, invincibility, speed shoes, `Time_Over`, restart flag, frame counter | sonic.asm:3109-3124 | `GameStateManager::resetSession()` / `LevelGamestate` constructor (rings/time); `AbstractPlayableSprite::resetState()` clears shields/invincibility/speed shoes | Exists |
| 37 | `OscillateNumInit` (initialize S1-specific oscillation table: oscillators 0-7 match S2, 8-15 differ) | sonic.asm:3125 | `OscillationManager::resetForSonic1()` called from `Sonic1GameModule::onLevelLoad()` | Exists |
| 38 | Set HUD update flags: score, rings, time counters all = 1 | sonic.asm:3126-3128 | `HudRenderManager` renders every frame; no separate dirty flags needed | Exists (implicit) |

### Phase L: Demo & Transition

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 39 | Load demo data: set demo press counter and timer from `DemoDataPtr` | sonic.asm:3129-3152 | Demo system init | Partial |
| 40 | Load water palette for fade (LZ): `PalLoad_Water` for underwater palette | sonic.asm:3154-3163 | `WaterSystem::loadForLevelS1()` loads water palette as part of water init | Exists |
| 41 | 4-frame VBla delay (wait 4 frames before fade-in) — UNIQUE TO S1 | sonic.asm:3165-3171 | Explicit 4-frame delay before fade | Needs impl |
| 42 | `PalFadeIn_Alt` (fade in palette lines 2-4) | sonic.asm:3173-3174 | No `startFadeIn()` currently; S1 title card handles fade-in internally via `SLIDE_OUT` state alpha fade | Partial |
| 43 | Start title card exit: advance title card object routines | sonic.asm:3177-3181 | `Sonic1TitleCardManager` state machine transitions to `SLIDE_OUT` after `DISPLAY` hold; exit driven by `update()` | Exists |
| 44 | `bclr #7,(v_gamemode).w` — clear pre-level flag, enter main loop | sonic.asm:3193 | Clear loading flag, enter main loop | Needs wrapper |

### S1 Key Differences from S2

1. **Title card art loaded directly** (step 5) via `NemDec` with interrupts disabled, not queued via PLC system
2. **`ClearScreen` happens AFTER RAM clear** (step 9), not before PLCs like S2
3. **Single collision index** (step 26) — UNIFIED model, no secondary path; collision loaded inside `Sonic1Level` constructor via `loadChunks()`
4. **Object placement BEFORE game state clear** (step 33 vs S2 step 44) — S1 places objects before clearing state
5. **4-frame VBla delay** before fade-in (step 41) — not present in S2/S3K
6. **No `SetLevelEndType`** — boss/signpost determination handled differently
7. **No spindash dust spawn** — spindash doesn't exist in S1
8. **No `RingsManager`/`SpecialCNZBumpers` init** — S1 doesn't have separate ring/bumper managers (rings are objects in S1)
9. **FG scroll redraw flag** explicitly set (step 22) — in the engine, this is implicit via `foregroundTilemapDirty = true`

### S1 Coverage Summary

- **Fully implemented:** 31/44 steps
- **Needs wrapper/integration:** 2 steps (loading flag set/clear)
- **Hardware-specific skip / N/A:** 5 steps (VDP registers, H-INT counter, ClearScreen, LoadTilesFromStart, clear input)
- **Partial:** 2 steps (demo system, PalFadeIn_Alt)
- **Needs impl:** 2 steps (ClearPLC, 4-frame delay)

---

## Sonic 3&K Level Init Profile

Source: `sonic3k.asm`, `Level:` routine (line 7504).

S3K has the most complex initialization: three decompression queue systems, zone-set
object table remapping, per-zone LevelSetupArray dispatch, character-specific PLCs
and palettes, AIZ intro cutscene special paths, and dual-routine event counters.

### Phase A: Pre-Title-Card Setup

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 1 | `bset #7,(Game_mode).w` (set pre-level flag) | sonic3k.asm:7505 | Set loading flag | Needs wrapper |
| 2 | `Play_SFX cmd_FadeOut` (fade out music; skipped for demos) | sonic3k.asm:7508 | `AudioManager::fadeOutMusic()` | Exists |
| 3 | `clr.w (Ending_running_flag).w` (clear ending flag) — UNIQUE TO S3K | sonic3k.asm:7512 | Clear ending state flag | Needs wrapper |
| 4 | `clr.w (Kos_decomp_queue_count).w` + `clearRAM Kos_decomp_stored_registers,$6C` (clear KosinskiM queue) — UNIQUE TO S3K | sonic3k.asm:7513-7514 | KosinskiM decompression queue clear | Needs impl (S3K-specific queue) |
| 5 | `Clear_Nem_Queue` (clear Nemesis PLC queue) | sonic3k.asm:7515 | Nemesis PLC queue clear | Needs impl |
| 6 | Special zone/respawn checks: zone `$D01` (special stage arena) or `$1701` (ending), handle `Respawn_table_keep` | sonic3k.asm:7516-7521 | `GameStateManager` special zone transition check | Needs impl |
| 7 | `Pal_FadeToBlack` for normal levels; `Pal_FadeToWhite` for special stage arena (`$D01`) or ending (`$1701`) — UNIQUE: S3K has white fade option | sonic3k.asm:7524/7529 | `FadeManager::startFadeToBlack()` or `FadeManager::startFadeToWhite()` | Exists (not wired into S3K init) |
| 8 | `Clear_DisplayData` (clear screen with ints disabled; skipped for demos) | sonic3k.asm:7535 | Clear display data (hardware VDP operation) | Missing (no `GraphicsManager::clearScreen()`) |
| 9 | Zero `Level_frame_counter` | sonic3k.asm:7538 | `GameLoop` frame counter reset | Needs wrapper |

### Phase B: Starpost & Zone Restoration

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 10 | Starpost zone restore: if starpost hit, restore `Current_zone_and_act` and `Apparent_zone_and_act` from saved values — handles mid-act zone changes like AIZ intro to AIZ1 | sonic3k.asm:7539-7551 | `GameStateManager` starpost zone restore | Needs impl |
| 11 | AIZ intro PLC override: if AIZ1 with no starpost and Sonic, replace level PLC with AIZ intro PLC — UNIQUE TO S3K | sonic3k.asm:7552-7596 | `Sonic3kPlcLoader::parsePlc()` + AIZ intro PLC via `Sonic3kBootstrapResolver` | Exists (via bootstrap resolver) |

### Phase B2: FBZ2 Lamppost 6 PLC Skip

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 11b | FBZ2 lamppost 6 PLC skip: if zone `$401` (FBZ2) and `Last_star_post_hit` = 6, skip loading level PLCs entirely | sonic3k.asm:7566-7569 | PLC skip for FBZ2 lamppost 6 checkpoint | Needs impl |

### Phase C: PLC Loading & Character Setup

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 12 | Load zone PLC (1st): read `LevelLoadBlock` byte 0 for PLC ID, call `Load_PLC` | sonic3k.asm:7571-7582 | `Sonic3kPlcLoader::parsePlc()` via `Sonic3k::loadLevel()` | Exists |
| 13 | `LevelLoad_ActiveCharacter`: copy `Player_option` to `Player_mode`; demos force Sonic+Tails or Knuckles | sonic3k.asm:7585 (calls 8085) | Player mode set via `GameModule` / `PlayerCharacter` | Exists |
| 14 | Load character/standard PLCs: character-specific PLC (varies by player mode, competition mode, graphics flags). AIZ intro gets special PLCs instead | sonic3k.asm:7586-7615 | `Sonic3kPlcLoader::parsePlc()` with character PLC IDs | Exists |

### Phase D: RAM Clear & VDP Setup

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 15 | `clearRAM`: sprite table, object RAM, lag frame counter, Tails CPU interaction, oscillating table, unknown region | sonic3k.asm:7618-7623 | `SpriteManager::clearAllSprites()`, `ObjectManager::reset()` | Exists |
| 16 | `Init_SpriteTable` (separate explicit sprite table initialization) — UNIQUE TO S3K | sonic3k.asm:7624 | Sprite table initialization | Needs wrapper (no `SpriteManager::initSpriteTable()`) |
| 17 | VDP register config: line scroll (`$8B03`), plane A (`$8230=$C000`), plane B (`$8407=$E000`), sprite table (`$857C=$F800`), 64x32 scroll, H-INT disabled, window register (`$9200`), background color, H-res 40 cells | sonic3k.asm:7625-7634 | Implicit (OpenGL) | N/A |
| 18 | Debug mode check: if debug cheat + A held, enable debug | sonic3k.asm:7635-7639 | `Engine` config + `DebugModeProvider` interface; `SonicConfigurationService::getBoolean(DEBUG_MODE_KEY)` | Exists |
| 19 | H-INT counter (`$8AFF`). Competition mode: enable H-INT, adjust plane A/B bases, set `$8A6B`, 128-cell scroll; special handling for zone `$F` | sonic3k.asm:7642-7655 | N/A (no hardware H-INT) | Skip |
| 20 | Init DMA queue: clear `DMA_queue`, set slot pointer | sonic3k.asm:7658-7659 | N/A (no DMA in OpenGL) | Skip |

### Phase E: Palette & Water

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 21 | `LoadPalette_Immediate` with palette ID 3 (or 5 for Knuckles) — CHARACTER-SPECIFIC palette | sonic3k.asm:7660-7666 | `CrossGameFeatureProvider::loadCharacterPalette()` via palette loading in `Sonic3kLevel` constructor | Exists |
| 22 | `CheckLevelForWater`: set `Water_flag` per zone (AIZ, HCZ, MGZ2/Knux, ICZ2, LBZ2). No water → water level = `$1000`. Water → load from `StartingWaterHeights`, configure H-INT handler (HInt2/HInt3/HInt4 depending on zone and VBlank budget), set water speed | sonic3k.asm:7667 (calls 9751) | `Sonic3kZoneFeatureProvider::hasWater()` | **Missing** (S3K returns `hasWater=false` for all zones) |
| 23 | Clear `Water_palette_line_2` buffer | sonic3k.asm:7668 | Water palette buffer clear | **Missing** (depends on step 22) |
| 24 | Enable H-INT for water: if water flag set, write `$8014` | sonic3k.asm:7671 | N/A (no hardware H-INT) | Skip |

### Phase F: Music

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 25 | Play level music: look up `LevelMusic_Playlist`, special handling for AIZ1 lamppost 3 music and MHZ Knuckles intro music. `Play_Music` | sonic3k.asm:7673-7701 | `AudioManager::playMusic()` | Exists |
| 26 | MHZ Knuckles intro art: if MHZ, Knuckles, S&K alone, no starpost, decompress squirrel/chicken animal art directly — UNIQUE TO S3K | sonic3k.asm:7711-7727 | `Sonic3kObjectArt` MHZ Knuckles intro art loading | Needs impl |

### Phase G: Title Card Animation

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 27 | Spawn title card: `move.l #Obj_TitleCard` to dynamic object slot 5 (skipped for zone `$1701` ending, or if `Act3_flag` set) — NOTE: S3K uses code pointer, not object ID | sonic3k.asm:7730-7735 | `Sonic3kTitleCardManager::initialize()` (via `TitleCardProvider` interface) | Exists |
| 28 | Title card loop: VBla wait, `Process_Kos_Queue`, `Process_Sprites`, `Render_Sprites`, `Process_Nem_Queue_Init`, `Process_Kos_Module_Queue` — runs THREE decompression queues during title card (Nemesis PLC + Kosinski + KosinskiM) | sonic3k.asm:7737-7748 | `Sonic3kTitleCardManager::update()` + `Sonic3kTitleCardManager::shouldReleaseControl()` loop | Exists |
| 29 | `clr.b (Act3_flag).w` | sonic3k.asm:7751 | Clear Act3 flag | Needs wrapper |

### Phase H: HUD & Level Boundaries

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 30 | `HUD_DrawInitial` (draw initial HUD with ints disabled) | sonic3k.asm:7753 | `HudRenderManager::draw()` | Exists |
| 31 | `LoadPalette` with ID 3 (load target palette for fade-in) | sonic3k.asm:7757-7758 | Palette loading in `Sonic3kLevel` constructor (target palette for fade-in) | Exists |
| 32 | `Get_LevelSizeStart` (camera min/max from `LevelSizes` table, scroll lock flags, distance from top) | sonic3k.asm:7759 | `Sonic3kLevel::loadBoundaries()` (private, called from constructor) — uses `Sonic3kBootstrapResolver` for AIZ intro index | Exists |
| 33 | `DeformBgLayer` (initial BG deformation) | sonic3k.asm:7760 | `ParallaxManager::initZone()` | Exists |

### Phase I: Level Data Loading (Two-Phase)

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 34 | `LoadLevelLoadBlock` (1st block): read `LevelLoadBlock` entries 0-1 (8x8 pattern art), `Queue_Kos_Module`, wait loop until all KosinskiM modules decompressed — ASYNC ART QUEUE, unique to S3K | sonic3k.asm:7761 | `ResourceLoader` with KosinskiM compression via `LevelResourcePlan` | Exists |
| 35 | `LoadLevelLoadBlock2` (2nd block): decompress blocks (entries 2-3 via `Kos_Decomp` to `Block_table`), decompress chunks (entries 4-5 to RAM), `Load_Level` from `LevelPtrs` (level layout), load act-specific PLC (entry 4 byte), load zone palette (entry 5 byte) | sonic3k.asm:7762 | `ResourceLoader` with Kosinski chunks/blocks, `Sonic3kLevel` constructor for layout | Exists |

### Phase J: Zone-Specific Setup

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 36 | `j_LevelSetup` → `LevelSetup`: clear event routines, screen shake, plane buffer addresses. Dispatch to per-zone/act setup via `LevelSetupArray` (draws initial FG plane A and BG plane B) — UNIQUE TO S3K, no equivalent in S1/S2 | sonic3k.asm:7764 (calls 38661 → 102180) | Per-zone setup dispatch via `Sonic3kZoneFeatureProvider::initZoneFeatures()` | Partial (only AIZ via bootstrap resolver) |
| 37 | `Animate_Init` (zone-specific animation counter initialization) — UNIQUE TO S3K | sonic3k.asm:7766 (calls 56368) | `Sonic3kLevelAnimationManager::update()` (no separate `init()`) | Needs impl (per-zone init not present) |

### Phase K: Collision

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 38 | `LoadSolids`: read `SolidIndexes` (32-bit entries with flags). Bit 31: Non-interleaved (S3K zones, primary at base, secondary at base+`$600`). Bit 31 clear: Interleaved (SK zones, primary/secondary alternate bytes in `$C00` block). DUAL_PATH model | sonic3k.asm:7767 (calls 9540) | `Sonic3k::getCollisionAddresses()` with flag decoding | Exists |

### Phase L: Water & Controls

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 39 | `Handle_Onscreen_Water_Height` (initial water height processing) | sonic3k.asm:7768 (calls 8474) | `WaterSystem::update()` | **Missing** (S3K water not implemented) |
| 40 | Lock/clear controls: clear logical+physical, `Ctrl_1_locked=1`, `Ctrl_2_locked=1`, `Level_started_flag=0` | sonic3k.asm:7770-7776 | `AbstractLevelEventManager::lockPlayerInput()` (sets `AbstractPlayableSprite::setControlLocked(true)`) | Exists |

### Phase M: Zone-Specific Object Spawning

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 41 | HCZ water surface: if zone 1 (HCZ) and `Water_flag` set, spawn `Obj_HCZWaveSplash` and `Obj_HCZWaterSplash` | sonic3k.asm:7777-7787 | HCZ water surface objects | **Missing** (HCZ not implemented) |
| 42 | MHZ pollen spawner: if zone 7 (MHZ), spawn `Obj_MHZ_Pollen_Spawner` | sonic3k.asm:7790-7792 | MHZ pollen object | **Missing** (MHZ not implemented) |

### Phase N: Game State Init

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 43 | Clear game state: if no lamppost, clear rings/timer/lives/status/`Respawn_table_keep`. Always clear: time-over, debug, restart, teleport, ring count, monitors broken, loser time, LRZ rocks, super flag | sonic3k.asm:7794-7833 | `GameStateManager::resetSession()` (partial — covers score/lives/emeralds but not all ROM fields) | Partial |
| 44 | `OscillateNumInit` (initialize oscillation table) | sonic3k.asm:7834 (calls 9572) | `OscillationManager::reset()` | Exists |
| 45 | Set HUD update flags + `Level_started_flag=1` — NOTE: S3K sets `Level_started_flag` HERE, BEFORE first object frame (S1/S2 set it after title card exit) | sonic3k.asm:7835-7838 | `HudRenderManager::invalidateCache()` + early `Level_started_flag` | Needs adjustment (flag timing) |
| 46 | Special zone HUD override: if zone `$D01` or `$1701`, clear timer update and level started flags | sonic3k.asm:7839-7846 | Special zone state override | Needs impl |

### Phase O: Player & Object Spawning

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 47 | `SpawnLevelMainSprites`: spawn collision reset list object, spawn players (Sonic/Tails/Knuckles based on `Player_mode`), spawn powerup shield, zone-specific intro setups (AIZ intro plane, HCZ2 fall, LRZ Knuckles cutscenes, etc.) — NOTE: player spawn happens AFTER game state init in S3K (different from S1/S2) | sonic3k.asm:7849 (calls 8111) | Player spawning + zone intro cutscene setup via `LevelManager::loadLevel()` | Partial (AIZ intro only) |
| 48 | `Load_Sprites` (initial object placement via spawn windowing) | sonic3k.asm:7850 | `ObjectManager.Placement` initial spawn via `ObjectManager::update()` | Exists |
| 49 | `Load_Rings` (initial ring placement) | sonic3k.asm:7851 | `RingManager` initial placement via level constructor | Exists |
| 50 | `Draw_LRZ_Special_Rock_Sprites` (LRZ rock rendering) — UNIQUE TO S3K | sonic3k.asm:7852 | LRZ rock sprites | **Missing** (LRZ not implemented) |

### Phase P: First Frame

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 51 | `Process_Sprites` (execute one frame of all objects) | sonic3k.asm:7853 | `ObjectManager::update()` | Exists |
| 52 | `Render_Sprites` (build VDP sprite table) | sonic3k.asm:7854 | `SpriteManager::draw()` | Exists |
| 53 | `Animate_Tiles` (first frame of tile animation) — UNIQUE TO S3K as explicit post-object call | sonic3k.asm:7855 | `Sonic3kLevelAnimationManager::update()` | Needs impl |

### Phase Q: Final Setup & Transition

| # | ROM Routine | ROM Reference | Engine Operation | Status |
|---|-------------|---------------|-----------------|--------|
| 54 | Set `Demo_timer = 1800` | sonic3k.asm:7856 | Demo timer init | Partial |
| 55 | `LoadWaterPalette` (zone-specific water transition palettes, water palette data addresses) — NOTE: loaded AFTER first object frame, much later than S1/S2 | sonic3k.asm:7857 | Water palette loading | **Missing** (S3K water not implemented) |
| 56 | Clear `Water_palette_line_2` buffer (again) | sonic3k.asm:7858 | Water palette buffer clear (second time) | **Missing** |
| 57 | Unlock controls: `Ctrl_1_locked=0`, `Ctrl_2_locked=0` | sonic3k.asm:7859-7860 | `AbstractLevelEventManager::unlockPlayerInput()` (sets `AbstractPlayableSprite::setControlLocked(false)`) | Exists |
| 58 | `GetDemoPtr` (load demo pointer data) | sonic3k.asm:7861 | Demo pointer load | Partial |
| 59 | `PLCLoad_AnimalsAndExplosion` (load animal/explosion PLCs; skipped for AIZ intro w/o starpost, or zones >= `$E`) — NOTE: loaded AFTER object frame, not in initial PLC batch | sonic3k.asm:7864-7872 | `Sonic3kPlcLoader::parsePlc()` with animal/explosion PLC IDs | Needs impl |
| 60 | Palette fade setup: set `Palette_fade_info=$202F`, call `Pal_FillBlack`, set fade timer `$16` | sonic3k.asm:7875-7877 | `FadeManager::startFadeFromBlack()` | Exists |
| 61 | Title card fade timer: set title card fade offset (`objoff_2E = $16`) | sonic3k.asm:7878 | `Sonic3kTitleCardManager::update()` (fade timer set during init, consumed by update loop) | Exists |
| 62 | Dummy controller input: `Ctrl_1 = $7F00`, `Ctrl_2 = $7F00` (initial hold-right for intro sequences) — UNIQUE TO S3K | sonic3k.asm:7879-7880 | `AbstractLevelEventManager::setForcedInput()` | Needs wiring |
| 63 | `andi.b #$7F,(Last_star_post_hit).w` (clear starpost high bit) | sonic3k.asm:7881 | Starpost high bit clear | Needs impl |
| 64 | `bclr #7,(Game_mode).w` — clear pre-level flag, enter main loop | sonic3k.asm:7882 | Clear loading flag, enter main loop | Needs wrapper |

### S3K Key Differences from S1/S2

1. **Three decompression queues** (steps 4-5, 28): Kosinski module queue, Nemesis PLC queue, and Kosinski queue — all processed during title card animation loop
2. **Fade to white option** (step 7) for special stage arena and ending transitions — `FadeManager::startFadeToWhite()` exists but is not wired into S3K init
3. **Starpost zone restoration** (step 10) handles mid-act zone changes (AIZ intro to AIZ1)
4. **AIZ intro PLC override** (step 11) replaces standard level PLC for intro sequence
5. **FBZ2 lamppost 6 PLC skip** (step 11b) skips loading level PLCs entirely for the FBZ2 late-act checkpoint
6. **Character-specific palette** (step 21): palette ID 3 for Sonic/Tails, 5 for Knuckles
7. **Complex water zone detection** (step 22): 5+ zones with water, character-dependent (MGZ2 Knuckles only), multiple H-INT handlers
8. **Per-zone LevelSetupArray** (step 36): zone/act-specific initialization dispatch, draws initial FG/BG planes
9. **Animate_Init** (step 37): per-zone animation counter setup not present in S1/S2
10. **`Level_started_flag` set BEFORE first object frame** (step 45) — S1/S2 set after title card exit
11. **Player spawn AFTER game state init** (step 47) — S1/S2 spawn before or during game state
12. **Water palette loaded AFTER first object frame** (step 55) — S1/S2 load during init
13. **Animal/explosion PLCs loaded AFTER object frame** (step 59) — deferred from initial batch
14. **Dummy controller input** (step 62) for intro sequences — not in S1/S2
15. **LRZ rock sprites** (step 50) and **MHZ pollen spawner** (step 42) — zone-specific ambient objects
16. **Control locking via `AbstractLevelEventManager::lockPlayerInput()`/`unlockPlayerInput()`** (steps 40, 57) — not a separate `InputManager`

### S3K Coverage Summary

- **Fully implemented:** 26/65 steps
- **Needs wrapper/integration:** 9 steps (loading flag, ending flag, frame counter, sprite table init, Act3 flag, HUD timing, dummy input, starpost bit, game mode flag)
- **Needs implementation:** 12 steps (KosinskiM queue, Nemesis queue, respawn checks, FBZ2 PLC skip, zone restore, MHZ art, Animate_Init, Animate_Tiles, special zone override, animal PLCs, starpost clear, dummy input wiring)
- **Hardware-specific skip / N/A:** 4 steps (VDP registers, H-INT, DMA, H-INT for water)
- **Missing (dependent on unimplemented subsystems):** 8 steps (ClearScreen, water init, water palette ×2, water height, HCZ surface, MHZ pollen, LRZ rocks)
- **Partial:** 4 steps (zone setup, game state, player spawning, demo)

---

## Integration with Existing Engine

### GameModule Extension

```java
public interface GameModule {
    // ... existing methods ...

    /** Returns the ROM-derived level initialization profile for this game */
    LevelInitProfile getLevelInitProfile();
}
```

### GameContext.forTesting() Migration

The current 8-phase manual reset in `GameContext.forTesting()` becomes:

```java
public static GameContext forTesting() {
    GameModule module = GameModuleRegistry.getCurrent();
    LevelInitProfile profile = module.getLevelInitProfile();

    // Run game-specific teardown
    for (InitStep step : profile.levelTeardownSteps()) {
        step.execute();
    }

    // Static fixups (GroundSensor, etc.) registered as post-teardown hooks
    for (StaticFixup fixup : profile.postTeardownFixups()) {
        fixup.apply();
    }

    return production();
}
```

### HeadlessTestFixture Integration

`HeadlessTestFixture.build()` currently calls `TestEnvironment.resetPerTest()` (a lighter
reset that preserves loaded level data). This maps to a subset of the teardown profile —
only the "cheap" steps that don't require level reload.

```java
public interface LevelInitProfile {
    List<InitStep> levelLoadSteps();
    List<InitStep> levelTeardownSteps();

    /** Subset of teardown steps safe for per-test reset (preserves level data) */
    List<InitStep> perTestResetSteps();
}
```

### Static Fixup Registration

Instead of ad-hoc `GroundSensor.setLevelManager()` and
`AizPlaneIntroInstance.setSidekickSuppressed(false)` calls:

```java
public interface StaticFixup {
    String name();
    String reason(); // Why this fixup is needed
    void apply();
}
```

Fixups are registered per-game and executed as the final step of teardown/init.

---

## Implementation Approach

### Phase 1: Define profiles (read-only)
- Create `InitStep`, `LevelInitProfile`, `StaticFixup` interfaces
- Implement `Sonic2LevelInitProfile` transcribing the 57 steps above
- Implement `Sonic1LevelInitProfile` transcribing the 44 steps above
- Implement `Sonic3kLevelInitProfile` transcribing the 65 steps above
- Each step's `Runnable` calls into existing engine methods
- Add `getLevelInitProfile()` to `GameModule` interface

### Phase 2: Wire profiles into GameContext
- Replace `GameContext.forTesting()` manual 8-phase with profile-driven teardown
- Replace `TestEnvironment.resetPerTest()` with `perTestResetSteps()`
- Validate all existing tests still pass

### Phase 3: Wire profiles into production level loading
- Replace `Sonic1.loadLevel()` / `Sonic2.loadLevel()` / `Sonic3k.loadLevel()` with
  profile step execution
- Each game's `loadLevel()` becomes: execute `levelLoadSteps()` in order
- Validate gameplay still works correctly

### Phase 4: Close implementation gaps
- Implement missing S3K steps (water system, zone-specific objects, animation init)
- Implement missing wrapper methods (loading flag, frame counter, scroll clear)
- Add PLC queue modeling for S2/S3K

---

## Open Questions

1. Should the profiles be truly immutable step lists, or should steps be conditionally
   included (e.g., water steps only when the zone has water)?
   - ROM approach: conditional logic is INSIDE the step (the step checks and no-ops)
   - Engine approach: profile builder conditionally includes steps
   - **Recommendation:** ROM approach — keep the step list fixed, let each step handle
     its own conditions, matching how the ROM code works

2. Should `levelTeardownSteps()` be the exact reverse of load, or a separate
   purpose-built sequence?
   - **Recommendation:** Separate sequence — teardown doesn't need to reverse every
     load step, just reset state for the next load

3. How should competition/2P mode variants be handled?
   - S2 has `MusicList2`, different H-INT, different PLCs
   - **Recommendation:** Conditional logic within steps (same as ROM), not separate profiles
