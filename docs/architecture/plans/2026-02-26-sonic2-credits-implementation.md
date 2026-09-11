# Sonic 2 Credits & Ending Sequence Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement the full Sonic 2 ending sequence (cutscene → 21 credit slides → logo flash → title return) behind a game-agnostic EndingProvider interface, refactoring S1 credits to use the same pattern.

**Architecture:** EndingProvider interface on GameModule abstracts ending/credits lifecycle. GameLoop calls provider polymorphically. S1EndingProvider wraps existing managers. S2EndingProvider manages cutscene, credits text, and logo flash phases.

**Tech Stack:** Java 21, OpenGL (GLFW), Nemesis/Enigma decompression, SMPS audio, PatternSpriteRenderer

**Design doc:** `docs/architecture/designs/2026-02-26-sonic2-credits-design.md`

---

## Phase 1: Foundation — Interface & S1 Refactoring

### Task 1: Create EndingPhase Enum and EndingProvider Interface

**Files:**
- Create: `src/main/java/com/openggf/game/EndingPhase.java`
- Create: `src/main/java/com/openggf/game/EndingProvider.java`

**Step 1: Create EndingPhase enum**

```java
package com.openggf.game;

public enum EndingPhase {
    CUTSCENE,
    CREDITS_TEXT,
    CREDITS_DEMO,
    POST_CREDITS,
    FINISHED
}
```

**Step 2: Create EndingProvider interface**

```java
package com.openggf.game;

public interface EndingProvider {
    void initialize();
    void update();
    void draw();
    EndingPhase getCurrentPhase();
    boolean isComplete();

    // S1 demo support — default no-ops for games without demo playback
    default boolean hasDemoLoadRequest() { return false; }
    default void consumeDemoLoadRequest() {}
    default int getDemoZone() { return 0; }
    default int getDemoAct() { return 0; }
    default int getDemoStartX() { return 0; }
    default int getDemoStartY() { return 0; }
    default void onDemoZoneLoaded() {}
    default int getDemoInputMask() { return 0; }
    default boolean isScrollFrozen() { return false; }
    default boolean hasTextReturnRequest() { return false; }
    default void consumeTextReturnRequest() {}
}
```

**Step 3: Build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/EndingPhase.java src/main/java/com/openggf/game/EndingProvider.java
git commit -m "feat: add EndingProvider interface and EndingPhase enum"
```

---

### Task 2: Add ENDING_CUTSCENE to GameMode

**Files:**
- Modify: `src/main/java/com/openggf/game/GameMode.java` (add after TRY_AGAIN_END, line ~36)

**Step 1: Add enum value**

Add `ENDING_CUTSCENE` after `TRY_AGAIN_END`:

```java
ENDING_CUTSCENE
```

**Step 2: Build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/GameMode.java
git commit -m "feat: add ENDING_CUTSCENE game mode"
```

---

### Task 3: Add getEndingProvider() to GameModule

**Files:**
- Modify: `src/main/java/com/openggf/game/GameModule.java` (add new provider method)
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java` (return null initially)
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java` (return null initially)
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java` (return null initially, if it exists)

**Step 1: Add default method to GameModule**

In `GameModule.java`, add after the last provider method (around line 317):

```java
/**
 * Returns the ending/credits provider for this game, or null if not yet implemented.
 */
default EndingProvider getEndingProvider() {
    return null;
}
```

**Step 2: Build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (default method, no implementations needed yet)

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/GameModule.java
git commit -m "feat: add getEndingProvider() to GameModule interface"
```

---

### Task 4: Create Sonic1EndingProvider

This wraps the existing `Sonic1CreditsManager` and `TryAgainEndManager` into the new interface without changing their internals.

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/credits/Sonic1EndingProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java` (return new provider)

**Step 1: Create Sonic1EndingProvider**

Study the existing flow in GameLoop.java lines 1804-2143. The provider needs to wrap:
- `Sonic1CreditsManager` states → `EndingPhase` mapping
- `TryAgainEndManager` → `POST_CREDITS` phase
- Demo load/return requests passthrough

```java
package com.openggf.game.sonic1.credits;

import com.openggf.game.EndingPhase;
import com.openggf.game.EndingProvider;

public class Sonic1EndingProvider implements EndingProvider {
    private Sonic1CreditsManager creditsManager;
    private TryAgainEndManager tryAgainEndManager;
    private EndingPhase currentPhase = EndingPhase.CREDITS_TEXT;
    private boolean finished = false;

    // Delegating flags
    private boolean demoLoadRequested = false;
    private boolean textReturnRequested = false;
    private boolean tryAgainExitRequested = false;

    @Override
    public void initialize() {
        creditsManager = new Sonic1CreditsManager();
        creditsManager.initialize();
        currentPhase = EndingPhase.CREDITS_TEXT;
    }

    @Override
    public void update() {
        if (currentPhase == EndingPhase.POST_CREDITS) {
            // TryAgainEndManager updated by GameLoop (needs InputHandler)
            return;
        }
        if (creditsManager != null) {
            creditsManager.update();
            syncPhaseFromCreditsManager();
        }
    }

    private void syncPhaseFromCreditsManager() {
        if (creditsManager.consumeFinishedRequest()) {
            // Credits done → transition to TRY AGAIN/END
            currentPhase = EndingPhase.POST_CREDITS;
            return;
        }
        if (creditsManager.consumeDemoLoadRequest()) {
            demoLoadRequested = true;
            currentPhase = EndingPhase.CREDITS_DEMO;
            return;
        }
        if (creditsManager.consumeTextReturnRequest()) {
            textReturnRequested = true;
            currentPhase = EndingPhase.CREDITS_TEXT;
            return;
        }
        // Map internal state to phase
        Sonic1CreditsManager.State state = creditsManager.getState();
        currentPhase = switch (state) {
            case TEXT_FADE_IN, TEXT_DISPLAY, TEXT_FADE_OUT -> EndingPhase.CREDITS_TEXT;
            case DEMO_LOADING, DEMO_FADE_IN, DEMO_PLAYING, DEMO_FADING_OUT -> EndingPhase.CREDITS_DEMO;
            case FINISHED -> EndingPhase.POST_CREDITS;
        };
    }

    @Override
    public void draw() {
        if (currentPhase == EndingPhase.CREDITS_TEXT && creditsManager != null) {
            creditsManager.drawCreditText();
        }
        if (currentPhase == EndingPhase.POST_CREDITS && tryAgainEndManager != null) {
            tryAgainEndManager.draw();
        }
    }

    @Override
    public EndingPhase getCurrentPhase() {
        return currentPhase;
    }

    @Override
    public boolean isComplete() {
        return finished;
    }

    // --- S1 demo support ---

    @Override
    public boolean hasDemoLoadRequest() {
        boolean req = demoLoadRequested;
        return req;
    }

    @Override
    public void consumeDemoLoadRequest() {
        demoLoadRequested = false;
    }

    @Override
    public int getDemoZone() {
        return creditsManager != null ? creditsManager.getDemoZone() : 0;
    }

    @Override
    public int getDemoAct() {
        return creditsManager != null ? creditsManager.getDemoAct() : 0;
    }

    @Override
    public int getDemoStartX() {
        return creditsManager != null ? creditsManager.getDemoStartX() : 0;
    }

    @Override
    public int getDemoStartY() {
        return creditsManager != null ? creditsManager.getDemoStartY() : 0;
    }

    @Override
    public void onDemoZoneLoaded() {
        if (creditsManager != null) {
            creditsManager.onDemoZoneLoaded();
        }
    }

    @Override
    public int getDemoInputMask() {
        return creditsManager != null ? creditsManager.getDemoInputMask() : 0;
    }

    @Override
    public boolean isScrollFrozen() {
        return creditsManager != null && creditsManager.isScrollFrozen();
    }

    @Override
    public boolean hasTextReturnRequest() {
        boolean req = textReturnRequested;
        return req;
    }

    @Override
    public void consumeTextReturnRequest() {
        textReturnRequested = false;
    }

    // --- TryAgainEnd delegation ---

    public void initializeTryAgainEnd() {
        tryAgainEndManager = new TryAgainEndManager();
        tryAgainEndManager.initialize();
    }

    public TryAgainEndManager getTryAgainEndManager() {
        return tryAgainEndManager;
    }

    public Sonic1CreditsManager getCreditsManager() {
        return creditsManager;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }
}
```

**Note:** This is a *wrapper* — the exact method signatures depend on what `Sonic1CreditsManager` exposes. Read that file carefully and adapt. The key methods to delegate are: `getDemoZone()`, `getDemoAct()`, `getDemoStartX()`, `getDemoStartY()`, `getDemoInputMask()`, `isScrollFrozen()`, `consumeDemoLoadRequest()`, `consumeTextReturnRequest()`, `consumeFinishedRequest()`. Some of these may need to be added or renamed on the manager.

**Step 2: Wire into Sonic1GameModule**

In `Sonic1GameModule.java`, add:

```java
@Override
public EndingProvider getEndingProvider() {
    return new Sonic1EndingProvider();
}
```

Add imports for `EndingProvider` and `Sonic1EndingProvider`.

**Step 3: Build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/credits/Sonic1EndingProvider.java \
        src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java
git commit -m "feat: create Sonic1EndingProvider wrapping existing credits managers"
```

---

### Task 5: Refactor GameLoop to Use EndingProvider

This is the largest refactoring task. Replace the ~10 S1-specific credits methods in `GameLoop.java` with provider-based dispatch.

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java` (lines ~76-96 fields, ~392-404 step dispatch, ~442-445 credits request, ~1804-2143 all credits methods)

**Step 1: Read existing GameLoop credits code thoroughly**

Read `GameLoop.java` lines 76-96 (fields), 392-445 (step dispatch + credits request), 1804-2143 (all credits methods). Understand every callback, fade, and mode transition.

**Step 2: Add EndingProvider field and entry point**

Replace the S1-specific fields:
```java
// OLD:
private Sonic1CreditsManager creditsManager;
private TryAgainEndManager tryAgainEndManager;

// NEW:
private EndingProvider endingProvider;
```

Replace `consumeCreditsRequest()` handling (line ~442):
```java
// OLD:
if (levelManager.consumeCreditsRequest()) {
    startCreditsFade();
    return;
}

// NEW:
if (levelManager.consumeCreditsRequest()) {
    startEndingFade();
    return;
}
```

**Step 3: Replace startCreditsFade + doEnterCredits**

```java
private void startEndingFade() {
    FadeManager.getInstance().startFadeToBlack(() -> doEnterEnding());
}

private void doEnterEnding() {
    EndingProvider provider = GameModuleRegistry.getCurrent().getEndingProvider();
    if (provider == null) {
        // Fallback: return to title
        setGameMode(GameMode.TITLE_SCREEN);
        return;
    }
    endingProvider = provider;
    endingProvider.initialize();

    EndingPhase phase = endingProvider.getCurrentPhase();
    setGameMode(gameModeForPhase(phase));
    FadeManager.getInstance().startFadeFromBlack(null);
}
```

**Step 4: Create unified ending update dispatch**

Replace the three separate mode branches in `step()` (lines ~392-403) with:

```java
if (currentGameMode == GameMode.CREDITS_TEXT
        || currentGameMode == GameMode.CREDITS_DEMO
        || currentGameMode == GameMode.TRY_AGAIN_END
        || currentGameMode == GameMode.ENDING_CUTSCENE) {
    updateEnding();
    return;
}
```

Then implement `updateEnding()` to delegate to the provider:

```java
private void updateEnding() {
    if (endingProvider == null) return;

    EndingPhase prevPhase = endingProvider.getCurrentPhase();
    endingProvider.update();
    EndingPhase newPhase = endingProvider.getCurrentPhase();

    if (endingProvider.isComplete()) {
        fadeToTitleScreen();
        return;
    }

    // Handle phase transitions
    if (newPhase != prevPhase) {
        handleEndingPhaseTransition(prevPhase, newPhase);
    }

    // Phase-specific per-frame work
    switch (newPhase) {
        case CREDITS_DEMO -> updateEndingDemo();
        case POST_CREDITS -> updateEndingPostCredits();
        default -> {} // CUTSCENE and CREDITS_TEXT handled by provider.update()/draw()
    }

    // Check for demo load/return requests
    if (endingProvider.hasDemoLoadRequest()) {
        endingProvider.consumeDemoLoadRequest();
        loadEndingDemoZone();
    }
    if (endingProvider.hasTextReturnRequest()) {
        endingProvider.consumeTextReturnRequest();
        returnFromEndingDemo();
    }
}
```

**Step 5: Migrate demo/postCredits methods**

Adapt `loadCreditsDemoZone()` → `loadEndingDemoZone()` to read zone/act/startPos from provider instead of Sonic1CreditsDemoData directly. Similarly adapt `returnToCreditsText()` → `returnFromEndingDemo()`.

Adapt `exitCreditsToTryAgainEnd()`/`doEnterTryAgainEnd()`/`updateTryAgainEnd()`/`exitTryAgainEndToTitleScreen()` to work through provider's POST_CREDITS phase. For S1, the provider's `Sonic1EndingProvider.initializeTryAgainEnd()` handles setup.

For S2, the POST_CREDITS phase is the logo flash, handled entirely by `Sonic2EndingProvider.update()`/`draw()` — no GameLoop-level methods needed.

**Step 6: Migrate getters for Engine.java rendering**

Replace `getCreditsManager()` and `getTryAgainEndManager()` with:

```java
public EndingProvider getEndingProvider() {
    return endingProvider;
}
```

**Step 7: Update Engine.java display()**

In `Engine.java` lines ~754-772, update credits rendering to use provider:

```java
case CREDITS_TEXT, ENDING_CUTSCENE -> {
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    EndingProvider provider = gameLoop.getEndingProvider();
    if (provider != null) {
        provider.draw();
    }
}
case TRY_AGAIN_END -> {
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    EndingProvider provider = gameLoop.getEndingProvider();
    if (provider != null) {
        provider.draw();
    }
}
```

**Step 8: Helper method**

```java
private GameMode gameModeForPhase(EndingPhase phase) {
    return switch (phase) {
        case CUTSCENE -> GameMode.ENDING_CUTSCENE;
        case CREDITS_TEXT -> GameMode.CREDITS_TEXT;
        case CREDITS_DEMO -> GameMode.CREDITS_DEMO;
        case POST_CREDITS -> GameMode.TRY_AGAIN_END;
        case FINISHED -> GameMode.TITLE_SCREEN;
    };
}
```

**Step 9: Build and test**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

Run: `mvn test -q`
Expected: All existing tests pass (S1 credits regression)

**Step 10: Commit**

```bash
git add src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/Engine.java
git commit -m "refactor: replace hardcoded S1 credits with EndingProvider dispatch in GameLoop"
```

---

### Task 6: S1 Credits Regression Verification

**Step 1: Run all tests**

Run: `mvn test -q`
Expected: All tests pass

**Step 2: Run S1 credits-specific test**

Run: `mvn test -Dtest=TestSbz1CreditsDemoBug -q`
Expected: PASS

**Step 3: Manual smoke test (if possible)**

If you have the S1 ROM, run the game, complete FBZ or use debug to trigger credits, and verify text/demo/TryAgainEnd still works correctly. This is optional but recommended.

---

## Phase 2: S2 ROM Data

### Task 7: Find and Add S2 Ending/Credits ROM Constants

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/constants/Sonic2Constants.java`

**Step 1: Use RomOffsetFinder to locate ending assets**

Run these commands to find ROM addresses for ending-related assets. Cross-reference with the disassembly labels:

```bash
# Search for ending-related items
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=search ending" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=search credit" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=search tornado" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=search cloud" -q
```

Also search the disassembly directly for key labels:
- `ArtNem_EndingSonic` / `ArtNem_EndingSuperSonic` / `ArtNem_EndingTails`
- `ArtNem_EndingFinalTornado` / `ArtNem_EndingPics` / `ArtNem_EndingMiniTornado`
- `ArtNem_EndingTitle` (Sonic 2 logo at end of credits)
- `ArtNem_Clouds`
- `MapEng_Ending1` through `MapEng_Ending4` (Enigma photo mappings)
- `MapEng_EndGameLogo` (Enigma logo mapping)
- `Pal_AC7E` through `Pal_AD3E` (ending palettes)
- `pal_A0FE` / `Ending Cycle.bin` (logo flash palette)
- `off_B2CA` (credits screen pointer table)
- `byte_A0EC` (logo flash strobe sequence)
- `ArtNem_Flicky` / `ArtNem_Eagle` / `ArtNem_Chicken` (animal art for ending)

**Step 2: Add constants to Sonic2Constants.java**

Add a clearly-marked section after existing constants:

```java
// === Ending Sequence Art ===
public static final int ART_NEM_ENDING_SONIC_ADDR = 0x??????;
public static final int ART_NEM_ENDING_SUPER_SONIC_ADDR = 0x??????;
public static final int ART_NEM_ENDING_TAILS_ADDR = 0x??????;
public static final int ART_NEM_ENDING_FINAL_TORNADO_ADDR = 0x??????;
public static final int ART_NEM_ENDING_PICS_ADDR = 0x??????;
public static final int ART_NEM_ENDING_MINI_TORNADO_ADDR = 0x??????;
public static final int ART_NEM_ENDING_TITLE_ADDR = 0x??????;
public static final int ART_NEM_CLOUDS_ADDR = 0x??????;
public static final int ART_NEM_FLICKY_ADDR = 0x??????;
public static final int ART_NEM_EAGLE_ADDR = 0x??????;
public static final int ART_NEM_CHICKEN_ADDR = 0x??????;

// === Ending Sequence Mappings (Enigma) ===
public static final int MAP_ENI_ENDING_1_ADDR = 0x??????;
public static final int MAP_ENI_ENDING_2_ADDR = 0x??????;
public static final int MAP_ENI_ENDING_3_ADDR = 0x??????;
public static final int MAP_ENI_ENDING_4_ADDR = 0x??????;
public static final int MAP_ENI_END_GAME_LOGO_ADDR = 0x??????;

// === Ending Sequence Palettes ===
public static final int PAL_ENDING_SONIC_ADDR = 0x??????;
public static final int PAL_ENDING_SUPER_SONIC_ADDR = 0x??????;
public static final int PAL_ENDING_TAILS_ADDR = 0x??????;
public static final int PAL_ENDING_BG_ADDR = 0x??????;
public static final int PAL_ENDING_PHOTOS_ADDR = 0x??????;
public static final int PAL_ENDING_CYCLE_ADDR = 0x??????;

// === Credits Data ===
public static final int CREDITS_SCREEN_PTR_TABLE_ADDR = 0xB2CA;
public static final int CREDITS_LOGO_STROBE_SEQ_ADDR = 0xA0EC;
```

Fill in the `??????` values from RomOffsetFinder results and disassembly cross-reference.

**Step 3: Build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/constants/Sonic2Constants.java
git commit -m "feat: add S2 ending and credits ROM address constants"
```

---

### Task 8: Create Sonic2CreditsData

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/credits/Sonic2CreditsData.java`
- Create: `src/test/java/com/openggf/game/sonic2/credits/TestSonic2CreditsData.java`

**Step 1: Write test for credits screen count and timing**

```java
package com.openggf.game.sonic2.credits;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSonic2CreditsData {
    @Test
    void testCreditScreenCount() {
        assertEquals(21, Sonic2CreditsData.TOTAL_CREDITS);
    }

    @Test
    void testTimingConstants() {
        assertEquals(0x18E, Sonic2CreditsData.SLIDE_DURATION_60FPS);
        assertEquals(0x16, Sonic2CreditsData.FADE_DURATION);
    }

    @Test
    void testLogoFlashConstants() {
        assertEquals(0x257, Sonic2CreditsData.LOGO_HOLD_FRAMES);
        assertEquals(9, Sonic2CreditsData.PALETTE_CYCLE_FRAME_COUNT);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSonic2CreditsData -q`
Expected: FAIL (class not found)

**Step 3: Create Sonic2CreditsData**

```java
package com.openggf.game.sonic2.credits;

public class Sonic2CreditsData {
    public static final int TOTAL_CREDITS = 21;
    public static final int SLIDE_DURATION_60FPS = 0x18E;
    public static final int SLIDE_DURATION_50FPS = 0x144;
    public static final int FADE_DURATION = 0x16;
    public static final int LOGO_HOLD_FRAMES = 0x257;
    public static final int LOGO_INITIAL_PAUSE = 0x3B;
    public static final int LOGO_FLASH_FRAMES = 0x5E;
    public static final int PALETTE_CYCLE_FRAME_COUNT = 9;
    public static final int PALETTE_CYCLE_BYTES_PER_FRAME = 24;

    // VRAM tile bases (from ROM)
    public static final int ARTTILE_CREDIT_TEXT = 0x0500;
    public static final int ARTTILE_ENDING_CHARACTER = 0x0019;
    public static final int ARTTILE_ENDING_FINAL_TORNADO = 0x0156;
    public static final int ARTTILE_ENDING_PICS = 0x0328;
    public static final int ARTTILE_ENDING_MINI_TORNADO = 0x0493;
    public static final int ARTTILE_CLOUDS = 0x0594;
}
```

**Step 4: Run test to verify pass**

Run: `mvn test -Dtest=TestSonic2CreditsData -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2CreditsData.java \
        src/test/java/com/openggf/game/sonic2/credits/TestSonic2CreditsData.java
git commit -m "feat: add Sonic2CreditsData with timing and VRAM constants"
```

---

## Phase 3: S2 Credits Text Rendering

### Task 9: Create Sonic2CreditsTextRenderer

This renders the 21 credit text slides. The ROM stores text data via pointer table at `off_B2CA` with custom 2-wide character encoding. Each screen has 1-7 text elements with palette index and VRAM position.

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/credits/Sonic2CreditsTextRenderer.java`

**Step 1: Study the ROM text format**

Read the disassembly around `ShowCreditsScreen` (s2.asm line 14376) and `off_B2CA` to understand:
- Pointer table format: word offsets from `off_B2CA` base
- Text element format: `dc.b palette, text..., $FF` terminator
- VRAM write command: 32-bit VDP write command per element
- Character encoding: 2-tile-wide letters, mapped via `EndCredChars` table

**Step 2: Implement the renderer**

The renderer needs to:
1. Load credit font patterns from ROM (`ART_NEM_CREDIT_TEXT_ADDR`) via NemesisReader
2. Parse the 21 credit screen definitions from ROM pointer table
3. For each screen, render centered text using PatternSpriteRenderer

Two approaches for text data:
- **Option A (ROM-parsed):** Read pointer table and text elements from ROM at runtime
- **Option B (Hardcoded):** Transcribe the 21 screens as Java constants

**Recommended: Option B (hardcoded)** — The 2-wide character encoding and VDP write commands are complex to parse at runtime. The text content never changes. Follow the pattern used by `Sonic1CreditsMappings.java`.

Create sprite mapping frames for each of the 21 screens. Each text line maps to sprite pieces using the credit text font. Reference `docs/s2disasm/s2.asm` around lines 14376-14623 for the exact text content and positioning.

Key implementation details:
- Font art is shared with intro screen but uses different tile base (`ArtTile_ArtNem_CreditText_CredScr = 0x0001`)
- Each character is 2 tiles wide (16px per letter)
- Palette 0 for role titles, palette 1 for names (check ROM)
- Center position: 160, 112 (same as S1)

```java
package com.openggf.game.sonic2.credits;

import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.graphics.GraphicsManager;
// ... imports

public class Sonic2CreditsTextRenderer {
    private PatternSpriteRenderer renderer;
    private boolean gpuDirty = true;

    public void initialize(Rom rom) {
        // Load credit text Nemesis art from ROM
        // Create sprite sheet with 21 frames (one per credit screen)
        // Each frame composed of sprite pieces for the text lines
    }

    public void draw(int creditsNum) {
        GraphicsManager gm = GraphicsManager.getInstance();
        if (gpuDirty) {
            renderer.ensurePatternsCached(gm, /* base index */);
            gpuDirty = false;
        }
        gm.beginPatternBatch();
        renderer.drawFrameIndex(creditsNum, 160, 112);
        gm.flushPatternBatch();
    }

    public void markGpuDirty() {
        gpuDirty = true;
    }
}
```

**Step 3: Build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2CreditsTextRenderer.java
git commit -m "feat: add Sonic2CreditsTextRenderer for 21 credit text slides"
```

---

### Task 10: Create S2 Credits Text Mappings

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/credits/Sonic2CreditsMappings.java`

**Step 1: Transcribe the 21 credit screens from disassembly**

Reference `docs/s2disasm/s2.asm` lines around 14497+ for the credit text content. Each screen has:
- Text lines with role titles (palette 0) and names (palette 1)
- Positioning derived from VDP write commands

Follow the same pattern as `Sonic1CreditsMappings.java`: create a `createFrames()` method returning `List<SpriteMappingFrame>` with 21 frames.

The 21 screens (from disassembly):
```
 0: SONIC 2 / CAST OF CHARACTERS
 1: EXECUTIVE PRODUCER / HAYAO NAKAYAMA / SHINOBU TOYODA
 2: PRODUCER / SHINOBU TOYODA
 3: DIRECTOR / MASAHARU YOSHII
 4: CHIEF PROGRAMMER / YUJI NAKA (YU2)
 5: GAME PLANNER / HIROKAZU YASUHARA (CAROL YAS)
 6: CHARACTER DESIGN AND CHIEF ARTIST / YASUSHI YAMAGUCHI (JUDY TOTOYA)
 7: ASSISTANT PROGRAMMERS / BILL WILLIS / MASANOBU YAMAMOTO
 8: OBJECT PLACEMENT / HIROKAZU YASUHARA / TAKAHIRO ANTO / YUTAKA SUGANO
 9: SPECIALSTAGE OBJECT PLACEMENT / YUTAKA SUGANO
10: ZONE ARTISTS / YASUSHI YAMAGUCHI / CRAIG STITT / BRENDA ROSS / JINA ISHIWATARI / TOM PAYNE / PHENIX RIE
11: ART AND CG / TIM SKELLY / PETER MORAWIEC
12: MUSIC COMPOSER / MASATO NAKAMURA / (@1992 DREAMS COME TRUE)
13: SOUND PROGRAMMER / TOMOYUKI SHIMADA
14: SOUND ASSISTANTS / MACKY / JIMITA / MILPO / IPPO / S.O / OYZ / N.GEE
15: PROJECT ASSISTANTS / SYUICHI KATAGI / TAKAHIRO HAMANO / YOSHIKI OOKA / STEVE WOITA
16: GAME MANUAL / ... (many names)
17: SPECIAL THANKS TO / ... (many names)
18: SPECIAL THANKS TO / ... (variant)
19: SPECIAL THANKS TO / ... (variant)
20: PRESENTED BY SEGA
```

Each letter in the credit font is 2 tiles wide × 2 tiles tall (16×16 pixels). Position each line centered horizontally using the same calculations as the ROM's VDP write commands.

**Step 2: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2CreditsMappings.java
git commit -m "feat: add Sonic2CreditsMappings with 21 credit screen definitions"
```

---

## Phase 4: S2 Logo Flash

### Task 11: Implement Logo Flash

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/credits/Sonic2LogoFlashManager.java`

**Step 1: Implement logo flash state machine**

The logo flash displays the "Sonic the Hedgehog 2" image with palette animation after all 21 credits.

State machine:
1. Load `ArtNem_EndingTitle` (Nemesis) to VRAM
2. Decode `MapEng_EndGameLogo` (Enigma) to get tile layout
3. Load initial palette
4. Run strobe sequence: cycle through palette frames per `byte_A0EC` lookup table
5. Hold for `LOGO_HOLD_FRAMES` (0x257 frames), skippable with button press
6. Signal completion

```java
package com.openggf.game.sonic2.credits;

public class Sonic2LogoFlashManager {
    private enum State { LOADING, FLASHING, HOLDING, DONE }
    private State state = State.LOADING;
    private int frameCounter;
    private int strobeIndex;
    private PatternSpriteRenderer logoRenderer;
    // Palette cycle data loaded from ROM

    public void initialize(Rom rom) {
        // Load ending title art (Nemesis)
        // Decode Enigma mapping for logo layout
        // Load palette cycle data (9 frames × 24 bytes from Ending Cycle.bin)
        // Load strobe sequence from byte_A0EC
        state = State.FLASHING;
        frameCounter = 0;
    }

    public void update() {
        switch (state) {
            case FLASHING -> updateFlash();
            case HOLDING -> updateHold();
        }
    }

    private void updateFlash() {
        // Advance strobe sequence, apply palette frame
        // After flash complete, transition to HOLDING
    }

    private void updateHold() {
        frameCounter++;
        if (frameCounter >= Sonic2CreditsData.LOGO_HOLD_FRAMES || buttonPressed()) {
            state = State.DONE;
        }
    }

    public void draw() {
        // Render logo using PatternSpriteRenderer
    }

    public boolean isDone() {
        return state == State.DONE;
    }
}
```

**Step 2: Build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2LogoFlashManager.java
git commit -m "feat: add Sonic2LogoFlashManager with palette strobe animation"
```

---

## Phase 5: S2 Ending Cutscene

### Task 12: Create Sonic2EndingCutsceneManager Skeleton

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java`
- Create: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingArt.java`

**Step 1: Create art loader**

`Sonic2EndingArt` loads all Nemesis art and palettes for the ending cutscene, selecting character-specific variants based on emerald count and player mode.

```java
package com.openggf.game.sonic2.credits;

public class Sonic2EndingArt {
    public enum EndingRoutine { SONIC, SUPER_SONIC, TAILS }

    public static EndingRoutine determineEndingRoutine() {
        int emeralds = GameServices.gameState().getEmeraldCount();
        // Check player mode for Tails-alone
        if (/* tails alone */) return EndingRoutine.TAILS;
        if (emeralds >= 7) return EndingRoutine.SUPER_SONIC;
        return EndingRoutine.SONIC;
    }

    public void loadArt(Rom rom, EndingRoutine routine) {
        // Load character-specific art based on routine
        // Load shared art (clouds, tornado, ending pics)
        // Load palettes
    }
}
```

**Step 2: Create cutscene manager skeleton**

```java
package com.openggf.game.sonic2.credits;

public class Sonic2EndingCutsceneManager {
    public enum CutsceneState {
        INIT,
        PHOTO_SEQUENCE,
        SKY_FALL,
        PLANE_APPROACH,
        PLANE_RESCUE,
        FLY_AWAY,
        TRIGGER_CREDITS
    }

    private CutsceneState state = CutsceneState.INIT;
    private int frameCounter;
    private Sonic2EndingArt endingArt;

    public void initialize(Rom rom) {
        endingArt = new Sonic2EndingArt();
        endingArt.loadArt(rom, Sonic2EndingArt.determineEndingRoutine());
        state = CutsceneState.PHOTO_SEQUENCE;
        frameCounter = 0;
    }

    public void update() {
        frameCounter++;
        switch (state) {
            case PHOTO_SEQUENCE -> updatePhotoSequence();
            case SKY_FALL -> updateSkyFall();
            case PLANE_APPROACH -> updatePlaneApproach();
            case PLANE_RESCUE -> updatePlaneRescue();
            case FLY_AWAY -> updateFlyAway();
            case TRIGGER_CREDITS -> {} // no-op, picked up by provider
        }
    }

    // Stub methods — implement in Tasks 13-15
    private void updatePhotoSequence() { /* TODO */ }
    private void updateSkyFall() { /* TODO */ }
    private void updatePlaneApproach() { /* TODO */ }
    private void updatePlaneRescue() { /* TODO */ }
    private void updateFlyAway() { /* TODO */ }

    public void draw() {
        // Render current cutscene state
    }

    public boolean isDone() {
        return state == CutsceneState.TRIGGER_CREDITS;
    }
}
```

**Step 3: Build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java \
        src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingArt.java
git commit -m "feat: add Sonic2EndingCutsceneManager skeleton and Sonic2EndingArt"
```

---

### Task 13: Implement Photo Sequence

The ending starts with 4 photo frames displayed sequentially with fade transitions. Each is an Enigma-mapped tilemap showing ending scenes.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java`

**Step 1: Implement photo rendering**

Reference ROM: ObjCA routines 0-6 (s2.asm lines 13109-13420). Four Enigma maps (`MapEng_Ending1` through `MapEng_Ending4`) displayed to Plane A at screen position, each held for ~0x180 frames with palette fade transitions.

The photo sequence should:
1. Display first photo, fade in
2. Hold for duration
3. Fade to palette change, display next photo
4. Repeat for all 4 photos
5. Transition to SKY_FALL state

Render photos using EnigmaReader to decode the mappings, then draw tiles via GraphicsManager.

**Step 2: Build and test**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java
git commit -m "feat: implement ending photo sequence with Enigma-mapped frames"
```

---

### Task 14: Implement Sky Fall and Plane Approach

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java`

**Step 1: Implement sky fall**

Reference ROM: ObjCA routine 8 (s2.asm ~line 13430). Character falls from sky with clouds scrolling upward. Background scroll rate: Y = 0x100/frame.

Render:
- Sky background (blue gradient, loaded from palette)
- Clouds moving upward at varying speeds (-0x300, -0x200, -0x100)
- Character sprite at center-bottom area, falling animation

**Step 2: Implement plane approach**

Reference ROM: ObjCA routines 10-12, ObjCC (s2.asm ~lines 13470-13623). Tornado flies in from left side.

Render:
- Tornado sprite moving from X=-0x10 to X=0xA0 at speed 0x100/frame
- Character on or near Tornado
- Clouds transitioning from vertical to horizontal scroll
- Bird objects (ObjCD) spawning during sequence

**Step 3: Build and test**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java
git commit -m "feat: implement sky fall and plane approach cutscene phases"
```

---

### Task 15: Implement Plane Rescue and Fly Away

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java`

**Step 1: Implement plane rescue**

Reference ROM: ObjCC states 2-4 (s2.asm ~lines 13579-13623). Character boards the Tornado, short animation of docking.

**Step 2: Implement fly away**

Tornado exits screen moving rightward. When off-screen, transition to TRIGGER_CREDITS.

**Step 3: Build and test**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java
git commit -m "feat: implement plane rescue and fly away cutscene phases"
```

---

## Phase 6: S2 Ending Provider & Integration

### Task 16: Create Sonic2EndingProvider

This is the top-level state machine that coordinates cutscene → credits text → logo flash.

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`

**Step 1: Implement state machine**

```java
package com.openggf.game.sonic2.credits;

import com.openggf.game.EndingPhase;
import com.openggf.game.EndingProvider;

public class Sonic2EndingProvider implements EndingProvider {
    private enum InternalState {
        CUTSCENE, CREDITS_FADE_IN, CREDITS_TEXT, CREDITS_FADE_OUT,
        LOGO_FLASH, FINISHED
    }

    private InternalState state = InternalState.CUTSCENE;
    private Sonic2EndingCutsceneManager cutsceneManager;
    private Sonic2CreditsTextRenderer textRenderer;
    private Sonic2LogoFlashManager logoFlashManager;
    private int currentSlide;
    private int slideTimer;

    @Override
    public void initialize() {
        cutsceneManager = new Sonic2EndingCutsceneManager();
        cutsceneManager.initialize(GameServices.rom().getRom());
        textRenderer = new Sonic2CreditsTextRenderer();
        textRenderer.initialize(GameServices.rom().getRom());
        // Play ending music
        AudioManager.getInstance().playMusic(Sonic2AudioConstants.MUS_ENDING);
        state = InternalState.CUTSCENE;
    }

    @Override
    public void update() {
        switch (state) {
            case CUTSCENE -> {
                cutsceneManager.update();
                if (cutsceneManager.isDone()) {
                    // Switch to credits music
                    AudioManager.getInstance().playMusic(Sonic2AudioConstants.MUS_CREDITS);
                    currentSlide = 0;
                    slideTimer = 0;
                    state = InternalState.CREDITS_FADE_IN;
                }
            }
            case CREDITS_FADE_IN -> {
                slideTimer++;
                if (slideTimer >= Sonic2CreditsData.FADE_DURATION) {
                    slideTimer = 0;
                    state = InternalState.CREDITS_TEXT;
                }
            }
            case CREDITS_TEXT -> {
                slideTimer++;
                if (slideTimer >= Sonic2CreditsData.SLIDE_DURATION_60FPS) {
                    slideTimer = 0;
                    state = InternalState.CREDITS_FADE_OUT;
                }
            }
            case CREDITS_FADE_OUT -> {
                slideTimer++;
                if (slideTimer >= Sonic2CreditsData.FADE_DURATION) {
                    currentSlide++;
                    if (currentSlide >= Sonic2CreditsData.TOTAL_CREDITS) {
                        logoFlashManager = new Sonic2LogoFlashManager();
                        logoFlashManager.initialize(GameServices.rom().getRom());
                        state = InternalState.LOGO_FLASH;
                    } else {
                        slideTimer = 0;
                        state = InternalState.CREDITS_FADE_IN;
                    }
                }
            }
            case LOGO_FLASH -> {
                logoFlashManager.update();
                if (logoFlashManager.isDone()) {
                    state = InternalState.FINISHED;
                }
            }
            case FINISHED -> {}
        }
    }

    @Override
    public void draw() {
        switch (state) {
            case CUTSCENE -> cutsceneManager.draw();
            case CREDITS_FADE_IN, CREDITS_TEXT, CREDITS_FADE_OUT ->
                textRenderer.draw(currentSlide);
            case LOGO_FLASH -> logoFlashManager.draw();
        }
    }

    @Override
    public EndingPhase getCurrentPhase() {
        return switch (state) {
            case CUTSCENE -> EndingPhase.CUTSCENE;
            case CREDITS_FADE_IN, CREDITS_TEXT, CREDITS_FADE_OUT -> EndingPhase.CREDITS_TEXT;
            case LOGO_FLASH -> EndingPhase.POST_CREDITS;
            case FINISHED -> EndingPhase.FINISHED;
        };
    }

    @Override
    public boolean isComplete() {
        return state == InternalState.FINISHED;
    }
}
```

**Step 2: Wire into Sonic2GameModule**

In `Sonic2GameModule.java`:

```java
@Override
public EndingProvider getEndingProvider() {
    return new Sonic2EndingProvider();
}
```

**Step 3: Build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingProvider.java \
        src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java
git commit -m "feat: add Sonic2EndingProvider and wire into Sonic2GameModule"
```

---

### Task 17: Wire Up DEZ Boss Trigger

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/events/Sonic2DEZEvents.java` (routine 8)
- Modify: `src/main/java/com/openggf/level/LevelManager.java` (rename method)

**Step 1: Rename requestCreditsTransition → requestEndingTransition**

In `LevelManager.java` (line ~4013):
- Rename `requestCreditsTransition()` → `requestEndingTransition()`
- Rename `consumeCreditsRequest()` → `consumeEndingRequest()`
- Update all callers (search for both old names)

Callers to update:
- `GameLoop.java` (line ~442): `consumeCreditsRequest()` → `consumeEndingRequest()`
- `Sonic1EndingSTHObjectInstance.java` (line ~110): `requestCreditsTransition()` → `requestEndingTransition()`

**Step 2: Add DEZ boss defeat trigger**

In `Sonic2DEZEvents.java`, add a new routine 10 (or extend routine 8) that triggers the ending when the Death Egg Robot is defeated:

```java
case 8 -> {
    // Death Egg Robot fight in progress
    // When boss sets defeated flag:
    if (/* boss defeated condition */) {
        eventRoutine += 2;
    }
}
case 10 -> {
    // Trigger ending sequence
    LevelManager.getInstance().requestEndingTransition();
}
```

**Note:** The exact boss-defeat detection depends on how the Death Egg Robot object signals completion. If the boss isn't fully implemented yet, add a comment placeholder and provide a debug key trigger for testing.

**Step 3: Build and test**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

Run: `mvn test -q`
Expected: All tests pass

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/events/Sonic2DEZEvents.java \
        src/main/java/com/openggf/level/LevelManager.java \
        src/main/java/com/openggf/GameLoop.java \
        src/main/java/com/openggf/game/sonic1/objects/Sonic1EndingSTHObjectInstance.java
git commit -m "feat: wire DEZ boss trigger and rename credits→ending transition"
```

---

### Task 18: Integration Testing

**Files:**
- Create: `src/test/java/com/openggf/game/sonic2/credits/TestSonic2EndingProvider.java`

**Step 1: Write state machine transition test**

```java
package com.openggf.game.sonic2.credits;

import com.openggf.game.EndingPhase;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSonic2EndingProvider {

    @Test
    void testInitialPhaseIsCutscene() {
        // Requires ROM — skip annotation if ROM not present
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        // provider.initialize() needs ROM access, so this test may
        // need @EnabledIf or conditional skip
        assertEquals(EndingPhase.CUTSCENE, provider.getCurrentPhase());
    }

    @Test
    void testPhaseProgression() {
        // Test that after cutscene isDone(), phase transitions to CREDITS_TEXT
        // May need mock or minimal stub for ROM-dependent init
    }

    @Test
    void testIsCompleteInitiallyFalse() {
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        assertFalse(provider.isComplete());
    }
}
```

**Step 2: Write Sonic1EndingProvider regression test**

```java
package com.openggf.game.sonic1.credits;

import com.openggf.game.EndingPhase;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSonic1EndingProvider {

    @Test
    void testInitialPhaseIsCreditsText() {
        // S1 has no cutscene phase — starts directly at CREDITS_TEXT
        // This tests that the wrapper correctly maps the initial state
    }
}
```

**Step 3: Run tests**

Run: `mvn test -q`
Expected: All tests pass

**Step 4: Commit**

```bash
git add src/test/java/com/openggf/game/sonic2/credits/TestSonic2EndingProvider.java \
        src/test/java/com/openggf/game/sonic1/credits/TestSonic1EndingProvider.java
git commit -m "test: add EndingProvider state machine and S1 regression tests"
```

---

## Task Dependency Summary

```
Task 1 (EndingPhase + EndingProvider) ─┐
Task 2 (ENDING_CUTSCENE GameMode) ─────┤
Task 3 (GameModule.getEndingProvider) ──┼── Task 4 (Sonic1EndingProvider) ── Task 5 (GameLoop refactor) ── Task 6 (regression)
                                        │
                                        └── Task 7 (ROM constants) ── Task 8 (CreditsData) ── Task 9-10 (TextRenderer)
                                                                                             ── Task 11 (LogoFlash)
                                                                                             ── Task 12-15 (Cutscene)
                                                                                             ── Task 16 (S2EndingProvider) ── Task 17 (DEZ trigger) ── Task 18 (integration tests)
```

**Critical path:** Tasks 1-5 must be done first (foundation). After that, Tasks 7-15 can proceed in any order. Task 16 depends on 9-15 all being done. Tasks 17-18 are final integration.

**Parallel opportunities:** After Task 6 completes, Tasks 7 (ROM constants), 9 (text renderer), 11 (logo flash), and 12 (cutscene skeleton) can all start in parallel since they're independent.
