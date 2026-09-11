package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.OscillationManager;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1GlassReflectionGraphRewind {

    private static final ObjectSpawn FAR_GLASS =
            new ObjectSpawn(0x0080, 0x0180, Sonic1ObjectIds.MZ_GLASS_BLOCK, 0x04, 0, false, 10);
    private static final ObjectSpawn NEAR_GLASS =
            new ObjectSpawn(0x0200, 0x01C0, Sonic1ObjectIds.MZ_GLASS_BLOCK, 0x04, 0, false, 11);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        OscillationManager.resetForSonic1();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    /**
     * S1 bug-triage row 4 investigation (docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md,
     * "Glass reflection oscillates incorrectly"): a subtype-4 (switch-activated
     * lowering) glass block reflection's Y motion, verified against
     * "docs/s1disasm/_incObj/30 MZ Large Green Glass Blocks.asm".
     * <p>
     * Glass_Type04 (lines 209-215) branches on {@code btst #3,obSubtype(a0)}:
     * bit 3 clear (the block itself) runs Glass_ChkSwitch's switch-gated
     * glass_dist countdown; bit 3 SET (the reflection -- its subtype always has
     * bit 3 set, since Glass_Main does {@code addq.b #8,obSubtype(a1)} when
     * creating it) instead falls straight through to
     * {@code move.b (v_oscillate+$12).w,d0 / subi.w #$10,d0} and NEVER reads
     * glass_dist at all, even though Glass_Reflect34/012 (lines 89-93, 105-110)
     * unconditionally copy the parent's glass_dist into the reflection's own
     * memory one instruction earlier -- that copy is simply discarded for
     * types 3-4. So a switch-activated block's reflection oscillating
     * independently while the block itself lowers and stops is genuine,
     * verified ROM behavior (present in the retail "World REV01" ROM this
     * project targets), not an engine defect. This test locks in that
     * ROM-accurate independence rather than "fixing" it to track the parent,
     * which the ledger's initial (untriaged) symptom description suggested but
     * the disassembly disproves. See docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md
     * row 4 for the ledger disposition.
     */
    @Test
    void type4ReflectionOscillatesIndependentlyOfParentGlassDistPerRom() {
        ObjectSpawn blockSpawn = new ObjectSpawn(0x0100, 0x0300, Sonic1ObjectIds.MZ_GLASS_BLOCK, 0x04, 0, false, 50);
        Sonic1GlassBlockObjectInstance parent = new Sonic1GlassBlockObjectInstance(blockSpawn);
        int reflectSubtype = reflectedSubtype(blockSpawn);
        Sonic1GlassReflectionInstance reflection =
                new Sonic1GlassReflectionInstance(blockSpawn, parent, reflectSubtype, isTall(blockSpawn));

        // Fix the shared oscillator at a known frame so both reflection.update()
        // calls below observe the identical (v_oscillate+$12) byte -- isolating
        // glass_dist as the only thing that changes between them.
        OscillationManager.update(37);
        int expectedOscByte = OscillationManager.getByte(0x10);

        // Block still fully raised (initial glass_dist = $90, INITIAL_GLASS_DIST).
        writeInt(parent, "glassDist", 0x90);
        reflection.update(1, null);
        int yWhileRaised = readInt(reflection, "y");

        // Block fully lowered by the switch (glass_dist driven to 0 by
        // Glass_ChkSwitch's "subq.w #2,glass_dist(a0)" loop).
        writeInt(parent, "glassDist", 0);
        reflection.update(1, null);
        int yWhileLowered = readInt(reflection, "y");

        assertEquals(yWhileRaised, yWhileLowered,
                "ROM Glass_Type04's bit-3-set (reflection) path never reads glass_dist, so the "
                        + "reflection's Y must not change when the parent's glass_dist changes");
        // Anchor is the parent's actual (constructed) Y, not the spawn Y directly:
        // this test never calls parent.update(), so parent.getY() stays at its
        // construction-time value (spawn.y() - INITIAL_GLASS_DIST); the reflection
        // must sync baseY from that CURRENT obY per Glass_Reflect34, not the parent's
        // static spawn baseline (spawn.y() itself).
        int parentCurrentY = readInt(parent, "y");
        assertEquals(parentCurrentY - (expectedOscByte - 0x10), yWhileLowered,
                "reflection Y must follow the oscillator formula from Glass_Type04 "
                        + "(v_oscillate+$12) - $10, anchored to the parent's CURRENT Y");
    }

    /**
     * S1 bug-triage row 4 re-investigation
     * (docs/.superpowers/sdd/glass-reinvestigation-report.md): the short-variant
     * reflection (Glass_Reflect34, subtypes 3-4) must resync its Y-reference from
     * the parent's CURRENT {@code obY} every frame -- {@code move.w obY(a1),objoff_30(a0)}
     * (sonic.lst BB5E: source displacement $0C = obY, not $30 = objoff_30) -- not
     * from the parent's static spawn baseline. Drives a real Type04 (switch-activated)
     * block + its spawned reflection child through actual {@link ObjectManager#update}
     * cycles, both before and after the switch triggers the parent's Y to actually
     * move, and asserts the reflection never drifts more than the ROM's small
     * oscillator shimmer (well under the 0x90/144px bug gap) from the block's
     * live position.
     */
    @Test
    void reflectionTracksParentsCurrentYThroughRealUpdatesIncludingAfterTriggeredLowering() {
        Sonic1SwitchManager switchManager = new Sonic1SwitchManager();
        ObjectSpawn blockSpawn = new ObjectSpawn(0x0100, 0x0300, Sonic1ObjectIds.MZ_GLASS_BLOCK, 0x04, 0, false, 60);
        Harness harness = Harness.createWithSwitchManager(List.of(blockSpawn), switchManager);
        ObjectManager objectManager = harness.objectManager();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4000, (short) 0x4000);

        // Drive several frames with the switch unpressed: the block stays fully
        // raised (glass_dist == INITIAL_GLASS_DIST), and the reflection must stay
        // glued to the block's CURRENT Y, not its spawn baseline.
        for (int frame = 0; frame < 5; frame++) {
            objectManager.update(0, player, List.of(), frame, false, true, false);
            assertReflectionWithinShimmerOfBlock(objectManager, "frame " + frame + " (switch unpressed)");
        }

        // Press the switch (subtype 0x04 -> high nybble 0 -> switch index 0), so
        // Glass_ChkSwitch begins lowering glass_dist by 2/frame (Glass_Type04),
        // moving the block's actual current Y every subsequent frame.
        switchManager.setBit(0, 0);

        for (int frame = 5; frame < 100; frame++) {
            objectManager.update(0, player, List.of(), frame, false, true, false);
            assertReflectionWithinShimmerOfBlock(objectManager, "frame " + frame + " (switch pressed, lowering)");
        }

        Sonic1GlassBlockObjectInstance block = liveGlassBlocks(objectManager).getFirst();
        assertEquals(0, readInt(block, "glassDist"),
                "switch-activated block must fully lower for this test to exercise a moving parent Y");
    }

    /**
     * S1 bug-triage row 4, third investigation round (post-merge; see
     * {@code .superpowers/sdd/glass-oscillator-report.md} and
     * {@code .superpowers/sdd/glass-visual-diff-report.md}): {@code Glass_Type04}'s
     * reflection Y write ({@code obY = objoff_30 - (v_oscillate+$12 - $10)},
     * {@code sonic.lst:42561-42592}, opcodes BC3A-BC82) runs EVERY frame the
     * reflection updates, unconditionally -- it never reads the parent's
     * {@code glass_dist}/switch state at all. Independently simulating ROM's
     * {@code OscillateNumDo} loop for this table entry (index 4: speed=4,
     * limit=$20, start value $0080, start direction "up" -- the exact
     * {@code S1_SPEEDS[4]}/{@code S1_LIMITS[4]}/{@code S1_INITIAL_VALUES[4]}/
     * {@code S1_INITIAL_DELTAS[4]} constants in {@link OscillationManager})
     * gives a continuous ~252-frame cycle where the oscillator value's high
     * byte sweeps [0, 62]:
     * <ul>
     *   <li>byte=0 (trough): d0 = 0-$10 = -16 -&gt; obY = baseline+16 (16px DOWN)</li>
     *   <li>byte=62 (peak): d0 = 62-$10 = +46 -&gt; obY = baseline-46 (46px UP)</li>
     * </ul>
     * This 62px peak-to-peak swing was cross-checked against a BizHawk hardware
     * RAM+screenshot capture AND the engine's own headless TraceCaptureTool
     * pixel diff, both confirming the engine reproduces real hardware
     * frame-for-frame -- this is deliberate, verified ROM behavior, not an
     * engine defect (ledger row 4, docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md,
     * disposition {@code rom-confirmed-intentional}).
     * <p>
     * <b>This is a PINNING test, not a regression repro: it passes unmodified
     * on current code.</b> Its purpose is to lock in the exact ROM-derived
     * envelope so a future "fix" attempting to address the original user
     * report cannot silently dampen or remove this verified motion without a
     * fresh disasm citation overriding the evidence above.
     */
    @Test
    void type4ReflectionSwings62PxWhileParentStationary() {
        Sonic1SwitchManager switchManager = new Sonic1SwitchManager();
        ObjectSpawn blockSpawn = new ObjectSpawn(0x0100, 0x0300, Sonic1ObjectIds.MZ_GLASS_BLOCK, 0x14, 0, false, 80);
        Harness harness = Harness.createWithSwitchManager(List.of(blockSpawn), switchManager);
        ObjectManager objectManager = harness.objectManager();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4000, (short) 0x4000);

        // Frame 0 establishes the parent (switch never pressed, so it never
        // lowers) and spawns the reflection child; the reflection's baseY
        // then tracks the parent's live Y every frame (Glass_Reflect34).
        OscillationManager.update(0);
        objectManager.update(0, player, List.of(), 0, false, true, false);
        Sonic1GlassBlockObjectInstance block = liveGlassBlocks(objectManager).getFirst();
        int baseline = block.getY();

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int frame = 1; frame < 260; frame++) {
            OscillationManager.update(frame);
            objectManager.update(0, player, List.of(), frame, false, true, false);
            Sonic1GlassReflectionInstance reflection = onlyReflection(objectManager);
            minY = Math.min(minY, reflection.getY());
            maxY = Math.max(maxY, reflection.getY());
        }

        assertEquals(0x90, readInt(block, "glassDist"),
                "parent must stay untriggered/stationary (switch never pressed) for this pinning test to "
                        + "isolate the reflection's own independent oscillator motion");
        assertEquals(baseline, block.getY(),
                "parent's live Y must never move for this pinning test's baseline to stay valid");
        assertEquals(baseline - 46, minY, "peak: reflection must rise exactly 46px above baseline");
        assertEquals(baseline + 16, maxY, "trough: reflection must sit exactly 16px below baseline");
        assertEquals(62, maxY - minY, "full ROM-computed peak-to-peak swing must stay exactly 62px");
    }

    private static void assertReflectionWithinShimmerOfBlock(ObjectManager objectManager, String context) {
        Sonic1GlassBlockObjectInstance block = liveGlassBlocks(objectManager).getFirst();
        Sonic1GlassReflectionInstance reflection = onlyReflection(objectManager);
        int delta = Math.abs(reflection.getY() - block.getY());
        assertTrue(delta <= 0x40,
                "reflection Y must stay within the ROM shimmer swing of the block's CURRENT Y at " + context
                        + " (Glass_Reflect34 copies the parent's live obY, not its spawn baseline); "
                        + "reflection.y=" + reflection.getY() + " block.y=" + block.getY() + " delta=" + delta);
    }

    @Test
    void glassReflectionRestoresFreshWithIdentityScalarsAndNearestParentRelink() {
        Harness harness = Harness.create(List.of(FAR_GLASS, NEAR_GLASS));
        ObjectManager objectManager = harness.objectManager();
        Sonic1GlassBlockObjectInstance nearParent =
                liveParentAt(objectManager, NEAR_GLASS.x());
        Sonic1GlassBlockObjectInstance farParent =
                liveParentAt(objectManager, FAR_GLASS.x());
        Sonic1GlassReflectionInstance before = objectManager.createDynamicObject(
                () -> new Sonic1GlassReflectionInstance(
                        NEAR_GLASS, nearParent, reflectedSubtype(NEAR_GLASS), isTall(NEAR_GLASS)));
        writeInt(before, "x", 0x0224);
        writeInt(before, "y", 0x0142);
        writeInt(before, "baseY", 0x01D8);
        writeInt(before, "glassDist", 0x56);

        ObjectRefId beforeId = objectId(objectManager, before);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(before);
        Sonic1GlassReflectionInstance replacement = objectManager.createDynamicObject(
                () -> new Sonic1GlassReflectionInstance(
                        FAR_GLASS, farParent, reflectedSubtype(FAR_GLASS), isTall(FAR_GLASS)));

        rewindRegistry.restore(snapshot);

        Sonic1GlassReflectionInstance restored = onlyReflection(objectManager);
        Sonic1GlassBlockObjectInstance restoredNearParent =
                liveParentAt(objectManager, NEAR_GLASS.x());
        Sonic1GlassBlockObjectInstance restoredFarParent =
                liveParentAt(objectManager, FAR_GLASS.x());
        assertNotSame(before, restored, "restore must recreate the removed reflection");
        assertNotSame(replacement, restored, "restore must drop unrelated post-snapshot reflections");
        assertEquals(beforeId, objectId(objectManager, restored),
                "reflection rewind identity must be preserved");
        assertSame(restoredNearParent, readObject(restored, "parent"),
                "reflection must relink to the nearest live glass block");
        assertNotSame(restoredFarParent, readObject(restored, "parent"),
                "reflection must not relink to the farther live glass block");
        assertEquals(0x0224, readInt(restored, "x"), "x scalar must restore exactly");
        assertEquals(0x0142, readInt(restored, "y"), "y scalar must restore exactly");
        assertEquals(0x01D8, readInt(restored, "baseY"), "baseY scalar must restore exactly");
        assertEquals(0x56, readInt(restored, "glassDist"), "glassDist scalar must restore exactly");
        assertEquals(reflectedSubtype(NEAR_GLASS), readInt(restored, "reflectSubtype"),
                "reflectSubtype must be derived from the captured spawn subtype");
        assertEquals(isTall(NEAR_GLASS), readBoolean(restored, "isTall"),
                "isTall must be derived from the captured spawn subtype");

        restored.update(1, new TestablePlayableSprite("sonic", (short) 0x0200, (short) 0x0180));
        assertFalse(restored.isDestroyed(), "restored reflection must survive with its relinked parent");
        assertSame(restoredNearParent, readObject(restored, "parent"),
                "update must continue following the relinked parent");
    }

    @Test
    void directGenericRecreateWithoutLiveGlassBlockReturnsNull() {
        Harness harness = Harness.create(List.of());
        ObjectInstance recreated = genericRecreate(harness.objectManager(), NEAR_GLASS);

        assertNull(recreated, "generic recreate must drop a reflection when no live glass block exists");
    }

    @Test
    void directGenericRecreateRelinksNearestLiveGlassBlock() {
        Harness harness = Harness.create(List.of(FAR_GLASS, NEAR_GLASS));
        ObjectInstance recreated = genericRecreate(harness.objectManager(), NEAR_GLASS);

        Sonic1GlassReflectionInstance reflection =
                assertInstanceOf(Sonic1GlassReflectionInstance.class, recreated);
        assertSame(liveParentAt(harness.objectManager(), NEAR_GLASS.x()),
                readObject(reflection, "parent"),
                "direct generic recreate must relink to the nearest live glass block");
        assertEquals(reflectedSubtype(NEAR_GLASS), readInt(reflection, "reflectSubtype"));
        assertEquals(isTall(NEAR_GLASS), readBoolean(reflection, "isTall"));
    }

    @Test
    void glassBlockAndReflectionRestoreFreshWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        Sonic1GlassBlockObjectInstance beforeParent = objectManager.createDynamicObject(
                () -> new Sonic1GlassBlockObjectInstance(NEAR_GLASS));
        Sonic1GlassReflectionInstance beforeReflection = objectManager.createDynamicObject(
                () -> new Sonic1GlassReflectionInstance(
                        NEAR_GLASS, beforeParent, reflectedSubtype(NEAR_GLASS), isTall(NEAR_GLASS)));
        writeObject(beforeParent, "reflectionChild", beforeReflection);
        writeInt(beforeParent, "x", 0x0220);
        writeInt(beforeParent, "y", 0x0140);
        writeInt(beforeParent, "baseY", 0x01E0);
        writeInt(beforeParent, "glassDist", 0xA0);
        writeInt(beforeReflection, "x", 0x0220);
        writeInt(beforeReflection, "y", 0x0150);
        writeInt(beforeReflection, "baseY", 0x01E0);
        writeInt(beforeReflection, "glassDist", 0x90);

        ObjectRefId parentId = objectId(objectManager, beforeParent);
        ObjectRefId reflectionId = objectId(objectManager, beforeReflection);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(beforeReflection);
        objectManager.removeDynamicObject(beforeParent);
        Sonic1GlassBlockObjectInstance replacementParent = objectManager.createDynamicObject(
                () -> new Sonic1GlassBlockObjectInstance(FAR_GLASS));
        Sonic1GlassReflectionInstance replacementReflection = objectManager.createDynamicObject(
                () -> new Sonic1GlassReflectionInstance(
                        FAR_GLASS, replacementParent, reflectedSubtype(FAR_GLASS), isTall(FAR_GLASS)));
        writeObject(replacementParent, "reflectionChild", replacementReflection);

        rewindRegistry.restore(snapshot);

        assertEquals(1, liveGlassBlocks(objectManager).size(),
                "restore must keep exactly the captured glass block");
        assertEquals(1, liveReflections(objectManager).size(),
                "restore must keep exactly the captured reflection");
        Sonic1GlassBlockObjectInstance restoredParent =
                assertInstanceOf(Sonic1GlassBlockObjectInstance.class,
                        objectWithId(objectManager, parentId));
        Sonic1GlassReflectionInstance restoredReflection =
                assertInstanceOf(Sonic1GlassReflectionInstance.class,
                        objectWithId(objectManager, reflectionId));
        assertNotSame(beforeParent, restoredParent, "restore must recreate the removed glass block");
        assertNotSame(beforeReflection, restoredReflection, "restore must recreate the removed reflection");
        assertNotSame(replacementParent, restoredParent, "restore must drop unrelated post-snapshot parents");
        assertNotSame(replacementReflection, restoredReflection,
                "restore must drop unrelated post-snapshot reflections");
        assertSame(restoredReflection, readObject(restoredParent, "reflectionChild"),
                "parent reflectionChild must point to the restored reflection");
        assertSame(restoredParent, readObject(restoredReflection, "parent"),
                "reflection parent must point to the restored glass block");
        assertEquals(0x0220, readInt(restoredParent, "x"), "parent x scalar must restore exactly");
        assertEquals(0x0140, readInt(restoredParent, "y"), "parent y scalar must restore exactly");
        assertEquals(0x01E0, readInt(restoredParent, "baseY"), "parent baseY scalar must restore exactly");
        assertEquals(0xA0, readInt(restoredParent, "glassDist"),
                "parent glassDist scalar must restore exactly");
        assertEquals(0x0220, readInt(restoredReflection, "x"), "reflection x scalar must restore exactly");
        assertEquals(0x0150, readInt(restoredReflection, "y"), "reflection y scalar must restore exactly");
        assertEquals(0x01E0, readInt(restoredReflection, "baseY"),
                "reflection baseY scalar must restore exactly");
        assertEquals(0x90, readInt(restoredReflection, "glassDist"),
                "reflection glassDist scalar must restore exactly");
    }

    @Test
    void glassBlockReflectionBackrefStillRequiresRewindIdentity() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        Sonic1GlassBlockObjectInstance parent = objectManager.createDynamicObject(
                () -> new Sonic1GlassBlockObjectInstance(NEAR_GLASS));
        Sonic1GlassReflectionInstance unmanagedReflection =
                new Sonic1GlassReflectionInstance(
                        NEAR_GLASS, parent, reflectedSubtype(NEAR_GLASS), isTall(NEAR_GLASS));
        writeObject(parent, "reflectionChild", unmanagedReflection);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> registryFor(objectManager).capture(),
                "required glass reflection backrefs must fail loudly when the target has no rewind identity");
        assertTrue(thrown.getMessage().contains("no registered id for object reference"));
    }

    @Test
    void glassBlockAndReflectionUseGenericRecreateWithoutExplicitDynamicCodecs() {
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1GlassBlockObjectInstance.class.getName()),
                "glass block must restore through generic recreate, not a dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1GlassReflectionInstance.class.getName()),
                "glass reflection must restore through generic recreate, not a dynamic codec");
    }

    private static ObjectInstance genericRecreate(ObjectManager objectManager, ObjectSpawn spawn) {
        PerObjectRewindSnapshot state = new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
        ObjectManagerSnapshot.DynamicObjectEntry entry =
                new ObjectManagerSnapshot.DynamicObjectEntry(
                        Sonic1GlassReflectionInstance.class.getName(), spawn, 0, state);
        return ObjectRewindDynamicCodecs.genericRecreate(
                entry, new DynamicObjectRecreateContext(objectManager));
    }

    private static int reflectedSubtype(ObjectSpawn spawn) {
        return ((spawn.subtype() & 0xFF) + 8) & 0x0F;
    }

    private static boolean isTall(ObjectSpawn spawn) {
        return (spawn.subtype() & 0xFF) < 3;
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(List<ObjectSpawn> spawns) {
            return create(spawns, null);
        }

        static Harness createWithSwitchManager(List<ObjectSpawn> spawns, Sonic1SwitchManager switchManager) {
            return create(spawns, switchManager);
        }

        private static Harness create(List<ObjectSpawn> spawns, Sonic1SwitchManager switchManager) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public <T> T gameService(Class<T> type) {
                    if (switchManager != null && type == Sonic1SwitchManager.class) {
                        return type.cast(switchManager);
                    }
                    return null;
                }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns, new Sonic1ObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static ObjectInstance objectWithId(ObjectManager objectManager, ObjectRefId id) {
        List<ObjectInstance> matches = objectManager.getActiveObjects().stream()
                .filter(object -> id.equals(objectManager.captureIdentityContext().requireIdentityTable().idFor(object)))
                .toList();
        assertEquals(1, matches.size(), "expected one live object for rewind id " + id);
        return matches.getFirst();
    }

    private static Sonic1GlassBlockObjectInstance liveParentAt(ObjectManager objectManager, int x) {
        List<Sonic1GlassBlockObjectInstance> matches = liveGlassBlocks(objectManager).stream()
                .filter(object -> object.getX() == x)
                .toList();
        assertEquals(1, matches.size(), "expected one live glass block at X " + x);
        return matches.getFirst();
    }

    private static List<Sonic1GlassBlockObjectInstance> liveGlassBlocks(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object instanceof Sonic1GlassBlockObjectInstance)
                .map(Sonic1GlassBlockObjectInstance.class::cast)
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static Sonic1GlassReflectionInstance onlyReflection(ObjectManager objectManager) {
        List<Sonic1GlassReflectionInstance> reflections = liveReflections(objectManager);
        assertEquals(1, reflections.size(), "expected exactly one live glass reflection");
        return reflections.getFirst();
    }

    private static List<Sonic1GlassReflectionInstance> liveReflections(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == Sonic1GlassReflectionInstance.class)
                .map(Sonic1GlassReflectionInstance.class::cast)
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static int readInt(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBoolean(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Object readObject(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void writeInt(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void writeObject(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Walk superclass chain.
            }
        }
        throw new NoSuchFieldException(fieldName);
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
