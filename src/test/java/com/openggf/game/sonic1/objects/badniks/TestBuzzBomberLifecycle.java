package com.openggf.game.sonic1.objects.badniks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.TestEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: a flying Buzz Bomber must not be removed by the spawn
 * windowing system while it is still near the camera viewport.
 * <p>
 * Root cause: ObjectManager.Placement checks the <em>original spawn X</em>
 * against the camera window.  When the camera moves backward (delta&lt;0),
 * {@code refreshWindow()} rebuilds the active set and any spawn whose X is
 * beyond {@code cameraX + LOAD_AHEAD} (640) is dropped.  A Buzz Bomber that
 * has flown far from its spawn can still be on-screen when this happens.
 * <p>
 * Fix: Sonic1BuzzBomberBadnikInstance overrides {@code isPersistent()} to
 * return {@code true} while its <em>current</em> position is within a margin
 * of the camera, preventing premature removal.
 */
public class TestBuzzBomberLifecycle {

    /**
     * Simulates the key Buzz Bomber behaviour relevant to this bug:
     * <ul>
     *   <li>The object moves away from its spawn (flies left).</li>
     *   <li>{@code isPersistent()} returns true while near the camera (the fix).</li>
     * </ul>
     */
    private static final class FlyingObject extends AbstractObjectInstance {
        private int currentX;
        private final int margin;

        FlyingObject(ObjectSpawn spawn, int margin) {
            super(spawn, "FlyingTest");
            this.currentX = spawn.x();
            this.margin = margin;
        }

        void setCurrentX(int x) {
            this.currentX = x;
        }

        @Override
        public int getX() {
            return currentX;
        }

        @Override
        public int getY() {
            return spawn.y();
        }

        @Override
        public boolean isPersistent() {
            // Mirrors the Buzz Bomber fix: persist while near the camera viewport
            return !isDestroyed() && isOnScreenX(margin);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {}
    }

    /**
     * Non-persistent control object â€“ should be removed when spawn leaves window.
     */
    private static final class StaticObject extends AbstractObjectInstance {
        StaticObject(ObjectSpawn spawn) {
            super(spawn, "StaticTest");
        }

        @Override
        public int getX() {
            return spawn.x();
        }

        @Override
        public int getY() {
            return spawn.y();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {}
    }

    private static final class TestRegistry implements ObjectRegistry {
        FlyingObject flyingInstance;
        private final int persistentObjectId;
        private final int flyingMargin;

        TestRegistry(int persistentObjectId, int flyingMargin) {
            this.persistentObjectId = persistentObjectId;
            this.flyingMargin = flyingMargin;
        }

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            if (spawn.objectId() == persistentObjectId) {
                flyingInstance = new FlyingObject(spawn, flyingMargin);
                return flyingInstance;
            }
            return new StaticObject(spawn);
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {}

        @Override
        public String getPrimaryName(int objectId) {
            return "Test";
        }
    }

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    /**
     * Core regression scenario: camera moves backward while a flying object
     * (Buzz Bomber) is still near the viewport.
     * <p>
     * Timeline:
     * <ol>
     *   <li>Camera at X=400 â†’ spawn at X=1000 enters window (load-ahead 640).</li>
     *   <li>Object "flies left" to X=600 (on-screen).</li>
     *   <li>Camera backs up to X=200 â†’ spawn at 1000 exceeds new windowEnd (840).</li>
     *   <li>Without fix: object removed. With fix: isPersistent() keeps it alive.</li>
     * </ol>
     */
    @Test
    public void testFlyingObjectSurvivesCameraBacktrack() {
        // Buzz Bomber-like object at X=1000
        ObjectSpawn flyingSpawn = new ObjectSpawn(1000, 400, 0x22, 0, 0, false, 0);
        // A static reference object at X=300 (inside the unload-behind window for camera at 200-400)
        ObjectSpawn staticSpawn = new ObjectSpawn(300, 400, 0x01, 0, 0, false, 0);

        List<ObjectSpawn> spawns = List.of(staticSpawn, flyingSpawn);
        TestRegistry registry = new TestRegistry(0x22, 160);
        ObjectManager manager = new ObjectManager(spawns, registry, 0, null, null);

        // --- Frame 1: Camera at X=400, windowEnd = 400+640 = 1040.
        //     Spawn at X=1000 â‰¤ 1040 â†’ object created.
        Camera camera = GameServices.camera();
        camera.setX((short) 400);
        manager.reset(400);
        manager.update(400, null, null, 1);
        assertEquals(2, manager.getActiveObjects().size(), "Both objects should be active");

        // --- The flying object moves left to X=600 (simulates buzz bomber flight).
        registry.flyingInstance.setCurrentX(600);

        // --- Frame 2: Camera backs up to X=200.
        //     placement.update() streams new window (windowEnd=840) at end of frame.
        //     Spawn at X=1000 > 840 â†’ spawn drops out of placement active set.
        camera.setX((short) 200);
        manager.update(200, null, null, 2);

        // --- Frame 3: syncActiveSpawns sees spawn X=1000 is no longer in placement.
        //     isPersistent() check fires:
        //       cameraBounds = [200, ?, 520, ?]
        //       isOnScreenX(160) for X=600: 600 â‰¤ 520+160=680 â†’ true â†’ persists
        manager.update(200, null, null, 3);

        assertEquals(2, manager.getActiveObjects().size(), "Flying object must survive camera backtrack while near viewport");
        assertTrue(manager.getActiveObjects().contains(registry.flyingInstance), "Flying instance specifically must still be active");
    }

    /**
     * Verify that a persistent flying object IS eventually removed when it
     * moves truly far from the camera (MarkObjGone equivalent).
     */
    @Test
    public void testFlyingObjectRemovedWhenFarFromCamera() {
        ObjectSpawn flyingSpawn = new ObjectSpawn(1000, 400, 0x22, 0, 0, false, 0);
        List<ObjectSpawn> spawns = List.of(flyingSpawn);
        TestRegistry registry = new TestRegistry(0x22, 160);
        ObjectManager manager = new ObjectManager(spawns, registry, 0, null, null);

        // Camera at X=400, spawn at 1000 in window â†’ object created.
        Camera camera = GameServices.camera();
        camera.setX((short) 400);
        manager.reset(400);
        manager.update(400, null, null, 1);
        assertEquals(1, manager.getActiveObjects().size());

        // Move beyond both the persistence margin and MarkObjGone's 640px X window.
        registry.flyingInstance.setCurrentX(700);

        // Camera backs up far to X=0.
        //   placement.update() streams new window at end of frame 2.
        camera.setX((short) 0);
        manager.update(0, null, null, 2);

        // Frame 3: syncActiveSpawns sees spawn X=1000 is no longer in placement.
        //   cameraBounds = [0, ?, 320, ?]
        //   isOnScreenX(160) for X=600: 600 â‰¤ 320+160=480? NO (600>480) â†’ not persistent â†’ removed.
        manager.update(0, null, null, 3);

        assertEquals(0, manager.getActiveObjects().size(), "Flying object should be removed when far from camera");
    }

    /**
     * control test: without the isPersistent override (margin=0, beyond screen),
     * a flying object at X=600 is removed when camera backs up enough that the
     * spawn leaves the window â€“ even though the object is on-screen.
     * This demonstrates the bug that the fix addresses.
     */
    @Test
    public void testNonPersistentObjectRemovedOnCameraBacktrack() {
        // Use margin=0 so isPersistent effectively returns false when off exact screen
        // Actually use objectId 0x01 so the registry creates a StaticObject (not persistent)
        ObjectSpawn staticSpawn = new ObjectSpawn(1000, 400, 0x01, 0, 0, false, 0);
        List<ObjectSpawn> spawns = List.of(staticSpawn);
        TestRegistry registry = new TestRegistry(0x22, 160); // 0x22 doesn't match 0x01
        ObjectManager manager = new ObjectManager(spawns, registry, 0, null, null);

        Camera camera = GameServices.camera();
        camera.setX((short) 400);
        manager.reset(400);
        manager.update(400, null, null, 1);
        assertEquals(1, manager.getActiveObjects().size(), "Object should be created");

        // Camera backs up â†’ placement.update() streams new window at end of frame 2.
        camera.setX((short) 200);
        manager.update(200, null, null, 2);

        // Frame 3: syncActiveSpawns sees spawn X=1000 no longer in placement â†’ removed.
        manager.update(200, null, null, 3);

        assertEquals(0, manager.getActiveObjects().size(), "Non-persistent object should be removed when spawn leaves window");
    }
}


