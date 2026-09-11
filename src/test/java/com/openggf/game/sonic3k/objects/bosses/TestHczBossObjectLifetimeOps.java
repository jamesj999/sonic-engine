package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHczBossObjectLifetimeOps {

    @Test
    void impactExplosionExpiresLatchedWhenTheBossSignalsDefeat() throws Exception {
        Fixture fixture = fixture();
        setBooleanField(fixture.boss(), "defeatSignal", true);

        fixture.impact().update(0, null);

        assertLatched(fixture.impact());
    }

    @Test
    void impactExplosionExpiresLatchedAfterItsFinalAnimationFrame() {
        Fixture fixture = fixture();

        for (int frame = 0; frame < 64 && !fixture.impact().isDestroyed(); frame++) {
            fixture.impact().update(frame, null);
        }

        assertLatched(fixture.impact());
    }

    private static Fixture fixture() {
        ObjectServices services = new TestObjectServices()
                .withConfiguration(SonicConfigurationService.createStandalone());
        HczEndBossInstance boss = withConstructionContext(services, () -> new HczEndBossInstance(
                new ObjectSpawn(0x4000, 0x0738, 0x9A, 0, 0, false, 0)));
        boss.setServices(services);
        HczEndBossBladeImpactExplosion impact = withConstructionContext(
                services, () -> new HczEndBossBladeImpactExplosion(boss, 0x4000, 0x07F7));
        impact.setServices(services);
        return new Fixture(boss, impact);
    }

    private static void setBooleanField(Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void assertLatched(AbstractObjectInstance object) {
        assertTrue(object.isDestroyed());
        assertFalse(object.isDestroyedRespawnable(),
                "boss child effects are one-shot dynamic objects, not placement respawns");
    }

    private static <T> T withConstructionContext(ObjectServices services, ThrowingSupplier<T> supplier) {
        try {
            Method set = AbstractObjectInstance.class
                    .getDeclaredMethod("setConstructionContext", ObjectServices.class);
            set.setAccessible(true);
            set.invoke(null, services);
            return supplier.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            try {
                Method clear = AbstractObjectInstance.class.getDeclaredMethod("clearConstructionContext");
                clear.setAccessible(true);
                clear.invoke(null);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    private record Fixture(HczEndBossInstance boss, HczEndBossBladeImpactExplosion impact) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
