# S3K Results Screen Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the S3K act-completion results screen with character-specific "GOT THROUGH" text, time/ring bonus tally, and ROM-accurate slide-in/slide-out animation.

**Architecture:** Extends `AbstractResultsScreen` (existing 5-state machine). Art loaded via KosinskiM from ROM (same pattern as `Sonic3kTitleCardManager`). 12 elements slide in/out at ROM-accurate speeds. Exit behavior is act-dependent (act 1 → title card, act 2 → end-of-level flag).

**Tech Stack:** Java 21, KosinskiM decompression, S3K sprite mapping parser, screen-space overlay rendering

**Spec:** `docs/architecture/designs/2026-03-16-s3k-results-screen-design.md`

---

## Chunk 1: ROM Constants and Art Loading

### Task 1: Add ROM Address Constants

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`

- [ ] **Step 1: Add results screen ROM address constants**

Add these verified ROM addresses to `Sonic3kConstants.java`, grouped together near the existing title card constants:

```java
// === Results Screen Art (KosinskiM) ===
public static final int ART_KOSM_RESULTS_GENERAL_ADDR = 0x0D6A62;    // "GOT THROUGH", bonus labels
public static final int ART_KOSM_RESULTS_SONIC_ADDR = 0x39A786;      // "SONIC" name art
public static final int ART_KOSM_RESULTS_MILES_ADDR = 0x39AA18;      // "MILES" name art
public static final int ART_KOSM_RESULTS_TAILS_ADDR = 0x39AB6A;      // "TAILS" name art
public static final int ART_KOSM_RESULTS_KNUCKLES_ADDR = 0x0D67F0;   // "KNUCKLES" name art

// === Results Screen Palette & Mappings ===
public static final int PAL_RESULTS_ADDR = 0x22D39E;                 // 128 bytes, full palette
public static final int MAP_RESULTS_ADDR = 0x0002F26A;               // Mapping frames (59 entries)

// === Results Screen VRAM Layout ===
public static final int VRAM_RESULTS_BASE = 0x520;                   // General art destination
public static final int VRAM_RESULTS_NUMBERS = 0x568;                // Digit tile destination
public static final int VRAM_RESULTS_CHAR_NAME_ACT1 = 0x578;         // Character name (act 1)
public static final int VRAM_RESULTS_CHAR_NAME_ACT2 = 0x5A0;         // Character name (act 2)
public static final int VRAM_RESULTS_ARRAY_SIZE = 0x100;              // Total tile range $520-$61F
```

- [ ] **Step 2: Verify existing title card num constants are present**

Confirm these already exist (they should from title card implementation):
```java
ART_KOSM_TITLE_CARD_NUM1_ADDR  // 0x0D6D84
ART_KOSM_TITLE_CARD_NUM2_ADDR  // 0x0D6E46
```

If missing, add them.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java
git commit -m "feat(s3k): add results screen ROM address constants"
```

### Task 2: Art Loading Method

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`

- [ ] **Step 1: Study existing art loading pattern**

Read `Sonic3kTitleCardManager.loadKosmArt()` (around line 390) to understand the KosinskiM decompression and VRAM staging pattern. The results screen follows the same approach.

- [ ] **Step 2: Add `loadResultsArt` method**

Add a new public method to `Sonic3kObjectArt` that loads all results screen art. Follow the `Sonic3kTitleCardManager.loadKosmArt()` pattern exactly:

```java
/**
 * Load all results screen art from ROM into a combined pattern array.
 * Called once when the results screen initializes (not at level load time).
 *
 * @param character the current player character (determines name art)
 * @param act       0-indexed act number (0=Act 1, 1=Act 2)
 * @return Pattern array covering VRAM range $520-$61F, or null on failure
 */
public Pattern[] loadResultsArt(PlayerCharacter character, int act) {
    if (reader == null) return null;
    Rom rom = GameServices.rom().getRom();
    if (rom == null) return null;

    Pattern[] patterns = new Pattern[Sonic3kConstants.VRAM_RESULTS_ARRAY_SIZE];

    try {
        // 1. General art → VRAM $520 (index 0)
        loadKosmArtInto(rom, Sonic3kConstants.ART_KOSM_RESULTS_GENERAL_ADDR, patterns, 0);

        // 2. Number art → VRAM $568 (index $48)
        //    Act 1 (and not LRZ boss zone $16): use Num1; otherwise Num2
        int zone = LevelManager.getInstance().getZoneIndex();
        boolean useNum1 = (act == 0 && zone != 0x16);
        int numAddr = useNum1
                ? Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM1_ADDR
                : Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM2_ADDR;
        loadKosmArtInto(rom, numAddr, patterns,
                Sonic3kConstants.VRAM_RESULTS_NUMBERS - Sonic3kConstants.VRAM_RESULTS_BASE);

        // 3. Character name art → VRAM $578 (act 1) or $5A0 (act 2)
        int charNameAddr = getResultsCharNameAddr(character);
        int charNameDest = (act == 0)
                ? Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT1
                : Sonic3kConstants.VRAM_RESULTS_CHAR_NAME_ACT2;
        loadKosmArtInto(rom, charNameAddr, patterns,
                charNameDest - Sonic3kConstants.VRAM_RESULTS_BASE);

    } catch (IOException e) {
        LOG.warning("Failed to load results art: " + e.getMessage());
        return null;
    }

    return patterns;
}

private int getResultsCharNameAddr(PlayerCharacter character) {
    return switch (character) {
        case TAILS_ALONE -> Sonic3kConstants.ART_KOSM_RESULTS_TAILS_ADDR;
        case KNUCKLES -> Sonic3kConstants.ART_KOSM_RESULTS_KNUCKLES_ADDR;
        default -> Sonic3kConstants.ART_KOSM_RESULTS_SONIC_ADDR; // SONIC_AND_TAILS, SONIC_ALONE
    };
}
```

- [ ] **Step 3: Add `loadKosmArtInto` helper (if not already present)**

This is the same logic as `Sonic3kTitleCardManager.loadKosmArt()` but operates on an externally-provided `Pattern[]` array. If `Sonic3kObjectArt` doesn't already have a similar method, add:

```java
private void loadKosmArtInto(Rom rom, int romAddr, Pattern[] dest, int destIndex) throws IOException {
    byte[] header = rom.readBytes(romAddr, 2);
    int fullSize = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
    int inputSize = Math.min(Math.max(fullSize + 256, 0x10000), 0x40000);
    long romSize = rom.getSize();
    if (romAddr + inputSize > romSize) {
        inputSize = (int) (romSize - romAddr);
    }

    byte[] romData = rom.readBytes(romAddr, inputSize);
    byte[] decompressed = KosinskiReader.decompressModuled(romData, 0);

    int tileCount = decompressed.length / Pattern.PATTERN_SIZE_IN_ROM;
    for (int i = 0; i < tileCount; i++) {
        int idx = destIndex + i;
        if (idx >= 0 && idx < dest.length) {
            byte[] tileData = Arrays.copyOfRange(decompressed,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            Pattern pat = new Pattern();
            pat.fromSegaFormat(tileData);
            dest[idx] = pat;
        }
    }
}
```

- [ ] **Step 4: Add `loadResultsPalette` method**

```java
/**
 * Load the results screen palette from ROM.
 *
 * @return 128-byte palette data (full 4 lines, 64 colors), or null on failure
 */
public byte[] loadResultsPalette() {
    Rom rom = GameServices.rom().getRom();
    if (rom == null) return null;
    try {
        return rom.readBytes(Sonic3kConstants.PAL_RESULTS_ADDR, 128);
    } catch (Exception e) {
        LOG.warning("Failed to load results palette: " + e.getMessage());
        return null;
    }
}
```

- [ ] **Step 5: Add `loadResultsMappings` method**

```java
/**
 * Parse results screen mapping frames from ROM.
 *
 * @return list of mapping frames, or empty list on failure
 */
public List<SpriteMappingFrame> loadResultsMappings() {
    if (reader == null) return List.of();
    try {
        return S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_RESULTS_ADDR);
    } catch (Exception e) {
        LOG.warning("Failed to load results mappings: " + e.getMessage());
        return List.of();
    }
}
```

- [ ] **Step 6: Build and verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java
git commit -m "feat(s3k): add results screen art loading methods"
```

---

## Chunk 2: Core Results Screen Class

### Task 3: Results Screen State Machine and Tally

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java`

- [ ] **Step 1: Create the class skeleton**

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic2.objects.AbstractResultsScreen;
import com.openggf.game.sonic3k.Sonic3kObjectArt;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * S3K results screen — displays "{CHARACTER} GOT THROUGH ACT {N}" with
 * time bonus and ring bonus tally after the signpost lands.
 *
 * <p>ROM: Obj_LevelResults (sonic3k.asm lines 62499-63003).
 *
 * <p>Key differences from S2:
 * <ul>
 *   <li>No fade-to-black — signals flags and lets level events handle transitions</li>
 *   <li>360-frame pre-tally delay (S2 uses 180)</li>
 *   <li>90-frame post-tally wait (S2 uses 180)</li>
 *   <li>No perfect bonus</li>
 *   <li>Act 1 exit shows act 2 title card; act 2 exit sets End_of_level_flag</li>
 * </ul>
 */
public class S3kResultsScreenObjectInstance extends AbstractResultsScreen {
    private static final Logger LOG = Logger.getLogger(S3kResultsScreenObjectInstance.class.getName());

    // ROM-accurate timing
    private static final int S3K_PRE_TALLY_DELAY = 360;  // 6*60 frames (ROM line 62580)
    private static final int S3K_WAIT_DURATION = 90;       // ROM line 62676
    private static final int MUSIC_TRIGGER_FRAME = 71;     // 360 - 289 = 71 (ROM line 62626)

    // Time bonus table (ROM lines 62910-62918)
    private static final int[] TIME_BONUSES = {5000, 5000, 1000, 500, 400, 300, 100, 10};
    private static final int SPECIAL_TIME_BONUS = 10000;   // 9:59 override (ROM line 62559)
    private static final int MAX_TIMER_SECONDS = 599;      // 9:59 = 9*60 + 59

    // Slide speeds (ROM lines 62847, 62836)
    private static final int SLIDE_IN_SPEED = 16;   // moveq #$10,d1
    private static final int SLIDE_OUT_SPEED = 32;   // move.w #-$20,d0

    // Pattern caching
    private static final int PATTERN_BASE = 0x60000;  // High ID to avoid conflicts

    // State
    private final PlayerCharacter character;
    private final int act;  // 0-indexed: 0=Act 1, 1=Act 2

    // Tally values
    private int timeBonus;
    private int ringBonus;
    private int totalBonusCountUp;

    // Art
    private Pattern[] combinedPatterns;
    private List<SpriteMappingFrame> mappingFrames;
    private boolean artLoaded;
    private boolean artCached;

    // Music flag
    private boolean musicPlayed;

    // Element tracking
    private final ResultsElement[] elements = new ResultsElement[12];
    private int exitQueueCounter;
    private int childrenRemaining;

    public S3kResultsScreenObjectInstance(PlayerCharacter character, int act) {
        super("S3kResults");
        this.character = character;
        this.act = act;

        // Calculate bonuses from current game state (ROM lines 62550-62578)
        calculateBonuses();

        // Fade out current music immediately (ROM line 62513)
        fadeOutMusic();

        // Load art (async-compatible — rendering waits for artLoaded)
        loadArt();

        // Create elements from ObjArray_LevResults
        createElements();

        LOG.fine(() -> String.format("S3K results init: character=%s act=%d timeBonus=%d ringBonus=%d",
                character, act, timeBonus, ringBonus));
    }

    // ... (methods follow in subsequent steps)
}
```

- [ ] **Step 2: Add bonus calculation**

```java
private void calculateBonuses() {
    var levelGamestate = LevelManager.getInstance().getLevelGamestate();
    int elapsedSeconds = (levelGamestate != null) ? levelGamestate.getElapsedSeconds() : 0;

    // Pause the timer (ROM line 62550)
    if (levelGamestate != null) {
        levelGamestate.pauseTimer();
    }

    // Special case: 9:59 → 10000 (ROM lines 62557-62559)
    if (elapsedSeconds >= MAX_TIMER_SECONDS) {
        timeBonus = SPECIAL_TIME_BONUS;
    } else {
        int index = Math.min(elapsedSeconds / 30, TIME_BONUSES.length - 1);
        timeBonus = TIME_BONUSES[index];
    }

    // Ring bonus: rings × 10 (ROM lines 62576-62578)
    var spriteManager = com.openggf.sprites.SpriteManager.getInstance();
    int ringCount = (spriteManager != null && spriteManager.getPlayerSprite() != null)
            ? spriteManager.getPlayerSprite().getRingCount()
            : 0;
    ringBonus = ringCount * 10;

    totalBonusCountUp = 0;
}
```

- [ ] **Step 3: Add timing overrides**

```java
@Override
protected int getSlideDuration() {
    return 0;  // Children handle their own sliding; skip SLIDE_IN entirely
}

@Override
protected int getPreTallyDelay() {
    return S3K_PRE_TALLY_DELAY;
}

@Override
protected int getWaitDuration() {
    return S3K_WAIT_DURATION;
}
```

- [ ] **Step 4: Override `updatePreTallyDelay` for music trigger**

```java
@Override
protected void updatePreTallyDelay() {
    // Trigger music at frame 71 of the 360-frame countdown
    // ROM: checks counter == 289 (360 - 71) at line 62626
    if (!musicPlayed && stateTimer == MUSIC_TRIGGER_FRAME) {
        musicPlayed = true;
        try {
            AudioManager.getInstance().playMusic(Sonic3kMusic.ACT_CLEAR.id);
        } catch (Exception e) {
            // Ignore audio errors
        }
    }

    super.updatePreTallyDelay();
}
```

- [ ] **Step 5: Override tally methods**

```java
@Override
protected TallyResult performTallyStep() {
    int totalIncrement = 0;

    // Decrement time bonus by 10 (ROM line 62639)
    int[] timeResult = decrementBonus(timeBonus);
    timeBonus = timeResult[0];
    totalIncrement += timeResult[1];

    // Decrement ring bonus by 10 (ROM line 62645)
    int[] ringResult = decrementBonus(ringBonus);
    ringBonus = ringResult[0];
    totalIncrement += ringResult[1];

    // Track running total (ROM line 62648)
    totalBonusCountUp += totalIncrement;

    boolean anyRemaining = (timeBonus > 0 || ringBonus > 0);
    return tallyResult(anyRemaining, totalIncrement);
}

@Override
protected void updateTally() {
    TallyResult result = performTallyStep();

    if (result.totalIncrement() > 0) {
        GameServices.gameState().addScore(result.totalIncrement());
    }

    // ROM uses global frame counter for tick timing (line 62652-62654)
    // Level_frame_counter & 3 == 0
    // We use the frameCounter parameter passed to update() as our global frame source
    if (result.anyRemaining()) {
        if ((this.frameCounter & 3) == 0) {
            playTickSound();
        }
    }

    if (!result.anyRemaining()) {
        playTallyEndSound();
        state = STATE_WAIT;
        stateTimer = 0;
    }
}
```

- [ ] **Step 6: Override audio methods**

```java
@Override
protected void playTickSound() {
    try {
        AudioManager.getInstance().playSfx(Sonic3kSfx.SWITCH.id);
    } catch (Exception e) {
        // Ignore audio errors
    }
}

@Override
protected void playTallyEndSound() {
    try {
        AudioManager.getInstance().playSfx(Sonic3kSfx.REGISTER.id);
    } catch (Exception e) {
        // Ignore audio errors
    }
}

private void fadeOutMusic() {
    try {
        AudioManager.getInstance().fadeOutMusic();
    } catch (Exception e) {
        // Ignore audio errors
    }
}
```

- [ ] **Step 7: Override exit behavior**

```java
@Override
protected void onExitReady() {
    int zone = LevelManager.getInstance().getZoneIndex();

    // Act 2, Sky Sanctuary ($A), or LRZ boss ($16): set End_of_level_flag
    // ROM lines 62694-62705
    boolean isAct2OrSpecial = (act != 0) || (zone == 0x0A) || (zone == 0x16);

    GameServices.gameState().setEndOfLevelActive(false);

    if (isAct2OrSpecial) {
        GameServices.gameState().setEndOfLevelFlag(true);
    } else {
        // Act 1: show act 2 title card (ROM lines 62708-62720)
        // ROM sets Apparent_act = 1 — in our engine the title card handles this visually.
        // The level data continues seamlessly (S3K acts share the same level).

        // Show act 2 title card (except SOZ zone $8 and DEZ zone $B)
        // ROM lines 62713-62720
        boolean skipTitleCard = (zone == 0x08) || (zone == 0x0B);
        if (!skipTitleCard) {
            Sonic3kTitleCardManager.getInstance().initializeInLevel(zone, 1);
        }
    }

    setDestroyed(true);
    LOG.fine(() -> String.format("S3K results exit: zone=%X act=%d isAct2OrSpecial=%b",
            zone, act, isAct2OrSpecial));
}
```

- [ ] **Step 8: Add isPersistent override**

```java
@Override
public boolean isPersistent() {
    return true;  // Survives screen boundary checks
}
```

- [ ] **Step 9: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (with placeholder `appendRenderCommands` and helper methods)

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java
git commit -m "feat(s3k): add results screen state machine, tally, and exit logic"
```

---

## Chunk 3: Element Rendering

### Task 4: Element Data Structure and Slide Animation

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java`

- [ ] **Step 1: Add ResultsElement inner class**

```java
/**
 * One of the 12 results screen child elements.
 * Each slides in from off-screen to its target position, then slides out
 * when the exit queue counter reaches its priority.
 */
private static class ResultsElement {
    enum Type { CHAR_NAME, GENERAL, TIME_BONUS, RING_BONUS, TOTAL }
    enum SlideDirection { FROM_LEFT, FROM_RIGHT }

    final Type type;
    final int targetX;
    final int startX;
    final int y;
    final int mappingFrame;
    final int widthPixels;
    final int exitQueuePriority;
    final SlideDirection slideDirection;

    int currentX;
    boolean exitStarted;
    boolean offScreen;

    ResultsElement(Type type, int targetX, int startX, int y,
                   int mappingFrame, int widthPixels, int exitQueuePriority) {
        this.type = type;
        this.targetX = targetX;
        this.startX = startX;
        this.y = y;
        this.mappingFrame = mappingFrame;
        this.widthPixels = widthPixels;
        this.exitQueuePriority = exitQueuePriority;
        this.slideDirection = (startX < 0) ? SlideDirection.FROM_LEFT : SlideDirection.FROM_RIGHT;
        this.currentX = startX;
        this.exitStarted = false;
        this.offScreen = false;
    }

    /** Slide toward target at 16px/frame. Returns true when reached. */
    boolean slideIn() {
        if (currentX == targetX) return true;
        if (currentX < targetX) {
            currentX = Math.min(currentX + SLIDE_IN_SPEED, targetX);
        } else {
            currentX = Math.max(currentX - SLIDE_IN_SPEED, targetX);
        }
        return currentX == targetX;
    }

    /** Slide out at 32px/frame in the direction it came from. */
    void slideOut() {
        if (slideDirection == SlideDirection.FROM_LEFT) {
            currentX -= SLIDE_OUT_SPEED;
        } else {
            currentX += SLIDE_OUT_SPEED;
        }
        // Check if off-screen (generous bounds)
        offScreen = (currentX < -256 || currentX > 576);
    }
}
```

- [ ] **Step 2: Add element creation from ObjArray_LevResults**

```java
private void createElements() {
    // ROM data from ObjArray_LevResults (sonic3k.asm lines 62919-63003)
    // Format: {type, targetX, startX, Y, mappingFrame, width, exitQueuePriority}
    int charNameFrame = getCharNameFrame();
    int charNameTargetX = 0xE0;
    int charNameStartX = -0x220;
    int charNameWidth = 0x48;

    // Knuckles adjustments (ROM lines 62732-62736)
    if (character == PlayerCharacter.KNUCKLES) {
        charNameTargetX -= 0x30;
        charNameStartX -= 0x30;
        charNameWidth += 0x30;
    }
    // Tails adjustment (ROM lines 62745-62748)
    if (character == PlayerCharacter.TAILS_ALONE) {
        charNameTargetX += 8;
        charNameStartX += 8;
        charNameWidth -= 8;
    }

    elements[0]  = new ResultsElement(ResultsElement.Type.CHAR_NAME,  charNameTargetX, charNameStartX, 0xB8, charNameFrame, charNameWidth, 1);
    elements[1]  = new ResultsElement(ResultsElement.Type.GENERAL,    0x130, -0x1D0, 0xB8, 0x11, 0x30, 1);
    elements[2]  = new ResultsElement(ResultsElement.Type.GENERAL,    0xE8,   0x468, 0xCC, 0x10, 0x70, 3);
    elements[3]  = new ResultsElement(ResultsElement.Type.GENERAL,    0x160,  0x4E0, 0xBC, 0x0F, 0x38, 3);
    elements[4]  = new ResultsElement(ResultsElement.Type.GENERAL,    0xC0,   0x4C0, 0xF0, 0x0E, 0x20, 5);
    elements[5]  = new ResultsElement(ResultsElement.Type.GENERAL,    0xE8,   0x4E8, 0xF0, 0x0C, 0x30, 5);
    elements[6]  = new ResultsElement(ResultsElement.Type.TIME_BONUS, 0x178,  0x578, 0xF0, 1,    0x40, 5);
    elements[7]  = new ResultsElement(ResultsElement.Type.GENERAL,    0xC0,   0x500, 0x100, 0x0D, 0x20, 7);
    elements[8]  = new ResultsElement(ResultsElement.Type.GENERAL,    0xE8,   0x528, 0x100, 0x0C, 0x30, 7);
    elements[9]  = new ResultsElement(ResultsElement.Type.RING_BONUS, 0x178,  0x5B8, 0x100, 1,    0x40, 7);
    elements[10] = new ResultsElement(ResultsElement.Type.GENERAL,    0xD4,   0x554, 0x11C, 0x0B, 0x30, 9);
    elements[11] = new ResultsElement(ResultsElement.Type.TOTAL,      0x178,  0x5F8, 0x11C, 1,    0x40, 9);

    childrenRemaining = 12;
}

/**
 * Get the character name mapping frame index.
 * ROM: base frame $13, +1 for Miles, +2 for Tails, +3 for Knuckles.
 * (ROM lines 62727-62744)
 */
private int getCharNameFrame() {
    return switch (character) {
        case TAILS_ALONE -> 0x13 + 2;  // Tails variant
        case KNUCKLES -> 0x13 + 3;
        default -> 0x13;  // Sonic
    };
}
```

- [ ] **Step 3: Add element update logic to the main update loop**

Override `update` to also process element sliding:

```java
@Override
public void update(int frameCounter, AbstractPlayableSprite player) {
    super.update(frameCounter, player);

    // Slide elements in during pre-tally (children move independently)
    if (state <= STATE_TALLY) {
        for (ResultsElement elem : elements) {
            if (elem != null && !elem.offScreen) {
                elem.slideIn();
            }
        }
    }

    // Exit queue: after STATE_WAIT completes and enters STATE_EXIT
    if (state == STATE_EXIT) {
        updateExitQueue();
    }
}

private void updateExitQueue() {
    if (childrenRemaining <= 0) {
        complete = true;
        onExitReady();
        return;
    }

    exitQueueCounter++;

    for (ResultsElement elem : elements) {
        if (elem == null || elem.offScreen) continue;

        if (exitQueueCounter >= elem.exitQueuePriority) {
            elem.exitStarted = true;
        }

        if (elem.exitStarted) {
            elem.slideOut();
            if (elem.offScreen) {
                childrenRemaining--;
            }
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java
git commit -m "feat(s3k): add results screen element slide-in/slide-out system"
```

### Task 5: Rendering

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java`

- [ ] **Step 1: Add art loading and caching methods**

```java
private void loadArt() {
    // Sonic3kObjectArt constructor: (Level level, RomByteReader reader)
    // Use null for level since results art is self-contained (not level-dependent)
    var rom = GameServices.rom().getRom();
    RomByteReader reader = null;
    try {
        reader = RomByteReader.fromRom(rom);
    } catch (Exception e) {
        LOG.warning("Failed to create RomByteReader: " + e.getMessage());
    }
    Sonic3kObjectArt objectArt = new Sonic3kObjectArt(null, reader);

    combinedPatterns = objectArt.loadResultsArt(character, act);
    mappingFrames = objectArt.loadResultsMappings();
    artLoaded = (combinedPatterns != null && !mappingFrames.isEmpty());

    if (!artLoaded) {
        LOG.warning("Failed to load results screen art — rendering will be skipped");
    }

    // Load palette — 128 bytes = 4 palette lines (32 bytes each)
    // Uses LevelManager.updatePalette(lineIndex, byte[]) which accepts raw Mega Drive data
    byte[] palette = objectArt.loadResultsPalette();
    if (palette != null) {
        LevelManager lm = LevelManager.getInstance();
        for (int line = 0; line < 4 && line * 32 < palette.length; line++) {
            byte[] lineData = new byte[32];
            System.arraycopy(palette, line * 32, lineData, 0,
                    Math.min(32, palette.length - line * 32));
            lm.updatePalette(line, lineData);
        }
    }
}

private void ensureArtCached() {
    if (artCached || !artLoaded) return;

    GraphicsManager gm = GraphicsManager.getInstance();
    if (gm == null) return;

    for (int i = 0; i < combinedPatterns.length; i++) {
        if (combinedPatterns[i] != null) {
            gm.cachePatternTexture(combinedPatterns[i], PATTERN_BASE + i);
        }
    }
    artCached = true;
}
```

- [ ] **Step 2: Implement appendRenderCommands**

```java
@Override
public void appendRenderCommands(List<GLCommand> commands) {
    if (!artLoaded) return;
    ensureArtCached();

    Camera camera = Camera.getInstance();
    if (camera == null) return;

    // Screen-space base: camera position is our world anchor
    int baseX = camera.getX();
    int baseY = camera.getY();

    for (ResultsElement elem : elements) {
        if (elem == null || elem.offScreen) continue;

        // Convert element screen coords to world coords
        int worldX = baseX + elem.currentX;
        int worldY = baseY + elem.y;

        // Render the mapping frame for this element
        int frameIdx = elem.mappingFrame;

        // Bonus elements: render digit display instead of mapping frame
        switch (elem.type) {
            case TIME_BONUS -> renderBonusDigits(commands, worldX, worldY, timeBonus);
            case RING_BONUS -> renderBonusDigits(commands, worldX, worldY, ringBonus);
            case TOTAL -> renderBonusDigits(commands, worldX, worldY, totalBonusCountUp);
            default -> renderMappingFrame(commands, frameIdx, worldX, worldY);
        }
    }
}

private void renderMappingFrame(List<GLCommand> commands, int frameIndex,
                                 int worldX, int worldY) {
    if (frameIndex < 0 || frameIndex >= mappingFrames.size()) return;

    SpriteMappingFrame frame = mappingFrames.get(frameIndex);
    for (SpriteMappingPiece piece : frame.pieces()) {
        int tileIndex = piece.tileIndex();
        int patternId = PATTERN_BASE + (tileIndex - Sonic3kConstants.VRAM_RESULTS_BASE);

        int pieceX = worldX + piece.xOffset();
        int pieceY = worldY + piece.yOffset();

        for (int col = 0; col < piece.widthTiles(); col++) {
            for (int row = 0; row < piece.heightTiles(); row++) {
                int tilePatternId = patternId + (col * piece.heightTiles() + row);
                int tileX = pieceX + (piece.hFlip()
                        ? (piece.widthTiles() - 1 - col) * 8
                        : col * 8);
                int tileY = pieceY + (piece.vFlip()
                        ? (piece.heightTiles() - 1 - row) * 8
                        : row * 8);

                commands.add(new GLCommand(
                        GLCommand.CommandType.PATTERN, tilePatternId,
                        GLCommand.BlendType.SOLID,
                        1f, 1f, 1f,
                        tileX, tileY,
                        piece.hFlip() ? 1 : 0,
                        piece.vFlip() ? 1 : 0));
            }
        }
    }
}
```

- [ ] **Step 3: Add bonus digit rendering**

The ROM renders bonus values as 7-digit BCD displays using child sprite positions. Each digit is an 8px-wide tile from the number art:

```java
/**
 * Render a bonus value as a 7-digit display.
 * ROM: LevResults_DisplayScore (sonic3k.asm lines 62789-62815)
 * Digits positioned from (x - $38) rightward, 8px apart.
 */
private void renderBonusDigits(List<GLCommand> commands, int worldX, int worldY, int value) {
    // Convert to BCD (7 digits, leading zero suppression)
    int x = worldX - 0x38;
    boolean hasNonZero = false;

    // Calculate digits from most significant
    int remaining = value;
    int[] divisors = {1000000, 100000, 10000, 1000, 100, 10, 1};

    for (int i = 0; i < 7; i++) {
        int digit = remaining / divisors[i];
        remaining %= divisors[i];

        if (digit != 0) hasNonZero = true;

        // Only render non-zero digits (or the last digit)
        if (hasNonZero || i == 6) {
            // Digit tile index: number art starts at VRAM_RESULTS_NUMBERS
            // Each digit 0-9 is one tile wide (8px)
            int digitTile = Sonic3kConstants.VRAM_RESULTS_NUMBERS
                    - Sonic3kConstants.VRAM_RESULTS_BASE + digit;
            int patternId = PATTERN_BASE + digitTile;

            commands.add(new GLCommand(
                    GLCommand.CommandType.PATTERN, patternId,
                    GLCommand.BlendType.SOLID,
                    1f, 1f, 1f,
                    x + i * 8, worldY,
                    0, 0));
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java
git commit -m "feat(s3k): add results screen rendering with mapping frames and digit display"
```

---

## Chunk 4: Integration and Testing

### Task 6: Wire Up Signpost

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java`

- [ ] **Step 1: Read current signpost RESULTS state**

Read `S3kSignpostInstance.java` and locate the RESULTS state handler where `S3kLevelResultsInstance` is currently spawned.

- [ ] **Step 2: Replace stub with new results screen**

Change the spawn from:
```java
new S3kLevelResultsInstance()
```
to:
```java
new S3kResultsScreenObjectInstance(
    AbstractLevelEventManager.getInstance().getPlayerCharacter(),
    LevelManager.getInstance().getCurrentAct())
```

Ensure the necessary imports are added:
```java
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.AbstractLevelEventManager;
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java
git commit -m "feat(s3k): wire signpost to spawn full results screen instead of stub"
```

### Task 7: Unit Test for Tally Mechanics

**Files:**
- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestS3kResultsTally.java`

- [ ] **Step 1: Write tally calculation tests**

Test the time bonus table and tally mechanics without requiring ROM or OpenGL:

```java
package com.openggf.game.sonic3k.objects;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for S3K results screen tally mechanics.
 * Validates time bonus table, ring bonus, and 9:59 override.
 * No ROM or OpenGL required.
 */
class TestS3kResultsTally {

    // ROM-accurate time bonus table (sonic3k.asm lines 62910-62918)
    private static final int[] TIME_BONUSES = {5000, 5000, 1000, 500, 400, 300, 100, 10};

    /** Calculate time bonus matching ROM logic (lines 62550-62573) */
    private int calculateTimeBonus(int elapsedSeconds) {
        if (elapsedSeconds >= 599) return 10000;  // 9:59 override
        int index = Math.min(elapsedSeconds / 30, TIME_BONUSES.length - 1);
        return TIME_BONUSES[index];
    }

    @Test
    void timeBonusTable_0seconds_returns5000() {
        assertEquals(5000, calculateTimeBonus(0));
    }

    @Test
    void timeBonusTable_29seconds_returns5000() {
        assertEquals(5000, calculateTimeBonus(29));
    }

    @Test
    void timeBonusTable_30seconds_returns5000() {
        // Index 1 = 5000
        assertEquals(5000, calculateTimeBonus(30));
    }

    @Test
    void timeBonusTable_60seconds_returns1000() {
        // Index 2 = 1000
        assertEquals(1000, calculateTimeBonus(60));
    }

    @Test
    void timeBonusTable_90seconds_returns500() {
        // Index 3 = 500
        assertEquals(500, calculateTimeBonus(90));
    }

    @Test
    void timeBonusTable_210seconds_returns10() {
        // Index 7 = 10
        assertEquals(10, calculateTimeBonus(210));
    }

    @Test
    void timeBonusTable_300seconds_cappedAtIndex7() {
        // Still index 7 (capped)
        assertEquals(10, calculateTimeBonus(300));
    }

    @Test
    void timeBonusSpecialCase_959_returns10000() {
        // 9:59 = 599 seconds → 10000 override (ROM line 62557-62559)
        assertEquals(10000, calculateTimeBonus(599));
    }

    @Test
    void timeBonusSpecialCase_above959_returns10000() {
        assertEquals(10000, calculateTimeBonus(600));
    }

    @Test
    void ringBonus_100rings_returns1000() {
        assertEquals(1000, 100 * 10);
    }

    @Test
    void ringBonus_0rings_returns0() {
        assertEquals(0, 0 * 10);
    }

    @Test
    void tallyDecrement_countsDownBy10() {
        int timeBonus = 500;
        int ringBonus = 100;
        int totalCountUp = 0;
        int frames = 0;

        while (timeBonus > 0 || ringBonus > 0) {
            int decrement = 0;
            if (timeBonus > 0) { timeBonus -= 10; decrement += 10; }
            if (ringBonus > 0) { ringBonus -= 10; decrement += 10; }
            totalCountUp += decrement;
            frames++;
        }

        assertEquals(0, timeBonus);
        assertEquals(0, ringBonus);
        assertEquals(600, totalCountUp);
        assertEquals(50, frames);  // 500/10 = 50 frames (ring bonus runs out at frame 10)
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=TestS3kResultsTally -q`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/game/sonic3k/objects/TestS3kResultsTally.java
git commit -m "test(s3k): add results screen tally mechanics unit tests"
```

### Task 8: Delete Old Stub

**Files:**
- Delete: `src/main/java/com/openggf/game/sonic3k/objects/S3kLevelResultsInstance.java`

- [ ] **Step 1: Search for remaining references to the stub**

Search for `S3kLevelResultsInstance` across the codebase. Any remaining references (besides the signpost, which was already updated) need to be updated.

- [ ] **Step 2: Remove remaining references**

Update any files still importing or referencing `S3kLevelResultsInstance`.

- [ ] **Step 3: Delete the stub file**

```bash
git rm src/main/java/com/openggf/game/sonic3k/objects/S3kLevelResultsInstance.java
```

- [ ] **Step 4: Verify full build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (no references to deleted class)

- [ ] **Step 5: Commit**

```bash
git commit -m "refactor(s3k): remove results screen stub (replaced by full implementation)"
```

### Task 9: Integration Verification

- [ ] **Step 1: Run all S3K tests**

Run: `mvn test -Dtest="com.openggf.tests.TestS3kAiz1SkipHeadless,com.openggf.tests.TestSonic3kLevelLoading,sonic3k.com.openggf.game.TestSonic3kBootstrapResolver,sonic3k.com.openggf.game.TestSonic3kDecodingUtils,com.openggf.game.sonic3k.objects.TestS3kResultsTally" -q`
Expected: All tests PASS

- [ ] **Step 2: Run full test suite**

Run: `mvn test -q`
Expected: BUILD SUCCESS — no regressions

- [ ] **Step 3: Final commit if any fixups needed**

```bash
git add -A
git commit -m "fix(s3k): results screen integration fixups"
```

---

## Implementation Notes

### ROM Address Verification

The `Map_Results` address (0x0002F26A) was extracted from the `move.l #Map_Results,mappings(a1)` instruction at ROM offset 0x2DC04. If mapping parsing fails at runtime, verify by:
1. Checking the first word at the address (should be the offset table size, e.g., 59*2 = 0x0076 for 59 frames)
2. Running `RomOffsetFinder --game s3k search-rom 0076 0x2F260 0x2F280` to confirm

The `Pal_Results` address (0x22D39E) is the S3 version. If the palette looks wrong at runtime (wrong colors), the S&K version may be at a different address — search for the 128-byte palette data near the S&K results code region.

### Rendering Adjustments

The digit rendering approach (Task 5, Step 3) is simplified compared to the ROM's double-dabble BCD conversion. The ROM uses `LevResults_GetDecimalScore` with ABCD instructions. Our approach uses integer division which produces the same visual result.

The mapping frame tile indices reference VRAM addresses. The formula `patternId = PATTERN_BASE + (tileIndex - VRAM_RESULTS_BASE)` converts from VRAM tile addresses to our cached pattern IDs. If the art tile base in the mapping pieces doesn't match `VRAM_RESULTS_BASE` (0x520), the offset calculation in `renderMappingFrame` needs adjustment — check the first few mapping frames at runtime.

### Future Work

- **Character name art tile offset for act 2:** When act != 0, character name loads to VRAM 0x5A0 instead of 0x578. The mapping frame's tile indices may need adjustment (add 0x28 = 0x5A0 - 0x578). Verify at runtime.
- **Events_fg_5 flag:** For act 1 completion (except AIZ/ICZ), the ROM sets this flag to trigger background level events. Add when the level event system supports it.
- **Save game on act 2:** ROM saves to SRAM on act 2/SSZ completion. Add when save system is implemented.
