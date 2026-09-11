package com.openggf.tests;

import com.openggf.game.sonic1.objects.Sonic1RingInstance;
import com.openggf.game.rewind.snapshot.RingSnapshot;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.rings.RingManager;
import com.openggf.level.rings.RingFrame;
import com.openggf.level.rings.RingFramePiece;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.level.spawn.AbstractPlacementManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestSonic1RingInstance {

    // â”€â”€ Static-property tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    public void testRingCollisionFlagsBeforeCollection() {
        assertEquals(0x47, Sonic1RingInstance.RING_COLLISION_FLAGS);
    }

    @Test
    public void testImplementsTouchResponseProvider() {
        assertTrue(TouchResponseProvider.class.isAssignableFrom(Sonic1RingInstance.class));
    }

    // â”€â”€ Construction-state tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Parent constructor (layout entry) starts in INIT state: no collision flags yet. */
    @Test
    public void testParentConstructorStartsInInitState() {
        Sonic1RingInstance ring = buildParentRing(100, 100);
        // INIT state: getCollisionFlags() returns 0 (only ANIMATE returns RING_COLLISION_FLAGS)
        assertEquals(0, ring.getCollisionFlags(), "Parent ring should have no collision flags in INIT state");
    }

    /**
     * Parent constructor with a single-element spawn list (the ring is its own spawn).
     * After one update (INITâ†’ANIMATE), collision flags become active.
     */
    @Test
    public void testSingleSpawnRingAnimatesAfterFirstUpdate() {
        RingSpawn spawn = new RingSpawn(50, 50);
        Sonic1RingInstance ring = buildParentRingFromSpawns(200, 200, List.of(spawn));
        // After INITâ†’ANIMATE, the ring should still be alive (no children to spawn,
        // no ringManager collected, so it stays ANIMATE).
        withContext(new StubObjectServices(), () -> ring.update(1, null));
        assertFalse(ring.isDestroyed(), "Single-spawn ring should not be destroyed after first update");
        assertEquals(Sonic1RingInstance.RING_COLLISION_FLAGS, ring.getCollisionFlags(), "Should be in ANIMATE state with full collision flags");
    }

    /**
     * Parent constructor with more than one ring spawn has a non-empty child list
     * internally. After the first update (which calls spawnChildren), the parent
     * transitions to ANIMATE and is not destroyed.
     */
    @Test
    public void testMultiSpawnParentTransitionsToAnimateWithChildren() {
        List<RingSpawn> spawns = List.of(
                new RingSpawn(50, 50),
                new RingSpawn(58, 50),
                new RingSpawn(66, 50)
        );
        Sonic1RingInstance ring = buildParentRingFromSpawns(50, 50, spawns);
        assertEquals(0, ring.getCollisionFlags(), "Before update: INIT â†’ no collision flags");

        withContext(new StubObjectServices(), () -> ring.update(1, null));

        assertFalse(ring.isDestroyed(), "Parent ring should not be destroyed after INITâ†’ANIMATE");
        assertEquals(Sonic1RingInstance.RING_COLLISION_FLAGS, ring.getCollisionFlags(), "Parent should be in ANIMATE state after first update");
    }

    // â”€â”€ State transition tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** After one update with no RingManager, INIT transitions to ANIMATE. */
    @Test
    public void testInitTransitionsToAnimateOnFirstUpdate() {
        Sonic1RingInstance ring = buildParentRing(100, 100);
        assertEquals(0, ring.getCollisionFlags(), "Before update: INIT â†’ no collision flags");

        withContext(new StubObjectServices(), () -> ring.update(1, null));

        assertEquals(Sonic1RingInstance.RING_COLLISION_FLAGS, ring.getCollisionFlags(), "After first update: ANIMATE â†’ collision flags active");
        assertFalse(ring.isDestroyed(), "Ring should not be destroyed after INITâ†’ANIMATE");
    }

    /** When ringManager.isCollected() returns true, ANIMATE transitions to SPARKLE (no collision). */
    @Test
    public void testAnimateStaysWhenNotCollected() {
        TestEnvironment.resetAll();
        RingSpawn ringSpawn = new RingSpawn(100, 100);
        RingManager rm = new RingManager(List.of(ringSpawn), null, null, null);

        // Ring is not collected via RingManager, so it should remain in ANIMATE.
        Sonic1RingInstance ring = buildParentRingFromSpawns(100, 100, List.of(ringSpawn));

        ObjectServices svc = new StubObjectServices() {
            @Override public RingManager ringManager() { return rm; }
        };

        // First update: INIT â†’ ANIMATE
        withContext(svc, () -> ring.update(1, null));
        // Second update: ANIMATE stays ANIMATE because ring not collected
        withContext(svc, () -> ring.update(2, null));

        assertEquals(Sonic1RingInstance.RING_COLLISION_FLAGS, ring.getCollisionFlags(), "Ring not collected: should stay ANIMATE");
        assertFalse(ring.isDestroyed(), "Ring not collected: should not be destroyed");
    }

    /** When ringManager is null in SPARKLE state, ring is destroyed immediately. */
    @Test
    public void testSparkleDestroysWhenRingManagerNull() {
        // Force the ring into SPARKLE state via reflection
        Sonic1RingInstance ring = buildParentRing(100, 100);
        forceState(ring, "SPARKLE");

        withContext(new StubObjectServices(), () -> ring.update(1, null));

        assertTrue(ring.isDestroyed(), "Ring in SPARKLE with null ringManager should be destroyed");
    }

    /** In SPARKLE state, collision flags return 0 (ring is no longer collidable). */
    @Test
    public void testSparkleStateHasNoCollisionFlags() {
        Sonic1RingInstance ring = buildParentRing(100, 100);
        forceState(ring, "SPARKLE");

        assertEquals(0, ring.getCollisionFlags(), "SPARKLE state should return 0 collision flags");
    }

    @Test
    public void testSparkleDeletionUsesGameplayFrameCounterNotAdvancedVblankCounter() {
        TestEnvironment.resetAll();

        RingSpawn ringSpawn = new RingSpawn(100, 100);
        RingManager ringManager = buildRenderCapableRingManager(List.of(ringSpawn));
        ringManager.reset(0);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) 100);
        when(player.getCentreY()).thenReturn((short) 100);
        when(player.getRolling()).thenReturn(false);
        when(player.getYRadius()).thenReturn((short) 19);
        when(player.getCrouching()).thenReturn(false);
        when(player.getDead()).thenReturn(false);
        ringManager.update(0, player, 350);

        Sonic1RingInstance ring = buildParentRingFromSpawns(100, 100, List.of(ringSpawn));
        forceState(ring, "SPARKLE");

        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getFrameCounter()).thenReturn(351);

        ObjectServices svc = new StubObjectServices() {
            @Override public RingManager ringManager() { return ringManager; }
            @Override public ObjectManager objectManager() { return objectManager; }
        };
        ring.setServices(svc);

        withContext(svc, () -> ring.update(353, null));

        assertFalse(ring.isDestroyed(),
                "Lagged VBlank time must not end Ring_Sparkle before gameplay time does");
    }

    @Test
    public void testCollectedChildRingsDoNotReserveSlots() {
        TestEnvironment.resetAll();

        List<RingSpawn> spawns = List.of(
                new RingSpawn(0x0100, 0x0080),
                new RingSpawn(0x0110, 0x0080),
                new RingSpawn(0x0120, 0x0080)
        );
        RingManager ringManager = new RingManager(spawns, null, null, null);
        BitSet collected = new BitSet();
        collected.set(1);
        ringManager.restore(new RingSnapshot(
                collected,
                new RingSnapshot.SparkleEntry[0],
                0,
                0,
                new int[0],
                0,
                0,
                0,
                0,
                0,
                new RingSnapshot.LostRingEntry[0],
                new RingSnapshot.AttractedRingEntry[0]));

        ObjectManager[] managerHolder = new ObjectManager[1];
        ObjectServices services = new StubObjectServices() {
            @Override public RingManager ringManager() { return ringManager; }
            @Override public ObjectManager objectManager() { return managerHolder[0]; }
        };
        ObjectManager objectManager = new ObjectManager(List.of(), new Sonic1SlotRegistry(),
                0, null, null, null, null, services);
        managerHolder[0] = objectManager;

        Sonic1RingInstance ring = buildParentRingFromSpawns(0x0100, 0x0080, spawns);
        ring.setServices(services);
        ring.update(1, null);

        List<Sonic1RingInstance> childRings = objectManager.getActiveObjects().stream()
                .filter(Sonic1RingInstance.class::isInstance)
                .map(Sonic1RingInstance.class::cast)
                .toList();
        assertEquals(1, childRings.size(),
                "Only the uncollected child ring should spawn an object");

        Sonic1RingInstance child = childRings.get(0);
        assertEquals(0x0120, child.getSpawn().x(),
                "The surviving child keeps its original Ring_PosData offset");
        assertEquals(ObjectSlotLayout.SONIC_1.firstDynamicSlot(),
                ((AbstractObjectInstance) child).getSlotIndex(),
                "Collected children skip FindFreeObj, so the first live child uses the first free slot");

        TestObject next = new TestObject(new ObjectSpawn(0x0200, 0x0080, 0x7E, 0, 0, false, 0));
        objectManager.addDynamicObject(next);
        assertEquals(ObjectSlotLayout.SONIC_1.firstDynamicSlot() + 1, next.getSlotIndex(),
                "No phantom reservation should block the next dynamic object slot");
    }

    /**
     * ROM-parity regression for the MZ3 "getSpawnIndex: identity miss" log spam:
     * {@code recreateForRewind} used to build a fresh {@code new RingSpawn(x, y)}
     * on every rewind/checkpoint restore, which is structurally equal to the
     * canonical spawn RingManager tracks but never identity-equal. Since
     * {@link AbstractPlacementManager#getSpawnIndex} keys its fast path on an
     * {@code IdentityHashMap}, every restored ring instance would permanently
     * fall back to the equals-based linear scan (and log a warning) for the
     * rest of its lifetime. The fix resolves the canonical instance from the
     * restore-time RingManager instead of reconstructing one.
     */
    @Test
    public void testRecreateForRewindResolvesCanonicalRingSpawnAvoidingIdentityMissWarning() {
        TestEnvironment.resetAll();

        RingSpawn canonical = new RingSpawn(4076, 1696);
        RingManager ringManager = new RingManager(List.of(canonical), null, null, null);

        ObjectServices svc = new StubObjectServices() {
            @Override public RingManager ringManager() { return ringManager; }
        };

        ObjectSpawn rewindSpawn = new ObjectSpawn(4076, 1696, 0x25, 0, 0, false, 0);
        Sonic1RingInstance template = buildParentRingFromSpawns(4076, 1696, List.of(canonical));

        Logger placementLogger = Logger.getLogger(AbstractPlacementManager.class.getName());
        List<String> warnings = new ArrayList<>();
        Handler captureHandler = new Handler() {
            @Override public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        placementLogger.addHandler(captureHandler);
        try {
            RewindRecreateContext ctx = new RewindRecreateContext(rewindSpawn, null, svc);
            AbstractObjectInstance recreated = template.recreateForRewind(ctx);

            assertTrue(recreated instanceof Sonic1RingInstance,
                    "recreateForRewind should return a Sonic1RingInstance");
            RingSpawn recreatedRingSpawn = getRingSpawnField((Sonic1RingInstance) recreated);
            assertSame(canonical, recreatedRingSpawn,
                    "recreateForRewind must reuse the canonical RingSpawn instance from "
                            + "RingManager instead of constructing a new one");

            // Exercise the restored instance's own lookup path (Ring_Sparkle uses this
            // overload) - it must hit the identity fast path with no fallback warning.
            ringManager.isCollectedAndSparkleDone(recreatedRingSpawn, 0);
            assertTrue(warnings.stream().noneMatch(m -> m != null && m.contains("identity miss")),
                    "recreateForRewind must not force an equals-based getSpawnIndex "
                            + "fallback warning: " + warnings);
        } finally {
            placementLogger.removeHandler(captureHandler);
        }
    }

    private static RingSpawn getRingSpawnField(Sonic1RingInstance ring) {
        try {
            Field field = Sonic1RingInstance.class.getDeclaredField("ringSpawn");
            field.setAccessible(true);
            return (RingSpawn) field.get(ring);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Helper methods ─────────────────────────────────────────────────────

    private static void withContext(ObjectServices svc, Runnable action) {
        setConstructionContext(svc);
        try {
            action.run();
        } finally {
            clearConstructionContext();
        }
    }

    private static Sonic1RingInstance buildParentRing(int x, int y) {
        RingSpawn spawn = new RingSpawn(x, y);
        return buildParentRingFromSpawns(x, y, List.of(spawn));
    }

    private static Sonic1RingInstance buildParentRingFromSpawns(int x, int y, List<RingSpawn> spawns) {
        ObjectSpawn os = new ObjectSpawn(x, y, 0x25, 0x00, 0, false, 0);
        Sonic1RingInstance[] holder = new Sonic1RingInstance[1];
        withContext(new StubObjectServices(), () -> {
            holder[0] = new Sonic1RingInstance(os, spawns);
            holder[0].setServices(new StubObjectServices());
        });
        return holder[0];
    }

    private static RingManager buildRenderCapableRingManager(List<RingSpawn> spawns) {
        Pattern pattern = new Pattern();
        pattern.setPixel(0, 0, (byte) 1);

        RingFrame frame = new RingFrame(List.of(new RingFramePiece(0, 0, 1, 1, 0, false, false, 0)));
        Pattern[] patterns = new Pattern[16];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = pattern;
        }

        RingSpriteSheet spriteSheet = new RingSpriteSheet(patterns, List.of(frame, frame, frame), 1, 1, 1, 2);
        RingManager ringManager = new RingManager(spawns, spriteSheet, null, null);
        GraphicsManager.getInstance().initHeadless();
        ringManager.ensurePatternsCached(GraphicsManager.getInstance(), 0);
        return ringManager;
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices svc) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(svc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void forceState(Sonic1RingInstance ring, String stateName) {
        try {
            Field stateField = Sonic1RingInstance.class.getDeclaredField("state");
            stateField.setAccessible(true);
            Class<?> stateClass = stateField.getType();
            Object[] constants = stateClass.getEnumConstants();
            for (Object constant : constants) {
                if (constant.toString().equals(stateName)) {
                    stateField.set(ring, constant);
                    return;
                }
            }
            throw new IllegalArgumentException("Unknown state: " + stateName);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class Sonic1SlotRegistry implements ObjectRegistry {
        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_1;
        }

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return null;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "test";
        }
    }

    private static final class TestObject extends AbstractObjectInstance {
        private TestObject(ObjectSpawn spawn) {
            super(spawn, "test-object");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // no rendering in headless tests
        }
    }
}


