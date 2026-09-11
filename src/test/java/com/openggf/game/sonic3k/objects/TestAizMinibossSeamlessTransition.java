package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AIZ2 fightable miniboss (object 0x91, {@link AizMinibossInstance}) is spawned in AIZ2 and
 * holds the boss arena camera lock every frame via {@code maintainArenaCameraLock()} even after
 * defeat (it does not use the defeat sequencer). It is {@code isPersistent()=true} and only
 * self-destroys once the end-of-level camera widening completes. If it were ever carried across a
 * seamless act reload while still alive — defeated or not — it would become an invisible,
 * camera-locking ghost in the next act, the same failure mode fixed for the AIZ1 cutscene miniboss
 * (0x90).
 *
 * <p>Guard against it: a carried AIZ miniboss removes itself and any tracked children instead of
 * riding into the next act (ROM: the boss object's RAM slot does not survive an act reload).
 */
class TestAizMinibossSeamlessTransition {

    @Test
    void minibossAndChildrenDoNotSurviveSeamlessActReload() throws Exception {
        AizMinibossInstance miniboss = new AizMinibossInstance(
                new ObjectSpawn(0x11F0, 0x0289, 0x91, 0, 0, false, 0x0289));
        miniboss.setServices(new StubObjectServices());

        AizMinibossFlameBarrelChild barrel = buildBarrel(miniboss, new TestObjectServices());
        miniboss.getChildComponents().add(barrel);

        // AIZ1 -> AIZ2 seamless transition world delta (LevelManager.offsetCarriedObjectsForTransition).
        miniboss.onCarriedAcrossSeamlessTransition(-0x2F00, -0x80);

        assertTrue(miniboss.isDestroyed(),
                "AIZ miniboss (0x91) must not ride a seamless act reload into the next act");
        assertTrue(barrel.isDestroyed(),
                "AIZ miniboss flame-barrel children must not survive the reload");
    }

    private static AizMinibossFlameBarrelChild buildBarrel(
            AizMinibossInstance parent, ObjectServices services) throws Exception {
        ThreadLocal<ObjectServices> context = constructionContext();
        context.set(services);
        try {
            AizMinibossFlameBarrelChild barrel = new AizMinibossFlameBarrelChild(parent, 0, false);
            barrel.setServices(services);
            return barrel;
        } finally {
            context.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<ObjectServices> constructionContext() throws Exception {
        Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
        field.setAccessible(true);
        return (ThreadLocal<ObjectServices>) field.get(null);
    }
}
