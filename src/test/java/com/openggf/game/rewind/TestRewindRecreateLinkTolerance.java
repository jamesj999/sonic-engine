package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.sonic1.objects.bosses.Sonic1SYZBossInstance;
import com.openggf.game.sonic2.objects.CogObjectInstance;
import com.openggf.game.sonic2.objects.MCZBrickObjectInstance;
import com.openggf.game.sonic2.objects.SwingingPlatformObjectInstance;
import com.openggf.game.sonic2.objects.badniks.AquisBadnikInstance;
import com.openggf.game.sonic2.objects.bosses.HTZBossFlamethrower;
import com.openggf.game.sonic2.objects.bosses.HTZBossLavaBall;
import com.openggf.game.sonic3k.objects.GumballMachineObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Fail-first coverage for the tolerant recreate-link fixes.
 *
 * <p>Each case reproduces the crash family the sweep addresses: a child object whose
 * {@code recreateForRewind} resolves a structural parent/sibling link by scanning the
 * restore-time {@link ObjectManager}, invoked when that link target is <em>absent</em>
 * (the player destroyed it and it was swept before capture). Before the fix each of
 * these threw {@code IllegalStateException} from the recreate helper; after the fix the
 * lookup returns empty and the recreate returns {@code null} — dropping the orphaned
 * child, exactly as its live {@code update()} self-expires with a dead parent.
 *
 * <p>The probe is built with a throwaway parent so the recreate method exists to call;
 * the manager passed in the {@link RewindRecreateContext} is empty, so the in-method
 * link scan finds nothing.
 */
class TestRewindRecreateLinkTolerance {

    private static final ObjectSpawn SPAWN = new ObjectSpawn(0x1800, 0x0500, 0, 0, 1, false, 0, 7);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void htzFlamethrowerDropsWhenBossAbsent() {
        assertDropsWhenLinkAbsent(om ->
                newInstance(HTZBossFlamethrower.class, new Class<?>[]{ObjectSpawn.class}, SPAWN));
    }

    @Test
    void htzLavaBallDropsWhenBossAbsent() {
        assertDropsWhenLinkAbsent(om ->
                newInstance(HTZBossLavaBall.class, new Class<?>[]{ObjectSpawn.class}, SPAWN));
    }

    @Test
    void syzBossSpikeDropsWhenBossAbsent() {
        assertDropsWhenLinkAbsent(om -> {
            Sonic1SYZBossInstance boss = construct(om,
                    () -> new Sonic1SYZBossInstance(SPAWN));
            return newInstance("com.openggf.game.sonic1.objects.bosses.SYZBossSpike",
                    new Class<?>[]{Sonic1SYZBossInstance.class},
                    boss);
        });
    }

    @Test
    void cogSlotChildDropsWhenParentAbsent() {
        assertDropsWhenLinkAbsent(om -> {
            CogObjectInstance parent = construct(om,
                    () -> new CogObjectInstance(SPAWN, "CogRewindProbe"));
            return newInstance("com.openggf.game.sonic2.objects.CogObjectInstance$CogSlotChildInstance",
                    new Class<?>[]{ObjectSpawn.class, CogObjectInstance.class}, SPAWN, parent);
        });
    }

    @Test
    void mczBrickDisplayChildDropsWhenParentAbsent() {
        assertDropsWhenLinkAbsent(om -> {
            MCZBrickObjectInstance parent = construct(om,
                    () -> new MCZBrickObjectInstance(SPAWN, "MCZRewindProbe"));
            return newInstance("com.openggf.game.sonic2.objects.MCZBrickObjectInstance$MCZBrickDisplayChild",
                    new Class<?>[]{MCZBrickObjectInstance.class}, parent);
        });
    }

    @Test
    void swingingPlatformDisplayChildDropsWhenParentAbsent() {
        assertDropsWhenLinkAbsent(om -> {
            SwingingPlatformObjectInstance parent = construct(om,
                    () -> new SwingingPlatformObjectInstance(SPAWN, "SwingRewindProbe"));
            return newInstance(
                    "com.openggf.game.sonic2.objects.SwingingPlatformObjectInstance$SwingingPlatformDisplayChild",
                    new Class<?>[]{SwingingPlatformObjectInstance.class}, parent);
        });
    }

    @Test
    void aquisWingChildDropsWhenParentAbsent() {
        assertDropsWhenLinkAbsent(om -> {
            AquisBadnikInstance parent = construct(om, () -> new AquisBadnikInstance(SPAWN));
            return newInstance("com.openggf.game.sonic2.objects.badniks.AquisBadnikInstance$AquisWingChild",
                    new Class<?>[]{ObjectSpawn.class, AquisBadnikInstance.class}, SPAWN, parent);
        });
    }

    @Test
    void gumballContainerChildDropsWhenMachineAbsent() {
        assertDropsWhenLinkAbsent(om -> {
            GumballMachineObjectInstance machine = construct(om,
                    () -> new GumballMachineObjectInstance(SPAWN));
            return newInstance(
                    "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$ContainerDisplayChild",
                    new Class<?>[]{ObjectSpawn.class, GumballMachineObjectInstance.class, int.class},
                    SPAWN, machine, 0);
        });
    }

    @Test
    void gumballSpringChildDropsWhenMachineAbsent() {
        assertDropsWhenLinkAbsent(om ->
                newInstance("com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$GumballSpringChild",
                        new Class<?>[]{}));
    }

    /**
     * Builds the probe via {@code probeBuilder}, empties the manager so the recreate's
     * link scan finds nothing, then asserts the recreate drops the child (returns null)
     * without throwing.
     */
    private void assertDropsWhenLinkAbsent(Function<ObjectManager, RewindRecreatable> probeBuilder) {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();

        RewindRecreatable probe = probeBuilder.apply(objectManager);

        // Sweep every object (including any throwaway parent/child) so the recreate's
        // getActiveObjects() link scan resolves to nothing — the absent-link state.
        objectManager.reset(harness.cameraX());

        RewindRecreateContext ctx = new RewindRecreateContext(
                SPAWN, null, harness.services(), objectManager, null);
        ObjectInstance recreated = probe.recreateForRewind(ctx);

        assertNull(recreated, probe.getClass().getName()
                + " must drop the orphaned child when its link target is absent, not throw");
    }

    /** Constructs an object with ThreadLocal service injection, matching production wiring. */
    private static <T extends ObjectInstance> T construct(ObjectManager om, Constructing<T> factory) {
        return om.createDynamicObject(() -> {
            try {
                return factory.build();
            } catch (Exception e) {
                throw new IllegalStateException("probe parent construction failed", e);
            }
        });
    }

    @FunctionalInterface
    private interface Constructing<T> {
        T build() throws Exception;
    }

    private static RewindRecreatable newInstance(
            Class<?> type, Class<?>[] paramTypes, Object... args) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return (RewindRecreatable) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct probe " + type.getName(), e);
        }
    }

    private static RewindRecreatable newInstance(
            String className, Class<?>[] paramTypes, Object... args) {
        return newInstance(forName(className), paramTypes, args);
    }

    private static Class<?> forName(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Missing probe class " + className, e);
        }
    }

    private static final class Harness {
        private final ObjectManager objectManager;
        private final MutableServices services;
        private final TestCamera camera;

        private Harness(ObjectManager objectManager, MutableServices services, TestCamera camera) {
            this.objectManager = objectManager;
            this.services = services;
            this.camera = camera;
        }

        static Harness create() {
            AbstractPlayableSprite player =
                    new TestablePlayableSprite("sonic", (short) 0x1200, (short) 0x0400);
            TestCamera camera = new TestCamera();
            camera.setFocusedSprite(player);
            MutableServices services = new MutableServices(camera);
            ObjectManager objectManager = new ObjectManager(
                    List.of(), null, 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            services.objectManager = objectManager;
            objectManager.reset(camera.getX());
            objectManager.setRewindInPlaceRestoreEnabledForTest(false);
            return new Harness(objectManager, services, camera);
        }

        ObjectManager objectManager() {
            return objectManager;
        }

        MutableServices services() {
            return services;
        }

        int cameraX() {
            return camera.getX();
        }
    }

    private static final class MutableServices extends StubObjectServices {
        private ObjectManager objectManager;
        private final Camera camera;

        private MutableServices(Camera camera) {
            this.camera = camera;
        }

        @Override public ObjectManager objectManager() { return objectManager; }
        @Override public Camera camera() { return camera; }
        @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
    }

    private static final class TestCamera extends Camera {
        private AbstractPlayableSprite focusedSprite;

        @Override public void setFocusedSprite(AbstractPlayableSprite sprite) { focusedSprite = sprite; }
        @Override public AbstractPlayableSprite getFocusedSprite() { return focusedSprite; }
        @Override public short getX() { return 0x1000; }
        @Override public short getY() { return 0x0300; }
        @Override public short getWidth() { return 320; }
        @Override public short getHeight() { return 224; }
        @Override public boolean isVerticalWrapEnabled() { return false; }
    }
}
