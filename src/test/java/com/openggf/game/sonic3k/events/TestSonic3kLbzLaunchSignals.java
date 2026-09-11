package com.openggf.game.sonic3k.events;

import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.camera.Camera;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.LevelConstants;
import com.openggf.level.Pattern;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_3K)
@ExtendWith(SingletonResetExtension.class)
class TestSonic3kLbzLaunchSignals {

    @BeforeEach
    void setUp() {
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void launchSignalWrappersDelegateWithoutReusingEventsFg5ReloadFlag() throws Exception {
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        events.setEventsFg5(true);
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        GameServices.zoneRuntimeRegistry().install(state);

        events.requestLaunchStart();
        events.requestPadCollapseStart();
        events.requestFinalFall();
        events.requestLbz2RideAnimatedTiles();
        state.setLaunchActive(true);
        state.setLaunchYDelta(-5);
        events.registerLaunchRiderAnchor(0x3456);

        assertTrue(eventsFg5(events), "LBZ1 -> LBZ2 reload flag must stay independent");
        assertTrue(events.isLaunchActive());
        assertEquals(-5, events.getLaunchYDelta());
        assertTrue(events.consumeLaunchStartRequested());
        assertFalse(events.consumeLaunchStartRequested());
        assertTrue(events.consumePadCollapseStartRequested());
        assertFalse(events.consumePadCollapseStartRequested());
        assertTrue(events.consumeFinalFallRequested());
        assertFalse(events.consumeFinalFallRequested());
        assertTrue(events.consumeLbz2RideAnimatedTilesRequested());
        assertTrue(events.consumeLbz2RideAnimatedTilesRequested(),
                "Anim_Counters+$F is a persistent flag, not a one-frame launch signal");
        assertEquals(0x3456, state.getLaunchRiderAnchorId().orElseThrow());
        assertTrue(eventsFg5(events), "consuming launch signals must not consume eventsFg5");
    }

    @Test
    void act2LaunchStartInitializesCameraWaterShakeAndMotionState() {
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        LbzZoneRuntimeState state = installAct2State();
        Camera camera = GameServices.camera();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4480, (short) 0x0700);
        camera.setFocusedSprite(player);
        camera.setX((short) 0x4300);
        camera.setY((short) 0x0660);

        events.requestLaunchStart();
        events.update(1, 1);

        assertTrue(state.isLaunchActive());
        assertTrue(state.isDeathEggRumble());
        assertEquals(0x4390, Short.toUnsignedInt(camera.getMaxX()));
        assertEquals(0x0668, Short.toUnsignedInt(camera.getMaxY()));
        assertEquals(0x0668, Short.toUnsignedInt(camera.getMaxYTarget()));
        assertEquals(0x3B, state.getPreLaunchDelay(),
                "the launch start frame consumes one pre-launch delay tick after initialization");
        assertEquals(0x1E00, state.getFgLaunchSpeed());
        assertEquals(0x6200, state.getBgLaunchSpeed());
        assertEquals(0, state.getLaunchYDelta(), "pre-launch delay should publish no rider delta");
    }

    @Test
    void launchStartReframesDeathEggSmallBackgroundRows() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 1)
                .build();
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level);
        com.openggf.level.Map map = level.getMap();
        assertNotNull(map);
        int width = map.getWidth();
        for (int col = 0; col < width; col++) {
            map.setValue(1, col, 0, (byte) 0x11);
            map.setValue(1, col, 1, (byte) 0x22);
            map.setValue(1, col, 2, (byte) (0x30 + (col & 0x0F)));
            map.setValue(1, col, 3, (byte) (0x50 + (col & 0x0F)));
        }
        Camera camera = fixture.camera();
        camera.setFocusedSprite(new TestablePlayableSprite("sonic", (short) 0x4480, (short) 0x0700));
        camera.setX((short) 0x4300);
        camera.setY((short) 0x0660);

        events.requestLaunchStart();
        events.update(1, 1);

        for (int col = 0; col < width; col++) {
            assertEquals(map.getValue(1, col, 2), map.getValue(1, col, 0),
                    "ROM LBZ2BGE_Normal copies BG row 2 over row 0 when the Death Egg launch starts");
            assertEquals(map.getValue(1, col, 3), map.getValue(1, col, 1),
                    "ROM LBZ2BGE_Normal copies BG row 3 over row 1 when the Death Egg launch starts");
        }
    }

    @Test
    void launchMotionPublishesYDeltaAndWaterTargetAfterPrelaunchDelay() {
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        LbzZoneRuntimeState state = installAct2State();
        Camera camera = GameServices.camera();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4430, (short) 0x0700);
        camera.setFocusedSprite(player);
        camera.setX((short) 0x4390);
        camera.setY((short) 0x0668);

        events.requestLaunchStart();
        events.update(1, 1);
        state.setPreLaunchDelay(0);
        events.update(1, 2);

        assertEquals(0x1E00, state.getFgAccum());
        assertEquals(0x6200, state.getBgAccum());
        assertEquals(0, state.getLaunchYDelta(),
                "first subpixel launch accumulation remains below one whole pixel");

        boolean sawRiderDelta = false;
        for (int frame = 3; frame < 16; frame++) {
            events.update(1, frame);
            sawRiderDelta |= state.getLaunchYDelta() > 0;
        }

        assertTrue(sawRiderDelta,
                "combined foreground/background high-word delta is the rider/cameo coupling value");
        assertTrue(state.getWaterTargetY() > 0x0668,
                "launch motion drains water by moving the target downward");
    }

    @Test
    void padCollapseAndFinalFallSignalsDriveLaunchPhases() {
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        LbzZoneRuntimeState state = installAct2State();
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(new TestablePlayableSprite("sonic", (short) 0x4430, (short) 0x0700));
        camera.setX((short) 0x4390);
        camera.setY((short) 0x0668);
        events.requestLaunchStart();
        events.update(1, 1);
        state.setPreLaunchDelay(0);

        events.requestPadCollapseStart();
        events.update(1, 2);

        assertTrue(state.isPadCollapseActive());
        assertTrue(state.getFgLaunchSpeed() < 0x1E00,
                "pad collapse starts the falling deceleration path");
        assertTrue(state.isLaunchActive(),
                "event-owned launch runtime should remain active through pad collapse");

        events.requestFinalFall();
        int beforeY = Short.toUnsignedInt(camera.getY());
        state.setPadCollapseActive(false);
        state.setWaterDisabled(true);
        state.setDetachWindowCopyTimer(0);
        events.update(1, 3);

        assertTrue(state.isFinalFallActive());
        assertTrue(state.isLaunchActive(),
                "event-owned final fall should not clear launchActive; ship release owns no runtime shutdown");
        assertEquals(beforeY - 2, Short.toUnsignedInt(camera.getY()),
                "final fall scrolls the camera upward two pixels per frame");
    }

    @Test
    void finalFallSignalWaitsForPlatformDetachToFinish() {
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        LbzZoneRuntimeState state = installAct2State();
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(new TestablePlayableSprite("sonic", (short) 0x4430, (short) 0x0700));
        camera.setX((short) 0x4390);
        camera.setY((short) 0x0668);
        state.setLaunchActive(true);
        state.setPadCollapseActive(true);
        state.setLaunchFallingAccelActive(true);
        state.setWaterDisabled(true);
        state.setPreLaunchDelay(0);
        state.setFgLaunchSpeed(0);
        state.setBgLaunchSpeed(-0x100);
        state.setWaterTargetY(0x0668);

        events.requestFinalFall();
        events.update(1, 3);

        assertFalse(state.isFinalFallActive(),
                "LBZ2BGE_PlatformDetach ignores the third Events_fg_5 use until the detach state has advanced");
        assertTrue(state.consumeFinalFallRequested(),
                "the final-fall signal must remain pending instead of being lost while the pad is detaching");
        assertEquals(0x0668, Short.toUnsignedInt(camera.getY()),
                "camera final fall must not start before the visual/collision platform detach has completed");
    }

    @Test
    void finalDetachDispatchWithFallSignalSkipsLaunchAccelerationAndMotion() {
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        LbzZoneRuntimeState state = installAct2State();
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(new TestablePlayableSprite("sonic", (short) 0x4430, (short) 0x0700));
        camera.setX((short) 0x4390);
        camera.setY((short) 0x0668);
        state.setLaunchActive(true);
        state.setPadCollapseActive(true);
        state.setLaunchFallingAccelActive(true);
        state.setWaterDisabled(true);
        state.setDetachScroll(0x28);
        state.setBgLaunchSpeed(-0x100);
        state.setBgAccum(0x123400);
        events.requestFinalFall();

        events.update(1, 4);

        assertTrue(state.isFinalFallActive());
        assertEquals(0x0666, Short.toUnsignedInt(camera.getY()));
        assertEquals(-0x100, state.getBgLaunchSpeed(),
                "LBZ2BGE_PlatformDetach branches directly to LBZ2BGE_Falling, bypassing sub_545DA");
        assertEquals(0x123400, state.getBgAccum());
    }

    @Test
    void finalFallSignalWaitsForDetachMilestoneNotJustInactivePad() {
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        LbzZoneRuntimeState state = installAct2State();
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(new TestablePlayableSprite("sonic", (short) 0x4430, (short) 0x0700));
        camera.setX((short) 0x4390);
        camera.setY((short) 0x0668);
        state.setLaunchActive(true);
        state.setPadCollapseActive(false);
        state.setLaunchFallingAccelActive(true);
        state.setWaterDisabled(false);
        state.setDetachWindowCopyTimer(0);

        events.requestFinalFall();
        events.update(1, 3);

        assertFalse(state.isFinalFallActive(),
                "LBZ2BGE_Falling must not start merely because the local pad-collapse latch is inactive");
        assertTrue(state.consumeFinalFallRequested(),
                "the third Events_fg_5 signal remains pending until LBZ2BGE_PlatformDetach has been reached");
        assertEquals(0x0668, Short.toUnsignedInt(camera.getY()),
                "camera must not move Sonic down before the platform detach path has armed");
    }

    @Test
    void padCollapseWaitsForNegativeBgSpeedBeforeDetachAndTerrainClear() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 1)
                .build();
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        S3kRuntimeArtCoordinator.current().resetForMissingSnapshot();
        GameServices.hardwareTiming().resetForMissingSnapshot();
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(GameServices.zoneRuntimeRegistry()).orElseThrow();
        Level level = GameServices.level().getCurrentLevel();
        Camera camera = fixture.camera();
        camera.setFocusedSprite(new TestablePlayableSprite("sonic", (short) 0x4430, (short) 0x0700));
        camera.setX((short) 0x4390);
        camera.setY((short) 0x0668);
        seedLaunchPadBlocks(level, 0x51);
        int[] visiblePadSample = findVisibleLaunchPadDescriptor();
        int padWorldX = visiblePadSample[0];
        int padWorldY = visiblePadSample[1];
        int visiblePadDescriptor = visiblePadSample[2];
        assertNotEquals(0, visiblePadDescriptor,
                "test setup must seed a visible launch-pad tile before the ROM layout clear");
        assertEquals(visiblePadDescriptor,
                GameServices.level().getForegroundTileDescriptorFromTilemapAtWorld(padWorldX, padWorldY),
                "test setup must start with the original platform visible in the live Scroll A tilemap");

        state.setLaunchActive(true);
        state.setPreLaunchDelay(0);
        state.setFgLaunchSpeed(0);
        state.setBgLaunchSpeed(0x0200);
        state.setWaterTargetY(0x0668);

        events.requestPadCollapseStart();
        for (int frame = 4; frame <= 8; frame += 4) {
            events.update(1, frame);
        }

        assertTrue(state.isPadCollapseActive());
        assertEquals(0, state.getDetachScroll(),
                "detach scroll must not tick while pad collapse is still waiting for negative BG speed");
        assertFalse(state.isWaterDisabled(),
                "water should stay enabled until the collapse enters the detach phase");
        assertLaunchPadBlocks(level, 0x51,
                "terrain must not clear before bgLaunchSpeed goes negative");

        int frame = 9;
        while (state.getBgLaunchSpeed() >= 0) {
            events.update(1, frame++);
        }

        assertTrue(state.isWaterDisabled(),
                "negative bgLaunchSpeed starts the detach phase and clears the water flag");
        assertEquals(0, state.getDetachScroll(),
                "the transition frame arms detach but does not consume the every-four-frame scroll tick");
        assertLaunchPadBlocks(level, 0x51,
                "terrain remains intact on the frame detach is armed");
        int tileColumn = (padWorldX - 0x4390) / Pattern.PATTERN_WIDTH;
        int tileRow = (padWorldY - 0x668) / Pattern.PATTERN_HEIGHT;
        assertFalse(events.isLbz2CopiedWindowActiveForTest(),
                "SpecialVInt_LBZ2WindowCopy fills the window nametable before the window is enabled");

        for (int copyFrame = 0; copyFrame < 0x1C; copyFrame++) {
            events.update(1, frame++);
            assertEquals(0, state.getDetachScroll(),
                    "SpecialVInt_LBZ2WindowCopy must finish before Events_bg+$16 starts detaching the pad");
            assertTrue(state.isPadCollapseActive(),
                    "pad collapse remains in LBZ2BGE_PlatformDetach while the window copy is pending");
            assertLaunchPadBlocks(level, 0x51,
                    "terrain must not clear while the ROM is copying the detachable platform window");
        }
        assertFalse(events.isLbz2CopiedWindowActiveForTest(),
                "28 row-copy VBlanks leave ScrollAClear pending");
        events.update(1, frame++);
        assertTrue(events.isLbz2CopiedWindowActiveForTest(),
                "SpecialVInt_LBZ2ScrollAClear enables the copied window immediately before detach scroll begins");
        assertEquals(0,
                GameServices.level().getForegroundTileDescriptorFromTilemapAtWorld(padWorldX, padWorldY),
                "SpecialVInt_LBZ2ScrollAClear clears Scroll A behind the copied window so the platform is not "
                        + "drawn twice");
        assertEquals(visiblePadDescriptor,
                events.getLbz2CopiedWindowDescriptorForTest(padWorldX, padWorldY),
                "the copied VDP window must retain platform pixels after Scroll A is cleared behind it");
        assertLaunchPadBlocks(level, 0x51,
                "Scroll A VRAM clear must not remove collision/layout before the ROM's later $28 detach clear");
        int originalCameraX = Short.toUnsignedInt(camera.getX());
        int originalCameraY = Short.toUnsignedInt(camera.getY());
        int copiedScreenX = events.getLbz2CopiedWindowRenderWorldXForTest(camera, tileColumn)
                - originalCameraX;
        int copiedScreenY = events.getLbz2CopiedWindowRenderWorldYForTest(camera, tileRow)
                - originalCameraY;
        camera.setX((short) (originalCameraX + 0x20));
        camera.setY((short) (originalCameraY + 0x20));
        assertEquals(copiedScreenX,
                events.getLbz2CopiedWindowRenderWorldXForTest(camera, tileColumn)
                        - Short.toUnsignedInt(camera.getX()),
                "SpecialVInt_LBZ2ScrollAClear exposes a VDP window, so copied platform pixels stay screen-fixed");
        assertEquals(copiedScreenY,
                events.getLbz2CopiedWindowRenderWorldYForTest(camera, tileRow)
                        - Short.toUnsignedInt(camera.getY()),
                "SpecialVInt_LBZ2ScrollAClear exposes a VDP window, so copied platform pixels stay screen-fixed");
        camera.setX((short) originalCameraX);
        camera.setY((short) originalCameraY);

        // The standing platform belongs to the unscrolled VDP window.
        int bandScreenYAtDetach0 = events.getLbz2CopiedWindowRenderWorldYForTest(camera, tileRow)
                - originalCameraY;
        state.setDetachScroll(0x10);
        int bandScreenYAtDetach10 = events.getLbz2CopiedWindowRenderWorldYForTest(camera, tileRow)
                - originalCameraY;
        assertEquals(bandScreenYAtDetach0, bandScreenYAtDetach10,
                "detach scroll must not lift the window platform into grounded Sonic");
        assertEquals(40, events.foregroundWindow().top());
        state.setDetachScroll(0);

        int detachTickChecks = 0;
        while (state.isPadCollapseActive()) {
            events.update(1, frame++);
            if (((frame - 1) & 3) == 0) {
                detachTickChecks++;
            }
            assertTrue(detachTickChecks <= 0x29,
                    "pad collapse should finish after $28 visible detach ticks plus the ROM clear check");
        }

        assertEquals(0x29, detachTickChecks);
        assertEquals(0, state.getDetachScroll());
        assertTrue(state.isLaunchActive(),
                "event-owned collapse completion should leave launchActive set for final-fall/transition consumers");
        assertLaunchPadBlocks(level, 0,
                "terrain clear must happen only when the detach scroll reaches $28");
        assertEquals(visiblePadDescriptor,
                events.getLbz2CopiedWindowDescriptorForTest(padWorldX, padWorldY),
                "SpecialVInt_LBZ2WindowCopy keeps copied platform pixels available for rendering even though "
                        + "the collision/layout cells have been cleared");
        assertTrue(events.isLbz2CopiedWindowActiveForTest());
        events.update(1, frame++);
        assertTrue(events.isLbz2CopiedWindowActiveForTest(), "ScrollAClear2 leaves window enabled");
        events.update(1, frame);
        assertFalse(events.isLbz2CopiedWindowActiveForTest(), "WindowClear restores Plane A then disables window");
        assertEquals(visiblePadDescriptor,
                GameServices.level().getForegroundTileDescriptorFromTilemapAtWorld(padWorldX, padWorldY),
                "WindowClear restores the repeated window row into the six hidden Plane A rows");
    }

    @Test
    void deathEggRumbleClearsWhenScreenShakeStops() {
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        LbzZoneRuntimeState state = installAct2State();
        state.setLaunchActive(true);
        state.setDeathEggRumble(true);
        state.setPreLaunchDelay(0);
        state.setFgLaunchSpeed(0);
        state.setBgLaunchSpeed(0);
        GameServices.gameState().setScreenShakeActive(false);

        events.update(1, 1);

        assertFalse(state.isDeathEggRumble(),
                "Death Egg rumble latch should clear as soon as the screen-shake flag is off");
    }

    @Test
    void deathEggTerrainSwapThresholdQueuesOneShotWithoutEventsFg5() throws Exception {
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        LbzZoneRuntimeState state = installAct2State();
        events.setEventsFg5(true);
        Camera camera = GameServices.camera();
        camera.setX((short) 0x3BC0);
        camera.setY((short) 0x0500);

        events.update(1, 1);
        events.update(1, 2);

        assertTrue(state.isDeathEggTerrainSwapQueued());
        assertFalse(state.isDeathEggTerrainSwapApplied(),
                "the applied latch is reserved for a completed level-backed mutation");
        assertTrue(eventsFg5(events), "terrain-swap routing must not repurpose the act-transition flag");
    }

    @Test
    void deathEggTerrainSwapAppliesRomBackedBlocksChunksAndArtOnce() throws IOException {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 1)
                .build();
        Sonic3kLBZEvents events = new Sonic3kLBZEvents();
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(GameServices.zoneRuntimeRegistry()).orElseThrow();
        Level level = GameServices.level().getCurrentLevel();
        int[] originalChunkZero = level.getChunk(0).saveState();

        ResourceLoader loader = new ResourceLoader(GameServices.rom().getRom());
        byte[] expected16x16 = loader.loadSingle(
                LoadOp.kosinskiBase(Sonic3kConstants.LBZ2_16X16_DEATH_EGG_KOS_ADDR));
        byte[] expected128x128 = loader.loadSingle(
                LoadOp.kosinskiBase(Sonic3kConstants.LBZ2_128X128_DEATH_EGG_KOS_ADDR));
        byte[] expected8x8 = loader.loadSingle(
                LoadOp.kosinskiMBase(Sonic3kConstants.LBZ2_8X8_DEATH_EGG_KOSM_ADDR));
        byte[] expectedDeathEgg2 = loader.loadSingle(
                LoadOp.kosinskiMBase(Sonic3kConstants.ART_KOSM_LBZ2_DEATH_EGG_2_8X8_ADDR));

        fixture.camera().setX((short) 0x3BC0);
        fixture.camera().setY((short) 0x0500);
        events.update(1, 1);
        events.update(1, 2);

        int serviceFrame = 3;
        byte[] expectedDeathEgg2Pattern = expectedPattern(expectedDeathEgg2, 0);
        while (serviceFrame < 10_000
                && (!state.isDeathEggTerrainSwapApplied()
                || !Arrays.equals(expectedDeathEgg2Pattern,
                        snapshot(level.getPattern(Sonic3kConstants.ART_TILE_LBZ2_DEATH_EGG_2))))) {
            HardwareBoundaryPump.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            HardwareBoundaryPump.service(HardwareServiceBoundary.POST_OBJECTS);
            events.update(1, serviceFrame++);
        }

        assertTrue(state.isDeathEggTerrainSwapQueued());
        assertTrue(state.isDeathEggTerrainSwapApplied());
        assertArrayEquals(expectedChunkState(expected16x16, 0, originalChunkZero),
                level.getChunk(0).saveState(),
                "LBZ2 Death Egg swap must restore 16x16 chunk descriptors from the ROM Kosinski block table");
        assertArrayEquals(expectedBlockState(expected128x128, 0),
                level.getBlock(0).saveState(),
                "LBZ2 Death Egg swap must restore 128x128 block descriptors from the ROM Kosinski chunk table");
        assertArrayEquals(expectedPattern(expected8x8, 0),
                snapshot(level.getPattern(0)),
                "LBZ2 Death Egg terrain art must replace VRAM tile $000 from the ROM KosM stream");
        assertArrayEquals(expectedDeathEgg2Pattern,
                snapshot(level.getPattern(Sonic3kConstants.ART_TILE_LBZ2_DEATH_EGG_2)),
                "Death Egg 2 art must land at tile $05A0 for launch pillars/explosion terrain");
    }

    private static LbzZoneRuntimeState installAct2State() {
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        GameServices.zoneRuntimeRegistry().install(state);
        return state;
    }

    private static void seedLaunchPadBlocks(Level level, int blockIndex) {
        LevelMutationSurface surface = LevelMutationSurface.forLevel(level);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                surface.setBlockInMap(0, 0x87 + col, 0x0B + row, blockIndex);
            }
        }
    }

    private static void assertLaunchPadBlocks(Level level, int expectedBlock, String message) {
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                assertEquals((byte) expectedBlock,
                        level.getMap().getValue(0, 0x87 + col, 0x0B + row),
                        message + " at pad cell (" + col + "," + row + ")");
            }
        }
    }

    private static int[] findVisibleLaunchPadDescriptor() {
        int left = 0x4390;
        int top = 0x668 + 40;
        int width = 320;
        int height = 8;
        for (int y = top; y < top + height; y += Pattern.PATTERN_HEIGHT) {
            for (int x = left; x < left + width; x += Pattern.PATTERN_WIDTH) {
                int descriptor = GameServices.level().getForegroundTileDescriptorAtWorld(x, y);
                if (descriptor != 0) {
                    return new int[]{x, y, descriptor};
                }
            }
        }
        return new int[]{left, top, 0};
    }

    private static boolean eventsFg5(Sonic3kLBZEvents events) throws Exception {
        Field field = Sonic3kLBZEvents.class.getDeclaredField("eventsFg5");
        field.setAccessible(true);
        return field.getBoolean(events);
    }

    private static int[] expectedChunkState(byte[] bytes, int chunkIndex, int[] originalChunkState) {
        int srcOffset = chunkIndex * Chunk.CHUNK_SIZE_IN_ROM;
        int[] state = new int[Chunk.PATTERNS_PER_CHUNK + 2];
        for (int pattern = 0; pattern < Chunk.PATTERNS_PER_CHUNK; pattern++) {
            state[pattern] = readWord(bytes, srcOffset + pattern * 2);
        }
        state[Chunk.PATTERNS_PER_CHUNK] = originalChunkState[Chunk.PATTERNS_PER_CHUNK];
        state[Chunk.PATTERNS_PER_CHUNK + 1] = originalChunkState[Chunk.PATTERNS_PER_CHUNK + 1];
        return state;
    }

    private static int[] expectedBlockState(byte[] bytes, int blockIndex) {
        int srcOffset = blockIndex * LevelConstants.BLOCK_SIZE_IN_ROM;
        int chunksPerBlock = LevelConstants.BLOCK_SIZE_IN_ROM / 2;
        int[] state = new int[chunksPerBlock];
        for (int chunk = 0; chunk < chunksPerBlock; chunk++) {
            state[chunk] = readWord(bytes, srcOffset + chunk * 2);
        }
        return state;
    }

    private static byte[] expectedPattern(byte[] art, int tileOffset) {
        Pattern expected = new Pattern();
        int src = tileOffset * Pattern.PATTERN_SIZE_IN_ROM;
        expected.fromSegaFormat(Arrays.copyOfRange(art, src, src + Pattern.PATTERN_SIZE_IN_ROM));
        return snapshot(expected);
    }

    private static byte[] snapshot(Pattern pattern) {
        byte[] pixels = new byte[Pattern.PATTERN_SIZE_IN_MEM];
        pattern.copyInto(pixels, 0);
        return pixels;
    }

    private static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
