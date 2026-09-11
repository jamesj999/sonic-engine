package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMgzBossObjectLifetimeOps {

    @BeforeEach
    void setUpCameraBounds() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void fallingDebrisExpiresLatchedAfterFallingBelowTheCamera() {
        MgzEndBossFallingDebrisChild debris = new MgzEndBossFallingDebrisChild(
                new ObjectSpawn(0, 0x130, 0, 0, 0, false, 0));
        debris.setServices(servicesWithCameraAtOrigin());

        debris.update(0, null);

        assertLatched(debris);
    }

    @Test
    void collapseEmitterExpiresLatchedAfterItsEmissionBudgetIsSpent() throws Exception {
        MgzEndBossKnuxCollapseEmitter emitter = new MgzEndBossKnuxCollapseEmitter(0, 0, false);
        emitter.setServices(servicesWithCameraAtOrigin());
        setIntField(emitter, "emissionsRemaining", 0);

        emitter.update(0, null);

        assertLatched(emitter);
    }

    @Test
    void collapseParticleExpiresLatchedBelowTheCamera() {
        MgzEndBossKnuxCollapseEmitter.Particle particle =
                new MgzEndBossKnuxCollapseEmitter.Particle(0, 0x141, 0, 0, 0);
        particle.setServices(servicesWithCameraAtOrigin());

        particle.update(0, null);

        assertLatched(particle);
    }

    @Test
    void defeatPartExpiresLatchedOutsideItsBossCameraBounds() {
        MgzEndBossKnuxDefeatPart part = new MgzEndBossKnuxDefeatPart(
                new ObjectSpawn(0, 0, 0, 0, 0, false, 0));
        part.setServices(servicesWithCamera(0x400, 0x400));

        part.update(0, null);

        assertLatched(part);
    }

    @Test
    void drillControllerExpiresLatchedWithItsDestroyedParent() {
        MgzEndBossKnuxInstance parent = new MgzEndBossKnuxInstance(
                new ObjectSpawn(0, 0, 0, 0, 0, false, 0));
        parent.setServices(servicesWithCameraAtOrigin());
        MgzEndBossKnuxDrillChild child = new MgzEndBossKnuxDrillChild(parent);
        child.setServices(servicesWithCameraAtOrigin());
        parent.setDestroyed(true);

        child.update(0, null);

        assertLatched(child);
    }

    @Test
    void completedPlacedBossUsesLatchedDestructionInsteadOfOffscreenRespawn() throws Exception {
        MgzEndBossKnuxInstance boss = new MgzEndBossKnuxInstance(
                new ObjectSpawn(0, 0, 0, 0, 0, true, 0));
        boss.setServices(new StubObjectServices().withPlayerQuery(
                new ObjectPlayerQuery(() -> null, List::of)));

        Method completion = MgzEndBossKnuxInstance.class
                .getDeclaredMethod("updatePostResultsContinuation");
        completion.setAccessible(true);
        completion.invoke(boss);

        assertLatched(boss);
    }

    private static StubObjectServices servicesWithCameraAtOrigin() {
        return servicesWithCamera(0, 0);
    }

    private static StubObjectServices servicesWithCamera(int x, int y) {
        Camera camera = new Camera();
        camera.setX((short) x);
        camera.setY((short) y);
        return new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void assertLatched(com.openggf.level.objects.AbstractObjectInstance object) {
        assertTrue(object.isDestroyed());
        assertFalse(object.isDestroyedRespawnable(),
                "boss choreography objects must not re-enter placement after expiring");
    }
}
