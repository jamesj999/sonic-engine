package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic2.OilSurfaceManager;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestOilSurfaceManager {

    private static final int OIL_SURFACE_Y = Sonic2Constants.OIL_SURFACE_Y - Sonic2Constants.OIL_SUBMERSION_MAX;

    private OilSurfaceManager manager;
    private TestOilSprite sprite;

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        manager = new OilSurfaceManager();
        sprite = new TestOilSprite("test", (short) 0, (short) 0);
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void keepsOilSupportWhenAirFlagTemporarilySet() {
        landOnOilSurface();
        assertTrue(manager.isStandingOnOil());

        // Simulate movement step clearing support before oil update.
        sprite.setAir(true);
        sprite.setOnObject(false);
        sprite.setYSpeed((short) 0x20);
        int before = manager.getSubmersion();

        manager.update(sprite);

        assertTrue(manager.isStandingOnOil());
        assertFalse(sprite.getAir());
        assertTrue(sprite.isOnObject());
        assertEquals(before - 1, manager.getSubmersion());
    }

    @Test
    void rollingOilLandingPublishesWalkWithoutAdvancingMapping() {
        sprite.setRolling(true);
        sprite.setAnimationId(Sonic2AnimationIds.ROLL.id());
        sprite.setMappingFrame(0x3F);

        landOnOilSurface();

        assertFalse(sprite.getRolling());
        assertEquals(Sonic2AnimationIds.WALK.id(), sprite.getAnimationId());
        assertEquals(0x3F, sprite.getMappingFrame(),
                "Obj07 lands after Animate and only publishes the raw Walk byte this frame");
    }

    @Test
    public void jumpReleaseClearsOilSupport() {
        landOnOilSurface();
        assertTrue(manager.isStandingOnOil());

        sprite.setAir(true);
        sprite.setJumping(true);
        sprite.setYSpeed((short) -0x200);

        manager.update(sprite);

        assertFalse(manager.isStandingOnOil());
        assertFalse(sprite.isOnObject());
    }

    @Test
    public void jumpReleaseTicksSubmersionBeforeClearingSupport() {
        landOnOilSurface();
        assertTrue(manager.isStandingOnOil());
        int before = manager.getSubmersion(sprite);

        sprite.setAir(true);
        sprite.setJumping(true);
        sprite.setYSpeed((short) -0x200);

        manager.updateSurface(sprite);

        assertEquals(before - 1, manager.getSubmersion(sprite),
                "Obj07 decrements oil_charNsubmersion before PlatformObject clears an airborne rider");
        assertFalse(manager.isStandingOnOil(sprite));
        assertFalse(sprite.isOnObject());
    }

    @Test
    public void suffocatesAfterSubmersionCountdownExpires() {
        landOnOilSurface();
        assertTrue(manager.isStandingOnOil());

        for (int i = 0; i < Sonic2Constants.OIL_SUBMERSION_MAX; i++) {
            manager.update(sprite);
            assertFalse(sprite.getDead(), "Should not be dead while submersion is decrementing");
        }

        assertEquals(0, manager.getSubmersion());
        manager.update(sprite);

        assertTrue(sprite.getDead(), "Should die when submersion reaches zero on standing frame");
        assertFalse(manager.isStandingOnOil());
        assertTrue(sprite.isOnObject(),
                "OOZ suffocation jumps to KillCharacter, which preserves Status_OnObj while setting Status_InAir");
    }

    @Test
    public void frictionSlideUsesLogicalHeldInputFromRomPreObjectSlot() throws Exception {
        sprite.setGSpeed((short) 0x0524);
        sprite.setDirectionalInputPressed(false, false, false, false);
        sprite.setLogicalInputState(false, false, false, true, false);

        invokeFrictionSlide();

        assertEquals(0x0528, sprite.getGSpeed() & 0xFFFF,
                "OilSlides reads Ctrl_1_Held_Logical before the player slot refreshes current raw input");
        assertTrue(sprite.isSliding());
    }

    @Test
    public void airborneSlideExitRefreshesMoveLockToRomDuration() {
        sprite.setAir(true);
        sprite.setSliding(true);
        sprite.setMoveLockTimer(3);

        manager.updateSlides(sprite);

        assertFalse(sprite.isSliding());
        assertEquals(5, sprite.getMoveLockTimer(),
                "OilSlides writes #5 to move_lock every time sliding exits, even over a shorter active lock");
    }

    @Test
    public void oilLandingPublishesSyntheticObj07InteractLatch() {
        landOnOilSurface();

        assertEquals(Sonic2ObjectIds.OIL, sprite.getLatchedSolidObjectId(),
                "Obj07 PlatformObject landing should publish the oil object id for TailsCPU_CheckDespawn");
        assertEquals(AbstractPlayableSprite.SYNTHETIC_INTERACT_SLOT, sprite.getInteractSlotIndex(),
                "Obj07 is manager-hosted, so its ROM interact target is represented by a synthetic slot");
    }

    @Test
    public void hurtLandingOnOilKeepsRoutineFourUntilHurtStop() {
        int centreY = OIL_SURFACE_Y + 1 - sprite.getYRadius();
        sprite.setCentreY((short) centreY);
        sprite.setAir(true);
        sprite.setOnObject(false);
        sprite.setJumping(false);
        sprite.setHurt(true);
        sprite.setXSpeed((short) 0x0200);
        sprite.setYSpeed((short) 0x0200);
        sprite.setGSpeed((short) 0);
        sprite.setAnimationId(Sonic2AnimationIds.HURT.id());
        sprite.setMappingFrame(0x5A);

        manager.update(sprite);

        assertTrue(sprite.isHurt(),
                "Obj07 landing clears Status_InAir, but S2 Tails_HurtStop owns routine-4 recovery next frame");
        assertFalse(sprite.getAir());
        assertTrue(sprite.isOnObject());
        assertEquals(0x0200, sprite.getXSpeed() & 0xFFFF);
        assertEquals(0x0200, sprite.getGSpeed() & 0xFFFF,
                "RideObject_SetRide copies x_vel to inertia on the landing frame before HurtStop zeroes it");
        assertEquals(0, sprite.getYSpeed());
        assertEquals(Sonic2AnimationIds.HURT.id(), sprite.getAnimationId(),
                "Obj07 runs after Animate, so HurtStop does not publish Walk until the next player tick");
        assertEquals(0x5A, sprite.getMappingFrame(),
                "the oil landing must not advance or replace the already-rendered Hurt mapping");
    }

    @Test
    public void deadFallingSidekickReleasesExistingOilSupport() throws Exception {
        landOnOilSurface();
        assertTrue(manager.isStandingOnOil(sprite));

        makeDeadFallingCpuSidekick(sprite);
        sprite.setAir(true);
        sprite.setOnObject(true);
        sprite.setYSpeed((short) 0x0620);

        manager.updateSurface(sprite);

        assertFalse(manager.isStandingOnOil(sprite));
        assertTrue(sprite.getAir(),
                "S2 Obj07 PlatformObject aborts for Obj02_Dead (routine >= 6), leaving Tails airborne");
        assertFalse(sprite.isOnObject(),
                "DEAD_FALLING mirrors ROM routine 6, so stale Obj07 support must be cleared");
        assertEquals(0x0620, sprite.getYSpeed() & 0xFFFF,
                "Obj07 must not run RideObject_SetRide for dead-falling Tails");
    }

    @Test
    public void deadFallingSidekickDoesNotLandOnBossLoweredOilSurface() throws Exception {
        makeDeadFallingCpuSidekick(sprite);
        manager.setOilSurfaceY(0x02D8);
        sprite.setCentreY((short) 0x0299);
        sprite.setAir(true);
        sprite.setOnObject(false);
        sprite.setYSpeed((short) 0x0620);

        manager.updateSurface(sprite);

        assertFalse(manager.isStandingOnOil(sprite));
        assertTrue(sprite.getAir());
        assertFalse(sprite.isOnObject());
        assertEquals(0x0299, sprite.getCentreY() & 0xFFFF,
                "PlatformObject_ChkYRange skips routine >= 6 before snapping y_pos");
        assertEquals(0x0620, sprite.getYSpeed() & 0xFFFF);
    }

    private void landOnOilSurface() {
        int centreY = OIL_SURFACE_Y + 1 - sprite.getYRadius();
        sprite.setCentreY((short) centreY);
        sprite.setAir(true);
        sprite.setOnObject(false);
        sprite.setJumping(false);
        sprite.setYSpeed((short) 0x200);
        manager.update(sprite);
    }

    private static void makeDeadFallingCpuSidekick(TestOilSprite sidekick) throws Exception {
        TestOilSprite leader = new TestOilSprite("leader", (short) 0, (short) 0);
        sidekick.setCpuControlled(true);
        SidekickCpuController controller = new SidekickCpuController(sidekick, leader);
        Field stateField = SidekickCpuController.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(controller, SidekickCpuController.State.DEAD_FALLING);
    }

    private void invokeFrictionSlide() throws Exception {
        Method method = OilSurfaceManager.class.getDeclaredMethod("applyFrictionSlide", AbstractPlayableSprite.class);
        method.setAccessible(true);
        method.invoke(manager, sprite);
    }

    private static class TestOilSprite extends AbstractPlayableSprite {
        TestOilSprite(String code, short x, short y) {
            super(code, x, y);
        }

        @Override
        public void draw() {
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 12;
            runDecel = 128;
            friction = 12;
            max = 1536;
            jump = 1664;
        }

        @Override
        protected void createSensorLines() {
        }
    }
}
