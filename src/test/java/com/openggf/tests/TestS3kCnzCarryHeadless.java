package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end headless check of the CNZ1 Tails-carry intro.
 *
 * <p>Mirrors the first ~200 frames of the {@code TestS3kCnzTraceReplay}
 * BK2 without running the full trace engine. Success criteria (per design
 * spec §10 row 1, 3 and §8.2, updated for the ROM 0x00 -> 0x0C
 * non-same-frame fix):
 * <ul>
 *   <li>Frame 1: {@code Sonic.x_speed == 0x0000} (ROM {@code loc_13A10}
 *       sets {@code Tails_CPU_routine=$C} and returns; the 0x0C body has
 *       not run yet)</li>
 *   <li>Frame 2: {@code Sonic.x_speed == 0x0100} and object-controlled/
 *       airborne (ROM {@code loc_13FC2} body runs, falling through to
 *       {@code loc_13FFA}; Tails movement then calls
 *       {@code Tails_Carry_Sonic})</li>
 *   <li>Frame 20: {@code Sonic.air == 1} (still carried)</li>
 *   <li>By frame ~200: state back to {@code NORMAL}, {@code object_control} cleared</li>
 * </ul>
 *
 * <p>Knuckles-alone coverage lives in {@code TestSonic3kCnzCarryTrigger}
 * (Task 4 unit tests); this class asserts only the Sonic+Tails path.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzCarryHeadless {

    private static final int ZONE_CNZ = 3;
    private static final int ACT_1 = 0;

    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        // Intro skip keeps the first frame on CNZ1 gameplay (skips zone-intro
        // title-card frames), so frame 1 is the carry intro's first tick.
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_CNZ, ACT_1);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
        if (oldSkipIntros != null) {
            SonicConfigurationService.getInstance()
                    .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkipIntros);
            oldSkipIntros = null;
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    private SidekickCpuController sidekickController() {
        return GameServices.sprites().getSidekicks().get(0).getCpuController();
    }

    @Test
    void cnz1Frame2SonicXSpeedMatchesRom() {
        AbstractPlayableSprite sonic = fixture.sprite();

        // Frame 1: ROM loc_13A10 (sonic3k.asm:26414) INIT handler sets
        // Tails_CPU_routine=$C and rts. Engine enters CARRY_INIT; the
        // 0x0C body that writes x_vel=$100 has NOT run yet.
        fixture.stepFrame(false, false, false, false, false);
        assertEquals((short) 0x0000, sonic.getXSpeed(),
                "Frame 1 Sonic.x_speed: 0x0C body has not run yet (INIT just set routine=$C)");

        // Frame 2: ROM loc_13FC2 (the 0x0C body, sonic3k.asm:26903)
        // writes x_vel=$100 and falls through (no rts) to loc_13FFA
        // (the 0x0E body). Engine transitions CARRY_INIT -> CARRYING
        // with the x_speed write.
        fixture.stepFrame(false, false, false, false, false);
        assertEquals((short) 0x0100, sonic.getXSpeed(),
                "Frame 2 Sonic.x_speed must match ROM carry velocity (loc_13FC2 write)");
        assertEquals((short) 0x0008, sonic.getYSpeed(),
                "Frame 2 Sonic.y_speed after Tails_Move_FlySwim adds carry gravity");
        assertTrue(sonic.isObjectControlled(),
                "Frame 2: Sonic object-controlled by Tails");
        assertTrue(sonic.getAir(),
                "Frame 2: Sonic airborne (being carried)");
    }

    /**
     * At frame 20, Tails is still airborne, so Sonic remains carried.
     */
    @Test
    void cnz1Frame20SonicStillCarried() {
        AbstractPlayableSprite sonic = fixture.sprite();

        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertTrue(sonic.getAir(), "Frame 20: Sonic must still be airborne");
        assertTrue(sonic.isObjectControlled(),
                "Frame 20: Sonic still object-controlled (carry has not released)");
    }

    @Test
    void cnz1CarryVelocityAccumulatesSyntheticRightPresses() {
        AbstractPlayableSprite sonic = fixture.sprite();

        for (int i = 0; i < 96; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertEquals((short) 0x0148, sonic.getXSpeed(),
                "Frame 96: Tails carry x_speed should accumulate three synthetic right presses");
        assertTrue(sonic.getAir(), "Frame 96: Sonic is still airborne before the landing probe");
        assertTrue(sonic.isObjectControlled(),
                "Frame 96: Sonic still object-controlled by Tails");
    }

    @Test
    void cnz1CarryRegrabPreservesRollingStatusAndRadii() {
        AbstractPlayableSprite sonic = fixture.sprite();

        for (int i = 0; i < 5; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(SidekickCpuController.State.CARRYING, sidekickController().getState(),
                "Precondition: CNZ carry must be active before the jump-off");

        fixture.stepFrame(false, false, false, false, true);
        assertFalse(sonic.isObjectControlled(),
                "A/B/C jump-off should release Sonic from Tails carry");
        assertTrue(sonic.getRolling(),
                "Jump-off path sets Status_Roll per sonic3k.asm:27295-27297");
        assertEquals(7, sonic.getXRadius(),
                "Jump-off path writes rolling x_radius=$07 per sonic3k.asm:27293");
        assertEquals(14, sonic.getYRadius(),
                "Jump-off path writes rolling y_radius=$0E per sonic3k.asm:27292");

        boolean regrabbed = false;
        for (int i = 0; i < 90; i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (sonic.isObjectControlled()) {
                regrabbed = true;
                break;
            }
        }

        assertTrue(regrabbed,
                "CNZ carry cooldown/proximity loop should regrab Sonic before landing");
        assertTrue(sonic.getRolling(),
                "sub_1459E regrab preserves Status_Roll; it only sets Status_InAir "
                        + "and clears Status_RollJump (sonic3k.asm:27399-27423)");
        assertEquals(7, sonic.getXRadius(),
                "sub_1459E does not restore x_radius, so rolling radius must persist");
        assertEquals(14, sonic.getYRadius(),
                "sub_1459E does not restore y_radius, so rolling radius must persist");

        boolean landed = false;
        for (int i = 0; i < 90; i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (!sonic.getAir()) {
                landed = true;
                break;
            }
        }

        assertTrue(landed,
                "Carried Sonic should land through the post-parentage SonicKnux_DoLevelCollision probe");
        assertFalse(sonic.getRolling(),
                "Player_TouchFloor clears Status_Roll on carried landing (sonic3k.asm:24344-24346)");
        assertEquals(sonic.getStandXRadius(), sonic.getXRadius(),
                "Player_TouchFloor restores default_x_radius on landing (sonic3k.asm:24342)");
        assertEquals(sonic.getStandYRadius(), sonic.getYRadius(),
                "Player_TouchFloor restores default_y_radius on landing (sonic3k.asm:24341)");
    }

    @Test
    void cnz1CarryGroundsOnRomLandingFrame() {
        AbstractPlayableSprite sonic = fixture.sprite();

        for (int i = 0; i < 106; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertTrue(sonic.getAir(),
                "Precondition: frame 106 should still be airborne before the landing probe");
        int preLandingYSub = sonic.getYSubpixelRaw();

        fixture.stepFrame(false, false, false, false, false);

        assertFalse(sonic.getAir(),
                () -> String.format(
                        "Frame 107: post-parentage SonicKnux_DoLevelCollision clears air on landing"
                                + " (x=%04X y=%04X xs=%04X ys=%04X gs=%04X)",
                        sonic.getCentreX() & 0xFFFF,
                        sonic.getCentreY() & 0xFFFF,
                        sonic.getXSpeed() & 0xFFFF,
                        sonic.getYSpeed() & 0xFFFF,
                        sonic.getGSpeed() & 0xFFFF));
        assertEquals((short) 0x06CC, sonic.getCentreY(),
                "Frame 107: carried Sonic should snap to the CNZ1 start-floor height");
        assertEquals(preLandingYSub, sonic.getYSubpixelRaw(),
                "Frame 107: Player_TouchFloor writes y_pos but preserves Sonic's y_sub");
        assertEquals((short) 0, sonic.getYSpeed(),
                "Frame 107: landing probe should clear Sonic.y_speed");
        assertEquals(sonic.getXSpeed(), sonic.getGSpeed(),
                "Frame 107: landing probe should transfer x_speed to ground speed");
        assertEquals(0, sonic.getAnimationId(),
                "Frame 107: Player_TouchFloor must publish anim=Walk while carry object-control remains set");

        fixture.stepFrame(false, false, false, false, false);
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        assertEquals(0, sonic.getAnimationId(),
                "Frame 108: carry release must preserve Sonic's landing Walk byte");
        assertEquals(0x20, tails.getAnimationId(),
                "Frame 108: carry release must restore Tails's ordinary flight animation");
        assertEquals(0xA0, tails.getMappingFrame(),
                "Frame 108: Tails's ordinary flight mapping must be visible on the release frame");
    }

    @Test
    void cnz1SidekickNormalFollowAppliesRecordedRightInputOnFrame123() {
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);

        for (int i = 0; i < 123; i++) {
            boolean right = i >= 106;
            fixture.stepFrame(false, false, false, right, false);
        }

        assertEquals(SidekickCpuController.State.NORMAL, sidekickController().getState(),
                "Frame 123: CNZ carry has released into ROM Tails_CPU_routine=$06");
        assertEquals((short) 0xFFD0, tails.getXSpeed(),
                "Frame 123: ROM loc_13D4A replays delayed RIGHT input through "
                        + "Tails_InputAcceleration_Freespace (sonic3k.asm:26656, 28330)");
    }

    @Test
    void cnz1CarryReleasesByFrame200() {
        AbstractPlayableSprite sonic = fixture.sprite();
        SidekickCpuController ctrl = sidekickController();

        int releasedAtFrame = -1;
        for (int i = 0; i < 200; i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (ctrl.getState() == SidekickCpuController.State.NORMAL) {
                releasedAtFrame = i;
                break;
            }
        }

        assertNotEquals(-1, releasedAtFrame,
                "Carry never transitioned to NORMAL within 200 frames");
        assertTrue(releasedAtFrame > 0,
                "Carry released on frame 0 — carry must engage for at least 1 frame before releasing");
        assertTrue(releasedAtFrame < 200,
                "Carry released outside the 200-frame window at frame " + releasedAtFrame);
        assertFalse(sonic.isObjectControlled(),
                "After release Sonic is no longer object-controlled");
    }
}
