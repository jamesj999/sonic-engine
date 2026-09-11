package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestRewindRecreateObjectLinks {
    private ObjectManager objectManager;
    private StubObjectServices services;

    @BeforeEach
    void setUp() {
        GraphicsManager.getInstance().initHeadless();
        ObjectManager[] holder = new ObjectManager[1];
        services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        objectManager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), mockCamera(), services);
        holder[0] = objectManager;
        objectManager.reset(0);
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void nearestLiveObjectSelectsClosestLiveCandidateOfRequestedType() {
        LinkProbeObject far = new LinkProbeObject(40, 40);
        LinkProbeObject nearestDestroyed = new LinkProbeObject(101, 101);
        LinkProbeObject nearestLive = new LinkProbeObject(108, 103);
        OtherProbeObject wrongType = new OtherProbeObject(100, 100);
        nearestDestroyed.setDestroyed(true);

        objectManager.addAuxiliaryDynamicObject(far);
        objectManager.addAuxiliaryDynamicObject(nearestDestroyed);
        objectManager.addAuxiliaryDynamicObject(nearestLive);
        objectManager.addAuxiliaryDynamicObject(wrongType);

        RewindRecreateContext ctx = new RewindRecreateContext(
                new ObjectSpawn(100, 100, 0, 0, 0, false, 0),
                null,
                services);

        LinkProbeObject result = RewindRecreateObjectLinks.nearestLiveObject(ctx, LinkProbeObject.class);

        assertSame(nearestLive, result,
                "nearestLiveObject must use getX/getY distance, skip destroyed objects, and filter by type");
    }

    @Test
    void nearestLiveObjectReturnsFirstMatchingLiveCandidateWhenSpawnIsMissing() {
        LinkProbeObject first = new LinkProbeObject(200, 200);
        LinkProbeObject second = new LinkProbeObject(10, 10);
        objectManager.addAuxiliaryDynamicObject(first);
        objectManager.addAuxiliaryDynamicObject(second);

        RewindRecreateContext ctx = new RewindRecreateContext(null, null, services);

        LinkProbeObject result = RewindRecreateObjectLinks.nearestLiveObject(ctx, LinkProbeObject.class);

        assertSame(first, result,
                "without captured spawn coordinates the helper must preserve existing first-live-candidate behavior");
    }

    @Test
    void nearestLiveObjectReturnsNullWhenNoObjectManagerIsAvailable() {
        RewindRecreateContext ctx = new RewindRecreateContext(
                new ObjectSpawn(100, 100, 0, 0, 0, false, 0),
                null,
                new StubObjectServices());

        assertNull(RewindRecreateObjectLinks.nearestLiveObject(ctx, LinkProbeObject.class));
    }

    @Test
    void aizEndBossRewindLinksUsesSharedNearestLiveObjectHelper() throws Exception {
        Class<?> links = Class.forName("com.openggf.game.sonic3k.objects.AizEndBossRewindLinks");

        for (Method method : links.getDeclaredMethods()) {
            assertFalse(method.getName().equals("nearest")
                            && method.getParameterCount() == 2
                            && method.getParameterTypes()[0] == RewindRecreateContext.class
                            && method.getParameterTypes()[1] == Class.class,
                    "AizEndBossRewindLinks should delegate generic nearest-live lookup to "
                            + "RewindRecreateObjectLinks");
        }
    }

    @Test
    void mhzEndBossChildrenUseSharedNearestLiveObjectHelper() throws Exception {
        for (String className : List.of(
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossHitProxyChild",
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossRobotnikHeadChild",
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossSpikeChild",
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossVisualChild",
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossWeatherMachineChild")) {
            Class<?> childType = Class.forName(className);

            for (Method method : childType.getDeclaredMethods()) {
                assertFalse(method.getName().equals("findLiveParentForRewind")
                                && method.getParameterCount() == 1
                                && method.getParameterTypes()[0] == RewindRecreateContext.class,
                        childType.getSimpleName()
                                + " should delegate parent relinking to RewindRecreateObjectLinks");
            }
        }
    }

    @Test
    void cnzAndMgzMinibossChildrenUseSharedNearestLiveObjectHelper() throws Exception {
        assertNoDeclaredRewindContextLookup(
                "com.openggf.game.sonic3k.objects.CnzMinibossTopInstance",
                "findLiveCnzMinibossParentForRewind");
        assertNoDeclaredRewindContextLookup(
                "com.openggf.game.sonic3k.objects.MgzMinibossInstance$DrillArmChild",
                "findLiveParentForRewind");
    }

    @Test
    void mhzWeatherVisualUsesSharedNearestLiveObjectHelper() throws Exception {
        assertNoDeclaredRewindContextLookup(
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossWeatherVisualChild",
                "findLiveParentForRewind");
        assertNoDeclaredMethod(
                "com.openggf.game.sonic3k.objects.bosses.MhzEndBossWeatherVisualChild",
                "nearestLiveParent");
    }

    private static void assertNoDeclaredRewindContextLookup(
            String className,
            String methodName) throws ClassNotFoundException {
        Class<?> childType = Class.forName(className);
        for (Method method : childType.getDeclaredMethods()) {
            assertFalse(method.getName().equals(methodName)
                            && method.getParameterCount() == 1
                            && method.getParameterTypes()[0] == RewindRecreateContext.class,
                    childType.getSimpleName()
                            + " should delegate parent relinking to RewindRecreateObjectLinks");
        }
    }

    private static void assertNoDeclaredMethod(
            String className,
            String methodName) throws ClassNotFoundException {
        Class<?> childType = Class.forName(className);
        for (Method method : childType.getDeclaredMethods()) {
            assertFalse(method.getName().equals(methodName),
                    childType.getSimpleName()
                            + " should delegate generic relinking to RewindRecreateObjectLinks");
        }
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    private static class LinkProbeObject extends AbstractObjectInstance {
        LinkProbeObject(int x, int y) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "LinkProbeObject");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }

    private static final class OtherProbeObject extends AbstractObjectInstance {
        OtherProbeObject(int x, int y) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "OtherProbeObject");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no-op
        }
    }
}
