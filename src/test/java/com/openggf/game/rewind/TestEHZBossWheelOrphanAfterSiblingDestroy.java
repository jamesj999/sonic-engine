package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.bosses.EHZBossWheel;
import com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.boss.AbstractBossChild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EHZ boss's three {@code EHZBossWheel} children are indistinguishable by runtime
 * class to {@code ObjectManager.adoptRewindReconstructionChild}, which historically
 * matched pending rewind-reconstruction children to captured
 * {@code DynamicObjectEntry} records purely by class name, FIFO. Once one wheel is
 * destroyed and pruned from {@code dynamicObjects} BEFORE the frame being captured,
 * the fixed 3-wheel re-spawn on restore has one more candidate than captured entries:
 * FIFO shifts captured state onto the wrong position (a subtype-2 entry adopted onto
 * a position constructed as subtype 1, leaving the {@code final animationState} field
 * -- configured from the position's ORIGINAL construction-time subtype, not the
 * corrected one -- silently wrong), and the last unmatched fresh child leaks as a
 * live orphan: dropped from {@code ObjectManager.dynamicObjects} but never removed
 * from {@code Sonic2EHZBossInstance.childComponents}, so it keeps receiving
 * {@code update()} forever and corrupts the shared {@code wheelYAccumulator}.
 *
 * <p>No ROM required: {@code Sonic2EHZBossInstance(ObjectSpawn)} reads no ROM.
 */
class TestEHZBossWheelOrphanAfterSiblingDestroy {

    private static final String EHZ_BOSS_CLASS =
            "com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance";
    private static final String EHZ_WHEEL_CLASS =
            "com.openggf.game.sonic2.objects.bosses.EHZBossWheel";

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void survivingWheelsAreExactAfterASiblingIsDestroyedBeforeCapture() throws Exception {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
        };

        ObjectSpawn bossSpawn = new ObjectSpawn(160, 240,
                Sonic2ObjectIds.EHZ_BOSS, 0, 0, false, 0);
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();
        ObjectManager om = new ObjectManager(
                List.of(bossSpawn), registry,
                0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = om;
        om.reset(0);

        ObjectInstance boss = findActiveByClass(om, EHZ_BOSS_CLASS);
        assertNotNull(boss, "EHZ boss must be materialised as an active object");

        List<AbstractBossChild> wheels = wheelsOf(boss);
        assertEquals(3, wheels.size(), "EHZ boss must spawn exactly 3 EHZBossWheel children");

        // Destroy + prune the subtype-0 wheel, matching normal per-frame cleanup
        // (Sonic2EHZBossInstance.updateDefeatBounce() bouncing a wheel off-screen).
        AbstractBossChild destroyedWheel = wheelWithSubtype(wheels, 0);
        assertNotNull(destroyedWheel, "precondition: a subtype-0 wheel must exist before destroy");
        destroyedWheel.setDestroyed(true);
        invokePrivate(om, "cleanupDestroyedDynamicObjects");

        assertEquals(2, countByClass(om, EHZ_WHEEL_CLASS),
                "precondition: exactly 2 wheels should remain tracked after the destroy+prune");

        RewindRegistry rr = new RewindRegistry();
        rr.register(om.rewindSnapshottable());
        CompositeSnapshot snap = rr.capture();
        rr.restore(snap);

        ObjectInstance restoredBoss = findActiveByClass(om, EHZ_BOSS_CLASS);
        assertNotNull(restoredBoss, "EHZ boss must remain active after restore");
        List<AbstractBossChild> restoredWheels = wheelsOf(restoredBoss);

        assertEquals(2, restoredWheels.size(),
                "childComponents must contain EXACTLY the 2 surviving wheels after restore, no "
                        + "orphan/duplicate left behind by the unmatched fresh reconstruction child");

        List<ObjectInstance> active = new ArrayList<>(om.getActiveObjects());
        for (AbstractBossChild wheel : restoredWheels) {
            assertTrue(active.contains(wheel),
                    "every childComponents wheel entry must also be tracked by the manager "
                            + "(no untracked live orphan)");
        }

        for (AbstractBossChild wheel : restoredWheels) {
            int subtype = readInt(wheel, "subtype");
            assertTrue(subtype == 1 || subtype == 2,
                    "surviving wheels must be exactly the original subtype-1/2 siblings, got " + subtype);
            ObjectAnimationState animationState = readObject(wheel, "animationState", ObjectAnimationState.class);
            int expectedAnimId = subtype == 2 ? 2 : 1; // EHZBossWheel ctor: useBackgroundArt = (subtype == 2)
            assertEquals(expectedAnimId, animationState.getAnimId(),
                    "wheel subtype=" + subtype + "'s animationState must be configured for its OWN "
                            + "original subtype (foreground animId=1 vs background animId=2), not "
                            + "stale from whichever fresh-construction position FIFO adopted it onto");
        }
    }

    /**
     * Repeat-rewind regression for {@code AbstractBossInstance#childSpawnOrdinalCounters}:
     * the counter must be re-derived from the SETTLED post-restore children, not trusted
     * at whatever value reconstruction's fixed always-re-spawn-3-wheels sequence happens
     * to leave it at. A boss that spawns a same-class child BEYOND its construction-time
     * set (simulated here by manually spawning a 4th {@code EHZBossWheel}, matching e.g. a
     * mid-fight repeat-spawn pattern) can have a live survivor whose captured ordinal
     * exceeds the reconstruction-time spawn count for that class -- leaving the counter at
     * the raw reconstruction count would let the NEXT post-restore spawn collide with that
     * survivor's ordinal, reopening the exact-match adoption ambiguity the ordinal scheme
     * exists to prevent.
     */
    @Test
    void childSpawnOrdinalCounterStaysConsistentAcrossRepeatedRewindWithExtraSpawns() throws Exception {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
        };

        ObjectSpawn bossSpawn = new ObjectSpawn(160, 240,
                Sonic2ObjectIds.EHZ_BOSS, 0, 0, false, 0);
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();
        ObjectManager om = new ObjectManager(
                List.of(bossSpawn), registry,
                0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = om;
        om.reset(0);

        ObjectInstance boss = findActiveByClass(om, EHZ_BOSS_CLASS);
        assertNotNull(boss, "EHZ boss must be materialised as an active object");
        assertEquals(3, wheelsOf(boss).size(), "precondition: 3 construction-time wheels");

        // Simulate a mid-fight repeat-spawn: a 4th EHZBossWheel beyond the fixed
        // construction-time set of 3. Gets ordinal 3 (counter was 3 -> now 4).
        AbstractBossChild extraWheel = spawnExtraWheel(boss, -0x50, 4);
        assertEquals(3, extraWheel.getChildOrdinal(),
                "the 4th wheel must get the next ordinal after the construction-time set");
        assertEquals(4, wheelsOf(boss).size());

        // Destroy + prune the ORIGINAL ordinal-2 wheel, leaving live ordinals {0, 1, 3}
        // -- a gap that only manifests the bug if the counter is trusted verbatim.
        AbstractBossChild destroyedWheel = wheelWithSubtype(wheelsOf(boss), 2);
        assertNotNull(destroyedWheel, "precondition: a subtype-2 (ordinal-2) wheel must exist");
        destroyedWheel.setDestroyed(true);
        invokePrivate(om, "cleanupDestroyedDynamicObjects");

        RewindRegistry rr = new RewindRegistry();
        rr.register(om.rewindSnapshottable());

        // ---- Restore round 1 ----
        CompositeSnapshot snap1 = rr.capture();
        rr.restore(snap1);

        ObjectInstance restoredBoss1 = findActiveByClass(om, EHZ_BOSS_CLASS);
        assertNotNull(restoredBoss1, "EHZ boss must remain active after restore");
        List<AbstractBossChild> restored1 = wheelsOf(restoredBoss1);
        assertEquals(3, restored1.size(), "childComponents must contain exactly the 3 survivors, no orphan");
        assertEquals(Set.of(0, 1, 3), ordinalsOf(restored1),
                "surviving wheels must keep their exact original ordinals across restore");

        int counterAfterRestore1 = readOrdinalCounter(restoredBoss1, EHZBossWheel.class);
        assertEquals(4, counterAfterRestore1,
                "childSpawnOrdinalCounters must be re-derived as max(live ordinal)+1 = 4, "
                        + "NOT trusted at whatever the fixed 3-wheel reconstruction sequence left it at");

        // A 5th wheel spawned post-restore must not collide with the just-restored
        // ordinal-3 survivor -- this is the exact ambiguity the ordinal scheme exists
        // to prevent, now proven closed across a rewind round-trip too.
        AbstractBossChild fifthWheel = spawnExtraWheel(restoredBoss1, -0x70, 4);
        assertEquals(4, fifthWheel.getChildOrdinal(),
                "post-restore spawn must continue from the re-derived counter, not duplicate "
                        + "the restored ordinal-3 survivor");
        List<AbstractBossChild> afterFifthSpawn = wheelsOf(restoredBoss1);
        assertEquals(1, countOrdinal(afterFifthSpawn, 4), "exactly one wheel may hold ordinal 4");
        assertEquals(4, afterFifthSpawn.size());

        // A second successive restore is deliberately NOT exercised here: the 5th wheel
        // (spawned beyond the boss's fixed 3-wheel construction-time set, like the 4th)
        // is not adopted from a reconstruction-pending candidate on a second restore --
        // it would fall through to EHZBossWheel.recreateForRewind(), which -- a SEPARATE,
        // pre-existing gap confirmed reachable by an earlier draft of this test -- never
        // re-registers the recreated instance into childComponents (unlike e.g. DEZ's
        // ArticulatedChild.recreateForRewind(), which calls addChildComponentOnce()).
        // That orphan-on-recreate gap is real but out of scope for the ordinal-counter
        // fix this test targets; tracked separately in
        // docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md rather than silently left.
    }

    /**
     * Follow-up fix (Wave 3, Fix 1): {@code EHZBossWheel.recreateForRewind()} (unlike DEZ's
     * {@code ArticulatedChild}/{@code ForearmChild}, which call a local
     * {@code addChildComponentOnce()} for exactly this reason) returned a fresh instance
     * WITHOUT re-registering it into {@code parent.childComponents}. This is reachable
     * on a SINGLE restore -- no destroy/prune or double-restore needed -- whenever a boss
     * has more live same-class children than its fixed construction-time spawn count: EHZ's
     * boss always re-spawns exactly 3 {@code EHZBossWheel} reconstruction-pending candidates
     * on every reconstruction, so a 4th (mid-fight repeat-spawn-style) wheel captured at
     * restore time has no matching candidate and falls straight through to
     * {@code recreateDynamicObject()} -> {@code EHZBossWheel.recreateForRewind()}.
     *
     * <p>The recreated wheel IS correctly added to {@code ObjectManager.dynamicObjects} (so
     * it appears in {@code getActiveObjects()}), but is absent from
     * {@code Sonic2EHZBossInstance.childComponents} -- the ONLY list
     * {@code AbstractBossInstance#updateChildren()} iterates to dispatch
     * {@code child.update(...)} -- so the boss stops driving it: a live object the manager
     * still tracks, but the boss no longer updates or reads (a desync that "returns via a
     * different door" rather than the more visible one where the object is untracked
     * entirely).
     */
    @Test
    void recreatedWheelBeyondTheFixedSetIsReRegisteredIntoChildComponents() throws Exception {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
        };

        ObjectSpawn bossSpawn = new ObjectSpawn(160, 240,
                Sonic2ObjectIds.EHZ_BOSS, 0, 0, false, 0);
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();
        ObjectManager om = new ObjectManager(
                List.of(bossSpawn), registry,
                0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = om;
        om.reset(0);

        ObjectInstance boss = findActiveByClass(om, EHZ_BOSS_CLASS);
        assertNotNull(boss, "EHZ boss must be materialised as an active object");
        assertEquals(3, wheelsOf(boss).size(), "precondition: 3 construction-time wheels");

        // A 4th wheel beyond EHZ's fixed 3-wheel construction-time set (mid-fight
        // repeat-spawn pattern). The boss's reconstruction always re-spawns exactly 3
        // candidates, so with 4 live wheels captured the surplus entry has no matching
        // candidate and falls through to EHZBossWheel.recreateForRewind() on this single
        // restore (no destroy/prune or second restore round needed).
        AbstractBossChild extraWheel = spawnExtraWheel(boss, -0x50, 4);
        assertEquals(3, extraWheel.getChildOrdinal(),
                "the 4th wheel must get the next ordinal after the construction-time set");
        assertEquals(4, wheelsOf(boss).size());

        RewindRegistry rr = new RewindRegistry();
        rr.register(om.rewindSnapshottable());
        CompositeSnapshot snap = rr.capture();
        rr.restore(snap);

        ObjectInstance restoredBoss = findActiveByClass(om, EHZ_BOSS_CLASS);
        assertNotNull(restoredBoss, "EHZ boss must remain active after restore");

        List<ObjectInstance> active = new ArrayList<>(om.getActiveObjects());
        long activeWheelCount = active.stream()
                .filter(o -> o.getClass().getName().equals(EHZ_WHEEL_CLASS) && !o.isDestroyed())
                .count();
        assertEquals(4, activeWheelCount,
                "the recreated 4th wheel must still be tracked by ObjectManager after restore "
                        + "(this half already worked pre-fix -- the bug is childComponents, not "
                        + "ObjectManager tracking)");

        List<AbstractBossChild> restoredWheels = wheelsOf(restoredBoss);
        assertEquals(4, restoredWheels.size(),
                "the recreated wheel must be re-registered into the boss's childComponents "
                        + "(the ONLY list AbstractBossInstance#updateChildren() iterates) -- not "
                        + "just tracked in ObjectManager.getActiveObjects() with the boss no "
                        + "longer driving/reading it");

        for (AbstractBossChild wheel : restoredWheels) {
            assertTrue(active.contains(wheel),
                    "every childComponents wheel entry must also be tracked by the manager "
                            + "(no untracked live orphan)");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<AbstractBossChild> wheelsOf(ObjectInstance boss) throws Exception {
        Field f = findField(boss.getClass(), "childComponents");
        f.setAccessible(true);
        List<AbstractBossChild> out = new ArrayList<>();
        Object value = f.get(boss);
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof AbstractBossChild c && c.getClass().getName().equals(EHZ_WHEEL_CLASS)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static AbstractBossChild wheelWithSubtype(List<AbstractBossChild> wheels, int subtype)
            throws Exception {
        for (AbstractBossChild wheel : wheels) {
            if (readInt(wheel, "subtype") == subtype) {
                return wheel;
            }
        }
        return null;
    }

    /**
     * Reflectively spawns one more {@code EHZBossWheel} directly via the inherited
     * {@code spawnChild} (bypassing the boss's private {@code spawnBossChild}
     * convenience wrapper, which does the same two steps) and registers it into
     * {@code childComponents} -- simulating a boss spawning an extra same-class
     * child beyond its fixed construction-time set.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static AbstractBossChild spawnExtraWheel(ObjectInstance boss, int xOffset, int priority)
            throws Exception {
        Method spawnChildMethod = AbstractObjectInstance.class.getDeclaredMethod("spawnChild", Supplier.class);
        spawnChildMethod.setAccessible(true);
        Supplier<EHZBossWheel> factory =
                () -> new EHZBossWheel((Sonic2EHZBossInstance) boss, 3, xOffset, priority);
        AbstractBossChild child = (AbstractBossChild) spawnChildMethod.invoke(boss, factory);
        Field childComponentsField = findField(boss.getClass(), "childComponents");
        childComponentsField.setAccessible(true);
        List rawChildComponents = (List) childComponentsField.get(boss);
        rawChildComponents.add(child);
        return child;
    }

    private static Set<Integer> ordinalsOf(List<AbstractBossChild> wheels) {
        Set<Integer> ordinals = new HashSet<>();
        for (AbstractBossChild wheel : wheels) {
            ordinals.add(wheel.getChildOrdinal());
        }
        return ordinals;
    }

    private static int countOrdinal(List<AbstractBossChild> wheels, int ordinal) {
        int count = 0;
        for (AbstractBossChild wheel : wheels) {
            if (wheel.getChildOrdinal() == ordinal) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static int readOrdinalCounter(ObjectInstance boss, Class<?> childClass) throws Exception {
        Field f = findField(boss.getClass(), "childSpawnOrdinalCounters");
        f.setAccessible(true);
        Map<Class<?>, Integer> counters = (Map<Class<?>, Integer>) f.get(boss);
        return counters.getOrDefault(childClass, 0);
    }

    private static void invokePrivate(Object target, String methodName) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(target);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static int readInt(Object target, String field) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        return f.getInt(target);
    }

    private static <T> T readObject(Object target, String field, Class<T> fieldType) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        return fieldType.cast(f.get(target));
    }

    private static ObjectInstance findActiveByClass(ObjectManager om, String className) {
        for (ObjectInstance o : om.getActiveObjects()) {
            if (o.getClass().getName().equals(className) && !o.isDestroyed()) {
                return o;
            }
        }
        return null;
    }

    private static int countByClass(ObjectManager om, String className) {
        int count = 0;
        for (ObjectInstance o : om.getActiveObjects()) {
            if (o.getClass().getName().equals(className) && !o.isDestroyed()) {
                count++;
            }
        }
        return count;
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
