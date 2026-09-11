package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.tests.TestEnvironment;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/** Focused native-contract tests for the AIZ FallingShot (loc_68C96) port. */
class TestAizMinibossNapalmProjectile {
    private Camera camera;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        SessionManager.clear();
        camera = TestEnvironment.activeGameplayMode().getCamera();
        camera.resetState();
        camera.setX((short) 0);
        camera.setY((short) 0);
    }

    @Test
    void fallingShotPublishesNativeHazardAndPostMovementTouchState() {
        AizMinibossNapalmProjectile projectile = new AizMinibossNapalmProjectile(100, 160);

        TouchResponseProvider touch = assertInstanceOf(TouchResponseProvider.class, projectile);
        assertEquals(0x98, touch.getCollisionFlags(), "ObjDat_AIZMiniboss_BarrelShot collision byte");
        assertTrue(projectile.usesCurrentTouchResponseState(),
                "loc_68C96 adds the projectile after MoveSprite2");
    }

    @Test
    void risingPhaseUsesRomVelocityAndWaitWindow() {
        AizMinibossNapalmProjectile projectile = new AizMinibossNapalmProjectile(100, 160);
        projectile.setServices(new TestObjectServices().withCamera(camera));

        projectile.update(0, null);
        assertEquals(160, projectile.getY(), "the init routine does not move before Rise dispatch");
        assertEquals(0x98, ((TouchResponseProvider) projectile).getCollisionFlags());

        projectile.update(1, null);
        assertEquals(156, projectile.getY(), "MoveSprite2 applies -$400 as -4 pixels");

        for (int frame = 2; frame <= 0x60; frame++) {
            projectile.update(frame, null);
        }
        assertTrue(projectile.getY() < 0,
                "the FallingShot remains in its native rising routine for the $60 wait");
    }

    @Test
    void firstRiseDispatchAdvancesRawAnimationImmediately() throws Exception {
        AizMinibossNapalmProjectile projectile = new AizMinibossNapalmProjectile(100, 160);
        projectile.setServices(new TestObjectServices().withCamera(camera));

        projectile.update(0, null); // FallingShot_Init
        assertEquals(0x0C, readIntField(projectile, "mappingFrame"));

        projectile.update(1, null); // first Rise: Animate_Raw before MoveSprite2
        assertEquals(0x0D, readIntField(projectile, "mappingFrame"),
                "the first Rise dispatch must consume AniRaw's initial C/D prefix");
    }

    @Test
    void rewindUsesGenericRecreateContract() {
        AizMinibossNapalmProjectile projectile = new AizMinibossNapalmProjectile(100, 160);
        assertInstanceOf(RewindRecreatable.class, projectile);
    }

    @Test
    void dropUsesNativeShotPositionAndPriorityTransition() {
        AizMinibossNapalmProjectile projectile = new AizMinibossNapalmProjectile(
                100, 160, 0, false, 0);
        RecordingObjectServices services = new RecordingObjectServices().withCamera(camera);
        projectile.setServices(services);

        projectile.update(0, null); // FallingShot_Init
        for (int frame = 1; frame <= 97; frame++) {
            projectile.update(frame, null); // Rise + Obj_Wait ($60)
        }
        for (int frame = 98; frame <= 106; frame++) {
            projectile.update(frame, null); // pause + FallingShot_Drop
        }
        assertEquals(36, projectile.getX(), "SetShotPosition selects camera X + $24");
        assertEquals(-0x20, projectile.getY(), "FallingShot_Drop selects camera Y - $20");
        assertEquals(List.of(Sonic3kSfx.PROJECTILE.id), services.playedSfx,
                "FallingShot emits only its inherited init/projectile sound; no throw sound");
        assertEquals(5, projectile.getPriorityBucket(),
                "priority changes only in FallingShot_StartFall after the delay");

        projectile.update(107, null);
        assertEquals(1, projectile.getPriorityBucket());
        projectile.update(108, null);
        assertEquals(-0x1C, projectile.getY(), "Fall uses positive $400 MoveSprite2 velocity");
    }

    @Test
    void floorRoutineSnapsByRomDistanceBeforeSpawningExplosion() {
        AizMinibossNapalmProjectile projectile = new AizMinibossNapalmProjectile(100, 160);
        RecordingObjectServices services = new RecordingObjectServices().withCamera(camera);
        projectile.setServices(services);
        for (int frame = 0; frame <= 107; frame++) {
            projectile.update(frame, null);
        }

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(36, -0x1C, 8))
                    .thenReturn(new TerrainCheckResult(-3, (byte) 0, 0));

            projectile.update(108, null);

            assertTrue(projectile.isDestroyed(), "ObjHitFloor_DoRoutine calls the explode callback");
            assertEquals(-0x1F, projectile.getY(),
                    "the native floor distance is added after MoveSprite2");
            assertEquals(List.of(Sonic3kSfx.PROJECTILE.id, Sonic3kSfx.MISSILE_EXPLODE.id),
                    services.playedSfx,
                    "floor impact adds only MissileExplode after the projectile init sound");
            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(36, -0x1C, 8));
        }
    }

    @Test
    void explosionChildrenUseNativeStaggerAndHazardWindow() throws Exception {
        AizMinibossNapalmExplosionChild child =
                new AizMinibossNapalmExplosionChild(200, 100, 0, true);
        assertInstanceOf(RewindRecreatable.class, child);
        assertEquals(0, child.getCollisionFlags(), "BossExplosionHitbox waits before animation");

        // One dispatch is the native routine-0 INIT; the subtype wait then
        // reaches StartAnim, whose routine-4 animation/touch starts on the
        // following dispatch.
        for (int frame = 0; frame < 27; frame++) {
            child.update(frame, null);
        }
        assertEquals(0x97, child.getCollisionFlags(),
                "BossExplosionHitbox becomes harmful when routine 4 starts");
        assertEquals(0, readIntField(child, "animationIndex"),
                "first routine-4 dispatch must advance Animate_RawMultiDelay to frame 0");
        assertEquals(200, child.getX());
        assertEquals(100, child.getY());

        child.update(27, null);
        assertEquals(0, readIntField(child, "animationIndex"),
                "AniRaw delay 1 holds frame 0 for the next dispatch (N+1 semantics)");
        child.update(28, null);
        assertEquals(1, readIntField(child, "animationIndex"));
    }

    private static int readIntField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static final class RecordingObjectServices extends TestObjectServices {
        private final List<Integer> playedSfx = new ArrayList<>();

        @Override
        public RecordingObjectServices withCamera(Camera camera) {
            super.withCamera(camera);
            return this;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }
    }
}
