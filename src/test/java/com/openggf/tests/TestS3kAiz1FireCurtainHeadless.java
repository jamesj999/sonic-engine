package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.audio.AudioManager;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.FireCurtainRenderState;
import com.openggf.game.sonic3k.events.FireCurtainStage;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.objects.AizHollowTreeObjectInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.game.GroundMode;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the AIZ1 miniboss fire curtain transition.
 *
 * <p>Exercises the fire transition state machine, fire curtain render state,
 * background tile sampling at the fire source coordinates, and palette state
 * at each phase of the transition.
 *
 * <p>The miniboss fight is bypassed by directly calling
 * {@code setEventsFg5(true)} on the AIZ events, which is equivalent to the
 * boss exit sequence completing.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz1FireCurtainHeadless {
    private static final int ZONE_AIZ = 0;
    private static final int ACT_1 = 0;
    private static final int FPS = 60;

    // User-provided coordinates for pre-miniboss area (decimal).
    private static final short START_X = (short) 12002;
    private static final short START_Y = (short) 872;

    // ROM constants (mirror Sonic3kAIZEvents).
    private static final int FIRE_SOURCE_X_AIZ1 = 0x1000;
    private static final int FIRE_OVERLAY_TILE_DEST = 0x500;

    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    private static Object oldLoadTimeSimulation;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        // The finish-queue tests hand-drive the fire transition: they queue
        // the act-2 batch through reflection, stage the overlay tiles and then
        // let the owner queue again, an order the ROM never produces. That only
        // fits the four-entry KosM FIFO when work is released immediately, so
        // pin the immediate load-time mode for this class; measured pacing is
        // covered by the trace-backed acceptance runs.
        oldLoadTimeSimulation = config.getConfigValue(SonicConfiguration.LOAD_TIME_SIMULATION);
        config.setConfigValue(SonicConfiguration.LOAD_TIME_SIMULATION, "NONE");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.LOAD_TIME_SIMULATION,
                oldLoadTimeSimulation != null ? oldLoadTimeSimulation : "FAST");
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();
        AizHollowTreeObjectInstance.resetTreeRevealCounter();
        GameServices.level().getObjectManager().reset(0);
    }

    // ---- Helpers ----

    private void teleportToMinibossArea() {
        sprite.setX(START_X);
        sprite.setY(START_Y);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAngle((byte) 0);
        sprite.setGroundMode(GroundMode.GROUND);
        sprite.setAir(false);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);
        sprite.setObjectMappingFrameControl(false);
        sprite.setForcedAnimationId(-1);
        Camera camera = fixture.camera();
        camera.updatePosition(true);
        sprite.updateSensors(sprite.getX(), sprite.getY());
    }

    /**
     * Advance sprite rightwards until the camera reaches the boss arena lock
     * area or a frame budget is exhausted. Returns the frame count used.
     */
    private int runRightUntilCameraSettles(int maxFrames) {
        int prevCameraX = fixture.camera().getX();
        int settledFrames = 0;
        for (int frame = 0; frame < maxFrames; frame++) {
            fixture.stepFrame(false, false, false, true, false);
            int cameraX = fixture.camera().getX();
            if (cameraX == prevCameraX) {
                settledFrames++;
                if (settledFrames >= 10) {
                    return frame;
                }
            } else {
                settledFrames = 0;
            }
            prevCameraX = cameraX;
        }
        return maxFrames;
    }

    private Sonic3kAIZEvents getAizEvents() {
        Sonic3kLevelEventManager lem =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        assertNotNull(lem, "Sonic3kLevelEventManager should exist");
        Sonic3kAIZEvents events = lem.getAizEvents();
        assertNotNull(events, "AIZ events should be initialized");
        return events;
    }

    // ---- Tests ----

    @Test
    public void backgroundMapAtFireSourceCoordinates_isMostlyEmpty() {
        teleportToMinibossArea();
        fixture.stepIdleFrames(1);

        LevelManager lm = GameServices.level();
        // Sample the BG sky area: x=0x1000..0x1140, y=0x20..0xE0.
        // The fire tiles live at Y >= 0x100; this range is the empty sky above.
        int emptyCount = 0;
        int totalCount = 0;
        for (int y = 0x20; y < 0xE0; y += 8) {
            for (int x = FIRE_SOURCE_X_AIZ1; x < FIRE_SOURCE_X_AIZ1 + 0x140; x += 8) {
                int desc = lm.getBackgroundTileDescriptorAtWorld(x, y);
                totalCount++;
                if ((desc & 0x7FF) == 0) {
                    emptyCount++;
                }
            }
        }

        // The diagnostic logs showed 142/142 empty at the fire source area (sky tiles).
        // This test documents the current state for regression tracking.
        assertTrue(emptyCount > totalCount / 2, "Expected most BG tiles at fire source (0x1000, 0x20-0xE0) to be empty sky; "
                + "got " + emptyCount + "/" + totalCount + " empty");
    }

    @Test
    public void backgroundMapAtLowerYCoordinates_hasNonEmptyTiles() {
        teleportToMinibossArea();
        fixture.stepIdleFrames(1);

        LevelManager lm = GameServices.level();
        // The full-screen capture in the diagnostic showed non-empty tiles at lower Y.
        // The BG has actual fire/terrain tiles below the sky area.
        int nonEmptyCount = 0;
        int totalCount = 0;
        for (int y = 0x100; y < 0x300; y += 8) {
            for (int x = FIRE_SOURCE_X_AIZ1; x < FIRE_SOURCE_X_AIZ1 + 0x140; x += 8) {
                int desc = lm.getBackgroundTileDescriptorAtWorld(x, y);
                totalCount++;
                if ((desc & 0x7FF) != 0) {
                    nonEmptyCount++;
                }
            }
        }

        assertTrue(nonEmptyCount > 0, "Expected some non-empty BG tiles at lower Y coords (0x100-0x300); "
                + "got " + nonEmptyCount + "/" + totalCount + " non-empty");
    }

    @Test
    public void fireTransitionTriggered_producesActiveRenderState() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);
        // First frame starts transition; rise advances on subsequent frames.
        // BG camera must scroll past FIRE_TILE_START_Y (0x100) for visible cover.
        fixture.stepIdleFrames(10);

        assertTrue(events.isFireTransitionActive(), "Fire transition should be active after Events_fg_5 set");

        FireCurtainRenderState state = events.getFireCurtainRenderState(224);
        assertTrue(state.active(), "Render state should be active");
        assertTrue(state.coverHeightPx() > 0, "Cover height should be > 0, got " + state.coverHeightPx());
        assertEquals(FireCurtainStage.AIZ1_RISING, state.stage(), "Stage should be AIZ1_RISING");
    }

    @Test
    public void fireTransitionRestoresLevelMusicAfterMinibossEscapeTimer()
            throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.playMusic(Sonic3kMusic.MINIBOSS.id);
        audio.presentFrame(PresentationMode.SILENT);

        LevelManager levelManager = GameServices.level();
        var currentAct = LevelManager.class.getDeclaredField("currentAct");
        currentAct.setAccessible(true);
        currentAct.setInt(levelManager, 1);
        levelManager.setApparentAct(0);

        Sonic3kAIZEvents events = getAizEvents();
        var timer = Sonic3kAIZEvents.class
                .getDeclaredField("fireMusicRestoreTimer");
        timer.setAccessible(true);
        timer.setInt(events, 0);
        var advance = Sonic3kAIZEvents.class
                .getDeclaredMethod("advanceFireMusicRestore");
        advance.setAccessible(true);
        advance.invoke(events);
        audio.presentFrame(PresentationMode.SILENT);

        assertNotNull(audio.captureLogicalSnapshot().presentation().activeMusic(),
                "the escape timer must leave level music active");
        assertEquals(Sonic3kMusic.AIZ1.id,
                audio.captureLogicalSnapshot().presentation().activeMusic().musicId(),
                "Restore_LevelMusic must use apparent AIZ1 while AIZ2 resources are loaded");
    }

    @Test
    public void fireOverlayTilesLoaded_inRenderState() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);
        fixture.stepIdleFrames(1);

        FireCurtainRenderState state = events.getFireCurtainRenderState(224);
        assertTrue(state.fireOverlayTileCount() > 0, "Fire overlay tiles should be loaded, got count=" + state.fireOverlayTileCount());
        assertEquals(FIRE_OVERLAY_TILE_DEST, state.fireOverlayTileBase(), "Fire overlay tile base should be 0x500");
    }

    @Test
    public void fireCurtainRise_progressesThroughPhases() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);

        boolean sawRising = false;
        boolean sawRefresh = false;
        boolean sawFinish = false;
        int risingCoverMax = 0;

        for (int frame = 0; frame < 5 * FPS; frame++) {
            fixture.stepIdleFrames(1);

            FireCurtainRenderState state = events.getFireCurtainRenderState(224);
            if (state.active()) {
                risingCoverMax = Math.max(risingCoverMax, state.coverHeightPx());
                switch (state.stage()) {
                    case AIZ1_RISING -> sawRising = true;
                    case AIZ1_REFRESH -> sawRefresh = true;
                    case AIZ1_FINISH -> sawFinish = true;
                    default -> { }
                }
            }

            if (sawFinish) {
                break;
            }
        }

        assertTrue(sawRising, "Should pass through AIZ1_RISING stage");
        assertTrue(sawRefresh, "Should progress to AIZ1_REFRESH stage");
        assertTrue(sawFinish, "Should reach AIZ1_FINISH stage");
        assertTrue(risingCoverMax >= 224, "Cover height should reach full screen (224px), got " + risingCoverMax);
    }

    @Test
    public void finishQueueWaitsForDirectAndModuleWorkWhenVblankPhaseIsReady()
            throws Exception {
        drainStartupKosWork();
        Sonic3kAIZEvents events = getAizEvents();
        stageFireOverlay(events);
        events.setFireSequencePhaseOrdinal(3);
        events.setFirePhaseFrames(63);
        queueAct2TransitionArt(events);
        GameServices.level().getObjectManager().initVblaCounter(2);

        fixture.stepIdleFrames(1);
        assertFalse(events.isAct2TransitionRequested(),
                "the fire owner must not bypass the three earlier Queue_Kos jobs");
        for (int frame = 0;
                frame < 100_000 && !events.isAct2TransitionRequested();
                frame++) {
            fixture.stepIdleFrames(1);
        }

        assertTrue(events.isAct2TransitionRequested(),
                "the handoff should occur after all three direct jobs and both "
                        + "module parents become ready");
    }

    @Test
    public void finishQueueWaitsForNextReadyVblankPhase() throws Exception {
        drainStartupKosWork();
        Sonic3kAIZEvents events = getAizEvents();
        stageFireOverlay(events);
        events.setFireSequencePhaseOrdinal(3);
        events.setFirePhaseFrames(63);
        queueAct2TransitionArt(events);
        GameServices.level().getObjectManager().initVblaCounter(0);

        fixture.stepIdleFrames(1);
        assertFalse(events.isAct2TransitionRequested(),
                "minimum work alone must not bypass the direct or module queues");
        for (int frame = 0;
                frame < 100_000 && !events.isAct2TransitionRequested();
                frame++) {
            fixture.stepIdleFrames(1);
        }

        assertTrue(events.isAct2TransitionRequested(),
                "the queue should hand off at the next eligible phase after "
                        + "all direct and module work is ready");
    }

    @Test
    public void finishQueueCompletesAfterSubmittedKosArtIsReady() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);
        for (int frame = 0;
                frame < 600 && !events.isAct2TransitionRequested();
                frame++) {
            fixture.stepIdleFrames(1);
        }

        assertTrue(events.isAct2TransitionRequested(),
                "the finish phase must hand off after its submitted AIZ2 art is ready");
    }

    private static void queueAct2TransitionArt(Sonic3kAIZEvents events)
            throws Exception {
        var method = Sonic3kAIZEvents.class.getDeclaredMethod("queueAct2KosArt");
        method.setAccessible(true);
        method.invoke(events);
    }

    private void stageFireOverlay(Sonic3kAIZEvents events) throws Exception {
        var method = Sonic3kAIZEvents.class.getDeclaredMethod(
                "ensureFireOverlayTilesLoaded");
        method.setAccessible(true);
        for (int frame = 0;
                frame < 100_000 && !events.isFireOverlayTilesLoaded();
                frame++) {
            method.invoke(events);
            fixture.stepIdleFrames(1);
        }
        assertTrue(events.isFireOverlayTilesLoaded(),
                "AIZ1 loc_1C5C6 must retain its prepared fire-overlay payload");
        assertTrue(events.getFireOverlayTileCount() > 0);
    }

    private void drainStartupKosWork() {
        fixture.stepIdleFrames(1);
        for (int frame = 0;
                frame < 100_000
                        && (GameServices.hardwareTiming().incompleteCount(
                        com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE) > 0
                        || GameServices.hardwareTiming().incompleteCount(
                        com.openggf.game.timing.HardwareWorkKind.KOS_DECOMPRESSION_QUEUE) > 0);
                frame++) {
            fixture.stepIdleFrames(1);
        }
        assertEquals(0, GameServices.hardwareTiming().incompleteCount(
                com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE));
        assertEquals(0, GameServices.hardwareTiming().incompleteCount(
                com.openggf.game.timing.HardwareWorkKind.KOS_DECOMPRESSION_QUEUE));
    }

    @Test
    public void fireCurtainRise_waveOffsetsArePopulated() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);

        // Step enough frames for cover height to grow and wave to animate
        for (int frame = 0; frame < 30; frame++) {
            fixture.stepIdleFrames(1);
        }

        FireCurtainRenderState state = events.getFireCurtainRenderState(224);
        assertTrue(state.active(), "Render state should be active");
        assertEquals(20, state.columnWaveOffsetsPx().length, "Should have 20 column wave offsets");

        // Wave offsets should not ALL be zero after some frames of animation
        boolean anyNonZero = false;
        for (int offset : state.columnWaveOffsetsPx()) {
            if (offset != 0) {
                anyNonZero = true;
                break;
            }
        }
        assertTrue(anyNonZero, "Wave offsets should contain non-zero values for wavy edge effect");
    }

    @Test
    public void paletteLine4_hasFireColorsAfterTransitionStart() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);
        fixture.stepIdleFrames(1);

        // After beginFireTransition(), palette line 4 (index 3) colors 1-6
        // should have fire transition words: 0x004E, 0x006E, 0x00AE, 0x00CE, 0x02EE, 0x0AEE
        Palette pal3 = GameServices.level().getCurrentLevel().getPalette(3);
        assertNotNull(pal3, "Palette line 4 (index 3) should exist");

        // Convert expected fire words to RGB for comparison
        int[] expectedFireWords = {0x004E, 0x006E, 0x00AE, 0x00CE, 0x02EE, 0x0AEE};
        for (int i = 0; i < expectedFireWords.length; i++) {
            Palette.Color color = pal3.getColor(i + 1);
            assertNotNull(color, "Palette color " + (i + 1) + " should exist");
            int segaWord = toSegaWord(color);
            assertEquals(expectedFireWords[i], segaWord, "Palette line 4 color " + (i + 1) + " should be fire color 0x"
                    + Integer.toHexString(expectedFireWords[i]));
        }
    }

    @Test
    public void paletteLine4_fullFirePaletteAfterMutation() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);

        // Step until mutation phase (AIZ1_REFRESH = mutation applied)
        boolean reachedRefresh = false;
        for (int frame = 0; frame < 5 * FPS; frame++) {
            fixture.stepIdleFrames(1);
            FireCurtainRenderState state = events.getFireCurtainRenderState(224);
            if (state.stage() == FireCurtainStage.AIZ1_REFRESH
                    || state.stage() == FireCurtainStage.AIZ1_FINISH) {
                reachedRefresh = true;
                break;
            }
        }
        assertTrue(reachedRefresh, "Should reach post-mutation phase");

        // After mutation, PalPointers #$0B should have been loaded, overwriting
        // ALL 16 colors of palette line 4 (index 3) with fire values.
        // Colors 7-15 should no longer have green AIZ1 values.
        Palette pal3 = GameServices.level().getCurrentLevel().getPalette(3);
        assertNotNull(pal3, "Palette line 4 should exist after mutation");

        // Check that colors 7-15 are NOT the typical green AIZ1 waterfall colors.
        // Normal AIZ1 palette line 4 colors 12-14 cycle between green values.
        // After fire palette, none should be pure green (no blue/red component).
        boolean anyGreenOnly = false;
        StringBuilder colorDump = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            Palette.Color c = pal3.getColor(i);
            if (c != null) {
                int sega = toSegaWord(c);
                colorDump.append(String.format(" [%d]=0x%04X", i, sega));
                // "Green-only" = green component set but red and blue are zero
                int r = (sega >> 1) & 0x7;
                int g = (sega >> 5) & 0x7;
                int b = (sega >> 9) & 0x7;
                if (i >= 7 && g > 2 && r == 0 && b == 0) {
                    anyGreenOnly = true;
                }
            }
        }
        assertTrue(!anyGreenOnly, "After mutation, palette line 4 should not have green-only colors in slots 7-15."
                + " Palette dump:" + colorDump);
    }

    @Test
    public void fireTransitionScrollActive_duringCurtainPhases() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);
        fixture.stepIdleFrames(1);

        assertTrue(events.isFireTransitionScrollActive(), "Fire transition scroll should be active during curtain");
    }

    @Test
    public void coverHeightGrowsOverTime_duringRisingPhase() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);

        int prevCover = 0;
        int growthCount = 0;
        for (int frame = 0; frame < 3 * FPS; frame++) {
            fixture.stepIdleFrames(1);
            FireCurtainRenderState state = events.getFireCurtainRenderState(224);
            if (state.active() && state.stage() == FireCurtainStage.AIZ1_RISING) {
                int cover = state.coverHeightPx();
                if (cover > prevCover) {
                    growthCount++;
                }
                prevCover = cover;
            }
        }

        assertTrue(growthCount >= 3, "Cover height should grow multiple times during rising phase, grew "
                + growthCount + " times");
    }

    @Test
    public void fireOverlayPatterns_haveNonTransparentPixelData() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        // Trigger fire transition to ensure overlay tiles are loaded
        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);
        fixture.stepIdleFrames(1);

        FireCurtainRenderState state = events.getFireCurtainRenderState(224);
        int tileBase = state.fireOverlayTileBase();
        int tileCount = state.fireOverlayTileCount();
        assertTrue(tileCount > 0, "Fire overlay tiles should be loaded");

        com.openggf.level.Level level = GameServices.level().getCurrentLevel();
        int patternCount = level.getPatternCount();
        int patternsChecked = 0;
        int patternsWithContent = 0;
        int totalNonZeroPixels = 0;
        int nullPatterns = 0;
        StringBuilder firstPatternDump = new StringBuilder();

        for (int i = 0; i < Math.min(tileCount, 20); i++) {
            int patternIndex = tileBase + i;
            if (patternIndex >= patternCount) {
                break;
            }
            com.openggf.level.Pattern pattern = level.getPattern(patternIndex);
            if (pattern == null) {
                nullPatterns++;
                continue;
            }
            patternsChecked++;
            boolean hasContent = false;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    byte pixel = pattern.getPixel(x, y);
                    if (pixel != 0) {
                        hasContent = true;
                        totalNonZeroPixels++;
                    }
                    // Dump first pattern's pixel data for debugging
                    if (i == 0) {
                        firstPatternDump.append(String.format("%X", pixel & 0xF));
                    }
                }
                if (i == 0) {
                    firstPatternDump.append("|");
                }
            }
            if (hasContent) {
                patternsWithContent++;
            }
        }

        // Also check patterns BELOW the fire overlay range (regular level art)
        // to verify the pattern array is functional
        int regularContent = 0;
        for (int i = 10; i < Math.min(30, patternCount); i++) {
            com.openggf.level.Pattern p = level.getPattern(i);
            if (p != null) {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        if (p.getPixel(x, y) != 0) {
                            regularContent++;
                            break;
                        }
                    }
                    if (regularContent > 0) break;
                }
            }
        }

        assertTrue(patternsChecked > 0, "Should have checked some fire overlay patterns; checked=" + patternsChecked
                + " null=" + nullPatterns
                + " patternCount=" + patternCount
                + " tileBase=0x" + Integer.toHexString(tileBase)
                + " tileCount=" + tileCount
                + " regularArtHasContent=" + (regularContent > 0)
                + " firstPatternPixels=" + firstPatternDump);
        assertTrue(patternsWithContent > 0 && totalNonZeroPixels > 10, "Fire overlay patterns should have non-transparent pixel data; "
                + patternsWithContent + "/" + patternsChecked + " had content, "
                + totalNonZeroPixels + " total non-zero pixels"
                + " null=" + nullPatterns
                + " patternCount=" + patternCount
                + " tileBase=0x" + Integer.toHexString(tileBase)
                + " regularArtHasContent=" + (regularContent > 0)
                + " firstPatternPixels=" + firstPatternDump);
    }

    @Test
    public void bgSamplingWithVdpMapping_findsFireTilesAfterBgYReachesFireArea() {
        teleportToMinibossArea();
        runRightUntilCameraSettles(3 * FPS);

        Sonic3kAIZEvents events = getAizEvents();
        events.setEventsFg5(true);
        fixture.stepIdleFrames(1);

        LevelManager lm = GameServices.level();
        // With VDP mapping: screen bottom (drawY=216) maps to sourceY = bgY + 216.
        // Fire tiles exist at BG Y >= 0x100. So fire appears when bgY + 216 >= 0x100,
        // i.e., bgY >= 0x100 - 216 â‰ˆ 0x28. Since bgY starts at 0x20 and increases,
        // fire tiles should appear within a few frames.
        int fireTilesFound = 0;
        for (int frame = 0; frame < 30; frame++) {
            fixture.stepIdleFrames(1);
            int bgY = events.getFireTransitionBgY();
            // Sample at screen bottom using VDP mapping
            int sourceY = bgY + 216; // screen bottom
            int desc = lm.getBackgroundTileDescriptorAtWorld(FIRE_SOURCE_X_AIZ1, sourceY);
            int patIdx = desc & 0x7FF;
            if (patIdx >= 0x500) {
                fireTilesFound++;
            }
        }
        assertTrue(fireTilesFound > 0, "BG sampling with VDP mapping should find fire tiles (0x500+) "
                + "at screen bottom within 30 frames; found " + fireTilesFound);
    }

    // ---- Utility ----

    /** Convert a Palette.Color back to a Mega Drive color word. */
    private static int toSegaWord(Palette.Color color) {
        if (color == null) {
            return 0;
        }
        int r3 = ((color.r & 0xFF) * 7 + 127) / 255;
        int g3 = ((color.g & 0xFF) * 7 + 127) / 255;
        int b3 = ((color.b & 0xFF) * 7 + 127) / 255;
        return ((b3 & 0x7) << 9) | ((g3 & 0x7) << 5) | ((r3 & 0x7) << 1);
    }
}
