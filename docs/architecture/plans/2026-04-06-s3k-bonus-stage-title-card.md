# S3K Bonus Stage Title Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add "BONUS STAGE" title card on bonus stage entry and zone title card on bonus stage exit, matching original ROM behavior.

**Architecture:** Extends existing `Sonic3kTitleCardManager` with a bonus mode (2 horizontal elements instead of 4). Introduces `PostTitleCardDestination` in `GameLoop` so `exitTitleCard()` can transition to either `LEVEL` or `BONUS_STAGE` depending on context. Follows the established `enterTitleCardFromResults` pattern for non-standard title card entry points.

**Tech Stack:** Java 21, existing title card / fade / GameLoop infrastructure.

**Spec:** `docs/architecture/designs/2026-04-06-s3k-bonus-stage-title-card-design.md`

---

### Task 1: Add Bonus Mapping Frames to Sonic3kTitleCardMappings

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardMappings.java`
- Test: `src/test/java/com/openggf/game/sonic3k/titlecard/TestSonic3kBonusTitleCardMappings.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/sonic3k/titlecard/TestSonic3kBonusTitleCardMappings.java`:

```java
package com.openggf.game.sonic3k.titlecard;

import com.openggf.game.titlecard.TitleCardMappings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies bonus title card mapping frames match Map - Title Card.asm lines 204-215.
 */
public class TestSonic3kBonusTitleCardMappings {

    @Test
    public void bonusFrameHasFivePieces() {
        TitleCardMappings.SpritePiece[] pieces = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_BONUS);
        assertEquals(5, pieces.length);
    }

    @Test
    public void stageFrameHasFivePieces() {
        TitleCardMappings.SpritePiece[] pieces = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_STAGE);
        assertEquals(5, pieces.length);
    }

    @Test
    public void competitionFrameSharesBonusData() {
        TitleCardMappings.SpritePiece[] competition = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_COMPETITION);
        TitleCardMappings.SpritePiece[] bonus = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_BONUS);
        assertSame("Competition and Bonus should share the same array", competition, bonus);
    }

    @Test
    public void bonusFrameTileIndicesMatchDisasm() {
        // Map - Title Card.asm line 204-209: tiles $53, $28, $5F, $71, $65
        TitleCardMappings.SpritePiece[] pieces = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_BONUS);
        assertEquals(0x553, pieces[0].tileIndex());
        assertEquals(0x528, pieces[1].tileIndex());
        assertEquals(0x55F, pieces[2].tileIndex());
        assertEquals(0x571, pieces[3].tileIndex());
        assertEquals(0x565, pieces[4].tileIndex());
    }

    @Test
    public void stageFrameTileIndicesMatchDisasm() {
        // Map - Title Card.asm line 210-215: tiles $65, $6B, $4D, $59, $1C
        TitleCardMappings.SpritePiece[] pieces = Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_STAGE);
        assertEquals(0x565, pieces[0].tileIndex());
        assertEquals(0x56B, pieces[1].tileIndex());
        assertEquals(0x54D, pieces[2].tileIndex());
        assertEquals(0x559, pieces[3].tileIndex());
        assertEquals(0x51C, pieces[4].tileIndex());
    }

    @Test
    public void existingZoneFramesUnchanged() {
        // Regression: AIZ frame still has 11 pieces
        assertEquals(11, Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_AIZ).length);
        // ZONE text still has 4 pieces
        assertEquals(4, Sonic3kTitleCardMappings.getFrame(
                Sonic3kTitleCardMappings.FRAME_ZONE).length);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSonic3kBonusTitleCardMappings -pl . -q`
Expected: FAIL — `FRAME_BONUS` / `FRAME_STAGE` / `FRAME_COMPETITION` not defined.

- [ ] **Step 3: Add bonus frame constants and piece arrays**

In `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardMappings.java`, add after `FRAME_HPZ = 17`:

```java
    public static final int FRAME_COMPETITION = 18;
    public static final int FRAME_BONUS = 19;
    public static final int FRAME_STAGE = 20;
```

Add piece arrays after `TC_HPZ`:

```java
    // Frame 19: "BONUS" (5 pieces) — Map - Title Card.asm line 204
    // Frame 18 (Competition) shares the same data (labels alias in disasm)
    private static final TitleCardMappings.SpritePiece[] TC_BONUS = {
            p(  0, 0, 2, 3, 0x553),  // B
            p( 16, 0, 3, 3, 0x528),  // O
            p( 40, 0, 2, 3, 0x55F),  // N
            p( 56, 0, 2, 3, 0x571),  // U
            p( 72, 0, 2, 3, 0x565),  // S
    };

    // Frame 20: "STAGE" (5 pieces) — Map - Title Card.asm line 210
    private static final TitleCardMappings.SpritePiece[] TC_STAGE = {
            p(  0, 0, 2, 3, 0x565),  // S
            p( 16, 0, 2, 3, 0x56B),  // T
            p( 32, 0, 2, 3, 0x54D),  // A
            p( 48, 0, 2, 3, 0x559),  // G
            p( 64, 0, 2, 3, 0x51C),  // E
    };
```

Add cases in the `getFrame()` switch:

```java
            case FRAME_COMPETITION -> TC_BONUS;
            case FRAME_BONUS -> TC_BONUS;
            case FRAME_STAGE -> TC_STAGE;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestSonic3kBonusTitleCardMappings -pl . -q`
Expected: PASS (all 7 tests).

- [ ] **Step 5: Run existing title card test for regression**

Run: `mvn test -Dtest=TestTitleCardPhysicsPolicy -pl . -q`
Expected: PASS (3 tests, unchanged).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardMappings.java \
        src/test/java/com/openggf/game/sonic3k/titlecard/TestSonic3kBonusTitleCardMappings.java
git commit -m "feat: add BONUS/STAGE mapping frames to S3K title card mappings"
```

---

### Task 2: Add Bonus Title Card Art ROM Address

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java:744`

- [ ] **Step 1: Add the constant**

In `Sonic3kConstants.java`, after the `TITLE_CARD_ZONE_ART_ADDRS` array (line 744), add:

```java
    // Bonus stage title card letter art (KosinskiM, 354 bytes → 42 tiles)
    // Loaded to VRAM $54D in place of zone-specific letters.
    // ROM verified by binary match at 0x0D726C (follows ArtKosM_FBZTitleCard).
    public static final int ART_KOSM_BONUS_TITLE_CARD_ADDR = 0x0D726C;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java
git commit -m "feat: add ART_KOSM_BONUS_TITLE_CARD_ADDR to S3K constants"
```

---

### Task 3: Add Bonus Mode to Sonic3kTitleCardManager

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java`
- Modify: `src/main/java/com/openggf/game/TitleCardProvider.java`
- Test: `src/test/java/com/openggf/game/sonic3k/titlecard/TestSonic3kBonusTitleCard.java`

- [ ] **Step 1: Add `initializeBonus()` default method to TitleCardProvider**

In `src/main/java/com/openggf/game/TitleCardProvider.java`, add after the `initializeInLevel` default method (line 23):

```java
    /**
     * Initializes the title card for a bonus stage entry.
     * S3K shows "BONUS STAGE" text; S1/S2 have no bonus stages so this is a no-op.
     */
    default void initializeBonus() {
        // No-op for games without bonus stages
    }
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/openggf/game/sonic3k/titlecard/TestSonic3kBonusTitleCard.java`:

```java
package com.openggf.game.sonic3k.titlecard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests bonus mode behavior in Sonic3kTitleCardManager.
 * These tests don't require ROM or OpenGL — they exercise the state machine only.
 */
public class TestSonic3kBonusTitleCard {

    private Sonic3kTitleCardManager manager;

    @BeforeEach
    public void setUp() {
        manager = Sonic3kTitleCardManager.getInstance();
        manager.reset();
    }

    @Test
    public void initializeBonusSetsSlideInState() {
        manager.initializeBonus();
        assertFalse("Should not be complete after init", manager.isComplete());
        assertFalse("Should not release control during slide-in", manager.shouldReleaseControl());
    }

    @Test
    public void bonusModeReleasesControlOnExit() {
        manager.initializeBonus();

        // Advance through SLIDE_IN (elements slide from right to target)
        // Max distance is 360-168=192 at 16px/frame = 12 frames
        for (int i = 0; i < 20; i++) {
            manager.update();
        }
        // Advance through DISPLAY hold (90 frames)
        for (int i = 0; i < 90; i++) {
            manager.update();
        }
        // Should now be in EXIT phase — control released
        assertTrue("Should release control during exit", manager.shouldReleaseControl());
    }

    @Test
    public void bonusModeCompletesAfterFullAnimation() {
        manager.initializeBonus();
        // Run enough frames for full animation cycle:
        // SLIDE_IN (~12 frames) + DISPLAY (90 frames) + EXIT (~10 frames)
        for (int i = 0; i < 150; i++) {
            manager.update();
        }
        assertTrue("Should be complete after full animation", manager.isComplete());
    }

    @Test
    public void normalModeStillWorksAfterBonusMode() {
        // First run bonus mode
        manager.initializeBonus();
        for (int i = 0; i < 150; i++) {
            manager.update();
        }
        assertTrue(manager.isComplete());

        // Then run normal mode
        manager.initialize(0, 0); // AIZ act 1
        assertFalse("Should not be complete after normal init", manager.isComplete());
    }

    @Test
    public void shouldNotRunPlayerPhysicsInBonusMode() {
        manager.initializeBonus();
        assertFalse(manager.shouldRunPlayerPhysics());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=TestSonic3kBonusTitleCard -pl . -q`
Expected: FAIL — `initializeBonus()` not yet implemented in the manager (default no-op).

- [ ] **Step 4: Implement bonus mode in Sonic3kTitleCardManager**

In `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java`:

**Add fields** after `private boolean inLevelMode;` (line 83):

```java
    private boolean bonusMode;  // 2-element "BONUS STAGE" layout

    // Bonus mode element definitions (ObjArray_TtlCardBonus, sonic3k.asm line 62482)
    // VDP coords converted to screen coords (subtract 128)
    private static final int BONUS_ELEMENT_COUNT = 2;
    private static final int BONUS_ELEM_BONUS = 0;
    private static final int BONUS_ELEM_STAGE = 1;
    private static final int[] BONUS_START_X = {264, 360};
    private static final int[] BONUS_TARGET_X = {72, 168};
    private static final int BONUS_Y = 104;
    private static final int[] BONUS_EXIT_PRIORITY = {1, 1};
```

**Add `initializeBonus()` method** after the existing `initializeInLevel()` method:

```java
    /**
     * Initializes for bonus stage mode — shows "BONUS STAGE" text.
     * Uses 2 horizontal elements (frames 19/20) instead of the normal 4-element layout.
     * Both elements have exit priority 1 (exit simultaneously).
     *
     * <p>ROM reference: ObjArray_TtlCardBonus (sonic3k.asm line 62482).
     */
    @Override
    public void initializeBonus() {
        this.bonusMode = true;
        this.inLevelMode = false;
        this.state = Sonic3kTitleCardState.SLIDE_IN;
        this.stateTimer = 0;
        this.phaseCounter = 0;

        // Set up 2 bonus elements — reuse the first 2 slots of the 4-element arrays
        elemFrame[0] = Sonic3kTitleCardMappings.FRAME_BONUS;
        elemFrame[1] = Sonic3kTitleCardMappings.FRAME_STAGE;

        for (int i = 0; i < BONUS_ELEMENT_COUNT; i++) {
            elemX[i] = BONUS_START_X[i];
            elemY[i] = BONUS_Y;
            elemAtTarget[i] = false;
            elemExiting[i] = false;
            elemExited[i] = false;
        }

        // Load bonus art (no zone/act needed — bonus art is always the same)
        if (!artLoaded || lastLoadedZone != -2) {
            loadBonusArt();
        }

        artCached = false;
        LOG.info("S3K bonus title card initialized");
    }
```

**Add `loadBonusArt()` method** after `loadAllArt()`:

```java
    /**
     * Loads bonus stage title card art.
     * Same shared blocks (RedAct, S3KZone text, act number) plus
     * ArtKosM_BonusTitleCard at VRAM $54D instead of zone-specific art.
     */
    private void loadBonusArt() {
        try {
            if (!GameServices.rom().isRomAvailable()) {
                LOG.warning("ROM not available for bonus title card art");
                return;
            }
            Rom rom = GameServices.rom().getRom();

            combinedPatterns = new Pattern[VRAM_ARRAY_SIZE];
            Pattern empty = new Pattern();
            Arrays.fill(combinedPatterns, empty);

            // 1. Load RedAct art → VRAM $500 (index 0)
            loadKosmArt(rom, Sonic3kConstants.ART_KOSM_TITLE_CARD_RED_ACT_ADDR, 0);

            // 2. Load S3KZone text → VRAM $510 (index $10)
            loadKosmArt(rom, Sonic3kConstants.ART_KOSM_TITLE_CARD_S3K_ZONE_ADDR,
                    Sonic3kConstants.VRAM_TITLE_CARD_ZONE_TEXT - VRAM_BASE);

            // 3. Load bonus-specific letter art → VRAM $54D (index $4D)
            loadKosmArt(rom, Sonic3kConstants.ART_KOSM_BONUS_TITLE_CARD_ADDR,
                    Sonic3kConstants.VRAM_TITLE_CARD_ZONE_ART - VRAM_BASE);

            artLoaded = true;
            artCached = false;
            lastLoadedZone = -2;  // Sentinel for "bonus art loaded"
            lastLoadedAct = -1;
            LOG.info("S3K bonus title card art loaded");
        } catch (Exception e) {
            LOG.warning("Failed to load S3K bonus title card art: " + e.getMessage());
            artLoaded = false;
        }
    }
```

**Modify `initInternal()`** — add `bonusMode = false;` at the start (after `this.currentAct = actIndex;`):

```java
        this.bonusMode = false;
```

**Modify `updateSlideIn()`** — use active element count:

```java
    private void updateSlideIn() {
        int count = bonusMode ? BONUS_ELEMENT_COUNT : ELEMENT_COUNT;
        boolean allAtTarget = true;
        for (int i = 0; i < count; i++) {
            if (!bonusMode && !actNumberVisible && i == ELEM_ACT_NUM) continue;
            if (!elemAtTarget[i]) {
                slideElement(i, true);
                if (!elemAtTarget[i]) allAtTarget = false;
            }
        }
        if (allAtTarget) {
            state = Sonic3kTitleCardState.DISPLAY;
            stateTimer = 0;
            LOG.fine("S3K title card: DISPLAY");
        }
    }
```

**Modify `updateExit()`** — use active element count and bonus priorities:

```java
    private void updateExit() {
        phaseCounter++;
        int count = bonusMode ? BONUS_ELEMENT_COUNT : ELEMENT_COUNT;
        int[] priorities = bonusMode ? BONUS_EXIT_PRIORITY : EXIT_PRIORITY;

        boolean allExited = true;
        for (int i = 0; i < count; i++) {
            if (!bonusMode && !actNumberVisible && i == ELEM_ACT_NUM) continue;
            if (elemExited[i]) continue;

            if (phaseCounter >= priorities[i]) {
                elemExiting[i] = true;
            }

            if (elemExiting[i]) {
                slideElement(i, false);
            }

            if (!elemExited[i]) {
                allExited = false;
            }
        }

        if (allExited) {
            state = Sonic3kTitleCardState.COMPLETE;
            LOG.fine("S3K title card: COMPLETE");
        }
    }
```

**Modify `slideElement()`** — use bonus positions when in bonus mode:

```java
    private void slideElement(int idx, boolean slideIn) {
        int speed = slideIn ? SLIDE_SPEED_IN : SLIDE_SPEED_OUT;

        if (bonusMode) {
            // Bonus mode: all elements are horizontal
            int goalX = slideIn ? BONUS_TARGET_X[idx] : BONUS_START_X[idx];
            int dir = Integer.compare(goalX, elemX[idx]);
            if (dir == 0) {
                if (slideIn) elemAtTarget[idx] = true;
                else elemExited[idx] = true;
                return;
            }
            elemX[idx] += dir * speed;
            if ((dir > 0 && elemX[idx] >= goalX) || (dir < 0 && elemX[idx] <= goalX)) {
                elemX[idx] = goalX;
                if (slideIn) elemAtTarget[idx] = true;
                else elemExited[idx] = true;
            }
            return;
        }

        // Normal mode (existing logic unchanged)
        int goalX = slideIn ? TARGET_X[idx] : START_X[idx];
        int goalY = slideIn ? TARGET_Y[idx] : START_Y[idx];

        if (IS_VERTICAL[idx]) {
            int dir = Integer.compare(goalY, elemY[idx]);
            if (dir == 0) {
                if (slideIn) elemAtTarget[idx] = true;
                else elemExited[idx] = true;
                return;
            }
            elemY[idx] += dir * speed;
            if ((dir > 0 && elemY[idx] >= goalY) || (dir < 0 && elemY[idx] <= goalY)) {
                elemY[idx] = goalY;
                if (slideIn) elemAtTarget[idx] = true;
                else elemExited[idx] = true;
            }
        } else {
            int dir = Integer.compare(goalX, elemX[idx]);
            if (dir == 0) {
                if (slideIn) elemAtTarget[idx] = true;
                else elemExited[idx] = true;
                return;
            }
            elemX[idx] += dir * speed;
            if ((dir > 0 && elemX[idx] >= goalX) || (dir < 0 && elemX[idx] <= goalX)) {
                elemX[idx] = goalX;
                if (slideIn) elemAtTarget[idx] = true;
                else elemExited[idx] = true;
            }
        }
    }
```

**Modify `draw()`** — use active element count and skip act number:

```java
    @Override
    public void draw() {
        ensureArtCached();

        GraphicsManager gm = GraphicsManager.getInstance();
        if (gm == null) return;

        // Black background during SLIDE_IN and DISPLAY (normal and bonus mode, not in-level)
        if (!inLevelMode &&
                (state == Sonic3kTitleCardState.SLIDE_IN || state == Sonic3kTitleCardState.DISPLAY)) {
            gm.registerCommand(new GLCommand(
                    GLCommand.CommandType.RECTI, -1,
                    0.0f, 0.0f, 0.0f,
                    0, 0, SCREEN_WIDTH, SCREEN_HEIGHT
            ));
        }

        gm.beginPatternBatch();

        if (bonusMode) {
            renderElement(gm, BONUS_ELEM_BONUS);
            renderElement(gm, BONUS_ELEM_STAGE);
        } else {
            renderElement(gm, ELEM_BANNER);
            renderElement(gm, ELEM_ZONE_NAME);
            renderElement(gm, ELEM_ZONE_TEXT);
            if (actNumberVisible) {
                renderElement(gm, ELEM_ACT_NUM);
            }
        }

        gm.flushPatternBatch();
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=TestSonic3kBonusTitleCard -pl . -q`
Expected: PASS (5 tests).

- [ ] **Step 6: Run all title card tests for regression**

Run: `mvn test -Dtest=TestTitleCardPhysicsPolicy,TestSonic3kBonusTitleCardMappings,TestSonic3kBonusTitleCard -pl . -q`
Expected: PASS (all 15 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/TitleCardProvider.java \
        src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java \
        src/test/java/com/openggf/game/sonic3k/titlecard/TestSonic3kBonusTitleCard.java
git commit -m "feat: add bonus mode to S3K title card manager

Two-element horizontal layout for BONUS STAGE text card.
Loads ArtKosM_BonusTitleCard for bonus-specific letter tiles.
Both elements exit simultaneously (priority=1, no stagger)."
```

---

### Task 4: Add PostTitleCardDestination and Wire Bonus Stage Entry

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`

- [ ] **Step 1: Add PostTitleCardDestination enum and field**

In `GameLoop.java`, add a private enum near the top of the class (after the existing field declarations, around line 85):

```java
    /**
     * Where to transition after a title card completes.
     * Normally LEVEL, but bonus stage entry routes through title card first.
     */
    private enum PostTitleCardDestination {
        /** Normal: title card → LEVEL mode (default) */
        LEVEL,
        /** Bonus stage entry: title card → BONUS_STAGE mode */
        BONUS_STAGE
    }

    private PostTitleCardDestination postTitleCardDestination = PostTitleCardDestination.LEVEL;
```

Also add fields to hold deferred bonus stage setup state (needed by `exitTitleCard` when destination is BONUS_STAGE):

```java
    // Deferred bonus stage setup — applied when title card exits with BONUS_STAGE destination
    private BonusStageProvider deferredBonusProvider;
    private BonusStageType deferredBonusType;
    private BonusStageState deferredBonusState;
```

- [ ] **Step 2: Modify doEnterBonusStage to show title card**

Replace the body of `doEnterBonusStage()` (lines 1026-1095) with:

```java
    private void doEnterBonusStage(BonusStageProvider provider, BonusStageType type,
                                    BonusStageState savedState) {
        bonusStageTransitionPending = false;

        // Register provider on GameRuntime so objects can access via GameServices.bonusStage()
        activeBonusStageProvider = provider;
        GameRuntime rt = RuntimeManager.getCurrent();
        if (rt != null) {
            rt.setActiveBonusStageProvider(provider);
        }

        provider.onEnter(type, savedState);

        // Load the bonus zone through the normal level loading path
        int zoneId = provider.getZoneId(type);
        int zone = (zoneId >> 8) & 0xFF;
        int act = zoneId & 0xFF;

        try {
            levelManager.loadZoneAndAct(zone, act);
            // Consume the default title card request — we'll show the bonus card instead
            levelManager.consumeTitleCardRequest();
        } catch (IOException e) {
            LOGGER.severe("Failed to load bonus stage zone: " + e.getMessage());
            provider.onExit();
            activeBonusStageProvider = null;
            if (rt != null) {
                rt.setActiveBonusStageProvider(null);
            }
            currentGameMode = GameMode.LEVEL;
            return;
        }

        // Defer bonus-stage-specific setup to exitTitleCard() when the title card completes
        deferredBonusProvider = provider;
        deferredBonusType = type;
        deferredBonusState = savedState;

        // Initialize the bonus title card
        TitleCardProvider tcp = getTitleCardProviderLazy();
        if (tcp != null) {
            tcp.initializeBonus();
        }

        // Enter TITLE_CARD mode — exitTitleCard will transition to BONUS_STAGE
        GameMode oldMode = currentGameMode;
        postTitleCardDestination = PostTitleCardDestination.BONUS_STAGE;
        currentGameMode = GameMode.TITLE_CARD;

        // Fade from black — level + title card become visible together
        fadeManager.startFadeFromBlack(null);

        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Entered bonus title card for " + type + " (zone 0x"
                + Integer.toHexString(zoneId) + ")");
    }
```

- [ ] **Step 3: Modify exitTitleCard to handle BONUS_STAGE destination**

Replace `exitTitleCard()` (lines 1602-1636) with:

```java
    private void exitTitleCard() {
        if (currentGameMode != GameMode.TITLE_CARD) {
            return;
        }

        GameMode oldMode = currentGameMode;

        if (postTitleCardDestination == PostTitleCardDestination.BONUS_STAGE) {
            // Transitioning to bonus stage after "BONUS STAGE" title card
            postTitleCardDestination = PostTitleCardDestination.LEVEL;
            currentGameMode = GameMode.BONUS_STAGE;

            // Apply deferred bonus stage setup
            applyDeferredBonusStageSetup();

            LOGGER.info("Exited bonus title card, entering BONUS_STAGE mode");
        } else if (returningFromSpecialStage) {
            currentGameMode = GameMode.LEVEL;
            returningFromSpecialStage = false;
            LOGGER.info("Exited Title Card, returned to level from special stage at checkpoint");
        } else {
            currentGameMode = GameMode.LEVEL;

            // Re-apply zone-specific player state (airborne intros like HCZ1, MGZ1)
            LevelEventProvider levelEvents = GameModuleRegistry.getCurrent().getLevelEventProvider();
            if (levelEvents instanceof com.openggf.game.sonic3k.Sonic3kLevelEventManager s3kEvents) {
                s3kEvents.applyZonePlayerState();
            }
            LOGGER.info("Exited Title Card, starting level");
        }

        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }
    }
```

- [ ] **Step 4: Add applyDeferredBonusStageSetup helper**

Add this method after `exitTitleCard()`:

```java
    /**
     * Applies bonus stage setup that was deferred until the title card completed.
     * Mirrors the setup previously done inline in doEnterBonusStage.
     */
    private void applyDeferredBonusStageSetup() {
        BonusStageProvider provider = deferredBonusProvider;
        BonusStageType type = deferredBonusType;
        BonusStageState savedState = deferredBonusState;

        // Clear deferred state
        deferredBonusProvider = null;
        deferredBonusType = null;
        deferredBonusState = null;

        if (provider == null || savedState == null) {
            LOGGER.warning("No deferred bonus stage state — skipping setup");
            return;
        }

        // Pause HUD timer (ROM: clr.b (Update_HUD_timer).w for zones $13-$15)
        // Restore saved ring count so HUD shows carried-over rings
        if (levelManager.getLevelGamestate() != null) {
            levelManager.getLevelGamestate().pauseTimer();
            levelManager.getLevelGamestate().setRings(savedState.savedRingCount());
        }

        // Set player VDP priority to HIGH (ROM lines 127411-127412)
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if (mainCode == null) mainCode = "sonic";
        var entrySprite = spriteManager.getSprite(mainCode);
        if (entrySprite instanceof AbstractPlayableSprite entryPlayable) {
            entryPlayable.setHighPriority(true);
        }

        // Play bonus stage music
        int musicId = provider.getMusicId(type);
        if (musicId >= 0) {
            AudioManager.getInstance().playMusic(musicId);
        }
    }
```

- [ ] **Step 5: Compile and run existing tests**

Run: `mvn test -Dtest=TestTitleCardPhysicsPolicy,TestSonic3kBonusTitleCard,TestSonic3kBonusTitleCardMappings -pl . -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/GameLoop.java
git commit -m "feat: wire bonus stage entry through title card

doEnterBonusStage now shows BONUS STAGE title card before entering
gameplay. PostTitleCardDestination routes exitTitleCard to BONUS_STAGE
mode. Bonus-specific setup (HUD pause, rings, priority, music)
deferred to applyDeferredBonusStageSetup."
```

---

### Task 5: Wire Bonus Stage Exit Through Zone Title Card

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`

- [ ] **Step 1: Modify doExitBonusStage to show zone title card**

Replace the body of `doExitBonusStage()` (lines 1146-1265) with:

```java
    private void doExitBonusStage(BonusStageProvider provider, BonusStageState savedState) {
        bonusStageTransitionPending = false;

        // Capture rewards BEFORE onExit() in case it resets counters.
        BonusStageProvider.BonusStageRewards rewards = provider.getRewards();

        provider.onExit();
        activeBonusStageProvider = null;

        // Clear from GameRuntime
        GameRuntime rt = RuntimeManager.getCurrent();
        if (rt != null) {
            rt.setActiveBonusStageProvider(null);
        }

        if (savedState == null) {
            LOGGER.warning("No saved state for bonus stage exit — returning to zone 0,0");
            try {
                levelManager.loadZoneAndAct(0, 0);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load fallback level", e);
            }
            currentGameMode = GameMode.LEVEL;
            fadeManager.startFadeFromBlack(null);
            return;
        }

        // Restore previous zone
        int zone = (savedState.savedZoneAndAct() >> 8) & 0xFF;
        int act = savedState.savedZoneAndAct() & 0xFF;

        try {
            levelManager.loadZoneAndAct(zone, act);
            // Consume the auto-generated title card request — we initialize it ourselves below
            levelManager.consumeTitleCardRequest();
        } catch (IOException e) {
            throw new RuntimeException("Failed to reload level after bonus stage", e);
        }

        // Restore event routine state (prevents camera lock replay)
        LevelEventProvider eventProvider = GameModuleRegistry.getCurrent().getLevelEventProvider();
        if (eventProvider instanceof AbstractLevelEventManager eventMgr) {
            eventMgr.restoreEventRoutineState(
                    savedState.dynamicResizeRoutineFg(),
                    savedState.dynamicResizeRoutineBg());
        }

        // Restore player position and collision path
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if (mainCode == null) mainCode = "sonic";
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            playable.setCentreX((short) savedState.playerX());
            playable.setCentreY((short) savedState.playerY());
            playable.setTopSolidBit(savedState.topSolidBit());
            playable.setLrbSolidBit(savedState.lrbSolidBit());
            playable.setXSpeed((short) 0);
            playable.setYSpeed((short) 0);
            playable.setGSpeed((short) 0);

            // Re-apply any shield earned during the bonus stage
            if (rewards.fireShield()) {
                playable.giveShield(ShieldType.FIRE);
            } else if (rewards.bubbleShield()) {
                playable.giveShield(ShieldType.BUBBLE);
            } else if (rewards.lightningShield()) {
                playable.giveShield(ShieldType.LIGHTNING);
            } else if (rewards.shield()) {
                playable.giveShield(ShieldType.BASIC);
            }

            // Reset VDP priority (loadZoneAndAct should clear this, but be explicit)
            playable.setHighPriority(false);
        }

        // Restore camera
        camera.setX((short) savedState.cameraX());
        camera.setY((short) savedState.cameraY());
        camera.setMaxY((short) savedState.cameraMaxY());
        camera.updatePosition(true);

        // Restore ring count + add bonus stage rewards
        if (levelManager.getLevelGamestate() != null) {
            levelManager.getLevelGamestate().setRings(savedState.savedRingCount() + rewards.rings());
            levelManager.getLevelGamestate().setTimerFrames(savedState.savedTimerFrames());
            levelManager.getLevelGamestate().resumeTimer();
        }

        // Award lives collected during the bonus stage
        if (rewards.lives() > 0) {
            for (int i = 0; i < rewards.lives(); i++) {
                gameState.addLife();
            }
        }

        // Initialize zone title card (ROM: Level routine always shows title card on reload)
        int apparentZone = (savedState.savedApparentZoneAndAct() >> 8) & 0xFF;
        int apparentAct = savedState.savedApparentZoneAndAct() & 0xFF;
        TitleCardProvider tcp = getTitleCardProviderLazy();
        if (tcp != null) {
            tcp.initialize(apparentZone, apparentAct);
        }

        // Enter TITLE_CARD mode — exitTitleCard will transition to LEVEL
        GameMode oldMode = currentGameMode;
        postTitleCardDestination = PostTitleCardDestination.LEVEL;
        currentGameMode = GameMode.TITLE_CARD;

        // Play zone music (ROM: Restore_LevelMusic during title card wait)
        int zoneMusicId = levelManager.getCurrentLevelMusicId();
        if (zoneMusicId >= 0) {
            AudioManager.getInstance().playMusic(zoneMusicId);
        }

        // Fade from black — level + zone title card become visible together
        fadeManager.startFadeFromBlack(null);

        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Exiting bonus stage, entering zone title card for zone " + zone + " act " + act);
    }
```

- [ ] **Step 2: Compile and run tests**

Run: `mvn test -Dtest=TestTitleCardPhysicsPolicy,TestSonic3kBonusTitleCard,TestSonic3kBonusTitleCardMappings -pl . -q`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/GameLoop.java
git commit -m "feat: wire bonus stage exit through zone title card

doExitBonusStage now shows the zone title card before returning to
LEVEL mode, matching ROM behavior (Level routine always displays
title card on zone reload). Zone music starts during title card."
```

---

### Task 6: Full Build Verification

**Files:** None — verification only.

- [ ] **Step 1: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests PASS, no regressions.

- [ ] **Step 2: Build executable JAR**

Run: `mvn package -pl . -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit (if any fixes needed)**

Only if Steps 1-2 revealed issues that required fixes.

---

### Task 7: Visual Verification

**Files:** None — manual testing.

- [ ] **Step 1: Test bonus stage entry title card**

Launch the game with S3K ROM. Enter a bonus stage (star post with 20+ rings). Verify:
- Screen fades to black
- "BONUS STAGE" text slides in from the right over black background
- Level fades in behind the text
- Text holds briefly, then both words slide out to the right simultaneously
- Player control begins after text exits

- [ ] **Step 2: Test bonus stage exit title card**

Complete or exit the bonus stage. Verify:
- Screen fades to black
- Zone title card appears (red banner, zone name, "ZONE", act number)
- Level fades in behind the title card
- Title card elements exit with staggered timing
- Player resumes at correct position with correct rings/shield

- [ ] **Step 3: Test all three bonus stage types**

Verify Gumball Machine, Glowing Spheres, and Slot Machine all show the same "BONUS STAGE" entry card.

- [ ] **Step 4: Verify normal zone title cards are unaffected**

Start a new game. Verify AIZ title card displays normally (4 elements, staggered exit). Load a few other zones to confirm no regression.
