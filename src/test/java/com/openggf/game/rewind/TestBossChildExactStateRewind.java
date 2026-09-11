package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.boss.AbstractBossChild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive-fidelity guard: construction-spawned boss children must be restored
 * to their EXACT captured state across a rewind round-trip when {@code target == keyframe}
 * (zero re-simulation frames), NOT to their constructor/init state.
 *
 * <h2>Why this is stronger than the count-only parity test</h2>
 * {@link com.openggf.game.sonic2.objects.bosses.TestBossChildRewindDoubleSpawn} and
 * {@link TestBossChildNoDoubleSpawnParity} only assert the child COUNT is unchanged.
 * Restoring children at their init (constructor) state would also keep the count correct
 * yet silently discard in-flight per-child state (falling trajectory, current position,
 * timers). A held-rewind seek to a frame that IS a keyframe must reproduce the exact
 * captured child state with no re-simulation.
 *
 * <h2>What the test does</h2>
 * <ol>
 *   <li>Materialise the DEZ Death Egg Robot (placed/active boss). Its constructor spawns
 *       its 10 permanent children (8 ArticulatedChild incl. 2 ForearmChild, 1 HeadChild,
 *       1 JetChild) and wires them into {@code childComponents}.</li>
 *   <li>Drive a child to a NON-init state by code (startFalling + a few update steps),
 *       advancing {@code currentX}/{@code currentY}/{@code fallTimer} away from their
 *       constructor values. Record that exact state.</li>
 *   <li>Capture, then restore (target == keyframe; zero re-sim frames).</li>
 *   <li>Assert (a) the child COUNT is unchanged (no double, no drop), AND
 *       (b) the boss's {@code childComponents} reference is the SAME live object that
 *       carries the EXACTLY restored non-init state (reference integrity + exact value).</li>
 * </ol>
 *
 * <p>This test FAILS on an init-respawn restore (the codec-less reconstruction path):
 * the restored child reverts to its constructor {@code currentX}/{@code fallTimer},
 * not the captured non-init values. It passes only once construction-spawned children
 * are restored to exact captured state with their boss references intact.
 *
 * <p>No ROM required: {@code Sonic2DeathEggRobotInstance(ObjectSpawn)} reads no ROM.
 */
public class TestBossChildExactStateRewind {

    private static final String DEZ_CLASS =
            "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance";
    private static final String ARTICULATED_CHILD_CLASS = DEZ_CLASS + "$ArticulatedChild";

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void dezArticulatedChildExactNonInitStateRestoredWithReferenceIntact() throws Exception {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
        };

        ObjectSpawn bossSpawn = new ObjectSpawn(160, 240,
                Sonic2ObjectIds.DEATH_EGG_ROBOT, 0, 0, false, 0);
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();
        ObjectManager om = new ObjectManager(
                List.of(bossSpawn), registry,
                0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = om;
        om.reset(0);

        // Locate the boss and one of its construction-spawned Articulated(non-Forearm)
        // children via the boss's own childComponents list (the reference we must keep intact).
        ObjectInstance boss = findActiveByClass(om, DEZ_CLASS);
        assertNotNull(boss, "DEZ boss must be materialised as an active object");

        List<AbstractBossChild> childComponents = readChildComponents(boss);
        AbstractBossChild target = null;
        for (AbstractBossChild c : childComponents) {
            if (c.getClass().getName().equals(ARTICULATED_CHILD_CLASS)) {
                target = c; // exact-class ArticulatedChild (not the ForearmChild subclass)
                break;
            }
        }
        assertNotNull(target, "boss must hold at least one ArticulatedChild in childComponents");

        // Drive the child to a NON-init state through real code: startFalling then update.
        // ArticulatedChild ctor sets fallTimer=0x80, falling=false, currentX=parentX.
        invoke(target, "startFalling", new Class<?>[]{int.class, int.class},
                new Object[]{0x400, -0x200});
        int frame = 5;
        for (int i = 0; i < 4; i++) {
            target.update(frame++, null);
        }

        int childCountBefore = countByClass(om, ARTICULATED_CHILD_CLASS);
        int expectedCurrentX = target.getCurrentX();
        int expectedCurrentY = target.getCurrentY();
        boolean expectedFalling = readBoolean(target, "falling");
        int expectedFallTimer = readInt(target, "fallTimer");

        // Sanity: the child really is at a non-init state (currentX moved off its spawn,
        // falling latched, fallTimer decremented below its 0x80 init).
        int initCurrentX = boss.getX();
        assertTrue(expectedFalling, "precondition: child should be falling (non-init)");
        assertTrue(expectedFallTimer < 0x80,
                "precondition: fallTimer should be decremented below init 0x80, was " + expectedFallTimer);
        assertTrue(expectedCurrentX != initCurrentX,
                "precondition: currentX should have moved off the init parentX " + initCurrentX
                        + ", was " + expectedCurrentX);

        RewindRegistry rr = new RewindRegistry();
        rr.register(om.rewindSnapshottable());

        CompositeSnapshot snap = rr.capture();
        rr.restore(snap);

        // (a) Count unchanged.
        int childCountAfter = countByClass(om, ARTICULATED_CHILD_CLASS);
        assertEquals(childCountBefore, childCountAfter,
                "ArticulatedChild count changed across rewind round-trip (double/drop): before="
                        + childCountBefore + " after=" + childCountAfter);

        // (b) Reference integrity + exact state: the boss's childComponents entry must be a
        // live restored child carrying the EXACT captured non-init state, not the init value.
        ObjectInstance restoredBoss = findActiveByClass(om, DEZ_CLASS);
        assertNotNull(restoredBoss, "DEZ boss must remain active after restore");
        List<AbstractBossChild> restoredComponents = readChildComponents(restoredBoss);

        AbstractBossChild restoredTarget = null;
        for (AbstractBossChild c : restoredComponents) {
            if (c.getClass().getName().equals(ARTICULATED_CHILD_CLASS)
                    && c.getCurrentX() == expectedCurrentX
                    && c.getCurrentY() == expectedCurrentY) {
                restoredTarget = c;
                break;
            }
        }
        assertNotNull(restoredTarget,
                "boss must hold (via childComponents) an ArticulatedChild restored to the exact"
                        + " captured non-init position (" + expectedCurrentX + "," + expectedCurrentY
                        + "). Restored components: " + describe(restoredComponents)
                        + " — an init-respawn restore reverts currentX to parentX=" + initCurrentX);

        // The boss reference must BE the live registered child (same instance in the manager).
        ObjectInstance registered = findRegisteredSame(om, restoredTarget);
        assertSame(restoredTarget, registered,
                "the boss childComponents reference must be the SAME live registered instance "
                        + "(reference integrity) — not an unregistered construction orphan");

        // Exact non-init scalar fidelity.
        assertEquals(expectedCurrentX, restoredTarget.getCurrentX(),
                "currentX must be restored EXACTLY (target==keyframe, zero re-sim)");
        assertEquals(expectedCurrentY, restoredTarget.getCurrentY(),
                "currentY must be restored EXACTLY");
        assertEquals(expectedFalling, readBoolean(restoredTarget, "falling"),
                "falling flag must be restored EXACTLY");
        assertEquals(expectedFallTimer, readInt(restoredTarget, "fallTimer"),
                "fallTimer must be restored EXACTLY (init would be 0x80)");
    }

    @Test
    void ehzBossWheelExactNonInitStateRestoredWithReferenceIntact() throws Exception {
        final String ehzBossClass = "com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance";
        final String ehzWheelClass = "com.openggf.game.sonic2.objects.bosses.EHZBossWheel";

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

        ObjectInstance boss = findActiveByClass(om, ehzBossClass);
        assertNotNull(boss, "EHZ boss must be materialised as an active object");

        List<AbstractBossChild> components = readChildComponents(boss);
        AbstractBossChild wheel = null;
        for (AbstractBossChild c : components) {
            if (c.getClass().getName().equals(ehzWheelClass)) {
                wheel = c;
                break;
            }
        }
        assertNotNull(wheel, "EHZ boss must hold an EHZBossWheel in childComponents");

        // Drive the wheel to a NON-init position via the public setPosition path.
        int initX = wheel.getCurrentX();
        int newX = initX + 0x37;
        int newY = wheel.getCurrentY() - 0x19;
        wheel.setPosition(newX, newY);
        assertTrue(wheel.getCurrentX() != initX, "precondition: wheel moved off init X");

        int countBefore = countByClass(om, ehzWheelClass);

        RewindRegistry rr = new RewindRegistry();
        rr.register(om.rewindSnapshottable());
        CompositeSnapshot snap = rr.capture();
        rr.restore(snap);

        int countAfter = countByClass(om, ehzWheelClass);
        assertEquals(countBefore, countAfter,
                "EHZBossWheel count changed across rewind round-trip: before=" + countBefore
                        + " after=" + countAfter);

        ObjectInstance restoredBoss = findActiveByClass(om, ehzBossClass);
        assertNotNull(restoredBoss, "EHZ boss must remain active after restore");
        List<AbstractBossChild> restoredComponents = readChildComponents(restoredBoss);
        AbstractBossChild restoredWheel = null;
        for (AbstractBossChild c : restoredComponents) {
            if (c.getClass().getName().equals(ehzWheelClass)
                    && c.getCurrentX() == newX && c.getCurrentY() == newY) {
                restoredWheel = c;
                break;
            }
        }
        assertNotNull(restoredWheel,
                "boss must hold (via childComponents) an EHZBossWheel restored to the exact"
                        + " captured non-init position (" + newX + "," + newY + ")");
        assertSame(restoredWheel, findRegisteredSame(om, restoredWheel),
                "EHZ wheel boss reference must be the SAME live registered instance");
        assertEquals(newX, restoredWheel.getCurrentX(), "EHZ wheel currentX restored exactly");
        assertEquals(newY, restoredWheel.getCurrentY(), "EHZ wheel currentY restored exactly");
    }

    @Test
    void syzBossSpikeExactNonInitStateRestoredWithReferenceIntact() throws Exception {
        final String syzBossClass = "com.openggf.game.sonic1.objects.bosses.Sonic1SYZBossInstance";
        final String syzSpikeClass = "com.openggf.game.sonic1.objects.bosses.SYZBossSpike";

        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
        };

        ObjectSpawn bossSpawn = new ObjectSpawn(160, 240,
                Sonic1ObjectIds.SYZ_BOSS, 0, 0, false, 0);
        Sonic1ObjectRegistry registry = new Sonic1ObjectRegistry();
        ObjectManager om = new ObjectManager(
                List.of(bossSpawn), registry,
                0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = om;
        om.reset(0);

        ObjectInstance boss = findActiveByClass(om, syzBossClass);
        assertNotNull(boss, "SYZ boss must be materialised as an active object");
        List<AbstractBossChild> components = readChildComponents(boss);
        AbstractBossChild spike = null;
        for (AbstractBossChild c : components) {
            if (c.getClass().getName().equals(syzSpikeClass)) {
                spike = c;
                break;
            }
        }
        assertNotNull(spike, "SYZ boss must hold a SYZBossSpike in childComponents");
        assertTrue(RewindRecreatable.class.isAssignableFrom(spike.getClass()),
                "SYZBossSpike must restore through RewindRecreatable generic recreate");

        // SYZBossSpike now pulls its state cache from the parent boss every
        // frame in its own update() rather than accepting pushed setters (see
        // SYZBossSpike#update). Prime the cache fields directly to reach the
        // same "mid block-drop, descending" non-init state the old
        // setSpikeActive/setBossState calls drove.
        writeBoolean(spike, "spikeActive", true);
        writeInt(spike, "bossRoutineSecondary", 4);
        writeInt(spike, "bossDropSubPhase", 0);
        writeInt(spike, "bossTimer", 0);
        for (int i = 0; i < 5; i++) {
            invoke(spike, "updateExtension", new Class<?>[]{}, new Object[]{});
        }
        int expectedExtensionDepth = readInt(spike, "extensionDepth");
        boolean expectedSpikeActive = readBoolean(spike, "spikeActive");
        int expectedRoutineSecondary = readInt(spike, "bossRoutineSecondary");
        int expectedDropSubPhase = readInt(spike, "bossDropSubPhase");
        int expectedBossTimer = readInt(spike, "bossTimer");
        assertTrue(expectedExtensionDepth > 0,
                "precondition: spike extension depth must move away from init state");
        int countBefore = countByClass(om, syzSpikeClass);

        RewindRegistry rr = new RewindRegistry();
        rr.register(om.rewindSnapshottable());
        CompositeSnapshot snap = rr.capture();
        rr.restore(snap);

        int countAfter = countByClass(om, syzSpikeClass);
        assertEquals(countBefore, countAfter,
                "SYZBossSpike count changed across rewind round-trip: before=" + countBefore
                        + " after=" + countAfter);

        ObjectInstance restoredBoss = findActiveByClass(om, syzBossClass);
        assertNotNull(restoredBoss, "SYZ boss must remain active after restore");
        List<AbstractBossChild> restoredComponents = readChildComponents(restoredBoss);
        AbstractBossChild restoredSpike = null;
        for (AbstractBossChild c : restoredComponents) {
            if (c.getClass().getName().equals(syzSpikeClass)) {
                restoredSpike = c;
                break;
            }
        }
        assertNotNull(restoredSpike,
                "boss must hold (via childComponents) a restored SYZBossSpike");
        assertSame(restoredSpike, findRegisteredSame(om, restoredSpike),
                "SYZ spike boss reference must be the SAME live registered instance");
        assertSame(restoredBoss, readObject(restoredSpike, "parent", ObjectInstance.class),
                "SYZ spike parent field must relink to the restored boss");
        assertEquals(expectedExtensionDepth, readInt(restoredSpike, "extensionDepth"),
                "SYZ spike extensionDepth restored exactly");
        assertEquals(expectedSpikeActive, readBoolean(restoredSpike, "spikeActive"),
                "SYZ spike active flag restored exactly");
        assertEquals(expectedRoutineSecondary, readInt(restoredSpike, "bossRoutineSecondary"),
                "SYZ spike cached boss routine restored exactly");
        assertEquals(expectedDropSubPhase, readInt(restoredSpike, "bossDropSubPhase"),
                "SYZ spike cached boss drop sub-phase restored exactly");
        assertEquals(expectedBossTimer, readInt(restoredSpike, "bossTimer"),
                "SYZ spike cached boss timer restored exactly");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<AbstractBossChild> readChildComponents(ObjectInstance boss) throws Exception {
        Field f = findField(boss.getClass(), "childComponents");
        f.setAccessible(true);
        List<AbstractBossChild> out = new ArrayList<>();
        Object value = f.get(boss);
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof AbstractBossChild c) {
                    out.add(c);
                }
            }
        }
        return out;
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

    private static void invoke(Object target, String method, Class<?>[] sig, Object[] args)
            throws Exception {
        var m = findMethod(target.getClass(), method, sig);
        m.setAccessible(true);
        m.invoke(target, args);
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String name, Class<?>[] sig)
            throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, sig);
            } catch (NoSuchMethodException ignored) {
                // walk up
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static boolean readBoolean(Object target, String field) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        return f.getBoolean(target);
    }

    private static int readInt(Object target, String field) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        return f.getInt(target);
    }

    private static void writeBoolean(Object target, String field, boolean value) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        f.setBoolean(target, value);
    }

    private static void writeInt(Object target, String field, int value) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        f.setInt(target, value);
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

    private static ObjectInstance findRegisteredSame(ObjectManager om, ObjectInstance which) {
        for (ObjectInstance o : om.getActiveObjects()) {
            if (o == which) {
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

    private static String describe(List<AbstractBossChild> components) {
        StringBuilder sb = new StringBuilder("[");
        for (AbstractBossChild c : components) {
            if (c.getClass().getName().equals(ARTICULATED_CHILD_CLASS)) {
                sb.append("Articulated(").append(c.getCurrentX()).append(',')
                        .append(c.getCurrentY()).append(") ");
            }
        }
        return sb.append(']').toString();
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
