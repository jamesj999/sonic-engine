package com.openggf.game.sonic1.objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1LavaGeyserOutOfRange {

    private static final class CountingMakerRegistry implements ObjectRegistry {
        int createCalls;

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            createCalls++;
            return new Sonic1LavaGeyserMakerObjectInstance(spawn);
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "LavaGeyserMaker";
        }
    }

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
        Camera camera = GameServices.camera();
        camera.setX((short) 0);
        camera.setY((short) 0);
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void makerIsNotRecreatedEachFrameWhenOnlyYIsOffscreen() {
        ObjectSpawn spawn = new ObjectSpawn(0x140, 0x700, 0x4C, 1, 0, false, 0);
        CountingMakerRegistry registry = new CountingMakerRegistry();
        ObjectManager manager = new ObjectManager(List.of(spawn), registry, 0, null, null);

        manager.reset(0);
        for (int i = 0; i < 5; i++) {
            manager.update(0, null, null, i + 1);
        }

        assertEquals(1, registry.createCalls, "Maker should be created once and persist in-range on X");
        assertEquals(1, manager.getActiveObjects().size(), "Maker should remain active (no Y-based out_of_range deletion)");
    }

    @Test
    public void makerDeletesWhenXIsOutOfRange() {
        ObjectSpawn farSpawn = new ObjectSpawn(0x3E8, 0x700, 0x4C, 1, 0, false, 0);
        Sonic1LavaGeyserMakerObjectInstance maker = new Sonic1LavaGeyserMakerObjectInstance(farSpawn);
        maker.setServices(new TestObjectServices().withCamera(GameServices.camera()));

        maker.update(1, null);

        assertTrue(maker.isDestroyed(), "Maker should delete when outside ROM out_of_range X window");
    }

    /**
     * Regression: lavafall (subtype != 0) third piece must NOT re-run initializeHead().
     * Without the fix, each frame's ensureInitialized() on the third piece would spawn
     * another body + third piece, cascading exponentially and crashing the engine.
     *
     * Tests the LavaGeyser HEAD directly with a live ObjectManager, bypassing the
     * maker (which requires a non-null player sprite for proximity checks).
     */
    @Test
    public void lavafallThirdPieceDoesNotCascadeSpawn() {
        GameServices.camera().setX((short) 0x100);
        GameServices.camera().setY((short) 0x300);

        // Build an ObjectManager with services wired back to itself so that
        // services().objectManager().addDynamicObject() actually works.
        CountingMakerRegistry registry = new CountingMakerRegistry();
        final ObjectManager[] managerRef = new ObjectManager[1];
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return managerRef[0];
            }
        };
        services.withCamera(GameServices.camera());
        ObjectManager manager = new ObjectManager(
                List.of(), registry, 0, null, null,
                null, GameServices.camera(), services);
        managerRef[0] = manager;

        // Directly add a lavafall HEAD (subtype 1) â€” this is what the maker spawns.
        // Its first update() calls initializeHead() which spawns body + third piece.
        ObjectSpawn headSpawn = new ObjectSpawn(0x180, 0x400, 0x4D, 1, 0, false, 0);
        Sonic1LavaGeyserObjectInstance head = new Sonic1LavaGeyserObjectInstance(
                headSpawn, Sonic1LavaGeyserObjectInstance.Role.HEAD,
                null, null, false);
        manager.addDynamicObject(head);

        // Run 10 frames. With the bug each frame adds 2+ objects exponentially.
        // Without the bug: 1 head + 1 body + 1 third piece = 3 total children,
        // and no further growth.
        for (int i = 0; i < 10; i++) {
            manager.update(0, null, null, i + 1);
        }

        int totalObjects = manager.getActiveObjects().size();
        assertTrue(totalObjects <= 5, "Lavafall should not cascade-spawn objects (found " + totalObjects
                + ", expected <= 5)");
    }

    /**
     * Bug repro (S1 bug-triage row 6, docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md):
     * "Lavafall landing flicker" -- a single-frame flicker at a vertical
     * lavafall's eventual landing spot when it starts.
     * <p>
     * Per docs/s1disasm/_incObj/"4C, 4D MZ Lava Geyser and Maker.asm":157-172,
     * ROM's Geyser_Main (routine 0) does not rts after applying the lavafall's
     * "subi.w #$250,obY(a0)" start-height offset -- it falls straight through
     * into spawning the body/third pieces and then Geyser_Action's own
     * SpeedToPos + DisplaySprite, all within the SAME frame the maker's
     * GMake_MakeLava creates it via FindNextFreeObj. So the ROM never displays
     * the newly created lava piece at its un-shifted (eventual landing) Y --
     * the very first DisplaySprite call after creation already reflects the
     * -0x250 shift.
     * <p>
     * {@link Sonic1LavaGeyserObjectInstance} models this by running its
     * {@code ensureInitialized()} (which applies the shift) from inside its own
     * first {@code update()} rather than its constructor. This test drives the
     * maker through {@code GMake_MakeLava} inside a real {@link ObjectManager}
     * exec loop (matching {@link ObjectLifetimeOps#assignFindNextFreeChildSlot}'s
     * ROM-faithful same-frame re-execution for a child allocated at a higher
     * slot) and asserts the newly spawned head never carries the maker's own Y
     * position after that single frame -- i.e. it never renders at the eventual
     * landing spot before reaching its ROM-created (shifted) position.
     */
    @Test
    public void lavafallHeadNeverRendersAtLandingYOnItsSpawnFrame() throws Exception {
        GameServices.camera().setX((short) 0);
        GameServices.camera().setY((short) 0);
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);

        ObjectSpawn makerSpawn = new ObjectSpawn(0x180, 0x400, 0x4C, 1, 0, false, 0);
        CountingMakerRegistry registry = new CountingMakerRegistry();
        final ObjectManager[] managerRef = new ObjectManager[1];
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return managerRef[0];
            }
        };
        services.withCamera(GameServices.camera());
        ObjectManager manager = new ObjectManager(
                List.of(makerSpawn), registry, 0, null, null,
                null, GameServices.camera(), services);
        managerRef[0] = manager;
        manager.reset(0);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        // Get the placed maker onto the active list, then jump it straight to
        // GMake_MakeLava (routine 6) -- equivalent to it having already run
        // through GMake_Wait/GMake_ChkType once Sonic came into proximity range,
        // which this test isn't exercising.
        manager.update(0, player, List.of(), 1, false);
        Sonic1LavaGeyserMakerObjectInstance maker = onlyMaker(manager);
        Field routineField = Sonic1LavaGeyserMakerObjectInstance.class.getDeclaredField("routine");
        routineField.setAccessible(true);
        routineField.setInt(maker, 6);

        // This single update() call is where ROM's GMake_MakeLava fires
        // FindNextFreeObj and the new lava piece falls through Geyser_Main into
        // Geyser_Action + DisplaySprite, all in the same pass.
        manager.update(0, player, List.of(), 2, false);

        List<Sonic1LavaGeyserObjectInstance> pieces = manager.getActiveObjects().stream()
                .filter(o -> o instanceof Sonic1LavaGeyserObjectInstance)
                .map(Sonic1LavaGeyserObjectInstance.class::cast)
                .filter(o -> !o.isDestroyed())
                .toList();
        assertTrue(pieces.size() >= 1, "GMake_MakeLava must have spawned at least the head piece");

        for (Sonic1LavaGeyserObjectInstance piece : pieces) {
            assertFalse(piece.getY() == makerSpawn.y(),
                    "no lava geyser piece (role=" + roleOf(piece)
                            + ") may render at the maker's own Y (the eventual landing spot) "
                            + "on its spawn frame -- got Y=" + piece.getY());
        }
    }

    /**
     * Same bug as {@link #lavafallHeadNeverRendersAtLandingYOnItsSpawnFrame()},
     * but forces the exact condition that made the original (pre-fix) code
     * flicker: {@code FindNextFreeObj} (here {@link ObjectManager#allocateSlotAfter})
     * finds nothing free AFTER the maker's own slot, so {@code spawnFreeChild}'s
     * generic fallback allocates a slot at a LOWER (already-executed-this-frame)
     * position instead. In that case {@code ObjectManager} defers the new head's
     * own first {@code update()} to next frame (see the "Child placed into a
     * slot at or below the parent's current execution slot" branch in
     * {@code ObjectManager.addDynamicObjectInternal}), so only a constructor-time
     * (not update()-time) position fix keeps the head from rendering at the
     * maker's own Y for that one frame.
     */
    @Test
    public void lavafallHeadNeverRendersAtLandingYWhenNoSlotIsFreeAfterMaker() throws Exception {
        GameServices.camera().setX((short) 0);
        GameServices.camera().setY((short) 0);
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);

        ObjectSpawn makerSpawn = new ObjectSpawn(0x180, 0x400, 0x4C, 1, 0, false, 0);
        CountingMakerRegistry registry = new CountingMakerRegistry();
        final ObjectManager[] managerRef = new ObjectManager[1];
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return managerRef[0];
            }
        };
        services.withCamera(GameServices.camera());
        ObjectManager manager = new ObjectManager(
                List.of(), registry, 0, null, null,
                null, GameServices.camera(), services);
        managerRef[0] = manager;
        manager.reset(0);

        // Find the LAST dynamic slot by allocating everything, then release it
        // all again -- placing the maker there means every other slot is
        // necessarily positioned earlier in the exec pass.
        int lastSlot = -1;
        int allocated;
        List<Integer> reservedForProbe = new java.util.ArrayList<>();
        while ((allocated = manager.allocateDynamicSlot()) >= 0) {
            lastSlot = allocated;
            reservedForProbe.add(allocated);
        }
        for (int slot : reservedForProbe) {
            manager.releaseDynamicSlot(slot);
        }
        assertTrue(lastSlot >= 0, "test precondition: dynamic pool must be non-empty");

        Sonic1LavaGeyserMakerObjectInstance maker = new Sonic1LavaGeyserMakerObjectInstance(makerSpawn);
        manager.addDynamicObjectAtSlot(maker, lastSlot);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        Field routineField = Sonic1LavaGeyserMakerObjectInstance.class.getDeclaredField("routine");
        routineField.setAccessible(true);
        routineField.setInt(maker, 6);

        // The maker occupies the LAST exec slot, so GMake_MakeLava's
        // FindNextFreeObj-equivalent (allocateSlotAfter) has nothing free after
        // it and spawnFreeChild's fallback (allocateSlot(), scanning from the
        // start) must grab an earlier, already-executed-this-pass slot instead
        // -- forcing the new head's own first update() to defer to next frame.
        manager.update(0, player, List.of(), 2, false);

        List<Sonic1LavaGeyserObjectInstance> pieces = manager.getActiveObjects().stream()
                .filter(o -> o instanceof Sonic1LavaGeyserObjectInstance)
                .map(Sonic1LavaGeyserObjectInstance.class::cast)
                .filter(o -> !o.isDestroyed())
                .toList();
        assertTrue(pieces.size() >= 1, "GMake_MakeLava must still spawn the head even via the slot-exhaustion fallback");

        for (Sonic1LavaGeyserObjectInstance piece : pieces) {
            assertFalse(piece.getY() == makerSpawn.y(),
                    "no lava geyser piece (role=" + roleOf(piece)
                            + ") may render at the maker's own Y even when its first update() "
                            + "is deferred to next frame -- got Y=" + piece.getY());
        }
    }

    private static String roleOf(Sonic1LavaGeyserObjectInstance piece) throws Exception {
        Field roleField = Sonic1LavaGeyserObjectInstance.class.getDeclaredField("role");
        roleField.setAccessible(true);
        return String.valueOf(roleField.get(piece));
    }

    private static Sonic1LavaGeyserMakerObjectInstance onlyMaker(ObjectManager manager) {
        List<Sonic1LavaGeyserMakerObjectInstance> makers = manager.getActiveObjects().stream()
                .filter(o -> o instanceof Sonic1LavaGeyserMakerObjectInstance)
                .map(Sonic1LavaGeyserMakerObjectInstance.class::cast)
                .filter(o -> !o.isDestroyed())
                .toList();
        assertEquals(1, makers.size(), "expected exactly one live maker");
        return makers.getFirst();
    }

    @Test
    public void geyserPiecePersistenceUsesXRangeNotYVisibility() {
        ObjectSpawn bodySpawn = new ObjectSpawn(0x180, 0x700, 0x4D, 0, 0, false, 0);
        Sonic1LavaGeyserObjectInstance body = new Sonic1LavaGeyserObjectInstance(
                bodySpawn, Sonic1LavaGeyserObjectInstance.Role.BODY, null, null, false);
        body.setServices(new TestObjectServices().withCamera(GameServices.camera()));

        assertTrue(body.isPersistent(), "Body piece should remain persistent when X is in range, even if Y is off-screen");

        GameServices.camera().setX((short) 0x600);
        // Sync the shared camera bounds to the moved camera (the engine does this
        // every frame via ObjectManager.updateCameraBounds); the width-driven
        // out_of_range check reads cameraBounds, not camera.getX() directly.
        AbstractObjectInstance.updateCameraBounds(0x600, 0, 0x600 + 320, 224, 0);

        assertFalse(body.isPersistent(), "Body piece should become non-persistent when X leaves out_of_range window");
    }

    /**
     * Bug repro (S1 bug-triage row 6, post-merge finding -- see
     * .superpowers/sdd/geyser-anim-report.md): "lavafall maker shows previous
     * cycle's ending animation frame at eruption start". Per
     * docs/s1disasm/_incObj/"4C, 4D MZ Lava Geyser and Maker.asm":64-87, ROM's
     * GMake_MakeLava (routine 6) falls straight through into GMake_Display's own
     * AnimateSprite call in the SAME dispatch pass the new obAnim is written --
     * so a repeating lavafall/geyser maker's rendered frame always reflects the
     * NEW eruption's first frame the instant it becomes visible again, never the
     * tail frame of the previous cycle's anim 3 (.bubble3) "ending" animation.
     * <p>
     * This drives a real lavafall maker (subtype 1) inside a live
     * {@link ObjectManager} exec loop, seeds {@code displayFrame} to the exact
     * stale value {@code updateDelete()} would have left behind at the end of a
     * prior cycle (1, .bubble3's last frame) and jumps {@code routine} straight
     * to 6 (MakeLava, as if Sonic has re-entered proximity range after the
     * Wait/ChkType span), then asserts the very same {@code update()} call that
     * fires {@code GMake_MakeLava} already shows the new eruption's real first
     * frame (19, .blank) -- not the leftover 1.
     */
    @Test
    public void lavafallMakerNeverRendersPreviousCycleEndingFrameAtNextEruption() throws Exception {
        GameServices.camera().setX((short) 0);
        GameServices.camera().setY((short) 0);
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);

        ObjectSpawn makerSpawn = new ObjectSpawn(0x180, 0x400, 0x4C, 1, 0, false, 0);
        CountingMakerRegistry registry = new CountingMakerRegistry();
        final ObjectManager[] managerRef = new ObjectManager[1];
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return managerRef[0];
            }
        };
        services.withCamera(GameServices.camera());
        ObjectManager manager = new ObjectManager(
                List.of(makerSpawn), registry, 0, null, null,
                null, GameServices.camera(), services);
        managerRef[0] = manager;
        manager.reset(0);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        manager.update(0, player, List.of(), 1, false);
        Sonic1LavaGeyserMakerObjectInstance maker = onlyMaker(manager);

        // Seed the exact end-of-previous-cycle state GMake_Delete leaves behind
        // (displayFrame=1, .bubble3's last frame; dark through Wait/ChkType), then
        // jump straight to routine 6 as if Sonic re-entered proximity range.
        Field displayFrameField = Sonic1LavaGeyserMakerObjectInstance.class.getDeclaredField("displayFrame");
        displayFrameField.setAccessible(true);
        displayFrameField.setInt(maker, 1);
        Field visibleField = Sonic1LavaGeyserMakerObjectInstance.class.getDeclaredField("visible");
        visibleField.setAccessible(true);
        visibleField.setBoolean(maker, false);
        Field routineField = Sonic1LavaGeyserMakerObjectInstance.class.getDeclaredField("routine");
        routineField.setAccessible(true);
        routineField.setInt(maker, 6);

        // This single update() call is where ROM's GMake_MakeLava fires and, in
        // the SAME pass, falls through into GMake_Display's AnimateSprite -- the
        // frame it renders here must already be the new eruption's first frame.
        manager.update(0, player, List.of(), 2, false);

        int displayFrame = displayFrameField.getInt(maker);
        assertEquals(19, displayFrame,
                "Lavafall maker must show .blank's frame (19) the instant GMake_MakeLava "
                        + "fires, not the previous cycle's .bubble3 tail frame (1) -- got "
                        + displayFrame);
    }
}


