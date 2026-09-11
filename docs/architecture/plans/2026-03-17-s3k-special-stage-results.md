# S3K Special Stage Results Screen (Chaos Emeralds) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the results screen shown after completing an S3K Blue Sphere special stage, displaying ring/time bonus tally, emerald collection indicators, and "GOT A CHAOS EMERALD" message when earned.

**Architecture:** New `S3kSpecialStageResultsScreen` class implementing `ResultsScreen` interface with a 6-state machine matching ROM routines 0-A. Reuses existing `Map_Results` mappings and `PatternSpriteRenderer` for rendering. Integrates via `Sonic3kSpecialStageProvider.createResultsScreen()`.

**Tech Stack:** Java 21, KosinskiM/Nemesis decompression, S3K sprite mapping parser, GL pattern rendering

**Spec:** `docs/architecture/designs/2026-03-17-s3k-special-stage-results-design.md`

---

## Chunk 1: ROM Constants and Art Loading

### Task 1: Add SS Results ROM Address Constants

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`

- [ ] **Step 1: Add special stage results constants**

Add near existing results constants (around line 595):

```java
// === Special Stage Results Art (KosinskiM) ===
public static final int ART_KOSM_SS_RESULTS_ADDR = 0x15BABE;          // SSResults text art (149 tiles, 4768 bytes)
public static final int ART_KOSM_SS_RESULTS_SUPER_ADDR = 0x15B374;    // Super form art (Sonic)
public static final int ART_KOSM_SS_RESULTS_SUPER_K_ADDR = 0x15B4F6;  // Super form art (Knuckles)

// === Special Stage Results VRAM Layout ===
public static final int VRAM_SS_RESULTS_CHAR_NAME = 0x4F1;   // Character name art
public static final int VRAM_SS_RESULTS_SUPER = 0x50F;        // Super form art
public static final int VRAM_SS_RESULTS_TEXT = 0x523;          // SS results text
public static final int VRAM_SS_RESULTS_GENERAL = 0x5B8;      // General results art
public static final int VRAM_SS_RESULTS_BASE = 0x4F1;         // Lowest VRAM address used
public static final int VRAM_SS_RESULTS_ARRAY_SIZE = 0x300;   // Tile range $4F1 to ~$7F0
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`

- [ ] **Step 3: Commit**

### Task 2: Add SS Results Art Loading Method

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`

- [ ] **Step 1: Add `loadSSResultsArt` method**

Add a new public method following the existing `loadResultsArt()` pattern but with SS-specific VRAM layout:

```java
/**
 * Load special stage results screen art from ROM.
 * VRAM layout differs from level results:
 * - $4F1: Character name
 * - $50F: Character Super form
 * - $523: SS results text (ArtKosM_SSResults)
 * - $5B8: General results art (ArtKosM_ResultsGeneral)
 * - $6BC: Ring/HUD text (Nemesis, ArtNem_RingHUDText)
 */
public Pattern[] loadSSResultsArt(PlayerCharacter character) {
    // Similar to loadResultsArt() but different VRAM destinations
    // 1. General art → VRAM $5B8
    // 2. Character name → VRAM $4F1
    // 3. Super form art → VRAM $50F
    // 4. SS results text → VRAM $523
    // 5. Ring HUD text → VRAM $6BC (Nemesis)
}
```

Follow the existing `loadResultsArt()` at line 669 and `loadHudTextIntoPatterns()` at line 568 as patterns.

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`

- [ ] **Step 3: Commit**

---

## Chunk 2: Core Results Screen Class

### Task 3: Create S3kSpecialStageResultsScreen

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/specialstage/S3kSpecialStageResultsScreen.java`

- [ ] **Step 1: Create class skeleton with state machine**

Implements `ResultsScreen`. 6 states: INIT, PRE_TALLY, POST_TALLY, EMERALD_CHECK, EMERALD_REVEAL, EXIT.

Constructor takes: `int ringsCollected, boolean gotEmerald, int stageIndex, int totalEmeraldCount, PlayerCharacter character`

Key fields:
- `ringBonus` = ringsCollected * 10
- `timeBonus` = gotEmerald ? 5000 : 0
- `preTallyTimer` = 360 (counts down)
- `frameCounter`, `complete`
- `ResultsElement[] elements` (19 initial + 6 reveal)
- Pattern/mapping/renderer fields for art

- [ ] **Step 2: Implement update() state machine**

Route through 6 states. Each state mirrors the ROM routine:
- INIT: one-shot setup, advance immediately
- PRE_TALLY: countdown from 360, music at 289, then tally
- POST_TALLY: 120f wait, continue icon if ≥50 rings, optional 270f wait
- EMERALD_CHECK: if earned+all7→cleanup objects; if earned→same; if failed→complete
- EMERALD_REVEAL: wait for cleanup, create 6 reveal elements, 240f wait
- EXIT: countdown, then `complete = true`

- [ ] **Step 3: Implement element creation from ObjDat2_2E834**

Create 19 elements with ROM-accurate positions (VDP coords - 128 for screen coords). Element types: LABEL, RING_BONUS, TIME_BONUS, CONTINUE, EMERALD, FAIL_MSG, CHAR_NAME, GOT_ALL, CHAOS_EM, NOW_TEXT, SUPER_TEXT.

Each element has: type, targetX, startX, y, mappingFrame, widthPixels, currentX, visible flag.

Conditional visibility based on `gotEmerald` and `totalEmeraldCount`:
- Element 5 (CONTINUE): visible only if ringsCollected >= 50
- Elements 6-12 (EMERALD): visible if `GameStateManager.hasEmerald(slot)`, with 3-state flicker
- Element 13 (FAIL_MSG): visible only if `!gotEmerald`
- Elements 14-15 (CHAR_NAME, GOT_ALL): visible only if `gotEmerald`
- Elements 16-18 (CHAOS_EM, NOW, SUPER): visible only if `gotEmerald && totalEmeraldCount >= 7`

- [ ] **Step 4: Implement element sliding**

Each frame, slide elements toward targetX at 16px/frame (same LevelResults_MoveElement logic).

- [ ] **Step 5: Implement tally countdown**

After 360-frame wait: decrement ringBonus and timeBonus by 10/frame. Add to score via `GameServices.gameState().addScore()`. Play sfx_Switch every 4 frames (`frameCounter & 3 == 0`). When both zero, play sfx_Register.

- [ ] **Step 6: Implement character-specific adjustments**

Apply sub_2EC80 offsets to character name elements:
- Sonic: offset=0, frameAdj=0
- Knuckles: offset=-$18, frameAdj=3
- Miles: offset=0, frameAdj=1
- Tails: offset=4, frameAdj=2

For S3K combined, labels get frame += $1A; Knuckles gets frame += 5 more.
If all 7 emeralds: character name shifts -$10 on x_pos and targetX.

- [ ] **Step 7: Verify compilation**

Run: `mvn compile -q`

- [ ] **Step 8: Commit**

### Task 4: Implement Rendering

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/S3kSpecialStageResultsScreen.java`

- [ ] **Step 1: Implement art loading in constructor**

Load art via `Sonic3kObjectArt.loadSSResultsArt()`. Parse mappings from `MAP_RESULTS_ADDR`. Adjust tile indices by `-VRAM_SS_RESULTS_BASE`. Create `ObjectSpriteSheet` and `PatternSpriteRenderer`.

Load `Pal_Results` for palette (128 bytes → 4 palette lines).

- [ ] **Step 2: Implement `appendRenderCommands()`**

For each visible element:
- Convert VDP coords to screen coords (- 128) — already done in element creation
- Camera is at (0,0) so screen coords = world coords
- LABEL/FAIL_MSG/CHAR_NAME/GOT_ALL/CHAOS_EM/NOW/SUPER: render mapping frame via `renderer.drawFrameIndex()`
- RING_BONUS/TIME_BONUS: render 7-digit display (same approach as level results `renderBonusDigits`)
- EMERALD: check visibility + 3-state flicker (`flickerCounter % 3 != 0` → draw)
- CONTINUE: blink via `(frameCounter >> 3) & 1` — draw only when bit is set

- [ ] **Step 3: Implement phase 2 reveal elements (ObjDat2_2E918)**

6 elements created in EMERALD_REVEAL state. Same rendering approach. These slide from off-screen right to their target positions.

- [ ] **Step 4: Implement cleanup slide-out objects**

5 pseudo-objects that slide existing bonus text to the right at 32px/frame. When off-screen, decrement `childCounter`. When counter reaches 0, advance to EMERALD_REVEAL.

- [ ] **Step 5: Verify compilation**

Run: `mvn compile -q`

- [ ] **Step 6: Commit**

---

## Chunk 3: Integration

### Task 5: Wire Up Provider and Engine

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageProvider.java`
- Modify: `src/main/java/com/openggf/Engine.java`

- [ ] **Step 1: Update provider createResultsScreen()**

Replace `NoOpResultsScreen.INSTANCE` with:
```java
PlayerCharacter character = AbstractLevelEventManager.getInstance().getPlayerCharacter();
return new S3kSpecialStageResultsScreen(
    ringsCollected, gotEmerald, stageIndex, totalEmeraldCount, character);
```

- [ ] **Step 2: Set white clear color for SS results**

In Engine.java around line 574, change the clear color for SPECIAL_STAGE_RESULTS from light blue to white:
```java
} else if (getCurrentGameMode() == GameMode.SPECIAL_STAGE_RESULTS) {
    glClearColor(1.0f, 1.0f, 1.0f, 1.0f);  // White background (from Pal_Results)
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`

- [ ] **Step 4: Commit**

### Task 6: Unit Tests

**Files:**
- Create: `src/test/java/com/openggf/game/sonic3k/specialstage/TestS3kSpecialStageResultsTally.java`

- [ ] **Step 1: Write tally calculation tests**

Test ring bonus = rings × 10, time bonus = 5000 if perfect else 0, ≥50 ring threshold for continue icon.

- [ ] **Step 2: Write emerald visibility tests**

Test element visibility logic: failed → element 13 visible, 14-18 hidden. Succeeded → element 13 hidden, 14 visible. All 7 → elements 16-18 visible.

- [ ] **Step 3: Run tests**

Run: `mvn test -Dtest=TestS3kSpecialStageResultsTally -q`

- [ ] **Step 4: Commit**

### Task 7: Full Test Suite Verification

- [ ] **Step 1: Run all S3K tests**

Run: `mvn test -q`
Verify no regressions.

- [ ] **Step 2: Final commit if fixups needed**
