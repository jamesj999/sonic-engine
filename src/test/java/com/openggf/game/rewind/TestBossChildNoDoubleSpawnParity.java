package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parity guard: construction-spawned boss children must NOT double-spawn after a
 * rewind round-trip.
 *
 * <h2>Background</h2>
 * When a boss lives in {@code activeObjects} (placed object), rewind restore
 * reconstructs it by calling {@code registry.create(spawn)} → constructor →
 * {@code initializeBossState()} → children are spawned into {@code dynamicObjects}.
 * If those children ALSO have a codec registered, the dynamic-objects restore loop
 * will recreate them a second time, doubling the count.
 *
 * <h2>Invariant</h2>
 * Construction-spawned children (those emitted during {@code initializeBossState()}
 * or the constructor, not from an update/attack routine) must NOT opt into an
 * explicit per-child dynamic recreate path. Reconstruction re-establishes them;
 * restoring them separately would only add duplicates.
 *
 * <h2>Bosses covered</h2>
 * <ul>
 *   <li><b>S2 DEZ Death Egg Robot</b>: {@code ArticulatedChild} ×6, {@code HeadChild} ×1,
 *       {@code JetChild} ×1, {@code ForearmChild} ×2 — 10 construction children total.
 *       {@code BombChild} and {@code SensorChild} are routine-spawned; BombChild now
 *       restores through a graph-tested {@code RewindRecreatable} relink, while SensorChild
 *       remains transient.
 *       Test asserts count stays at 10 after restore.</li>
 *   <li><b>S1 SYZ Boss</b>: {@code SYZBossSpike} is spawned inside
 *       {@code initializeBossState()} — 1 construction child.
 *       Test asserts count stays at 1 after restore.</li>
 *   <li><b>S1 ScrapEggman</b>: {@code ScrapEggmanButton} is spawned directly in the
 *       constructor via {@code spawnDynamicObject()} — 1 construction child.
 *       Test asserts count stays at 1 after restore.</li>
 *   <li><b>S2 EHZ Boss</b>: {@code EHZBossVehicleTop}, {@code EHZBossGroundVehicle},
 *       {@code EHZBossPropeller}, {@code EHZBossWheel} ×3, {@code EHZBossSpike} — 7
 *       construction children (initializeBossState → spawnChildComponents). All five
 *       distinct classes had codecs (the bug: 7 → 14). Test asserts count stays at 7.</li>
 * </ul>
 */
public class TestBossChildNoDoubleSpawnParity {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    // =========================================================================
    // S2 DEZ Death Egg Robot — construction children
    // =========================================================================

    /**
     * DEZ robot: 10 children spawned during construction (initializeBossState).
     * After a rewind round-trip, the count must remain 10 (no doubling).
     */
    @Test
    void dezRobotConstructionChildCountUnchangedAfterRewindRoundTrip() {
        final String DEZ_PREFIX =
                "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$";

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

        RewindRegistry rr = new RewindRegistry();
        rr.register(om.rewindSnapshottable());

        int childCountBefore = countByPrefix(om, DEZ_PREFIX);

        CompositeSnapshot snap = rr.capture();
        rr.restore(snap);

        int childCountAfter = countByPrefix(om, DEZ_PREFIX);

        assertEquals(childCountBefore, childCountAfter,
                "S2 DEZ robot: construction child count changed across rewind round-trip — "
                        + "double-spawn detected. before=" + childCountBefore
                        + " after=" + childCountAfter
                        + " (expected no change; construction-spawned children are "
                        + "re-established by boss reconstruction and must NOT have codecs)");
    }

    // =========================================================================
    // S1 SYZ Boss — SYZBossSpike (construction child)
    // =========================================================================

    /**
     * SYZ Boss: 1 spike child spawned inside initializeBossState().
     * After a rewind round-trip, the count must remain 1 (no doubling).
     */
    @Test
    void syzBossConstructionChildCountUnchangedAfterRewindRoundTrip() {
        final String SYZ_SPIKE_CLASS =
                "com.openggf.game.sonic1.objects.bosses.SYZBossSpike";

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

        RewindRegistry rr = new RewindRegistry();
        rr.register(om.rewindSnapshottable());

        int childCountBefore = countByExactClass(om, SYZ_SPIKE_CLASS);

        CompositeSnapshot snap = rr.capture();
        rr.restore(snap);

        int childCountAfter = countByExactClass(om, SYZ_SPIKE_CLASS);

        assertEquals(childCountBefore, childCountAfter,
                "S1 SYZ Boss: SYZBossSpike (construction child) count changed across "
                        + "rewind round-trip — double-spawn detected. before="
                        + childCountBefore + " after=" + childCountAfter
                        + " (SYZBossSpike is spawned in initializeBossState() and must "
                        + "NOT have a codec — reconstruction re-establishes it)");
    }

    // =========================================================================
    // S1 ScrapEggman — ScrapEggmanButton (constructor-spawned)
    // =========================================================================

    /**
     * ScrapEggman: 1 button child spawned directly in the constructor.
     * After a rewind round-trip, the count must remain 1 (no doubling).
     */
    @Test
    void scrapEggmanConstructionChildCountUnchangedAfterRewindRoundTrip() {
        final String BUTTON_CLASS =
                "com.openggf.game.sonic1.objects.bosses.Sonic1ScrapEggmanInstance"
                        + "$ScrapEggmanButton";

        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
        };

        ObjectSpawn bossSpawn = new ObjectSpawn(160, 240,
                Sonic1ObjectIds.SCRAP_EGGMAN, 0, 0, false, 0);
        Sonic1ObjectRegistry registry = new Sonic1ObjectRegistry();
        ObjectManager om = new ObjectManager(
                List.of(bossSpawn), registry,
                0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = om;
        om.reset(0);

        RewindRegistry rr = new RewindRegistry();
        rr.register(om.rewindSnapshottable());

        int childCountBefore = countByExactClass(om, BUTTON_CLASS);

        CompositeSnapshot snap = rr.capture();
        rr.restore(snap);

        int childCountAfter = countByExactClass(om, BUTTON_CLASS);

        assertEquals(childCountBefore, childCountAfter,
                "S1 ScrapEggman: ScrapEggmanButton (constructor-spawned) count changed "
                        + "across rewind round-trip — double-spawn detected. before="
                        + childCountBefore + " after=" + childCountAfter
                        + " (ScrapEggmanButton is spawned in the constructor and must "
                        + "NOT have a codec — reconstruction re-establishes it)");
    }

    // =========================================================================
    // S2 EHZ Boss — construction children (drill-car boss)
    // =========================================================================

    /**
     * EHZ boss: 6 children spawned during construction (initializeBossState →
     * spawnChildComponents): 1 VehicleTop, 1 GroundVehicle, 1 Propeller,
     * 3 Wheel, 1 Spike = 7 construction children. All five distinct child
     * classes had codecs (the bug). After a rewind round-trip, the per-class
     * count must remain unchanged (no doubling).
     */
    @Test
    void ehzBossConstructionChildCountUnchangedAfterRewindRoundTrip() {
        final String EHZ_CHILD_PKG =
                "com.openggf.game.sonic2.objects.bosses.EHZBoss";

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

        RewindRegistry rr = new RewindRegistry();
        rr.register(om.rewindSnapshottable());

        int childCountBefore = countByPrefix(om, EHZ_CHILD_PKG);

        CompositeSnapshot snap = rr.capture();
        rr.restore(snap);

        int childCountAfter = countByPrefix(om, EHZ_CHILD_PKG);

        assertEquals(childCountBefore, childCountAfter,
                "S2 EHZ Boss: construction child count changed across rewind round-trip — "
                        + "double-spawn detected. before=" + childCountBefore
                        + " after=" + childCountAfter
                        + " (EHZBossVehicleTop/GroundVehicle/Propeller/Wheel/Spike are all "
                        + "spawned in initializeBossState() and must NOT have codecs — "
                        + "reconstruction re-establishes them)");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Count live non-destroyed objects whose class name starts with the given prefix. */
    private static int countByPrefix(ObjectManager om, String prefix) {
        int count = 0;
        for (ObjectInstance o : om.getActiveObjects()) {
            if (o.getClass().getName().startsWith(prefix) && !o.isDestroyed()) {
                count++;
            }
        }
        return count;
    }

    /** Count live non-destroyed objects with exactly the given binary class name. */
    private static int countByExactClass(ObjectManager om, String className) {
        int count = 0;
        for (ObjectInstance o : om.getActiveObjects()) {
            if (o.getClass().getName().equals(className) && !o.isDestroyed()) {
                count++;
            }
        }
        return count;
    }

    /** Minimal camera centred at (0,0) with standard 320x224 viewport. */
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
