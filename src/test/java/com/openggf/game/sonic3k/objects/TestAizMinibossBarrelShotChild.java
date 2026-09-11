package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.graphics.GLCommand;

import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;
import com.openggf.tests.TestEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestAizMinibossBarrelShotChild {

    private DummyBoss parent;
    private AizMinibossFlameBarrelChild barrel;
    private Camera camera;

    @BeforeEach
    public void setUp() {
        TestEnvironment.resetAll();
        SessionManager.clear();
        camera = TestEnvironment.activeGameplayMode().getCamera();
        camera.resetState();
        camera.setX((short) 0);
        camera.setY((short) 0);
        parent = new DummyBoss();
        barrel = new AizMinibossFlameBarrelChild(parent, 0, false);
        barrel.setServices(new TestObjectServices().withCamera(camera));
    }

    @Test
    public void simpleModeNeverBecomesHazardousAndSelfDeletes() {
        AizMinibossBarrelShotChild shot = new AizMinibossBarrelShotChild(
                parent, barrel, 100, 100, AizMinibossBarrelShotChild.Mode.SIMPLE);
        shot.setServices(new TestObjectServices().withCamera(camera));

        for (int i = 0; i < 250 && !shot.isDestroyed(); i++) {
            shot.update(i, null);
            assertEquals(0, shot.getCollisionFlags());
        }

        assertTrue(shot.isDestroyed());
    }

    @Test
    public void advancedNonCollidingModeNeverSetsCollisionFlags() {
        AizMinibossBarrelShotChild shot = new AizMinibossBarrelShotChild(
                parent, barrel, 100, 100, AizMinibossBarrelShotChild.Mode.ADVANCED_NON_COLLIDING);
        shot.setServices(new TestObjectServices().withCamera(camera));

        for (int i = 0; i < 220 && !shot.isDestroyed(); i++) {
            shot.update(i, null);
            assertEquals(0, shot.getCollisionFlags());
        }
    }

    @Test
    public void advancedCollidingModeEventuallyEntersHazardPhase() {
        AizMinibossBarrelShotChild shot = new AizMinibossBarrelShotChild(
                parent, barrel, 100, 100, AizMinibossBarrelShotChild.Mode.ADVANCED_COLLIDING);
        shot.setServices(new TestObjectServices().withCamera(camera));

        boolean sawCollision = false;
        for (int i = 0; i < 260 && !shot.isDestroyed(); i++) {
            shot.update(i, null);
            if (shot.getCollisionFlags() != 0) {
                sawCollision = true;
                break;
            }
        }

        assertTrue(sawCollision, "Expected colliding shot to expose collision flags during top-drop");
    }

    @Test
    public void flameChildTracksParentOffsetsAndFlip() {
        AizMinibossFlameChild flame = new AizMinibossFlameChild(parent, -0x64, 4, 0);
        flame.setServices(new TestObjectServices().withCamera(camera));

        flame.update(0, null);
        assertEquals(parent.getX() - 0x64, flame.getX());

        parent.getState().renderFlags = 1;
        flame.update(1, null);
        assertEquals(parent.getX() + 0x64, flame.getX());
    }

    @Test
    public void barrelShotDeclaresShieldDeflectTouchResponseProfile() {
        AizMinibossBarrelShotChild shot = new AizMinibossBarrelShotChild(
                parent, barrel, 100, 100, AizMinibossBarrelShotChild.Mode.ADVANCED_COLLIDING);

        assertDeclaresProfileMethods(AizMinibossBarrelShotChild.class);

        TouchResponseProfile profile = shot.getTouchResponseProfile();
        assertStandardSingleRegionEnemy(profile);
        assertTrue(profile.requiresRenderFlagForTouch());
        assertEquals(TouchShieldDeflectCapability.SHIELD_DEFLECT, profile.shieldDeflectCapability());
        assertEquals(0x18, profile.shieldReactionFlags());
        assertEquals(profile, shot.getTouchResponseProfile(false));
    }

    @Test
    public void flameChildDeclaresFireShieldTouchResponseProfile() {
        AizMinibossFlameChild flame = new AizMinibossFlameChild(parent, -0x64, 4, 0);

        assertDeclaresProfileMethods(AizMinibossFlameChild.class);

        TouchResponseProfile profile = flame.getTouchResponseProfile();
        assertStandardSingleRegionEnemy(profile);
        assertTrue(profile.requiresRenderFlagForTouch());
        assertEquals(TouchShieldDeflectCapability.NONE, profile.shieldDeflectCapability());
        assertEquals(0x10, profile.shieldReactionFlags());
        assertEquals(profile, flame.getTouchResponseProfile(false));
    }

    private static void assertDeclaresProfileMethods(Class<?> type) {
        assertDoesNotThrow(() -> type.getDeclaredMethod("getTouchResponseProfile"));
        assertDoesNotThrow(() -> type.getDeclaredMethod("getTouchResponseProfile", boolean.class));
    }

    private static void assertStandardSingleRegionEnemy(TouchResponseProfile profile) {
        assertEquals(TouchCategoryDecodeMode.NORMAL, profile.categoryDecodeMode());
        assertFalse(profile.continuousCallbacks());
        assertFalse(profile.multiRegionSource());
        assertEquals(TouchAttackBouncePolicy.STANDARD_ENEMY_KILL, profile.attackBouncePolicy());
        assertEquals(TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY, profile.actorContextPolicy());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                profile.stopAfterFirstOverlapPolicy());
    }

    private final class DummyBoss extends AbstractBossInstance {
        private DummyBoss() {
            super(new ObjectSpawn(0x1200, 0x300, 0x91, 0, 0, false, 0), "DummyBoss");
            setServices(new TestObjectServices().withCamera(camera));
            state.x = 0x1200;
            state.y = 0x300;
            state.xFixed = state.x << 16;
            state.yFixed = state.y << 16;
        }

        @Override
        protected void initializeBossState() {
            state.routine = 0;
            state.hitCount = 6;
        }

        @Override
        protected void updateBossLogic(int vIntRunCount, PlayableEntity player) {
            // No-op test stub.
        }

        @Override
        protected int getInitialHitCount() {
            return 6;
        }

        @Override
        protected void onHitTaken(int remainingHits) {
            // No-op test stub.
        }

        @Override
        protected int getCollisionSizeIndex() {
            return 0x0F;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No-op test stub.
        }

        @Override
        public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
            // No-op test stub.
        }

        @Override
        protected int getBossHitSfxId() {
            return 0;
        }

        @Override
        protected int getBossExplosionSfxId() {
            return 0;
        }
    }
}


