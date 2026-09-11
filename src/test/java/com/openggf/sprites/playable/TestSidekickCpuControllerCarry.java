package com.openggf.sprites.playable;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.objects.PerObjectRewindSnapshot.SidekickCpuRewindExtra;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.physics.Direction;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Hydration parity tests: confirm the new state-enum values are accepted by
 * {@link SidekickCpuController#hydrateFromRomCpuState}. We reuse the AIZ1
 * shared fixture because the hydration method does not depend on zone layout;
 * any level that registers a Tails sidekick is enough.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSidekickCpuControllerCarry {

    private static final int ZONE_AIZ = 0;
    private static final int ACT_1 = 0;

    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_1);
    }

    @AfterAll
    static void cleanup() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private SidekickCpuController controller;
    private HeadlessTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        controller = GameServices.sprites().getSidekicks().get(0).getCpuController();
    }

    @AfterEach
    void resetSharedController() {
        // The shared-fixture sidekick controller instance persists across tests;
        // clear the transient-carrier marker so it doesn't leak into the
        // SONIC_AND_TAILS follow/release tests.
        if (controller != null) {
            controller.setTransientCarrySidekick(false);
        }
    }

    @Test
    void hydrateAccepts0x0CCarryInit() {
        assertDoesNotThrow(() -> controller.hydrateFromRomCpuState(0x0C, 0, 0, 0, false, 0, 0));
        assertEquals(SidekickCpuController.State.CARRY_INIT, controller.getState());
    }

    @Test
    void hydrateAccepts0x0ECarrying() {
        controller.hydrateFromRomCpuState(0x0E, 0, 0, 0, false, 0, 0);
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
    }

    @Test
    void hydrateAccepts0x20Carrying() {
        controller.hydrateFromRomCpuState(0x20, 0, 0, 0, false, 0, 0);
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
    }

    // =====================================================================
    // Task 6: Tails-carry state-machine behavioural tests
    // =====================================================================

    /** Stub trigger that always enters carry; used to exercise state machine in AIZ. */
    private SidekickCarryTrigger alwaysOnTrigger() {
        return new SidekickCarryTrigger() {
            @Override
            public boolean shouldEnterCarry(int zoneId, int actId, PlayerCharacter pc) {
                return true;
            }
            @Override
            public void applyInitialPlacement(AbstractPlayableSprite carrier,
                                              AbstractPlayableSprite cargo) {
                // Intentional no-op; teleport parity is covered by Task 4's applyInitialPlacement test.
            }
            @Override public int   carryDescendOffsetY()            { return Sonic3kConstants.CARRY_DESCEND_OFFSET_Y; }
            @Override public short carryInitXVel()                  { return Sonic3kConstants.CARRY_INIT_TAILS_X_VEL; }
            @Override public int   carryInputInjectMask()           { return Sonic3kConstants.CARRY_INPUT_INJECT_MASK; }
            @Override public int   carryJumpReleaseCooldownFrames() { return Sonic3kConstants.CARRY_COOLDOWN_JUMP_RELEASE; }
            @Override public int   carryLatchReleaseCooldownFrames(){ return Sonic3kConstants.CARRY_COOLDOWN_LATCH_RELEASE; }
            @Override public short carryReleaseJumpYVel()           { return Sonic3kConstants.CARRY_RELEASE_JUMP_Y_VEL; }
            @Override public short carryReleaseJumpXVel()           { return Sonic3kConstants.CARRY_RELEASE_JUMP_X_VEL; }
        };
    }

    /** Stub trigger for MGZ rescue carry: Tails pulses A/B/C every 8 frames. */
    private SidekickCarryTrigger alwaysOnJumpPulseTrigger() {
        return new SidekickCarryTrigger() {
            @Override
            public boolean shouldEnterCarry(int zoneId, int actId, PlayerCharacter pc) {
                return true;
            }
            @Override
            public void applyInitialPlacement(AbstractPlayableSprite carrier,
                                              AbstractPlayableSprite cargo) {
                // Intentional no-op; this test only verifies carry-flight vertical motion.
            }
            @Override public int   carryDescendOffsetY()            { return Sonic3kConstants.CARRY_DESCEND_OFFSET_Y; }
            @Override public short carryInitXVel()                  { return 0; }
            @Override public int   carryInputInjectMask()           { return 0x07; }
            @Override public boolean carryInjectsJump()             { return true; }
            @Override public boolean usesMgzBossTransitionControl() { return true; }
            @Override public int   carryJumpReleaseCooldownFrames() { return Sonic3kConstants.CARRY_COOLDOWN_JUMP_RELEASE; }
            @Override public int   carryLatchReleaseCooldownFrames(){ return Sonic3kConstants.CARRY_COOLDOWN_LATCH_RELEASE; }
            @Override public short carryReleaseJumpYVel()           { return Sonic3kConstants.CARRY_RELEASE_JUMP_Y_VEL; }
            @Override public short carryReleaseJumpXVel()           { return Sonic3kConstants.CARRY_RELEASE_JUMP_X_VEL; }
        };
    }

    /**
     * Resets the fixture's sidekick controller to INIT with the stub trigger installed,
     * and returns (sonic, tails) for convenience.
     */
    private AbstractPlayableSprite[] prepareCarry() {
        AbstractPlayableSprite sonic = fixture.sprite();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        // Position both high above any AIZ1 terrain so the post-parentage
        // collision probe (ROM Tails_Carry_Sonic:27330) finds no ground and
        // the carry tick exercises pure state-machine logic.  AIZ1's spawn
        // ground is near Y=1052; Y=580/600 is well clear.
        sonic.setCentreY((short) 600);
        tails.setCentreY((short) 580);
        sonic.setAir(true);
        tails.setAir(true);
        controller.setCarryTrigger(alwaysOnTrigger());
        controller.setInitialState(SidekickCpuController.State.INIT);
        return new AbstractPlayableSprite[] { sonic, tails };
    }

    private AbstractPlayableSprite[] prepareCarry(SidekickCarryTrigger trigger) {
        AbstractPlayableSprite[] pair = prepareCarry();
        controller.setCarryTrigger(trigger);
        controller.setInitialState(SidekickCpuController.State.INIT);
        return pair;
    }

    // --- init transition --------------------------------------------------

    @Test
    void initWithTriggerTransitionsToCarryInitThenCarryingAcrossTwoFrames() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];

        // First tick: ROM loc_13A10 (sonic3k.asm:26414) sets
        // Tails_CPU_routine=$C and rts. Engine mirrors by entering
        // CARRY_INIT and returning without executing the 0x0C body
        // (which writes x_vel=$100).
        controller.update(1);
        assertEquals(SidekickCpuController.State.CARRY_INIT, controller.getState(),
                "Frame 1 state must be CARRY_INIT (ROM Tails_CPU_routine=$C just set, body not yet run)");
        assertEquals((short) 0x0000, sonic.getXSpeed(),
                "Frame 1 x_speed unchanged — ROM 0x0C body has not fired yet");

        // Second tick: ROM loc_13FC2 (the 0x0C body, sonic3k.asm:26903)
        // sets x_vel=$100 and falls through (no rts) to loc_13FFA (the
        // 0x0E body). Engine mirrors by transitioning CARRY_INIT ->
        // CARRYING with the x_speed write.
        controller.update(2);
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState(),
                "Frame 2 state must be CARRYING (0x0C body ran; fell through to 0x0E)");
        assertTrue(sonic.isObjectControlled(),
                "Sonic must be object-controlled while carried");
        assertEquals((short) 0x0100, sonic.getXSpeed(),
                "Frame 2 x_speed must match Tails's carry x_vel (loc_13FC2 write)");
        assertTrue(sonic.getAir(), "Sonic.air must be true while carried");
    }

    // --- per-frame parentage ---------------------------------------------

    @Test
    void carryInitImmediatelyParentsSonicBeforeCarrierMovement() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        sonic.setCentreX((short) 0x1200);
        sonic.setCentreY((short) 0x0740);
        tails.setCentreX((short) 0x3CC0);
        tails.setCentreY((short) 0x0700);

        controller.update(1);  // INIT -> CARRY_INIT
        controller.update(2);  // CARRY_INIT body: ROM sub_1459E

        assertEquals((short) 0x3CC0, sonic.getCentreX(),
                "ROM sub_1459E positions Sonic at Tails.x immediately when routine $14 runs");
        assertEquals((short) 0x071C, sonic.getCentreY(),
                "ROM sub_1459E positions Sonic at Tails.y+$1C before Tails moves this frame");
        assertTrue(sonic.isObjectControlled(),
                "Sonic must be object-controlled as soon as the MGZ pickup routine runs");
    }

    @Test
    void carryingCopiesPostMovementTailsVelocityToSonicEachFrame() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        controller.update(1);  // INIT -> CARRY_INIT
        controller.update(2);  // CARRY_INIT -> CARRYING (with x_speed write)
        controller.finishCarryAfterCarrierMovement();

        for (int i = 0; i < 10; i++) {
            controller.update(3 + i);
            short postMovementXSpeed = (short) (0x0100 + (i + 1) * 0x18);
            short postMovementYSpeed = (short) ((i + 1) * 0x08);
            tails.setXSpeed(postMovementXSpeed);
            tails.setYSpeed(postMovementYSpeed);
            controller.finishCarryAfterCarrierMovement();
            assertEquals(postMovementXSpeed, sonic.getXSpeed(),
                    "Sonic.x_speed must copy Tails's post-movement x_vel on frame " + (i + 3));
            assertEquals(postMovementYSpeed, sonic.getYSpeed(),
                    "Sonic.y_speed must copy Tails's post-movement y_vel on frame " + (i + 3));
            assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
        }
    }

    @Test
    void scriptedPickupRunsRawCarryAnimatorAfterCarrierMovement() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite sonic = pair[0];
        sonic.setMappingFrame(0x98);

        controller.update(1);  // INIT -> CARRY_INIT
        controller.update(2);  // sub_1459E resets anim_frame/timer

        assertEquals(0x91, sonic.getMappingFrame(),
                "CPU routine $14 falls through to its pre-movement Tails_Carry_Sonic pass");
        assertEquals(1, sonic.getAnimationFrameIndex());
        assertEquals(0x0B, sonic.getAnimationTick());

        controller.finishCarryAfterCarrierMovement();
        assertEquals(0x91, sonic.getMappingFrame(),
                "the later Tails_FlyingSwimming pass advances the same raw carry animator");
        assertEquals(1, sonic.getAnimationFrameIndex());
        assertEquals(0x0A, sonic.getAnimationTick());
    }

    @Test
    void carryInitRefreshResetsRawAnimatorEvenWhenCarryIsAlreadyActive() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite sonic = pair[0];

        controller.update(1);
        controller.update(2);
        controller.finishCarryAfterCarrierMovement();
        sonic.setAnimationFrameIndex(8);
        sonic.setAnimationTick(5);
        sonic.setMappingFrame(0x90);

        controller.setInitialState(SidekickCpuController.State.CARRY_INIT);
        controller.update(3);

        assertEquals(1, sonic.getAnimationFrameIndex(),
                "native sub_1459E clears anim_frame before the CPU raw carry pass");
        assertEquals(0x0B, sonic.getAnimationTick(),
                "the refreshed CPU pass restarts AniRaw_Tails_Carry after an earlier regrab");
        assertEquals(0x91, sonic.getMappingFrame());
        controller.finishCarryAfterCarrierMovement();
        assertEquals(0x91, sonic.getMappingFrame());
        assertEquals(0x0A, sonic.getAnimationTick());
    }

    @Test
    void carryingCopiesPostMovementTailsDirectionToSonic() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        controller.update(1);
        controller.update(2);
        assertTrue(sonic.isObjectControlled(),
                "Precondition: Sonic should be carried before direction parentage is tested");

        sonic.setDirection(Direction.RIGHT);
        tails.setDirection(Direction.LEFT);
        controller.finishCarryAfterCarrierMovement();

        assertEquals(Direction.LEFT, sonic.getDirection(),
                "ROM Tails_Carry_Sonic copies Tails's facing bit onto carried Sonic each parentage frame");
    }

    // --- release path A: ground contact ---------------------------------

    @Test
    void groundReleasesCarry() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);  // INIT -> CARRY_INIT
        controller.update(2);  // CARRY_INIT -> CARRYING

        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());

        sonic.setAir(false);  // simulate landing
        controller.update(3);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());
        assertFalse(sonic.isObjectControlled());
        assertEquals(0, controller.getReleaseCooldownForTest(),
                "Ground release has no cooldown");
    }

    // --- solo-leader transient carrier fly-off (ROM routine $10) ---------

    @Test
    void transientCarrierEntersFlyoffOnGroundReleaseInsteadOfNormal() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.setTransientCarrySidekick(true);
        controller.update(1);  // INIT -> CARRY_INIT
        controller.update(2);  // CARRY_INIT -> CARRYING

        ObjectInstance staleSupport = mock(ObjectInstance.class);
        GameServices.level().getObjectManager().forceRidingObjectForBootstrap(sonic, staleSupport);
        assertTrue(GameServices.level().getObjectManager().isRidingObject(sonic),
                "Precondition: carried Sonic retains a prior SolidObject riding relationship");
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());

        sonic.setAir(false);   // simulate landing
        controller.update(3);

        assertEquals(SidekickCpuController.State.CARRY_FLYOFF, controller.getState(),
                "ROM loc_14068: a solo-leader throwaway carrier routes to routine $10 "
                        + "(fly off) on the landing drop, not routine 6 (normal follow)");
        assertFalse(sonic.isObjectControlled(),
                "Sonic is released from object control when the carry ends");
    }

    @Test
    void transientCarrierInjectsFlightInputOnFlapCadenceWhileOnScreen() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        controller.setTransientCarrySidekick(true);
        controller.update(1);
        controller.update(2);
        sonic.setAir(false);
        controller.update(3);  // -> CARRY_FLYOFF
        assertEquals(SidekickCpuController.State.CARRY_FLYOFF, controller.getState());

        tails.setRenderFlagOnScreen(true);

        // ROM loc_1408A pulses A/B/C + Right into Ctrl_2 every 16 frames; the
        // controller injects that flight input on the cadence and lets normal
        // Tails_FlyingSwimming physics move the carrier (no direct position write).
        controller.update(16);
        assertTrue(controller.getInputJump(),
                "ROM loc_1408A injects A/B/C (flap) every 16 frames");
        assertTrue(controller.getInputRight(),
                "ROM loc_1408A injects Right (drift) every 16 frames");
        assertFalse(controller.isTransientFlyoffDespawned(),
                "Carrier is not removed while still on-screen");

        // Off-cadence frames inject no synthetic input (Ctrl_2_logical cleared).
        controller.update(17);
        assertFalse(controller.getInputJump(), "no flap off the 16-frame cadence");
        assertFalse(controller.getInputRight(), "no drift off the 16-frame cadence");
    }

    @Test
    void transientCarrierDespawnsOnceOffScreen() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        controller.setTransientCarrySidekick(true);
        controller.update(1);
        controller.update(2);
        sonic.setAir(false);
        controller.update(3);  // -> CARRY_FLYOFF

        tails.setRenderFlagOnScreen(true);
        controller.update(4);
        assertFalse(controller.isTransientFlyoffDespawned(),
                "Still on-screen: carrier keeps flying");

        tails.setRenderFlagOnScreen(false);
        controller.update(5);
        assertTrue(controller.isTransientFlyoffDespawned(),
                "ROM loc_140AC deletes the carrier object once render_flags reports it off-screen");
    }

    @Test
    void transientCarrierStaysInReleasedLoopThenRegrabsLeaderInPickupRange() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        controller.setTransientCarrySidekick(true);
        controller.update(1);  // INIT -> CARRY_INIT
        controller.update(2);  // -> CARRYING
        controller.finishCarryAfterCarrierMovement();  // establishes the carry latch
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());

        // External velocity change on the carried Sonic triggers the ROM
        // Tails_Carry_Sonic latch-mismatch release (loc_1445A/loc_14460): the
        // carrier drops Sonic into the cooldown/regrab loop, NOT into follow AI.
        sonic.setXSpeed((short) 0x0400);
        controller.update(3);
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState(),
                "Released throwaway carrier stays in the carry routine (loc_14534 loop), not NORMAL");
        assertFalse(sonic.isObjectControlled(),
                "Sonic is released from object control while the carrier waits to regrab");

        // Park Sonic inside the ROM loc_14542 pickup window (dx in [-$10,$10),
        // Sonic ~$20-$30 below Tails), airborne, so the regrab fires once the
        // cooldown elapses.
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0700);
        sonic.setCentreX((short) 0x1000);
        sonic.setCentreY((short) 0x0724);
        sonic.setXSpeed((short) 0);
        sonic.setYSpeed((short) 0);

        // ROM loc_14542 plays sfx_Grab on a successful regrab; capture SFX output.
        GameServices.audio().commandTimeline().clear();

        boolean regrabbed = false;
        int sfxFiredOnRegrabFrame = -1;
        for (int f = 4; f < 4 + 80 && !regrabbed; f++) {
            sonic.setAir(true);  // keep Sonic airborne (no physics in this unit test)
            int sfxBefore = sfxCount();
            controller.update(f);
            assertEquals(SidekickCpuController.State.CARRYING, controller.getState(),
                    "Carrier must remain in the carry routine throughout the cooldown");
            if (sonic.isObjectControlled()) {
                regrabbed = true;
                sfxFiredOnRegrabFrame = sfxCount() - sfxBefore;
            } else {
                assertEquals(sfxBefore, sfxCount(),
                        "No SFX should play while the carrier is still waiting to regrab");
            }
        }
        assertTrue(regrabbed,
                "ROM loc_14542: after the cooldown the carrier re-grabs Sonic when he is in pickup range");
        assertEquals(1, sfxFiredOnRegrabFrame,
                "ROM loc_14542 plays sfx_Grab exactly once on the regrab");
    }

    private static int sfxCount() {
        return (int) GameServices.audio().commandTimeline().entries().stream()
                .map(entry -> entry.command())
                .filter(AudioCommand.PlaySfx.class::isInstance)
                .count();
    }

    @Test
    void nonTransientCarrierStillFollowsOnGroundRelease() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        // transient flag deliberately left false (SONIC_AND_TAILS persistent Tails)
        controller.update(1);
        controller.update(2);
        sonic.setAir(false);
        controller.update(3);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState(),
                "A persistent sidekick keeps following on release (ROM routine 6), no fly-off");
    }

    // --- release path B: A/B/C press ------------------------------------

    @Test
    void jumpPressReleasesCarryWithJumpVelocity() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);  // INIT -> CARRY_INIT
        controller.update(2);  // CARRY_INIT -> CARRYING

        // Simulate rising-edge jump press: previous frame false, this frame true.
        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(3);

        assertEquals(SidekickCpuController.State.CARRYING, controller.getState(),
                "Jump release keeps Tails in ROM routine $E until Sonic lands or is regrabbed");
        assertFalse(sonic.isObjectControlled(),
                "Jump release clears Sonic object_control while Tails runs the cooldown/regrab loop");
        assertEquals((short) 0x0100, sonic.getXSpeed(),
                "A/B/C-only carry jump release preserves x_vel; only a directional press overwrites it");
        assertEquals((short) -0x0380, sonic.getYSpeed(),
                "Jump release imparts -0x380 y_vel");
        assertTrue(sonic.getAir(),
                "Tails_Carry_Sonic jump release sets Status_InAir");
        assertFalse(GameServices.level().getObjectManager().isRidingObject(sonic),
                "Carry jump release must discard physical object support even though the ROM leaves Status_OnObj stale");
        assertTrue(sonic.isJumping(),
                "Tails_Carry_Sonic jump release sets the jumping latch");
        assertTrue(sonic.getRolling(),
                "Tails_Carry_Sonic jump release sets Status_Roll");
        assertFalse(sonic.getRollingJump(),
                "Tails_Carry_Sonic jump release clears Status_RollJump");
        assertEquals(0x12, controller.getReleaseCooldownForTest(),
                "Jump release cooldown is 0x12 (~18 frames)");
        controller.finishCarryAfterCarrierMovement();
        assertEquals(0x11, controller.getReleaseCooldownForTest(),
                "the later Tails_FlyingSwimming carry probe consumes the first cooldown tick");
    }

    @Test
    void jumpPressWithDirectionalPressOverwritesCarryReleaseXVelocity() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);
        controller.update(2);

        sonic.setDirectionalInputPressed(false, false, false, false);
        sonic.setDirectionalInputPressed(false, false, false, true);
        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(3);

        assertEquals((short) 0x0200, sonic.getXSpeed(),
                "ROM high-byte right press applies the carry jump-release x_vel override");
        assertEquals(0x3C, controller.getReleaseCooldownForTest(),
                "any held direction replaces the short jump-release cooldown with $3C");
    }

    @Test
    void jumpPressWithVerticalDirectionUsesLongCooldownWithoutChangingXVelocity() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);
        controller.update(2);

        sonic.setDirectionalInputPressed(true, false, false, false);
        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(3);

        assertEquals((short) 0x0100, sonic.getXSpeed(),
                "Up selects the long delay but does not alter carried x_vel");
        assertEquals(0x3C, controller.getReleaseCooldownForTest());
    }

    @Test
    void mgzCarryJumpReleaseStaysInCarryRoutineAndRegrabsWhenClose() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0x0600);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0690);
        sonic.setCentreX((short) 0x1000);
        sonic.setCentreY((short) 0x06AC);
        controller.update(1);
        controller.update(2);

        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(3);

        assertEquals(SidekickCpuController.State.CARRYING, controller.getState(),
                "MGZ routine $18 clears Flying_carrying_Sonic_flag but keeps Tails in the carry CPU routine");
        assertFalse(sonic.isObjectControlled());
        assertTrue(controller.usesFlyingCarryMovement(),
                "Released MGZ carry still runs through Tails_FlyingSwimming while Tails chases Sonic for re-grab");
        assertEquals(0x12, controller.getReleaseCooldownForTest());

        sonic.setJumpInputPressed(false);
        sonic.setRollingJump(false);
        sonic.setAir(true);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0690);
        sonic.setCentreX((short) 0x1008);
        sonic.setCentreY((short) 0x06B4);
        sonic.setXSpeed((short) 0);
        sonic.setYSpeed((short) 0);
        for (int frame = 4; frame < 4 + 0x11; frame++) {
            controller.update(frame);
            assertFalse(sonic.isObjectControlled(),
                    "ROM loc_14534 returns only while byte 1(a2) remains nonzero after decrement");
        }

        controller.update(4 + 0x11);
        controller.finishCarryAfterCarrierMovement();

        assertTrue(sonic.isObjectControlled(),
                "When the cooldown decrement reaches zero, MGZ Tails_Carry_Sonic should run the proximity grab on that frame");
        assertTrue(controller.isFlyingCarrying());
        assertEquals((short) tails.getCentreX(), (short) sonic.getCentreX());
        assertEquals((short) (tails.getCentreY() + Sonic3kConstants.CARRY_DESCEND_OFFSET_Y),
                (short) sonic.getCentreY());
    }

    @Test
    void mgzReleasedCarryRechecksPickupRangeAfterCarrierMovement() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0x0600);
        tails.setCentreY((short) 0x0690);
        controller.update(1);
        controller.update(2);
        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(3);

        sonic.setJumpInputPressed(false);
        tails.getTailsCarryController().setCooldown(0);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0690);
        sonic.setCentreX((short) 0x1000);
        sonic.setCentreY((short) 0x06C1);
        sonic.setAir(true);
        controller.update(4);

        assertFalse(sonic.isObjectControlled(),
                "The pre-body position is one pixel below loc_14542's pickup window");
        tails.setCentreY((short) 0x0695);
        controller.finishCarryAfterCarrierMovement();

        assertTrue(sonic.isObjectControlled(),
                "Tails_Carry_Sonic must recheck pickup range after current carrier movement");
        assertTrue(sonic.isObjectMappingFrameControl(),
                "the post-movement pickup transfers mapping ownership to AniRaw_Tails_Carry");
    }

    @Test
    void mgzJumpReleasePreservesPreBodyLogicalDirectionForOnePass() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0x0600);
        tails.setCentreY((short) 0x0690);
        controller.update(1);
        controller.update(2);

        sonic.setDirectionalInputPressed(false, false, false, true);
        controller.update(3);
        assertTrue(controller.getInputRight());
        SidekickCpuRewindExtra beforeRelease = controller.captureRewindState();
        controller.restoreRewindState(withMgzControlScalars(
                beforeRelease,
                false, 0x20,
                beforeRelease.mgzReleasedChaseLatched(),
                beforeRelease.mgzReleasedChaseXAccel(),
                beforeRelease.mgzReleasedChaseYAccel()));

        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(4);

        assertTrue(controller.getInputRight(),
                "Tails_FlyingSwimming consumes the prior Ctrl_2 direction before Tails_Carry_Sonic releases Sonic");
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, controller.getDiagnosticGeneratedHeldInput());
        assertEquals(0x21, controller.captureRewindState().mgzCarryFlapTimer(),
                "routine $18 advances Tails_CPU_auto_fly_timer before the later jump-release check");

        sonic.setJumpInputPressed(false);
        controller.update(5);

        assertFalse(controller.getInputRight());
        assertEquals(0, controller.getDiagnosticGeneratedHeldInput(),
                "The released-carry cooldown publishes an empty Ctrl_2 logical word on its following pass");
    }

    @Test
    void mgzReleasedChasePublishesAutoFlapIntoCtrl2Logical() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0x0600);
        tails.setCentreY((short) 0x0690);
        controller.update(1);
        controller.update(2);

        sonic.setJumpInputPressed(true);
        controller.update(3);
        sonic.setJumpInputPressed(false);
        SidekickCpuRewindExtra released = controller.captureRewindState();
        controller.restoreRewindState(withMgzControlScalars(
                released,
                false, 0x57,
                false,
                released.mgzReleasedChaseXAccel(),
                released.mgzReleasedChaseYAccel()));

        controller.update(4);

        assertTrue(controller.getInputJumpPress(),
                "loc_142E2 should generate the threshold flap while the carry is released");
        assertEquals(AbstractPlayableSprite.INPUT_JUMP,
                controller.getDiagnosticGeneratedHeldInput(),
                "the released chase must publish its generated A/B/C pulse into Ctrl_2_logical");
    }

    @Test
    void mgzCarryReleasesSonicWhenTailsIsHurt() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0x0600);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0690);
        controller.update(1);
        controller.update(2);
        assertTrue(sonic.isObjectControlled(),
                "Precondition: MGZ carry should own Sonic before Tails takes damage");

        tails.applyHurt(sonic.getCentreX());

        assertTrue(sonic.isObjectControlled(),
                "Touch_Hurt changes Tails' routine after Sonic's earlier player slot has run");
        assertTrue(tails.getTailsCarryController().isCarryingMainCharacter(),
                "the carry flag survives until Tails enters the hurt routine on the next frame");

        controller.update(3);

        assertFalse(sonic.isObjectControlled(),
                "Tails' hurt routine clears Player_1 object_control before hurt movement");
        assertEquals(0, tails.getTailsCarryController().capture().cooldown(),
                "clr.w clears the adjacent native carry cooldown byte too");
        assertFalse(controller.isFlyingCarrying(),
                "Tails must stop actively carrying Sonic as soon as the carrier is hurt");
        assertFalse(controller.usesFlyingCarryMovement(),
                "Hurt Tails must run the ROM hurt movement path, not MGZ carry-flight movement");
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState(),
                "MGZ leaves Tails in the rescue carry CPU routine after the active carry flag is cleared");
    }

    @Test
    void mgzReleasedCarryLatchesRomChaseWhenSonicFallsOffscreen() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite sonic = pair[0];
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0x0600);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0690);
        sonic.setCentreX((short) 0x1000);
        sonic.setCentreY((short) 0x06AC);
        controller.update(1);
        controller.update(2);

        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(3);

        sonic.setJumpInputPressed(false);
        sonic.setRenderFlagOnScreen(false);
        sonic.setAir(true);
        sonic.setYSpeed((short) 0x0300);
        sonic.setCentreX((short) 0x1040);
        sonic.setCentreY((short) 0x0800);
        tails.setCentreX((short) 0x1000);
        tails.setCentreY((short) 0x0690);
        tails.setXSpeed((short) 0);
        tails.setYSpeed((short) 0);

        controller.update(4);

        assertEquals((short) 0, tails.getXSpeed(),
                "ROM loc_14330 only latches rescue acceleration on the first offscreen/falling frame");
        assertEquals((short) 0, tails.getYSpeed(),
                "ROM loc_14330 falls through to Tails_Carry_Sonic without applying the latched acceleration");

        controller.update(5);

        assertEquals((short) 0x0010, tails.getXSpeed(),
                "ROM loc_14362 adds abs(Sonic.x - Tails.x) / 4 while chasing the released Sonic");
        assertEquals((short) 0x008A, tails.getYSpeed(),
                "ROM loc_14362 adds abs(Sonic.y - Tails.y) * 3/8 when Tails is above Sonic");
    }

    // --- release path C: latch mismatch ---------------------------------

    @Test
    void externalXSpeedChangeReleasesCarryWithLatchCooldown() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);  // INIT -> CARRY_INIT
        controller.update(2);  // CARRY_INIT -> CARRYING with latchX = 0x100
        sonic.setXSpeed((short) 0x0500);  // external bumper-style write

        controller.update(3);

        assertEquals(SidekickCpuController.State.CARRYING, controller.getState(),
                "Latch-mismatch release keeps Tails in ROM routine $E while the cooldown/regrab loop runs");
        assertFalse(sonic.isObjectControlled());
        assertEquals(0x3C, controller.getReleaseCooldownForTest(),
                "Latch-mismatch release cooldown is 0x3C (~60 frames)");
    }

    // --- cooldown countdown ---------------------------------------------

    @Test
    void cooldownDecrementsEveryFrame() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);  // INIT -> CARRY_INIT
        controller.update(2);  // CARRY_INIT -> CARRYING
        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(3);
        int cooldownStart = controller.getReleaseCooldownForTest();
        assertEquals(0x12, cooldownStart);

        sonic.setJumpInputPressed(false);
        controller.update(4);
        controller.update(5);
        controller.update(6);

        assertEquals(cooldownStart - 3, controller.getReleaseCooldownForTest(),
                "Cooldown must decrement 1 per frame");
    }

    // --- input injection ------------------------------------------------

    @Test
    void carryInjectsSyntheticRightEvery32Frames() {
        prepareCarry();
        controller.update(1);  // INIT -> CARRY_INIT
        controller.update(2);  // CARRY_INIT -> CARRYING
        boolean sawInjection = false;
        for (int i = 3; i < 67; i++) {
            controller.update(i);
            if (controller.getInputRight()) {
                sawInjection = true;
                break;
            }
        }
        assertTrue(sawInjection, "Right-press injection must fire at least once in 64 frames");
    }

    @Test
    void jumpPulseCarryAppliesTailsFlightLift() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0);
        tails.setCentreY((short) 0x0400);

        controller.update(6);  // INIT -> CARRY_INIT
        controller.update(7);  // CARRY_INIT -> CARRYING

        boolean sawJumpPulse = false;
        for (int frame = 7; frame < 24; frame++) {
            controller.update(frame);
            sawJumpPulse |= controller.getInputJumpPress();
            controller.applyFlyingCarryVerticalVelocity();
        }

        assertTrue(sawJumpPulse, "MGZ rescue carry should pulse A/B/C on its eight-frame cadence");
        assertTrue(tails.getYSpeed() < 0,
                "MGZ rescue carry must run Tails_Move_FlySwim lift; otherwise Tails starts below screen and never reaches Sonic");
    }

    @Test
    void readyFlightStateConsumesJumpPressBeforeApplyingEightGravity() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0);
        tails.setCentreY((short) 0x0400);

        controller.update(6);  // INIT -> CARRY_INIT
        controller.update(7);  // CARRY_INIT -> CARRYING
        controller.update(8);  // ROM-visible eight-frame A/B/C pulse
        tails.setDoubleJumpFlag(1);
        tails.setYSpeed((short) 0);

        controller.applyFlyingCarryVerticalVelocity();

        assertTrue(controller.getInputJumpPress(), "Precondition: frame 8 injects a flap press");
        assertEquals(2, tails.getDoubleJumpFlag(),
                "Ready state 1 consumes the flap press by advancing to lift state 2");
        assertEquals((short) 0x0008, tails.getYSpeed(),
                "The transition frame still executes state-1 +0x08 flight gravity");
    }

    @Test
    void activeFlapAdvancesStateAndAppliesLiftWithoutFlightGravity() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite tails = pair[1];
        controller.update(1);
        controller.update(2);
        tails.setDoubleJumpFlag(2);
        tails.setYSpeed((short) 0);

        controller.applyFlyingCarryVerticalVelocity();

        assertEquals(3, tails.getDoubleJumpFlag());
        assertEquals((short) -0x20, tails.getYSpeed(),
                "Flap state applies -0x20 directly; it must not also add state-1 +0x08 gravity");
    }

    @Test
    void oddRomVisibleFrameDecrementsCarryFlightTimer() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite tails = pair[1];
        controller.update(1);
        controller.update(2);
        tails.setDoubleJumpFlag(1);
        tails.setDoubleJumpProperty((byte) 0x10);

        controller.update(3);
        controller.applyFlyingCarryVerticalVelocity();

        assertEquals(0x0F, tails.getDoubleJumpProperty() & 0xFF,
                "Tails_Move_FlySwim decrements flight time on odd ROM-visible frames");
    }

    @Test
    void airborneCarryMovementAppliesVerticalFlightLogicOncePerPhysicsTick() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite tails = pair[1];
        controller.update(1);
        controller.update(2);
        tails.setDoubleJumpFlag(1);
        tails.setDoubleJumpProperty((byte) 0x10);
        tails.setYSpeed((short) 0);

        tails.getMovementManager().handleMovement(
                false, false, false, false, false, false, false, false);

        assertEquals((short) 0x0008, tails.getYSpeed(),
                "The carry-aware ObjectMoveAndFall phase runs +0x08 exactly once, not once per call site");
        assertEquals(0x10, tails.getDoubleJumpProperty() & 0xFF,
                "The even ROM-visible frame leaves the flight timer unchanged during the same physics tick");
    }

    @Test
    void cpuRewindRoundTripPreservesOnlyMgzControlSequencingScalars() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0);
        tails.setCentreY((short) 0x0400);
        controller.update(1);
        controller.update(2);

        SidekickCpuRewindExtra carrying = withMgzControlScalars(
                controller.captureRewindState(),
                true, 0x46,
                true, (short) 0x0057, (short) 0x0068);
        controller.restoreRewindState(carrying);

        assertTrue(carrying.mgzCarryIntroAscend());
        assertEquals(0x46, carrying.mgzCarryFlapTimer());
        assertTrue(carrying.mgzReleasedChaseLatched());
        assertEquals((short) 0x0057, carrying.mgzReleasedChaseXAccel());
        assertEquals((short) 0x0068, carrying.mgzReleasedChaseYAccel());

        SidekickCpuRewindExtra mutated = withMgzControlScalars(
                carrying,
                false, 0x23,
                false, (short) 0x0034, (short) 0x0045);
        controller.restoreRewindState(mutated);
        assertEquals(mutated, controller.captureRewindState(),
                "Precondition: every targeted carry scalar was mutated away from its saved sentinel");

        controller.restoreRewindState(carrying);

        SidekickCpuRewindExtra restored = controller.captureRewindState();
        assertTrue(restored.mgzCarryIntroAscend());
        assertEquals(0x46, restored.mgzCarryFlapTimer());
        assertTrue(restored.mgzReleasedChaseLatched());
        assertEquals((short) 0x0057, restored.mgzReleasedChaseXAccel());
        assertEquals((short) 0x0068, restored.mgzReleasedChaseYAccel());
        assertEquals(carrying, restored,
                "CPU rewind restores its MGZ intro/flap/chase sequencing scalars");
    }

    private static SidekickCpuRewindExtra withMgzControlScalars(
            SidekickCpuRewindExtra source,
            boolean mgzCarryIntroAscend,
            int mgzCarryFlapTimer,
            boolean mgzReleasedChaseLatched,
            short mgzReleasedChaseXAccel,
            short mgzReleasedChaseYAccel) {
        return new SidekickCpuRewindExtra(
                source.state(),
                source.deadFallingRomCpuRoutine(),
                source.despawnCounter(),
                source.frameCounter(),
                source.controlCounter(),
                source.controller2Held(),
                source.controller2Logical(),
                source.inputUp(),
                source.inputDown(),
                source.inputLeft(),
                source.inputRight(),
                source.inputJump(),
                source.inputJumpPress(),
                source.jumpingFlag(),
                source.minXBound(),
                source.maxXBound(),
                source.minYBound(),
                source.maxYBound(),
                source.lastInteractObjectId(),
                source.normalDespawnLastRenderFlagOffscreen(),
                source.normalDespawnFreshRenderEntryDelayConsumed(),
                source.diagnosticS3kInteractWord(),
                source.normalFrameCount(),
                source.approachFrameCount(),
                source.sidekickCount(),
                source.normalPushingGraceFrames(),
                source.suppressNextAirbornePushFollowSteering(),
                source.releasedUnderwaterPushConsumed(),
                source.objectOrderGracePushBypassThisFrame(),
                source.pendingGroundedFollowNudge(),
                source.pendingGroundedFollowNudgeFrame(),
                source.suppressNextLevelEventNormalMovement(),
                source.catchUpUsesRomVisibleLevelFrameCounter(),
                source.levelEventDormantMarkerReleasePending(),
                source.skipPhysicsThisFrame(),
                source.deadOnObjectReenteredVisibleWindow(),
                source.deferredDespawnDeadFallContinuingThisFrame(),
                source.levelStartLeaderHistoryPrefillPending(),
                source.bootstrapPreludePlacementApplied(),
                source.cpuFrameCounterFromStoredLevelFrame(),
                source.nextCpuFrameCounterOverride(),
                source.catchUpFrameCounterOverride(),
                source.lastNormalAutoJumpPressFrameCounter(),
                source.controller2SignedLocked(),
                source.nativeEndingPosePending(),
                source.latestNormalStepDiagnostics(),
                mgzCarryIntroAscend,
                mgzCarryFlapTimer,
                mgzReleasedChaseLatched,
                mgzReleasedChaseXAccel,
                mgzReleasedChaseYAccel,
                source.flightTimer(),
                source.catchUpTargetX(),
                source.catchUpTargetY(),
                source.initialPresentationSuppressed(),
                source.initialPresentationWasHidden());
    }

    @Test
    void mgzCarryIntroSwitchesToPlayerSteeredFlightAtCameraY90() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0x0600);
        tails.setCentreY((short) 0x0690);
        controller.update(1);  // INIT -> CARRY_INIT
        fixture.sprite().setDirectionalInputPressed(false, false, true, false);
        controller.update(2);  // CARRY_INIT -> CARRYING; routine $16 reaches Camera_Y+$90

        assertFalse(controller.getInputLeft(),
                "loc_14106 clears Ctrl_2 before its Camera_Y+$90 threshold branch; routine $18 input starts next CPU pass");

        fixture.sprite().setDirectionalInputPressed(false, false, true, false);
        controller.update(3);

        assertTrue(controller.getInputLeft(),
                "ROM loc_141D2 ORs P1 left/right into Ctrl_2 during MGZ routine $18");
        assertFalse(controller.getInputRight());
    }

    @Test
    void mgzCpuRoutineTransitionsPreserveInheritedAutoFlyTimerForPlayerSteeredFlight() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0x0600);
        tails.setCentreY((short) 0x0690);
        controller.update(1);  // INIT -> CARRY_INIT

        SidekickCpuRewindExtra beforeCarryInit = controller.captureRewindState();
        controller.restoreRewindState(withMgzControlScalars(
                beforeCarryInit,
                false, 0x3C,
                beforeCarryInit.mgzReleasedChaseLatched(),
                beforeCarryInit.mgzReleasedChaseXAccel(),
                beforeCarryInit.mgzReleasedChaseYAccel()));

        controller.setInitialState(SidekickCpuController.State.MGZ_RESCUE_WAIT);
        assertEquals(0x3C, controller.captureRewindState().mgzCarryFlapTimer(),
                "ROM routine $12 does not clear the shared Tails_CPU_auto_fly_timer global");
        controller.setInitialState(SidekickCpuController.State.CARRY_INIT);
        controller.update(2);  // CARRY_INIT -> CARRYING; routine $16 reaches Camera_Y+$90

        assertEquals(0x3C, controller.captureRewindState().mgzCarryFlapTimer(),
                "ROM routines $14/$16 leave Tails_CPU_auto_fly_timer untouched");

        fixture.sprite().setDirectionalInputPressed(true, false, false, false);
        controller.update(3);

        assertTrue(controller.getInputJumpPress(),
                "The inherited timer must trigger the first routine $18 Up flap immediately");
        assertEquals(0, controller.captureRewindState().mgzCarryFlapTimer());
    }

    @Test
    void mgzCarryUpInputUsesRomTwentyFrameFlightPulseThreshold() {
        AbstractPlayableSprite[] pair = prepareCarry(alwaysOnJumpPulseTrigger());
        AbstractPlayableSprite tails = pair[1];
        fixture.camera().setY((short) 0x0600);
        tails.setCentreY((short) 0x0690);
        controller.update(1);
        controller.update(2);

        fixture.sprite().setDirectionalInputPressed(true, false, false, false);
        boolean pulsedBeforeThreshold = false;
        for (int frame = 3; frame < 3 + 0x1F; frame++) {
            controller.update(frame);
            pulsedBeforeThreshold |= controller.getInputJumpPress();
        }
        controller.update(3 + 0x1F);

        assertFalse(pulsedBeforeThreshold,
                "MGZ routine $18 should not keep pulsing A/B/C every 8 frames once P1 control is active");
        assertTrue(controller.getInputJumpPress(),
                "Holding Up should make Tails flap on the ROM $20-frame threshold");
    }
}
