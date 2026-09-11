package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@RequiresRom(SonicGame.SONIC_3K)
class TestClamerObjectInstance {

    @Test
    void waitOffscreenHandoffRunsInitializationBeforeStateDispatch() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        try {
            ClamerObjectInstance clamer =
                    new ClamerObjectInstance(new ObjectSpawn(0x0100, 0x0070, 0xA3, 0, 1, false, 0));
            RecordingServices services = new RecordingServices();
            clamer.setServices(services);
            AbstractPlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
            player.setCentreX((short) (0x0100 + 0x40));
            player.setCentreY((short) 0x0070);

            clamer.update(0x1000, player);
            assertEquals(0, services.spawnedChildren.size(),
                    "Obj_WaitOffscreen must only release the wrapper on its first visible pass");
            assertEquals(0x02, clamer.testRoutine());

            clamer.update(0x1001, player);
            assertEquals(1, services.spawnedChildren.size(),
                    "Clamer initialization must create its spring child on the pass after the wait wrapper");
            assertEquals(0x02, clamer.testRoutine(),
                    "the initialization pass must return before dispatching the idle state");

            clamer.update(0x1002, player);
            assertEquals(0x06, clamer.testRoutine(),
                    "the idle gate must run only after the ROM-style initialization pass");
        } finally {
            AbstractObjectInstance.resetCameraBoundsForTests();
        }
    }

    @Test
    void updateAllocatesRomSpringChildSlotAtChildObjDat89148Offset() {
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0578, 0x0690, 0xA3, 0, 0, false, 0));
        RecordingServices services = new RecordingServices();
        clamer.setServices(services);
        AbstractPlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        clamer.testRunProductionInitializationAfterOffscreenWait();

        assertEquals(1, services.spawnedChildren.size(),
                "Obj_Clamer loc_88FDC creates one ChildObjDat_89148 child slot");
        ObjectInstance child = services.spawnedChildren.get(0);
        assertEquals(0x0578, child.getX());
        assertEquals(0x0688, child.getY());

        clamer.update(0x021D, player);
        assertEquals(1, services.spawnedChildren.size(), "Clamer must not respawn its child every update");

        clamer.onUnload();
        assertTrue(child.isDestroyed(), "Parent unload should release the slot-only spring child");
    }

    @Test
    void springChildUsesRomOffsetAndLaunchesPlayer() {
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0578, 0x0690, 0xA3, 0, 0, false, 0));
        clamer.setServices(new TestObjectServices());
        clamer.testRunProductionInitializationAfterOffscreenWait();
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        AbstractPlayableSprite player = fixture.sprite();
        player.setCentreX((short) 0x0573);
        player.setCentreY((short) 0x0678);
        player.setAir(false);
        player.setJumping(true);

        assertEquals(0x0578, clamer.getX());
        assertEquals(0x0690, clamer.getY());
        assertEquals(0x0578, clamer.getMultiTouchRegions()[1].x());
        assertEquals(0x0688, clamer.getMultiTouchRegions()[1].y());

        clamer.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0x026C);

        assertEquals((short) 0x0800, player.getXSpeed());
        assertEquals((short) 0x0800, player.getGSpeed());
        assertEquals((short) -0x0800, player.getYSpeed());
        assertEquals(0x067E, player.getCentreY());
        assertEquals(Direction.RIGHT, player.getDirection());
        assertEquals(Sonic3kAnimationIds.SPRING.id(), player.getAnimationId());
        assertTrue(player.getAir());
        assertFalse(player.isJumping());
        assertEquals(0, clamer.getCollisionFlags());

        clamer.update(0x026C, player);
        fixture.stepFrame(false, false, true, false, false);

        assertEquals((short) 0x07E8, player.getXSpeed());
        assertEquals((short) -0x07C8, player.getYSpeed());

        // ROM Clamer spring $2E cooldown (loc_890C8 -> loc_890D0,
        // sonic3k.asm:185965-185973) drains over a single update frame; the
        // engine state machine drains through COOLDOWN_DRAIN -> COOLDOWN_DONE.
        // Touch_Special during cooldown latches collision_property
        // (sonic3k.asm:21162-21194); the next loc_890AA update consumes it.
        clamer.update(0x026D, player);
        fixture.stepFrame(false, false, false, false, false);
        // F=0x026E touch sets the cprop latch (state=COOLDOWN_DONE).
        clamer.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0x026E);
        // F=0x026E spring update consumes the latch and applies the launch.
        clamer.update(0x026E, player);

        assertEquals(0x0674, player.getCentreY());
        assertEquals((short) 0x0800, player.getXSpeed());
        assertEquals((short) -0x0800, player.getYSpeed());
    }

    @Test
    void flippedSpringLaunchesLeft() {
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0578, 0x0690, 0xA3, 0, 1, false, 0));
        clamer.setServices(new TestObjectServices());
        clamer.testRunProductionInitializationAfterOffscreenWait();
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        clamer.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);

        assertEquals((short) -0x0800, player.getXSpeed());
        assertEquals((short) -0x0800, player.getGSpeed());
        assertEquals(Direction.LEFT, player.getDirection());
    }

    /**
     * ROM Clamer_Index auto-close gate (sonic3k.asm:185880-185902).
     * <p>When the closer player is within {@code abs(dx) < 0x60} of the
     * Clamer parent, on the side it faces, the parent transitions
     * routine 0x02 -> 0x06 ({@code loc_89036}). When the player is on
     * the opposite side or out of range, the parent stays in routine 0x02.
     */
    @Test
    void autoCloseFiresWhenPlayerOnFacingSideWithinThreshold() {
        // Clamer facing right (renderFlags bit 0 = 1). Player approaching
        // from the right side at dx=+0x40 (< 0x60 threshold).
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0C98, 0x0470, 0xA3, 0, 1, false, 0));
        clamer.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setCentreX((short) (0x0C98 + 0x40));
        player.setCentreY((short) 0x0470);

        clamer.testRunProductionInitializationAfterOffscreenWait();
        assertEquals(0x02, clamer.testRoutine());
        clamer.update(0x1000, player);
        // Facing right, player on the right (d0=2, then -=2 = 0): close.
        assertEquals(0x06, clamer.testRoutine());
    }

    @Test
    void autoCloseHoldsWhenPlayerOnOppositeSide() {
        // Clamer facing right; player on the LEFT (d0=0, then -=2 = -2).
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0C98, 0x0470, 0xA3, 0, 1, false, 0));
        clamer.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setCentreX((short) (0x0C98 - 0x40));
        player.setCentreY((short) 0x0470);

        clamer.testRunProductionInitializationAfterOffscreenWait();
        clamer.update(0x1000, player);
        assertEquals(0x02, clamer.testRoutine());
    }

    @Test
    void autoCloseHoldsWhenPlayerBeyondThreshold() {
        // Clamer facing right; player on the right but at dx=0x60 (== threshold).
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0C98, 0x0470, 0xA3, 0, 1, false, 0));
        clamer.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setCentreX((short) (0x0C98 + 0x60));
        player.setCentreY((short) 0x0470);

        clamer.testRunProductionInitializationAfterOffscreenWait();
        clamer.update(0x1000, player);
        assertEquals(0x02, clamer.testRoutine());
    }

    @Test
    void autoCloseAnimationCompletesAndReopens() {
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0C98, 0x0470, 0xA3, 0, 1, false, 0));
        clamer.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setCentreX((short) (0x0C98 + 0x40));
        player.setCentreY((short) 0x0470);

        clamer.testRunProductionInitializationAfterOffscreenWait();
        clamer.update(0x1000, player);
        assertEquals(0x06, clamer.testRoutine());

        // Move the player out of range so the gate would not re-trigger.
        player.setCentreX((short) (0x0C98 + 0x80));
        for (int i = 0; i < 160; i++) {
            clamer.update(0x1001 + i, player);
        }
        // After the close timer expires, loc_89056 resets routine to 0x02.
        assertEquals(0x02, clamer.testRoutine());
    }

    @Test
    void autoCloseFrameEightSpawnsRomProjectileChildSlot() {
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0100, 0x0070, 0xA3, 0, 1, false, 0));
        RecordingServices services = new RecordingServices();
        clamer.setServices(services);
        AbstractPlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) (0x0100 + 0x40));
        player.setCentreY((short) 0x0070);

        clamer.testRunProductionInitializationAfterOffscreenWait();
        clamer.update(0x1000, player);
        assertEquals(0x06, clamer.testRoutine());

        for (int i = 0; i < 54; i++) {
            clamer.update(0x1001 + i, player);
        }
        assertEquals(1, services.spawnedChildren.size(),
                "ROM byte_89185 reaches mapping_frame 8 on the 55th routine-6 tick, not earlier");
        clamer.update(0x1001 + 54, player);

        assertEquals(2, services.spawnedChildren.size(),
                "Clamer should allocate the spring child and ChildObjDat_89150 projectile");
        ObjectInstance projectile = services.spawnedChildren.get(1);
        assertTrue(projectile.usesCurrentTouchResponseState(),
                "Collision_response_list retains the loc_86D5E projectile SST pointer");
        assertEquals(0x0110, projectile.getX(),
                "CreateChild5_ComplexAdjusted flips the -$10 X offset when render_flags bit 0 is set");
        assertEquals(0x0072, projectile.getY());

        projectile.update(0x1100, player);
        assertEquals(0x0112, projectile.getX(), "MoveSprite2 applies +$200 X velocity with no gravity");
        assertEquals(0x0072, projectile.getY());
    }

    @Test
    void verticallyOffscreenAutoCloseDoesNotSpawnProjectile() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x140, 0xE0, 0);
        try {
            ClamerObjectInstance clamer =
                    new ClamerObjectInstance(new ObjectSpawn(0x0100, 0x0400, 0xA3, 0, 1, false, 0));
            RecordingServices services = new RecordingServices();
            clamer.setServices(services);
            AbstractPlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
            player.setCentreX((short) 0x0140);
            player.setCentreY((short) 0x0400);

            clamer.testRunProductionInitializationAfterOffscreenWait();
            clamer.update(0x1000, player);
            for (int i = 0; i < 55; i++) {
                clamer.update(0x1001 + i, player);
            }

            assertEquals(1, services.spawnedChildren.size(),
                    "loc_89064 tests render_flags bit 7, so only the permanent spring child exists");
        } finally {
            AbstractObjectInstance.resetCameraBoundsForTests();
        }
    }

    @Test
    void springFireDuringAutoCloseDoesNotAbortProjectilePath() {
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0100, 0x0070, 0xA3, 0, 0, false, 0));
        RecordingServices services = new RecordingServices();
        clamer.setServices(services);
        AbstractPlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) (0x0100 - 0x40));
        player.setCentreY((short) 0x0070);

        clamer.testRunProductionInitializationAfterOffscreenWait();
        clamer.update(0x0260, player);
        assertEquals(0x06, clamer.testRoutine());

        clamer.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0x0261);
        clamer.update(0x0261, player);

        assertEquals(0x06, clamer.testRoutine(),
                "ROM loc_890AA only sets parent $38 bit 0; loc_89064 continues the auto-close path");

        for (int i = 0; i < 70; i++) {
            clamer.update(0x0262 + i, player);
        }

        assertEquals(2, services.spawnedChildren.size(),
                "Routine 6 must still reach mapping_frame 8 and spawn ChildObjDat_89150");
        ObjectInstance projectile = services.spawnedChildren.get(1);
        assertEquals(0x00F0, projectile.getX());
        assertEquals(0x0072, projectile.getY());
    }

    @Test
    void playerParticipationUsesObjectPlayerQueryForAutoCloseAndCpropTarget() {
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0C98, 0x0470, 0xA3, 0, 1, false, 0));
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        nativeP2.setCpuControlled(true);
        main.setCentreX((short) (0x0C98 - 0x80));
        nativeP2.setCentreX((short) (0x0C98 + 0x40));
        clamer.setServices(new QueryOnlyServices(main, nativeP2));
        clamer.testRunProductionInitializationAfterOffscreenWait();

        clamer.testStepIdle(main);

        assertEquals(0x06, clamer.testRoutine());

        clamer.onTouchResponse(main, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);
        clamer.update(0, main);
        clamer.update(1, main);
        main.setYSpeed((short) 0);
        clamer.onTouchResponse(nativeP2, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 2);
        clamer.update(2, main);

        assertEquals((short) -0x0800, nativeP2.getYSpeed());
        assertEquals((short) 0, main.getYSpeed());
    }

    @Test
    void autoCloseUsesUpdatePrimaryAsNativeP1WhenQueryMainDiffers() {
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0C98, 0x0470, 0xA3, 0, 1, false, 0));
        TestablePlayableSprite updatePrimary = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestablePlayableSprite queriedMain = new TestablePlayableSprite("knuckles", (short) 0, (short) 0);
        updatePrimary.setCentreX((short) (0x0C98 + 0x40));
        queriedMain.setCentreX((short) (0x0C98 - 0x40));
        clamer.setServices(new QueryOnlyServices(queriedMain, List.of()));

        clamer.testStepIdle(updatePrimary);

        assertEquals(0x06, clamer.testRoutine());
    }

    @Test
    void cpropNativeP2UsesQueriedSidekickWhenQueryMainDiffersFromPrimary() {
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0578, 0x0690, 0xA3, 0, 0, false, 0));
        TestablePlayableSprite updatePrimary = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        TestablePlayableSprite queriedMain = new TestablePlayableSprite("knuckles", (short) 0, (short) 0);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        nativeP2.setCpuControlled(true);
        clamer.setServices(new QueryOnlyServices(queriedMain, List.of(nativeP2)));
        clamer.testRunProductionInitializationAfterOffscreenWait();

        clamer.onTouchResponse(updatePrimary, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);
        clamer.update(0, updatePrimary);
        clamer.update(1, updatePrimary);
        updatePrimary.setYSpeed((short) 0);
        clamer.onTouchResponse(nativeP2, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 2);
        clamer.update(2, updatePrimary);

        assertEquals((short) -0x0800, nativeP2.getYSpeed());
        assertEquals((short) 0, queriedMain.getYSpeed());
        assertEquals((short) 0, updatePrimary.getYSpeed());
    }

    @Test
    void touchResponseProfileDeclaresClamerSpecialMultiRegionContract() {
        ClamerObjectInstance clamer =
                new ClamerObjectInstance(new ObjectSpawn(0x0578, 0x0690, 0xA3, 0, 0, false, 0));

        TouchResponseProfile profile = clamer.getTouchResponseProfile();

        assertEquals(TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY, profile.categoryDecodeMode());
        assertTrue(profile.continuousCallbacks());
        assertTrue(profile.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                profile.stopAfterFirstOverlapPolicy());
    }

    private static final class QueryOnlyServices extends TestObjectServices {
        private final ObjectPlayerQuery playerQuery;

        private QueryOnlyServices(PlayableEntity main, PlayableEntity nativeP2) {
            this(main, List.of(nativeP2));
        }

        private QueryOnlyServices(PlayableEntity main, List<? extends PlayableEntity> sidekicks) {
            this.playerQuery = new ObjectPlayerQuery(() -> main, () -> sidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return playerQuery;
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            throw new AssertionError("Clamer must query participants through ObjectPlayerQuery");
        }
    }

    private static final class RecordingServices extends TestObjectServices {
        private final List<ObjectInstance> spawnedChildren = new ArrayList<>();
        private final ObjectManager objectManager;

        private RecordingServices() {
            objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                ObjectInstance child = invocation.getArgument(0);
                spawnedChildren.add(child);
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any());
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }
    }
}
