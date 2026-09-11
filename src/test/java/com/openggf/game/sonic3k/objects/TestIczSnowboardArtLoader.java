package com.openggf.game.sonic3k.objects;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.game.OscillationManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.util.PatternDecompressor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestIczSnowboardArtLoader {
    private static final int ZONE_ICZ = 0x05;
    private static final int ACT_1 = 0;

    private Object oldSkipIntros;
    private Object oldMainCharacter;
    private Object oldSidekickCharacters;

    @BeforeEach
    void setUpConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacters = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);

        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
    }

    @AfterEach
    void restoreConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacters != null ? oldSidekickCharacters : "");
        // The headless fixtures advance the process-wide OscillationManager; clear it
        // so later classes in the same fork start from the reset baseline.
        OscillationManager.reset();
    }

    @Test
    void snowboardFramesApplyRomDplcBeforeRendering() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();

        PatternSpriteRenderer renderer = IczSnowboardArtLoader.snowboardRenderer(TestEnvironment.objectServices());
        renderer.drawFrameIndex(8, 0, 0);

        Pattern[] renderedBank = rendererPatterns(renderer);
        Pattern[] sourceArt = PatternDecompressor.fromBytes(GameServices.rom().getRom().readBytes(
                Sonic3kConstants.ART_UNC_SNOWBOARD_ADDR,
                Sonic3kConstants.ART_UNC_SNOWBOARD_SIZE));

        assertSamePattern(sourceArt[0x19], renderedBank[0],
                "Snowboard frame 8 must load DPLC source tile 0x19 into rendered bank tile 0");
    }

    @Test
    void angleTableResolvesAnimationIdsToSonicSnowboardMappingFrames() {
        assertEquals(11, IczSnowboardIntroInstance.groundSnowboardMappingFrameForAngle(0x00));
        assertEquals(12, IczSnowboardIntroInstance.groundSnowboardMappingFrameForAngle(0x10));
        assertEquals(10, IczSnowboardIntroInstance.groundSnowboardMappingFrameForAngle(0x20));
        assertEquals(9, IczSnowboardIntroInstance.groundSnowboardMappingFrameForAngle(0x30));
    }

    @Test
    void startupObjectControlHoldsSonicRollMappingFrame() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();

        assertEquals(0, fixture.sprite().getMappingFrame(),
                "ROM Obj_LevelIntroICZ1 initializes Sonic mapping_frame to 0");
        for (int frame = 0; frame < 20; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertEquals(0, fixture.sprite().getMappingFrame(),
                "ROM object_control=3 skips Animate_Sonic during the startup lock");
        assertTrue(fixture.sprite().isObjectMappingFrameControl(),
                "Startup object_control bit 1 should give the intro object frame control");
    }

    @Test
    void restoredSetupControllerRetainsNativeStartupOwnership() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        Sonic3kLevelEventManager events =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();

        events.getIczEvents().restoreSnowboardIntroPostPreludeReset(fixture.sprite());
        IczSnowboardIntroInstance intro = latestSnowboardIntro();

        assertEquals("STARTUP_LOCK", intro.stateNameForTest(),
                "A restored native setup object must execute its own routine-0 startup");
        assertTrue(fixture.sprite().isObjectMappingFrameControl(),
                "ROM object_control=3 must remain owned until the controller's 30-frame timer expires");
        assertEquals(0, fixture.sprite().getMappingFrame(),
                "Restoring the setup object must retain its ROM-owned mapping frame");
    }

    @Test
    void startupReleaseObjectDispatchDefersPlayerMotionUntilNextPlayerSlot() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        Sonic3kLevelEventManager events =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();

        events.getIczEvents().restoreSnowboardIntroPostPreludeReset(fixture.sprite());
        IczSnowboardIntroInstance intro = latestSnowboardIntro();
        int initialX = fixture.sprite().getCentreX();
        int initialY = fixture.sprite().getCentreY();
        int initialXSub = fixture.sprite().getXSubpixelRaw();
        int initialYSub = fixture.sprite().getYSubpixelRaw();
        short initialYSpeed = fixture.sprite().getYSpeed();

        for (int dispatch = 0; dispatch < 30; dispatch++) {
            intro.update(dispatch, fixture.sprite());
        }

        assertFalse(fixture.sprite().isObjectMappingFrameControl(),
                "ROM loc_39780 must release object_control when its 30-tick timer expires");
        assertEquals(initialX, fixture.sprite().getCentreX(),
                "The later controller slot must not run the already-completed player X motion");
        assertEquals(initialY, fixture.sprite().getCentreY(),
                "The later controller slot must not run the already-completed player Y motion");
        assertEquals(initialXSub, fixture.sprite().getXSubpixelRaw());
        assertEquals(initialYSub, fixture.sprite().getYSubpixelRaw());
        assertEquals(initialYSpeed, fixture.sprite().getYSpeed(),
                "Gravity belongs to the next ordinary player dispatch");
    }

    @Test
    void snowboardLaunchKeepsBoardSeparateUntilSonicHandoff() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        IczSnowboardIntroInstance intro = snowboardIntro();

        while (fixture.sprite().getCentreX() < IczSnowboardIntroInstance.INITIAL_SNOWBOARD_X) {
            fixture.stepFrame(false, false, false, false, false);
        }
        for (int frame = 0; frame < 4; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertFalse(intro.isSonicSnowboardOverlayActiveForTest(),
                "Sonic snowboarding overlay must not draw before the ROM handoff at x=$184");
        assertNotEquals(fixture.sprite().getCentreY(), intro.getCurrentYForTest(),
                "The board should bounce with its own Y velocity while Sonic jumps toward the handoff");
    }

    @Test
    void snowboardBounceTriggersAtRomXThresholdRegardlessOfY() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        IczSnowboardIntroInstance intro = snowboardIntro();

        while (fixture.sprite().getCentreX() < IczSnowboardIntroInstance.INITIAL_SNOWBOARD_X) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertTrue(fixture.sprite().getCentreY() < IczSnowboardIntroInstance.INITIAL_SNOWBOARD_Y - 0x10,
                "Sanity check: this reproduction reaches the board X while Sonic is still visually high");
        assertEquals("BOARD_LAUNCH", intro.stateNameForTest(),
                "ROM loc_39796 launches the board as soon as Sonic reaches x=$00C0");
        assertEquals(0x0400, fixture.sprite().getXSpeed() & 0xFFFF,
                "ROM writes x_vel(a2)=$0400 when the board bounce starts");
        assertEquals(0xF800, fixture.sprite().getYSpeed() & 0xFFFF,
                "ROM writes y_vel(a2)=-$0800 when the board bounce starts");
    }

    @Test
    void snowboardBounceUsesRomPreLaunchArcAtContact() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();

        advanceUntil(fixture, () -> "BOARD_LAUNCH".equals(snowboardIntro().stateNameForTest()),
                "Obj_LevelIntroICZ1 must reach BOARD_LAUNCH within a bounded number of frames");

        assertEquals(0x00C0, fixture.sprite().getCentreX(),
                "ROM loc_39796 should first launch the board when Sonic reaches x=$00C0");
        assertEquals(0x0159, fixture.sprite().getCentreY(),
                "ROM MoveSprite_TestGravity arc from y=$00F0 should put Sonic at y=$0159 on the launch frame");
    }

    @Test
    void snowboardBounceKeepsSonicAirborneLikeRomIntroArc() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();

        advanceUntil(fixture, () -> "BOARD_LAUNCH".equals(snowboardIntro().stateNameForTest()),
                "Obj_LevelIntroICZ1 must reach BOARD_LAUNCH within a bounded number of frames");

        assertTrue(fixture.sprite().getAir(),
                "Sonic should still have Status_InAir when Obj_LevelIntroICZ1 applies the board bounce");
    }

    @Test
    void snowboardBounceCorrectsToRomLaunchYIfEngineTerrainArcIsHigh() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        IczSnowboardIntroInstance intro = snowboardIntro();

        advanceUntil(fixture, () -> "WAIT_FOR_BOARD_JUMP".equals(intro.stateNameForTest()),
                "Obj_LevelIntroICZ1 must reach WAIT_FOR_BOARD_JUMP within a bounded number of frames");

        fixture.sprite().setCentreX((short) IczSnowboardIntroInstance.INITIAL_SNOWBOARD_X);
        fixture.sprite().setCentreY((short) 0x0120);
        intro.update(fixture.frameCount(), fixture.sprite());

        assertEquals("BOARD_LAUNCH", intro.stateNameForTest());
        assertEquals(0x0159, fixture.sprite().getCentreY(),
                "The board bounce must use the ROM intro arc Y even if terrain collision left Sonic visually high");
    }

    @Test
    void snowboardSequenceAllowsJumpWhileControlLocked() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        IczSnowboardIntroInstance intro = snowboardIntro();

        advanceUntil(fixture,
                () -> intro.isSonicSnowboardOverlayActiveForTest() && !fixture.sprite().getAir(),
                "the snowboard overlay must hand off and land within a bounded number of frames");
        fixture.stepFrame(false, false, false, false, true);

        assertFalse(fixture.sprite().getAir(),
                "ROM loc_3984E queues the snowboard jump after Sonic's player slot has already run");
        fixture.stepFrame(false, false, false, false, false);

        assertTrue(fixture.sprite().getAir(),
                "Queued jump input should launch Sonic on the next locked snowboard frame");
        assertTrue(fixture.sprite().getRolling(),
                "The queued snowboard jump should use the normal rolling jump transition");
    }

    @Test
    void airborneSnowboardAnimationUsesFullRomDelayBeforeAdvancing() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        IczSnowboardIntroInstance intro = snowboardIntro();

        advanceUntil(fixture,
                () -> intro.isSonicSnowboardOverlayActiveForTest() && !fixture.sprite().getAir(),
                "the snowboard overlay must hand off and land within a bounded number of frames");
        queueAndConsumeSnowboardJump(fixture);

        assertEquals(6, intro.getCurrentMappingFrame(),
                "Airborne snowboard animation should start on ROM mapping frame 6");
        for (int frame = 0; frame < 7; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(6, intro.getCurrentMappingFrame(),
                "ROM delay 7 should display the first airborne snowboard frame for 8 ticks");

        fixture.stepFrame(false, false, false, false, false);
        assertEquals(7, intro.getCurrentMappingFrame(),
                "Airborne snowboard animation should advance after the full ROM delay");
    }

    @Test
    void airborneSnowboardAnimationHoldsFinalRomFrameUntilLanding() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        IczSnowboardIntroInstance intro = snowboardIntro();

        advanceUntil(fixture,
                () -> intro.isSonicSnowboardOverlayActiveForTest() && !fixture.sprite().getAir(),
                "the snowboard overlay must hand off and land within a bounded number of frames");
        queueAndConsumeSnowboardJump(fixture);
        fixture.sprite().setCentreY((short) (fixture.sprite().getCentreY() - 0x80));
        fixture.sprite().setYSpeed((short) -0x0200);
        fixture.sprite().setAir(true);

        assertEquals(6, intro.getCurrentMappingFrame(),
                "Airborne snowboard animation should start on ROM mapping frame 6");
        stepAirborneSnowboardFrames(fixture, 8);
        assertEquals(7, intro.getCurrentMappingFrame(),
                "ROM animation 0 advances from frame 6 to frame 7 after delay 7 expires");
        stepAirborneSnowboardFrames(fixture, 8);
        assertEquals(8, intro.getCurrentMappingFrame(),
                "ROM animation 0 advances from frame 7 to frame 8 after delay 7 expires");
        stepAirborneSnowboardFrames(fixture, 8);
        assertEquals(8, intro.getCurrentMappingFrame(),
                "ROM $FE,1 repeats from the final airborne snowboard frame until Sonic lands");
    }

    @Test
    void initialHandoffAirborneStateUsesRomStaticRideFrameUntilFirstLanding() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        IczSnowboardIntroInstance intro = snowboardIntro();

        advanceUntil(fixture, intro::isSonicSnowboardOverlayActiveForTest,
                "the snowboard overlay must become active within a bounded number of frames");

        assertTrue(fixture.sprite().getAir(),
                "The snowboard overlay handoff should happen while Sonic is still airborne");
        for (int frame = 0; frame < 12; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            assertEquals(9, intro.getCurrentMappingFrame(),
                    "ROM keeps the initial airborne snowboard handoff on animation 1/frame 9");
        }
    }

    @Test
    void snowboardCrashWaitsUntilWallStopsSonic() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_ICZ, ACT_1)
                .build();
        IczSnowboardIntroInstance intro = snowboardIntro();

        advanceUntil(fixture, intro::isSonicSnowboardOverlayActiveForTest,
                "the snowboard overlay must become active within a bounded number of frames");
        fixture.sprite().setCentreX((short) 0x3880);
        fixture.sprite().setXSpeed((short) 0x0400);
        fixture.stepFrame(false, false, false, false, false);

        assertEquals("SNOWBOARDING", intro.stateNameForTest(),
                "The crash release should wait for wall collision to zero Sonic's x velocity");
        assertTrue(fixture.sprite().isControlLocked(),
                "Sonic should remain in the snowboard sequence until the wall stop occurs");
    }

    private Pattern[] rendererPatterns(PatternSpriteRenderer renderer) throws Exception {
        Field field = PatternSpriteRenderer.class.getDeclaredField("spriteSheet");
        field.setAccessible(true);
        ObjectSpriteSheet sheet = (ObjectSpriteSheet) field.get(renderer);
        return sheet.getPatterns();
    }

    private IczSnowboardIntroInstance snowboardIntro() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(IczSnowboardIntroInstance.class::isInstance)
                .map(IczSnowboardIntroInstance.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private IczSnowboardIntroInstance latestSnowboardIntro() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(IczSnowboardIntroInstance.class::isInstance)
                .map(IczSnowboardIntroInstance.class::cast)
                .toList()
                .getLast();
    }

    private void stepIdleFrames(HeadlessTestFixture fixture, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
    }

    private void stepAirborneSnowboardFrames(HeadlessTestFixture fixture, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            fixture.sprite().setAir(true);
            fixture.sprite().setYSpeed((short) -0x0200);
            fixture.stepFrame(false, false, false, false, false);
        }
    }

    private void queueAndConsumeSnowboardJump(HeadlessTestFixture fixture) {
        fixture.stepFrame(false, false, false, false, true);
        fixture.stepFrame(false, false, false, false, false);
    }

    private void assertSamePattern(Pattern expected, Pattern actual, String message) {
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                assertTrue(expected.getPixel(x, y) == actual.getPixel(x, y), message);
            }
        }
    }

    /**
     * Pumps frames until {@code condition} holds. Bounded so an intro sequence that
     * stops advancing fails fast instead of spinning the fork forever.
     */
    private static void advanceUntil(
            HeadlessTestFixture fixture, BooleanSupplier condition, String description) {
        int frames = 0;
        while (!condition.getAsBoolean()) {
            assertTrue(frames++ < 4096, description);
            fixture.stepFrame(false, false, false, false, false);
        }
    }

}
