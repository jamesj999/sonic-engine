package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.boss.AbstractBossChild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips the LBZ end boss (Obj 0xCB) graph children across a mid-fight rewind.
 *
 * <p>The boss's routine-spawned children (bobbing platform chain, Robotnik runner, rising
 * tube segments, spike balls) are not captured directly: the boss holds them in
 * {@code final} identity collections ({@code ownedChildren}/{@code platformChildren}) which
 * are structural rewind state, and the children themselves carry structural constructor
 * args (chain subtype/sibling link, tube offsets) that are re-derived from spawn order.
 * Previously these children returned {@code null} from {@code recreateForRewind}, so a
 * mid-fight rewind dropped them and the boss lost its child links. Each graph child now
 * reconstructs itself through the parent's reconstruction path (mirroring the CPZ boss pipe
 * segment re-register pattern), rebuilding the boss's collections.
 */
class TestS3kLbzEndBossChildLinksRewind {

    private static final ObjectSpawn SPAWN =
            new ObjectSpawn(160, 240, Sonic3kObjectIds.LBZ_END_BOSS, 0, 0, false, 0);

    /** Forces the S3KL/LBZ zone context so the registry materialises the real boss. */
    private static final class LbzZoneRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_LBZ;
        }
    }

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void midFightRewindRebuildsPlatformChainAndRunnerLinks() throws Exception {
        ObjectManager objectManager = createManagerWithBoss();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        LbzEndBossInstance boss = only(objectManager, LbzEndBossInstance.class);

        // Advance into the fight: startIntro spawns the four bobbing platforms + runner.
        invokePrivate(boss, "startIntro");
        assertEquals(4, boss.getPlatformChildrenForTests().size(),
                "precondition: intro spawns four platform children");
        assertTrue(boss.hasRunnerForTests(), "precondition: intro spawns the Robotnik runner");
        assertEquals(7, boss.getOwnedChildrenForTests().size(),
                "precondition: owned children = cockpit + tower + 4 platforms + runner");
        ObjectRefId bossId = objectId(objectManager, boss);

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        // Diverge: remove a platform before restore.
        objectManager.removeDynamicObject((ObjectInstance) boss.getPlatformChildrenForTests().get(0));
        assertEquals(3, liveOfSimpleName(objectManager, "LbzEndBossPlatformChild"),
                "diverge step drops one platform before restore");

        rewindRegistry.restore(snapshot);

        LbzEndBossInstance restored = objectById(objectManager, LbzEndBossInstance.class, bossId);
        assertNotSame(boss, restored, "restore recreates the boss fresh");

        assertEquals(4, liveOfSimpleName(objectManager, "LbzEndBossPlatformChild"),
                "all four platform objects must be recreated on restore");
        assertEquals(4, restored.getPlatformChildrenForTests().size(),
                "restored boss must rebuild its platform child list");
        assertTrue(restored.hasRunnerForTests(),
                "restored boss must rebuild its runner link");
        assertEquals(7, restored.getOwnedChildrenForTests().size(),
                "restored boss must rebuild its full owned-child list "
                        + "(cockpit + tower + 4 platforms + runner)");

        // Every rebuilt platform link must point at a live, freshly recreated instance.
        List<?> platforms = restored.getPlatformChildrenForTests();
        for (Object platform : platforms) {
            assertSame(platform, objectManager.getActiveObjects().stream()
                            .filter(o -> o == platform && !o.isDestroyed())
                            .findFirst().orElse(null),
                    "platform link must reference a live restored instance");
            assertNotSame(boss, platform, "restored platform must not be a pre-rewind instance");
        }
    }

    @Test
    void midFightRewindRebuildsRisingTubesAndLaunchedSpikeBall() throws Exception {
        ObjectManager objectManager = createManagerWithBoss();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        LbzEndBossInstance boss = only(objectManager, LbzEndBossInstance.class);
        invokePrivate(boss, "startIntro");
        invokePrivate(boss, "startRising");   // spawns the three rising tube segments
        invokePrivate(boss, "launchSpikeBall"); // spawns one spike ball projectile
        assertEquals(3, liveOfSimpleName(objectManager, "LbzEndBossTubeSegmentChild"),
                "precondition: three tube segments spawned");
        assertEquals(1, liveOfSimpleName(objectManager, "LbzEndBossSpikeBallChild"),
                "precondition: one spike ball launched");
        int ownedBefore = boss.getOwnedChildrenForTests().size();
        ObjectRefId bossId = objectId(objectManager, boss);

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        // Diverge: drop a tube and the spike ball.
        objectManager.removeDynamicObject(firstOfSimpleName(objectManager, "LbzEndBossTubeSegmentChild"));
        objectManager.removeDynamicObject(firstOfSimpleName(objectManager, "LbzEndBossSpikeBallChild"));

        rewindRegistry.restore(snapshot);

        LbzEndBossInstance restored = objectById(objectManager, LbzEndBossInstance.class, bossId);
        assertEquals(3, liveOfSimpleName(objectManager, "LbzEndBossTubeSegmentChild"),
                "all three tube segments must be recreated on restore");
        assertEquals(1, liveOfSimpleName(objectManager, "LbzEndBossSpikeBallChild"),
                "the launched spike ball must be recreated on restore");
        assertEquals(ownedBefore, restored.getOwnedChildrenForTests().size(),
                "restored boss must rebuild its full owned-child list including tubes and spike ball");
    }

    @Test
    void midDefeatRewindRebuildsDebrisSmokeAndExtender() throws Exception {
        ObjectManager objectManager = createManagerWithBoss();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        LbzEndBossInstance boss = only(objectManager, LbzEndBossInstance.class);
        invokePrivate(boss, "startIntro");
        invokePrivate(boss, "launchSpikeBall");
        // The spike-ball explosion sprays eight debris pieces and four smoke puffs.
        invokePrivate(firstOfSimpleName(objectManager, "LbzEndBossSpikeBallChild"), "explode");
        // startDefeat spawns the gradual-max-X extender (and the DEFERRED explosion controller).
        invokePrivate(boss, "startDefeat");

        assertEquals(8, liveOfSimpleName(objectManager, "LbzEndBossDebrisChild"),
                "precondition: eight debris pieces from the spike-ball spray");
        assertEquals(4, liveOfSimpleName(objectManager, "LbzEndBossSmokePuffChild"),
                "precondition: four smoke puffs from the spike-ball spray");
        assertEquals(1, liveOfSimpleName(objectManager, "LbzEndBossGradualMaxXExtenderChild"),
                "precondition: one gradual-max-X extender from startDefeat");
        List<Integer> debrisFramesBefore = intFieldOf(objectManager, "LbzEndBossDebrisChild", "frame");
        List<Integer> debrisXVelBefore = intFieldOf(objectManager, "LbzEndBossDebrisChild", "xVel");
        ObjectRefId bossId = objectId(objectManager, boss);

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        // Diverge: drop a debris piece, a smoke puff, and the extender.
        objectManager.removeDynamicObject(firstOfSimpleName(objectManager, "LbzEndBossDebrisChild"));
        objectManager.removeDynamicObject(firstOfSimpleName(objectManager, "LbzEndBossSmokePuffChild"));
        objectManager.removeDynamicObject(firstOfSimpleName(objectManager, "LbzEndBossGradualMaxXExtenderChild"));

        rewindRegistry.restore(snapshot);

        LbzEndBossInstance restored = objectById(objectManager, LbzEndBossInstance.class, bossId);
        assertEquals(8, liveOfSimpleName(objectManager, "LbzEndBossDebrisChild"),
                "all eight debris pieces must be recreated on restore");
        assertEquals(4, liveOfSimpleName(objectManager, "LbzEndBossSmokePuffChild"),
                "all four smoke puffs must be recreated on restore");
        assertEquals(1, liveOfSimpleName(objectManager, "LbzEndBossGradualMaxXExtenderChild"),
                "the gradual-max-X extender must be recreated on restore");
        assertTrue(restored.usesLocalGradualMaxXExtenderForTests(),
                "the boss's extender-active flag must restore");

        // The debris cosmetics (frame) and motion (xVel) — newly captured scalars — must
        // round-trip: the restored multiset matches the captured one.
        assertEquals(debrisFramesBefore, intFieldOf(objectManager, "LbzEndBossDebrisChild", "frame"),
                "debris render frames must round-trip across the rewind");
        assertEquals(debrisXVelBefore, intFieldOf(objectManager, "LbzEndBossDebrisChild", "xVel"),
                "debris x velocities must round-trip across the rewind");
    }

    @Test
    void midDefeatExplosionControllerIsDeferredOutsideTheCapturedStructuralChildGraph() throws Exception {
        ObjectManager objectManager = createManagerWithBoss();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        LbzEndBossInstance boss = only(objectManager, LbzEndBossInstance.class);
        invokePrivate(boss, "startDefeat");

        ObjectInstance controller = firstOfSimpleName(objectManager, "LbzEndBossExplosionControllerChild");
        assertTrue(boss.getOwnedChildrenForTests().contains(controller),
                "the controller remains a live boss-owned helper while defeat is active");
        assertFalse(boss.getChildComponents().contains(controller),
                "the deferred controller must not be treated as a compact structural child reference");
        assertSame(boss, parentOf((AbstractBossChild) controller),
                "the live deferred controller still keeps its normal parent back-reference");

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();
        rewindRegistry.restore(snapshot);

        LbzEndBossInstance restored = only(objectManager, LbzEndBossInstance.class);
        assertEquals(0, liveOfSimpleName(objectManager, "LbzEndBossExplosionControllerChild"),
                "the documented deferred controller must not be fabricated by generic reconstruction");
        assertFalse(restored.getChildComponents().stream()
                        .anyMatch(child -> child.getClass().getSimpleName().equals("LbzEndBossExplosionControllerChild")),
                "the restored compact graph must not retain an unresolved deferred controller link");
    }

    @Test
    void midDefeatRewindKeepsExtenderAndCameraBoundaryCoherent() throws Exception {
        // POST_DEFEAT_CAMERA_MAX_X is $3AB8; start the boundary below it so the extender
        // keeps extending (does not immediately snap-and-expire).
        final int startMaxX = 0x3A00;
        ObjectManager objectManager = createManagerWithBoss();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        Camera camera = harnessCamera(objectManager);
        camera.setMaxX((short) startMaxX);

        LbzEndBossInstance boss = only(objectManager, LbzEndBossInstance.class);
        invokePrivate(boss, "startDefeat");
        ObjectInstance extender = firstOfSimpleName(objectManager, "LbzEndBossGradualMaxXExtenderChild");

        // Drive the extender a few frames: it accumulates a $4000/frame sub-pixel value and
        // adds the integer part to Camera_max_X each frame (accelerating), mutating global
        // camera state and its own accumulator together.
        for (int f = 1; f <= 6; f++) {
            driveUpdate(extender, f);
        }
        int capturedMaxX = camera.getMaxX() & 0xFFFF;
        int capturedAccumulator = readInt(extender, "accumulator");
        assertTrue(capturedMaxX > startMaxX, "precondition: extender advanced the camera boundary");
        assertTrue(capturedAccumulator > 0, "precondition: extender holds a non-zero accumulator");

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        rewindRegistry.register(camera);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        // Diverge BOTH the global camera boundary and the object graph: push the boundary to
        // the snap target and drop the extender.
        camera.setMaxX((short) 0x3AB8);
        objectManager.removeDynamicObject(extender);

        rewindRegistry.restore(snapshot);

        ObjectInstance restoredExtender =
                firstOfSimpleName(objectManager, "LbzEndBossGradualMaxXExtenderChild");
        assertNotSame(extender, restoredExtender, "restore recreates the extender fresh");
        // Object and global state must agree at the captured frame: neither lost nor double-applied.
        assertEquals(capturedMaxX, camera.getMaxX() & 0xFFFF,
                "camera boundary must restore to its captured value (not the diverged one)");
        assertEquals(capturedAccumulator, readInt(restoredExtender, "accumulator"),
                "extender accumulator must restore to its captured value");

        // One more step from the restored pair must continue the ROM progression exactly, i.e.
        // add the current integer accumulator part to the restored boundary (no double-apply).
        int expectedNextMaxX = capturedMaxX + ((capturedAccumulator + 0x4000) >>> 16);
        driveUpdate(restoredExtender, 7);
        assertEquals(expectedNextMaxX, camera.getMaxX() & 0xFFFF,
                "post-restore extender must continue extending from the restored boundary");
    }

    private static Camera harnessCamera(ObjectManager objectManager) throws Exception {
        LbzEndBossInstance boss = only(objectManager, LbzEndBossInstance.class);
        Method m = LbzEndBossInstance.class.getDeclaredMethod("cameraOrNull");
        m.setAccessible(true);
        return (Camera) m.invoke(boss);
    }

    private static void driveUpdate(ObjectInstance object, int frameCounter) throws Exception {
        Method m = object.getClass().getDeclaredMethod("update", int.class,
                com.openggf.game.PlayableEntity.class);
        m.setAccessible(true);
        m.invoke(object, frameCounter, null);
    }

    private static List<Integer> intFieldOf(ObjectManager objectManager, String simpleName, String field) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass().getSimpleName().equals(simpleName))
                .filter(o -> !o.isDestroyed())
                .map(o -> readInt(o, field))
                .sorted()
                .toList();
    }

    private static int readInt(Object target, String field) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object parentOf(AbstractBossChild child) throws Exception {
        var parent = AbstractBossChild.class.getDeclaredField("parent");
        parent.setAccessible(true);
        return parent.get(child);
    }

    private static ObjectManager createManagerWithBoss() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
        };
        ObjectManager objectManager = new ObjectManager(
                List.of(SPAWN), new LbzZoneRegistry(), 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = objectManager;
        objectManager.reset(0);
        return objectManager;
    }

    private static void invokePrivate(Object target, String method) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(target);
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        return objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance).map(type::cast)
                .filter(o -> !o.isDestroyed())
                .filter(o -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable().idFor(o)))
                .findFirst().orElseThrow(() -> new AssertionError("missing restored " + type.getSimpleName()));
    }

    private static ObjectInstance firstOfSimpleName(ObjectManager objectManager, String simpleName) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass().getSimpleName().equals(simpleName))
                .filter(o -> !((ObjectInstance) o).isDestroyed())
                .findFirst().orElseThrow(() -> new AssertionError("no live " + simpleName));
    }

    private static long liveOfSimpleName(ObjectManager objectManager, String simpleName) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass().getSimpleName().equals(simpleName))
                .filter(o -> !((ObjectInstance) o).isDestroyed())
                .count();
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(object -> type.isInstance(object) && !object.isDestroyed())
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
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
}
