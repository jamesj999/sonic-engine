package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Session-level rewind coverage for scalar-only AIZ2 transient children whose
 * handwritten codecs are being deleted in favor of Phase-2 generic recreate.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestAiz2SelfContainedTransientRewind {

    private static final int ZONE_AIZ = 0;
    private static final int ACT_2 = 1;

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    void scalarOnlyTransientChildrenRestoreThroughSessionSnapshot() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_AIZ, ACT_2)
                .build();

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be available after AIZ2 boot");

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager, "ObjectManager must be available for AIZ2");

        int baseX = fixture.camera().getX() + 160;
        int baseY = fixture.camera().getY() + 112;

        AizBombExplosionInstance bombExplosion = objectManager.createDynamicObject(
                () -> new AizBombExplosionInstance(baseX - 0x30, baseY - 0x30, 1, 0));
        AizEndBossDebrisChild endBossDebris = objectManager.createDynamicObject(
                () -> new AizEndBossDebrisChild(baseX, baseY, 3));
        AizMinibossImpactFlameChild impactFlame = objectManager.createDynamicObject(
                () -> new AizMinibossImpactFlameChild(baseX + 0x20, baseY - 0x20, 0x0A, true));
        AizMinibossDebrisChild minibossDebris = objectManager.createDynamicObject(
                () -> new AizMinibossDebrisChild(baseX - 0x50, baseY - 0x40, 2, 4));

        List<AbstractObjectInstance> tracked = List.of(
                bombExplosion,
                endBossDebris,
                impactFlame,
                minibossDebris);

        for (int frame = 0; frame < 3; frame++) {
            for (AbstractObjectInstance instance : tracked) {
                instance.update(frame, fixture.sprite());
                assertFalse(instance.isDestroyed(),
                        instance.getClass().getSimpleName() + " should survive setup frame " + frame);
            }
        }

        assertEquals(1, countLive(objectManager, AizBombExplosionInstance.class),
                "precondition: exactly one bomb explosion fixture is live");
        assertEquals(1, countLive(objectManager, AizEndBossDebrisChild.class),
                "precondition: exactly one end-boss debris fixture is live");
        assertEquals(1, countLive(objectManager, AizMinibossImpactFlameChild.class),
                "precondition: exactly one impact-flame fixture is live");
        assertEquals(1, countLive(objectManager, AizMinibossDebrisChild.class),
                "precondition: exactly one miniboss debris fixture is live");

        Map<Class<?>, Map<String, Object>> capturedScalars = new LinkedHashMap<>();
        for (AbstractObjectInstance instance : tracked) {
            capturedScalars.put(instance.getClass(), declaredPrimitiveFieldValues(instance));
        }

        CompositeSnapshot snapshot = registry.capture();
        assertNotNull(snapshot, "capture() must return a snapshot");

        for (AbstractObjectInstance instance : tracked) {
            objectManager.removeDynamicObject(instance);
        }
        assertEquals(0, countLive(objectManager, AizBombExplosionInstance.class),
                "diverge step must remove the bomb explosion");
        assertEquals(0, countLive(objectManager, AizEndBossDebrisChild.class),
                "diverge step must remove the end-boss debris");
        assertEquals(0, countLive(objectManager, AizMinibossImpactFlameChild.class),
                "diverge step must remove the impact flame");
        assertEquals(0, countLive(objectManager, AizMinibossDebrisChild.class),
                "diverge step must remove the miniboss debris");

        registry.restore(snapshot);

        assertScalarRoundTrip(objectManager, AizBombExplosionInstance.class, capturedScalars);
        assertScalarRoundTrip(objectManager, AizEndBossDebrisChild.class, capturedScalars);
        assertScalarRoundTrip(objectManager, AizMinibossImpactFlameChild.class, capturedScalars);
        assertScalarRoundTrip(objectManager, AizMinibossDebrisChild.class, capturedScalars);
    }

    private static <T extends AbstractObjectInstance> void assertScalarRoundTrip(
            ObjectManager objectManager,
            Class<T> type,
            Map<Class<?>, Map<String, Object>> expectedScalars) throws Exception {
        List<T> restored = liveObjects(objectManager, type);
        assertEquals(1, restored.size(),
                "restore must recreate exactly one live " + type.getSimpleName());
        assertEquals(expectedScalars.get(type), declaredPrimitiveFieldValues(restored.get(0)),
                "restored primitive scalar fields must match captured state for " + type.getSimpleName());
    }

    private static int countLive(ObjectManager objectManager, Class<?> type) {
        return liveObjects(objectManager, type).size();
    }

    private static <T> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass() == type && !o.isDestroyed())
                .map(type::cast)
                .toList();
    }

    private static Map<String, Object> declaredPrimitiveFieldValues(ObjectInstance instance) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !field.getType().isPrimitive()) {
                continue;
            }
            field.setAccessible(true);
            values.put(field.getName(), field.get(instance));
        }
        return values;
    }
}
