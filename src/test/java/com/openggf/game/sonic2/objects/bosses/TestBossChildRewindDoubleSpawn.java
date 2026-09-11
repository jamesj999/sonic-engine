package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
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
 * Empirical diagnostic for the double-spawn bug in boss child rewind.
 *
 * <h2>Hypothesis under test</h2>
 * When a boss lives in {@code activeObjects} (placed object), rewind restore
 * reconstructs it by calling {@code registry.create(spawn)} → constructor →
 * {@code initializeBossState()} → {@code spawnChildren()} → N children land in
 * {@code dynamicObjects} as a side effect.  The restore loop then processes each
 * captured {@code DynamicObjectEntry} for those same children and calls their
 * codecs ({@code recreateDynamicObject}), adding N more children to
 * {@code dynamicObjects}.  Total: 2 × N children instead of N.
 *
 * <h2>Test target</h2>
 * {@link Sonic2DeathEggRobotInstance} is the canonical case:
 * <ul>
 *   <li>It is a placed/active object (not dynamic) in the DEZ level spawn list.</li>
 *   <li>Its constructor → {@code initializeBossState()} → {@code spawnChildren()} spawns
 *       10 permanent children into {@code dynamicObjects} via {@code spawnFreeChild()}.</li>
 *   <li>The generic dynamic recreate path can restore the child objects separately
 *       ({@code ArticulatedChild} x6, {@code HeadChild} x1, {@code JetChild} x1);
 *       {@code ForearmChild} x2 are intentionally excluded from separate recreate.</li>
 * </ul>
 *
 * <h2>Expected child breakdown (pre-fix, if double-spawn occurs)</h2>
 * <pre>
 *   Child type       From constructor  From codec  Total
 *   ArticulatedChild        6              6        12  (should be 6)
 *   ForearmChild            2              0         2  (correct: no codec)
 *   HeadChild               1              1         2  (should be 1)
 *   JetChild                1              1         2  (should be 1)
 *   ─────────────────────────────────────────────────
 *   Total                  10              8        18  (should be 10)
 * </pre>
 *
 * <h2>This test does NOT require a ROM</h2>
 * {@code Sonic2DeathEggRobotInstance(ObjectSpawn)} contains no ROM reads;
 * {@code Sonic2ObjectRegistry()} has a no-arg constructor that defers loading.
 * The only runtime dependency is {@code GraphicsManager.initHeadless()} for pattern
 * atlas init.
 *
 * <h2>Empirically verified result (2026-06-18)</h2>
 * DOUBLE-SPAWN CONFIRMED — this test FAILS on the current codebase:
 * <pre>
 *   childCountBefore=10  [ArticulatedChild=6 ForearmChild=2 HeadChild=1 JetChild=1]
 *   childCountAfter=18   [ArticulatedChild=12 ForearmChild=2 HeadChild=2 JetChild=2]
 * </pre>
 * The 8 children with codecs (6 ArticulatedChild + 1 HeadChild + 1 JetChild) are
 * doubled; the 2 ForearmChild (no codec registered) remain at 2 — exactly confirming
 * the mechanism: constructor side-effects + codec recreate = 2× for coded children.
 * This test is intentionally left failing to document the bug until a fix lands.
 *
 * <h2>Interpreting the result</h2>
 * <ul>
 *   <li>If the assertion <em>passes</em>: the bug is fixed — promote this to a
 *       green regression test.</li>
 *   <li>If the assertion <em>fails</em>: DOUBLE-SPAWN CONFIRMED — the failure
 *       message prints both counts and a per-type breakdown.</li>
 * </ul>
 */
public class TestBossChildRewindDoubleSpawn {

    /** Object ID of the Death Egg Robot (placed in DEZ act 2). */
    private static final int DEZ_ROBOT_OBJ_ID = Sonic2ObjectIds.DEATH_EGG_ROBOT;

    /** Binary class-name prefix for all DEZ robot inner child classes. */
    private static final String DEZ_ROBOT_CLASS_PREFIX =
            "com.openggf.game.sonic2.objects.bosses.Sonic2DeathEggRobotInstance$";

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void dezBossChildCountUnchangedAfterRewindRoundTrip() {
        // -----------------------------------------------------------------------
        // 1. Build ObjectManager with the DEZ boss as a PLACED (active) object.
        //    The boss spawn is centred at (160, 240) — well within the camera
        //    window — so syncActiveSpawnsLoad() materialises it into activeObjects.
        // -----------------------------------------------------------------------
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCameraAtOrigin();
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public Camera camera() {
                return camera;
            }
        };

        // Spawn at (160, 240): inside the 320-wide camera window starting at x=0.
        ObjectSpawn bossSpawn = new ObjectSpawn(
                160, 240, DEZ_ROBOT_OBJ_ID, 0, 0, false, 0);

        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();

        // 8-param constructor: pass explicit graphicsManager + camera + services.
        // No touchResponseTable / planeSwitcherConfig needed for the rewind probe.
        ObjectManager objectManager = new ObjectManager(
                List.of(bossSpawn),
                registry,
                0,       // planeSwitcherObjectId — irrelevant (config = null)
                null,    // planeSwitcherConfig
                null,    // touchResponseTable
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;

        // Materialize the boss spawn into activeObjects.
        // reset() calls syncActiveSpawnsLoad(false) which iterates the placement
        // window and calls registry.create(bossSpawn) → registerActiveObject().
        // The boss constructor → initializeBossState() → spawnChildren() fires here,
        // putting 10 children into dynamicObjects as a construction side effect.
        objectManager.reset(0);

        // -----------------------------------------------------------------------
        // 2. Wire up the RewindRegistry and register the ObjectManager.
        // -----------------------------------------------------------------------
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());

        // -----------------------------------------------------------------------
        // 3. Verify initial state: boss in activeObjects, 10 children in dynamicObjects.
        // -----------------------------------------------------------------------
        int childCountBefore = countDezChildren(objectManager);
        String breakdownBefore = childBreakdown(objectManager);

        // -----------------------------------------------------------------------
        // 4. Capture the rewind snapshot.
        // -----------------------------------------------------------------------
        CompositeSnapshot snap = rewindRegistry.capture();

        // -----------------------------------------------------------------------
        // 5. Restore the snapshot.
        //    During restore:
        //      (a) Active-objects loop reconstructs the boss via registry.create()
        //          → constructor → initializeBossState() → spawnChildren() → 10 new
        //          children added to the now-cleared dynamicObjects.
        //      (b) Dynamic-objects loop recreates each captured DynamicObjectEntry
        //          via the DEZ child codecs → 8 more children added.
        //    If the bug exists, dynamicObjects has 18 children after this call.
        // -----------------------------------------------------------------------
        rewindRegistry.restore(snap);

        int childCountAfter = countDezChildren(objectManager);
        String breakdownAfter = childBreakdown(objectManager);

        // -----------------------------------------------------------------------
        // 6. Assert: child count must not increase after a round-trip.
        //    A failing assertion here means DOUBLE-SPAWN IS CONFIRMED.
        // -----------------------------------------------------------------------
        assertEquals(childCountBefore, childCountAfter,
                "DOUBLE-SPAWN DETECTED: DEZ robot child count changed across rewind round-trip.\n"
                        + "  childCountBefore=" + childCountBefore + "  " + breakdownBefore + "\n"
                        + "  childCountAfter=" + childCountAfter + "  " + breakdownAfter + "\n"
                        + "Cause: boss constructor re-runs initializeBossState() → spawnChildren() "
                        + "during activeObjects restore AND the dynamic-object loop re-runs the "
                        + "child codecs — producing 2× the expected children.\n"
                        + "Fix: strip constructor-spawned children from dynamicObjects before "
                        + "the codec restore loop, or suppress spawnFreeChild side effects "
                        + "inside withRewindActiveRestore.");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Count all live DEZ robot inner children across both activeObjects and
     * dynamicObjects.  Matches on binary class-name prefix to cover all six
     * inner types (ArticulatedChild, ForearmChild, HeadChild, JetChild,
     * SensorChild, BombChild) without requiring direct imports of static inner
     * classes.
     */
    private static int countDezChildren(ObjectManager om) {
        int count = 0;
        for (ObjectInstance o : om.getActiveObjects()) {
            if (isDezChild(o) && !o.isDestroyed()) {
                count++;
            }
        }
        return count;
    }

    /** Human-readable per-type breakdown for failure messages. */
    private static String childBreakdown(ObjectManager om) {
        int articulated = 0, forearm = 0, head = 0, jet = 0, sensor = 0, bomb = 0;
        for (ObjectInstance o : om.getActiveObjects()) {
            if (!isDezChild(o) || o.isDestroyed()) {
                continue;
            }
            String name = o.getClass().getSimpleName();
            switch (name) {
                case "ForearmChild"    -> forearm++;
                case "ArticulatedChild"-> articulated++;
                case "HeadChild"       -> head++;
                case "JetChild"        -> jet++;
                case "SensorChild"     -> sensor++;
                case "BombChild"       -> bomb++;
            }
        }
        return "[ArticulatedChild=" + articulated
                + " ForearmChild=" + forearm
                + " HeadChild=" + head
                + " JetChild=" + jet
                + " SensorChild=" + sensor
                + " BombChild=" + bomb + "]";
    }

    private static boolean isDezChild(ObjectInstance o) {
        return o.getClass().getName().startsWith(DEZ_ROBOT_CLASS_PREFIX);
    }

    /** Minimal mock camera centred at (0,0) with standard 320×224 viewport. */
    private static Camera mockCameraAtOrigin() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
