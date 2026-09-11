# Sonic 2 Credits & Ending Sequence Design

## Overview

Implement the full Sonic 2 ending sequence: DEZ boss defeat trigger → ending cutscene → 21 credit text slides → Sonic 2 logo flash → title screen return. Introduce a game-agnostic `EndingProvider` interface so both S1 and S2 (and future S3K) credits use the same GameLoop integration.

## Architecture: EndingProvider Interface

### Interface

```java
interface EndingProvider {
    void initialize();
    void update();
    void draw();
    EndingPhase getCurrentPhase();
    boolean isComplete();

    // S1-specific demo support (default no-ops)
    default LevelLoadRequest consumeDemoLoadRequest() { return null; }
    default void onDemoZoneLoaded() {}
    default int getDemoInputMask() { return 0; }
    default boolean isScrollFrozen() { return false; }
}

enum EndingPhase {
    CUTSCENE,       // S2: falling/tornado. S1: STH text
    CREDITS_TEXT,   // Both: text slides
    CREDITS_DEMO,   // S1 only: demo playback between slides
    POST_CREDITS,   // S1: TRY AGAIN/END. S2: logo flash
    FINISHED        // Return to title
}
```

### GameModule Integration

`GameModule.getEndingProvider()` returns the game-specific implementation. `Sonic2GameModule` returns `Sonic2EndingProvider`, `Sonic1GameModule` returns `Sonic1EndingProvider` (wrapping existing managers).

### GameLoop Refactoring

Replace ~10 hardcoded S1 credits methods with a unified `updateEnding()` that:
1. Calls `endingProvider.update()`
2. Syncs `currentGameMode` to `provider.getCurrentPhase()`
3. Handles engine-level coordination (level loading for S1 demos, fade manager)

GameMode additions: `ENDING_CUTSCENE` for the S2 cutscene phase. Existing modes (`CREDITS_TEXT`, `CREDITS_DEMO`, `TRY_AGAIN_END`) retained.

## S2 Ending Cutscene

### Manager: `Sonic2EndingCutsceneManager`

Self-contained renderer (not using game object/sprite system) since cutscene entities don't interact with level physics. Mirrors ROM's ObjCA state machine.

### State Machine

| State | Description | Duration |
|-------|-------------|----------|
| `INIT` | Load art, palettes, set up scroll, spawn cloud renderer | ~10 frames |
| `PHOTO_SEQUENCE` | Display 4 Enigma-mapped ending photos with fade transitions | ~0x180 frames per photo |
| `SKY_FALL` | Character falling through sky, clouds scrolling up | ~0xC0 frames |
| `PLANE_APPROACH` | Tornado flies in from left, character animation | ~0x480 frames |
| `PLANE_RESCUE` | Character boards plane, birds spawn | ~0x100 frames |
| `FLY_AWAY` | Plane exits screen | Until offscreen |
| `TRIGGER_CREDITS` | Signal transition to credits phase | Instant |

### Character Variants (ROM's `Ending_Routine`)

| Routine | Character | Art | Animal | Condition |
|---------|-----------|-----|--------|-----------|
| 0 | Sonic | `Ending Sonic.nem` | Flicky | Default |
| 2 | Super Sonic | `Ending Super Sonic.nem` | Eagle | 7 emeralds |
| 4 | Tails | `Ending Tails.nem` | Chicken | Tails-alone mode |

### Art Assets (all Nemesis-compressed)

| Asset | VRAM Tile Base | Purpose |
|-------|---------------|---------|
| `ArtNem_EndingCharacter` | 0x0019 | Main character sprite (variant-specific) |
| `ArtNem_EndingFinalTornado` | 0x0156 | Closeup Tornado with character |
| `ArtNem_EndingPics` | 0x0328 | 4 sequential ending photo frames |
| `ArtNem_EndingMiniTornado` | 0x0493 | Tornado sequence sprites |
| `ArtNem_Clouds` | 0x0594 | Background clouds |
| `ArtNem_Animal_2` | 0x0594 | Rescued animals (Flicky/Eagle/Chicken) |

### Palettes

- `Ending Sonic.bin` / `Ending Tails.bin` / `Ending Super Sonic.bin` - Character palettes
- `Ending Background.bin` - Sky/cloud palette
- `Ending Photos.bin` - Photo frame palette

### Rendering

Uses `PatternSpriteRenderer` for sprites. Photo frames rendered via Enigma-decoded tilemaps (`MapEng_Ending1` through `MapEng_Ending4`). Clouds rendered as moving sprites with parallax-style vertical/horizontal scrolling.

## S2 Credits Text

### 21 Slides from ROM

Parsed directly from ROM pointer table at `off_B2CA` (0xB2CA). Each screen contains 1-7 text elements with palette index and VRAM position. Font is Nemesis-compressed (`Credit Text.nem`) loaded to VRAM tile 0x0500.

### Character Encoding

2-wide character encoding: each letter maps to a pair of tiles via charset remapping table. Parsed from ROM rather than hardcoded to avoid transcription errors.

### Timing

- Per-slide duration: 0x18E frames (60 FPS) ≈ 6.6 seconds
- Fade in/out: 0x16 frames each
- Music: `MusID_Credits` (0x9E)

### Renderer: `Sonic2CreditsTextRenderer`

Loads credit font from ROM, parses 21 screen definitions from pointer table, renders each screen centered using `PatternSpriteRenderer`.

## S2 Logo Flash (Post-Credits)

After 21 text slides:
1. Load `ArtNem_EndingTitle` (Sonic 2 logo) to VRAM 0x0000
2. Decode `MapEng_EndGameLogo` (Enigma mapping) to display
3. Run palette flash: `Ending Cycle.bin` (9 palette frames × 24 bytes) with strobe sequence from `byte_A0EC`
4. Hold ~0x257 frames, skippable with button press
5. Signal `FINISHED` phase

## DEZ Boss Trigger

1. Rename `LevelManager.requestCreditsTransition()` → `requestEndingTransition()`
2. `Sonic2DEZEvents` calls it when boss is defeated (routine 8 completion)
3. GameLoop consumes the request, fades to black, initializes `EndingProvider`
4. Testable from debug mode if DEZ boss fight isn't complete yet

## Package Structure

```
com.openggf.game/
    EndingProvider.java
    EndingPhase.java

com.openggf.game.sonic1.credits/
    Sonic1EndingProvider.java        // Wraps existing Sonic1CreditsManager + TryAgainEndManager
    (existing files unchanged)

com.openggf.game.sonic2.credits/
    Sonic2EndingProvider.java        // Top-level state machine
    Sonic2EndingCutsceneManager.java // Cutscene phase
    Sonic2CreditsTextRenderer.java   // 21 text slides
    Sonic2CreditsData.java           // ROM addresses, timing constants
    Sonic2EndingArt.java             // Art loading (character variants, font, logo)

com.openggf.game.sonic2/
    Sonic2GameModule.java            // Add getEndingProvider()
```

## Testing

- **Unit tests:** `Sonic2CreditsData` ROM parsing (pointer table, text elements, palette data) - skipped without ROM
- **Integration test:** `TestSonic2EndingHeadless` - state machine transitions through all phases
- **S1 regression:** Verify `Sonic1EndingProvider` wrapping preserves existing S1 credits behavior
