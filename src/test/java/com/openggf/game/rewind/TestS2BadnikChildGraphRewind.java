package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.GrounderRockProjectile;
import com.openggf.game.sonic2.objects.GrounderWallInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.badniks.AquisBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.BalkiryBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.BalkiryJetObjectInstance;
import com.openggf.game.sonic2.objects.badniks.GrounderBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.RexonBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.RexonHeadObjectInstance;
import com.openggf.game.sonic2.objects.badniks.ShellcrackerBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.ShellcrackerClawInstance;
import com.openggf.game.sonic2.objects.badniks.SlicerBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.SlicerPincerInstance;
import com.openggf.game.sonic2.objects.badniks.SolBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.SolFireballObjectInstance;
import com.openggf.game.sonic2.objects.badniks.TurtloidBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.TurtloidJetInstance;
import com.openggf.game.sonic2.objects.badniks.TurtloidRiderInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestS2BadnikChildGraphRewind {

    private static final ObjectSpawn GROUNDER_SPAWN =
            new ObjectSpawn(0x0100, 0x0120, Sonic2ObjectIds.GROUNDER_IN_WALL, 0, 0, false, 10);
    private static final ObjectSpawn REXON_SPAWN =
            new ObjectSpawn(0x0160, 0x0140, Sonic2ObjectIds.REXON, 0, 0, false, 20);
    private static final ObjectSpawn SHELLCRACKER_SPAWN =
            new ObjectSpawn(0x01C0, 0x0150, Sonic2ObjectIds.SHELLCRACKER, 0, 0, false, 30);
    private static final ObjectSpawn SLICER_SPAWN =
            new ObjectSpawn(0x0200, 0x0160, Sonic2ObjectIds.SLICER, 0, 0, false, 40);
    private static final ObjectSpawn SOL_SPAWN =
            new ObjectSpawn(0x0240, 0x0170, Sonic2ObjectIds.SOL, 0, 0, false, 50);
    private static final ObjectSpawn BALKIRY_SPAWN =
            new ObjectSpawn(0x0260, 0x0180, Sonic2ObjectIds.BALKIRY, 0, 0, false, 60);
    private static final ObjectSpawn TURTLOID_SPAWN =
            new ObjectSpawn(0x02A0, 0x0190, Sonic2ObjectIds.TURTLOID, 0, 0, false, 70);
    private static final ObjectSpawn AQUIS_SPAWN =
            new ObjectSpawn(0x02D0, 0x01A0, Sonic2ObjectIds.AQUIS, 0, 0, false, 75);
    private static final String AQUIS_WING_CLASS =
            "com.openggf.game.sonic2.objects.badniks.AquisBadnikInstance$AquisWingChild";

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void s2BadnikChildGraphRestoresWithoutDropsDoublesOrStaleReferences() throws Exception {
        Harness harness = Harness.createWithParents();
        ObjectManager objectManager = harness.objectManager();
        BadnikChildGraph before = BadnikChildGraph.spawnRepresentativeFamily(objectManager);

        Map<Class<?>, Integer> beforeCounts = before.counts();
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeDynamicChildren(objectManager);
        BadnikChildGraph replacement = BadnikChildGraph.spawnReplacementFamily(objectManager);
        assertNotEquals(beforeIds.get("grounderWall0"), objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacement.grounderWall0()));

        rewindRegistry.restore(snapshot);

        BadnikChildGraph restored = BadnikChildGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, restored.counts(),
                "restore must not drop or duplicate S2 badnik child graph objects");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured dynamic child identities");

        assertAllReferencesPointAtRestoredGraph(restored);
        assertRestoredObjectsAreFresh(before, restored);
        assertImportantScalarFieldsRestored(before, restored);
    }

    @Test
    void missingRequiredObjectReferencesStillFailWhenTargetHasNoRewindIdentity() {
        Harness externalHarness = Harness.createWithParents();
        BadnikChildGraph external = BadnikChildGraph.spawnRepresentativeFamily(externalHarness.objectManager());
        RequiredReferenceFixture fixture = new RequiredReferenceFixture(external.rexonHead1());
        RewindCaptureContext context =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture, context));
        assertNotNull(thrown.getMessage());
        assertFalse(thrown.getMessage().isBlank());
        assertEquals(true, thrown.getMessage().contains("no registered id for object reference"),
                "non-null required object references must still require registered rewind identities");
    }

    @Test
    void detachedFallingSlicerPincerRestoresWithoutLiveParent() throws Exception {
        Harness harness = Harness.createWithoutParents();
        ObjectManager objectManager = harness.objectManager();
        ObjectSpawn spawn = new ObjectSpawn(
                0x0300, 0x0180, Sonic2ObjectIds.SLICER_PINCERS, 0, 0, false, 80);
        SlicerPincerInstance before = objectManager.createDynamicObject(
                () -> new SlicerPincerInstance(spawn, null, 0x0320, 0x0190, 0x140, true, 0));
        invokePrivate(before, "startFalling");
        setIntField(before, "timer", 0x42);
        setIntField(before, "xVelocity", -0x1A0);
        setIntField(before, "yVelocity", 0x120);

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(before);

        rewindRegistry.restore(snapshot);

        SlicerPincerInstance restored = only(objectManager, SlicerPincerInstance.class);
        assertNotSame(before, restored, "restore must recreate the detached pincer");
        assertEquals(null, readObjectField(restored, "parent"),
                "detached falling pincer must restore with no live parent");
        assertScalarFieldsEqual(before, restored,
                "phase", "currentX", "currentY", "xVelocity", "yVelocity", "timer", "hFlip");
    }

    @Test
    void detachedFlyingSolFireballRestoresWithoutLiveParent() throws Exception {
        Harness harness = Harness.createWithoutParents();
        ObjectManager objectManager = harness.objectManager();
        ObjectSpawn spawn = new ObjectSpawn(0x0340, 0x01A0, Sonic2ObjectIds.SOL, 0, 0, false, 90);
        SolFireballObjectInstance before = objectManager.createDynamicObject(
                () -> new SolFireballObjectInstance(spawn, null, 0));
        setObjectField(before, "state", enumValue(before, "State", "FLYING"));
        setIntField(before, "currentX", 0x0360);
        setIntField(before, "currentY", 0x01B0);
        setIntField(before, "xVelocity", -0x200);
        setIntField(before, "angle", 0x40);

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(before);

        rewindRegistry.restore(snapshot);

        SolFireballObjectInstance restored = only(objectManager, SolFireballObjectInstance.class);
        assertNotSame(before, restored, "restore must recreate the detached fireball");
        assertEquals(null, readObjectField(restored, "parent"),
                "detached flying Sol fireball must restore with no live parent");
        assertScalarFieldsEqual(before, restored, "state", "angle", "currentX", "currentY", "xVelocity");
    }

    private static void assertAllReferencesPointAtRestoredGraph(BadnikChildGraph graph) throws Exception {
        assertSame(graph.grounder(), readObjectField(graph.grounderWall0(), "parent"));
        assertSame(graph.grounder(), readObjectField(graph.grounderRock0(), "parent"));
        assertSame(graph.balkiry(), readObjectField(graph.balkiryJet(), "parent"));
        assertSame(graph.rexon(), readObjectField(graph.rexonHead0(), "parent"));
        assertSame(graph.rexon(), readObjectField(graph.rexonHead1(), "parent"));
        assertSame(graph.rexon(), readObjectField(graph.rexonHead2(), "parent"));
        assertSame(graph.rexonHead1(), readObjectField(graph.rexonHead0(), "linkedHead"));
        assertSame(graph.rexonHead2(), readObjectField(graph.rexonHead1(), "linkedHead"));
        assertSame(graph.shellcracker(), readObjectField(graph.shellcrackerClaw0(), "parent"));
        assertSame(graph.shellcracker(), readObjectField(graph.shellcrackerClaw1(), "parent"));
        assertSame(graph.slicer(), readObjectField(graph.slicerPincer0(), "parent"));
        assertSame(graph.sol(), readObjectField(graph.solFireball0(), "parent"));
        assertSame(graph.sol(), readObjectField(graph.solFireball1(), "parent"));
        assertSame(graph.aquis(), readObjectField(graph.aquisWing(), "parent"));
        assertSame(graph.aquisWing(), readObjectField(graph.aquis(), "wingChild"));
        assertSame(graph.turtloid(), readObjectField(graph.turtloidRider(), "parent"));
        assertSame(graph.turtloid(), readObjectField(graph.turtloidJet(), "parent"));

        List<?> heads = readListField(graph.rexon(), "heads");
        assertEquals(5, heads.size(), "Rexon parent head list must be restored");
        assertSame(graph.rexonHead0(), heads.get(0));
        assertSame(graph.rexonHead1(), heads.get(1));
        assertSame(graph.rexonHead2(), heads.get(2));

        List<?> fireballs = readListField(graph.sol(), "fireballs");
        assertEquals(4, fireballs.size(), "Sol parent fireball list must be restored");
        assertSame(graph.solFireball0(), fireballs.get(0));
        assertSame(graph.solFireball1(), fireballs.get(1));
    }

    private static void assertRestoredObjectsAreFresh(BadnikChildGraph before, BadnikChildGraph restored) {
        for (int i = 0; i < before.dynamicChildren().size(); i++) {
            assertNotSame(before.dynamicChildren().get(i), restored.dynamicChildren().get(i),
                    "restored child must be a fresh instance at index " + i);
        }
    }

    private static void assertImportantScalarFieldsRestored(
            BadnikChildGraph before,
            BadnikChildGraph restored) {
        assertScalarFieldsEqual(before.grounderWall0(), restored.grounderWall0(),
                "currentX", "currentY", "xVelocity", "yVelocity", "xSubpixel", "ySubpixel", "activated");
        assertScalarFieldsEqual(before.grounderRock0(), restored.grounderRock0(),
                "currentX", "currentY", "xVelocity", "yVelocity", "xSubpixel", "ySubpixel",
                "mappingFrame", "activated", "rockIndex");
        assertScalarFieldsEqual(before.balkiryJet(), restored.balkiryJet(), "animTimer", "animFrame");
        assertScalarFieldsEqual(before.rexonHead0(), restored.rexonHead0(),
                "headIndex", "headNumber", "xFlip", "state", "currentX", "currentY", "baseX", "baseY",
                "xVelocity", "yVelocity", "xSubpixel", "ySubpixel", "waitTimer", "raiseTimer",
                "oscillationPhase", "phaseDirection", "oscillationFrameCounter", "projectileTimer",
                "destroyed");
        assertScalarFieldsEqual(before.shellcrackerClaw0(), restored.shellcrackerClaw0(),
                "pieceIndex", "facingRight", "currentX", "currentY", "xVelocity", "yVelocity",
                "state", "timer", "retractTimer", "animFrame", "initRoutinePending");
        assertScalarFieldsEqual(before.slicerPincer0(), restored.slicerPincer0(),
                "phase", "currentX", "currentY", "xVelocity", "yVelocity", "timer",
                "hFlip", "animIndex", "animTimer");
        assertScalarFieldsEqual(before.solFireball0(), restored.solFireball0(),
                "state", "angle", "currentX", "currentY", "xVelocity");
        assertScalarFieldsEqual(before.aquis(), restored.aquis(),
                "state", "timer", "shotsRemaining", "shootingFlag", "currentX", "currentY",
                "xVelocity", "yVelocity", "wingSpawned");
        assertScalarFieldsEqual(before.aquisWing(), restored.aquisWing(),
                "wingX", "wingY", "wingFacingLeft");
        assertScalarFieldsEqual(before.turtloid(), restored.turtloid(),
                "state", "timer", "xVelocity", "animFrame", "childrenSpawned");
        assertScalarFieldsEqual(before.turtloidRider(), restored.turtloidRider(),
                "currentX", "currentY", "mappingFrame", "destroyed");
        assertScalarFieldsEqual(before.turtloidJet(), restored.turtloidJet(),
                "currentX", "currentY", "animFrame", "animTimer");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness createWithParents() {
            return create(List.of(GROUNDER_SPAWN, REXON_SPAWN, SHELLCRACKER_SPAWN,
                    SLICER_SPAWN, SOL_SPAWN, BALKIRY_SPAWN));
        }

        static Harness createWithoutParents() {
            return create(List.of());
        }

        private static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new Sonic2ObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private static final class RequiredReferenceFixture {
        ObjectInstance object;

        private RequiredReferenceFixture(ObjectInstance object) {
            this.object = object;
        }
    }

    private record BadnikChildGraph(
            GrounderBadnikInstance grounder,
            RexonBadnikInstance rexon,
            ShellcrackerBadnikInstance shellcracker,
            SlicerBadnikInstance slicer,
            SolBadnikInstance sol,
            BalkiryBadnikInstance balkiry,
            AquisBadnikInstance aquis,
            TurtloidBadnikInstance turtloid,
            GrounderWallInstance grounderWall0,
            GrounderWallInstance grounderWall1,
            GrounderRockProjectile grounderRock0,
            GrounderRockProjectile grounderRock1,
            BalkiryJetObjectInstance balkiryJet,
            ObjectInstance aquisWing,
            TurtloidRiderInstance turtloidRider,
            TurtloidJetInstance turtloidJet,
            RexonHeadObjectInstance rexonHead0,
            RexonHeadObjectInstance rexonHead1,
            RexonHeadObjectInstance rexonHead2,
            RexonHeadObjectInstance rexonHead3,
            RexonHeadObjectInstance rexonHead4,
            ShellcrackerClawInstance shellcrackerClaw0,
            ShellcrackerClawInstance shellcrackerClaw1,
            SlicerPincerInstance slicerPincer0,
            SlicerPincerInstance slicerPincer1,
            SolFireballObjectInstance solFireball0,
            SolFireballObjectInstance solFireball1,
            SolFireballObjectInstance solFireball2,
            SolFireballObjectInstance solFireball3) {

        static BadnikChildGraph spawnRepresentativeFamily(ObjectManager objectManager) {
            GrounderBadnikInstance grounder = only(objectManager, GrounderBadnikInstance.class);
            RexonBadnikInstance rexon = only(objectManager, RexonBadnikInstance.class);
            ShellcrackerBadnikInstance shellcracker = only(objectManager, ShellcrackerBadnikInstance.class);
            SlicerBadnikInstance slicer = only(objectManager, SlicerBadnikInstance.class);
            SolBadnikInstance sol = only(objectManager, SolBadnikInstance.class);
            BalkiryBadnikInstance balkiry = only(objectManager, BalkiryBadnikInstance.class);
            AquisBadnikInstance aquis = ensureAquisParent(objectManager);
            TurtloidBadnikInstance turtloid = ensureTurtloidParent(objectManager);

            invokePrivate(grounder, "spawnWalls");
            invokePrivate(grounder, "spawnRocks");
            readListFieldUnchecked(rexon, "heads").clear();
            invokePrivate(rexon, "createHeads");
            invokePrivate(shellcracker, "spawnClawPieces");
            invokePrivate(slicer, "spawnPincers");
            readListFieldUnchecked(sol, "fireballs").clear();
            setBooleanField(sol, "fireballsSpawned", false);
            invokePrivate(sol, "ensureFireballsSpawned");
            if (liveObjects(objectManager, BalkiryJetObjectInstance.class).isEmpty()) {
                invokePrivate(balkiry, "spawnJetChild");
            }
            setObjectField(aquis, "wingChild", null);
            setBooleanField(aquis, "wingSpawned", false);
            invokePrivate(aquis, "spawnWingChildOnce");
            invokePrivate(turtloid, "ensureChildrenSpawned");

            BadnikChildGraph graph = fromLiveObjects(objectManager);
            seedDistinctState(graph);
            return graph;
        }

        static BadnikChildGraph spawnReplacementFamily(ObjectManager objectManager) {
            return spawnRepresentativeFamily(objectManager);
        }

        private static TurtloidBadnikInstance ensureTurtloidParent(ObjectManager objectManager) {
            List<TurtloidBadnikInstance> existing = liveObjects(objectManager, TurtloidBadnikInstance.class);
            if (existing.isEmpty()) {
                return objectManager.createDynamicObject(() -> new TurtloidBadnikInstance(TURTLOID_SPAWN));
            }
            assertEquals(1, existing.size(), "expected at most one live TurtloidBadnikInstance");
            return existing.getFirst();
        }

        private static AquisBadnikInstance ensureAquisParent(ObjectManager objectManager) {
            List<AquisBadnikInstance> existing = liveObjects(objectManager, AquisBadnikInstance.class);
            if (existing.isEmpty()) {
                return objectManager.createDynamicObject(() -> new AquisBadnikInstance(AQUIS_SPAWN));
            }
            assertEquals(1, existing.size(), "expected at most one live AquisBadnikInstance");
            return existing.getFirst();
        }

        static BadnikChildGraph fromLiveObjects(ObjectManager objectManager) {
            List<GrounderWallInstance> walls = liveObjects(objectManager, GrounderWallInstance.class);
            List<GrounderRockProjectile> rocks = liveObjects(objectManager, GrounderRockProjectile.class);
            List<RexonHeadObjectInstance> heads = liveObjects(objectManager, RexonHeadObjectInstance.class);
            List<ShellcrackerClawInstance> claws = liveObjects(objectManager, ShellcrackerClawInstance.class);
            List<SlicerPincerInstance> pincers = liveObjects(objectManager, SlicerPincerInstance.class);
            List<SolFireballObjectInstance> fireballs = liveObjects(objectManager, SolFireballObjectInstance.class);
            List<ObjectInstance> aquisWings = liveObjectsByName(objectManager, AQUIS_WING_CLASS);
            List<TurtloidRiderInstance> riders = liveObjects(objectManager, TurtloidRiderInstance.class);
            List<TurtloidJetInstance> jets = liveObjects(objectManager, TurtloidJetInstance.class);
            SolBadnikInstance sol = only(objectManager, SolBadnikInstance.class);
            List<SolFireballObjectInstance> orderedFireballs = readListFieldUnchecked(sol, "fireballs").stream()
                    .map(SolFireballObjectInstance.class::cast)
                    .toList();
            assertEquals(4, walls.size(), "expected four live Grounder walls");
            assertEquals(5, rocks.size(), "expected five live Grounder rocks");
            assertEquals(5, heads.size(), "expected five live Rexon heads");
            assertEquals(8, claws.size(), "expected eight live Shellcracker claw pieces");
            assertEquals(2, pincers.size(), "expected two live Slicer pincers");
            assertEquals(4, fireballs.size(), "expected four live Sol fireballs");
            assertEquals(4, orderedFireballs.size(), "expected four parent-owned Sol fireballs");
            assertEquals(1, aquisWings.size(), "expected one live Aquis wing child");
            assertEquals(1, riders.size(), "expected one live Turtloid rider");
            assertEquals(1, jets.size(), "expected one live Turtloid jet");
            return new BadnikChildGraph(
                    only(objectManager, GrounderBadnikInstance.class),
                    only(objectManager, RexonBadnikInstance.class),
                    only(objectManager, ShellcrackerBadnikInstance.class),
                    only(objectManager, SlicerBadnikInstance.class),
                    sol,
                    only(objectManager, BalkiryBadnikInstance.class),
                    only(objectManager, AquisBadnikInstance.class),
                    only(objectManager, TurtloidBadnikInstance.class),
                    walls.get(0), walls.get(1),
                    rocks.get(0), rocks.get(1),
                    only(objectManager, BalkiryJetObjectInstance.class),
                    aquisWings.getFirst(),
                    riders.getFirst(), jets.getFirst(),
                    headByIndex(heads, 0), headByIndex(heads, 2), headByIndex(heads, 4),
                    headByIndex(heads, 6), headByIndex(heads, 8),
                    clawByIndex(claws, 0), clawByIndex(claws, 2),
                    pincers.get(0), pincers.get(1),
                    orderedFireballs.get(0), orderedFireballs.get(1),
                    orderedFireballs.get(2), orderedFireballs.get(3));
        }

        Map<Class<?>, Integer> counts() {
            Map<Class<?>, Integer> counts = new LinkedHashMap<>();
            for (ObjectInstance object : allObjects()) {
                counts.merge(object.getClass(), 1, Integer::sum);
            }
            return counts;
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            var table = objectManager.captureIdentityContext().requireIdentityTable();
            ids.put("grounder", table.idFor(grounder));
            ids.put("grounderWall0", table.idFor(grounderWall0));
            ids.put("grounderRock0", table.idFor(grounderRock0));
            ids.put("balkiryJet", table.idFor(balkiryJet));
            ids.put("rexonHead0", table.idFor(rexonHead0));
            ids.put("rexonHead1", table.idFor(rexonHead1));
            ids.put("shellcrackerClaw0", table.idFor(shellcrackerClaw0));
            ids.put("slicerPincer0", table.idFor(slicerPincer0));
            ids.put("solFireball0", table.idFor(solFireball0));
            ids.put("aquis", table.idFor(aquis));
            ids.put("aquisWing", table.idFor(aquisWing));
            ids.put("turtloid", table.idFor(turtloid));
            ids.put("turtloidRider", table.idFor(turtloidRider));
            ids.put("turtloidJet", table.idFor(turtloidJet));
            return ids;
        }

        void removeDynamicChildren(ObjectManager objectManager) {
            List<ObjectInstance> toRemove = objectManager.getActiveObjects().stream()
                    .filter(BadnikChildGraph::isTargetDynamicChild)
                    .toList();
            for (ObjectInstance object : toRemove) {
                objectManager.removeDynamicObject(object);
            }
        }

        private static boolean isTargetDynamicChild(ObjectInstance object) {
            return object instanceof GrounderWallInstance
                    || object instanceof GrounderRockProjectile
                    || object instanceof BalkiryJetObjectInstance
                    || object instanceof TurtloidBadnikInstance
                    || object instanceof RexonHeadObjectInstance
                    || object instanceof ShellcrackerClawInstance
                    || object instanceof SlicerPincerInstance
                    || object instanceof SolFireballObjectInstance
                    || object instanceof AquisBadnikInstance
                    || object.getClass().getName().equals(AQUIS_WING_CLASS)
                    || object instanceof TurtloidRiderInstance
                    || object instanceof TurtloidJetInstance;
        }

        private List<ObjectInstance> allObjects() {
            return List.of(grounder, rexon, shellcracker, slicer, sol, balkiry, aquis, turtloid,
                    grounderWall0, grounderWall1, grounderRock0, grounderRock1,
                    balkiryJet, aquisWing, turtloidRider, turtloidJet,
                    rexonHead0, rexonHead1, rexonHead2, rexonHead3, rexonHead4,
                    shellcrackerClaw0, shellcrackerClaw1, slicerPincer0, slicerPincer1,
                    solFireball0, solFireball1, solFireball2, solFireball3);
        }

        private List<ObjectInstance> dynamicChildren() {
            return List.of(aquis, turtloid,
                    grounderWall0, grounderWall1, grounderRock0, grounderRock1,
                    balkiryJet, aquisWing, turtloidRider, turtloidJet,
                    rexonHead0, rexonHead1, rexonHead2, rexonHead3, rexonHead4,
                    shellcrackerClaw0, shellcrackerClaw1, slicerPincer0, slicerPincer1,
                    solFireball0, solFireball1, solFireball2, solFireball3);
        }
    }

    private static void seedDistinctState(BadnikChildGraph graph) {
        setBooleanField(graph.grounderWall0(), "activated", true);
        setIntField(graph.grounderWall0(), "xVelocity", 0x155);
        setIntField(graph.grounderWall0(), "yVelocity", -0x122);
        setIntField(graph.grounderWall0(), "xSubpixel", 0x44);
        setIntField(graph.grounderWall0(), "ySubpixel", 0x55);
        setBooleanField(graph.grounderRock0(), "activated", true);
        setIntField(graph.grounderRock0(), "xVelocity", -0x244);
        setIntField(graph.grounderRock0(), "yVelocity", 0x133);
        setIntField(graph.grounderRock0(), "xSubpixel", 0x22);
        setIntField(graph.grounderRock0(), "ySubpixel", 0x77);
        setIntField(graph.balkiryJet(), "animTimer", 1);
        setIntField(graph.balkiryJet(), "animFrame", 9);
        setIntField(graph.rexonHead0(), "waitTimer", 11);
        setIntField(graph.rexonHead0(), "raiseTimer", 13);
        setIntField(graph.rexonHead0(), "oscillationPhase", 0x25);
        setIntField(graph.rexonHead0(), "phaseDirection", -1);
        setIntField(graph.rexonHead0(), "oscillationFrameCounter", 7);
        setIntField(graph.rexonHead0(), "projectileTimer", 29);
        setIntField(graph.shellcrackerClaw0(), "timer", 5);
        setIntField(graph.shellcrackerClaw0(), "retractTimer", 6);
        setBooleanField(graph.shellcrackerClaw0(), "facingRight", true);
        setIntField(graph.slicerPincer0(), "timer", 0x33);
        setIntField(graph.slicerPincer0(), "xVelocity", -0x180);
        setIntField(graph.slicerPincer0(), "yVelocity", 0x140);
        setBooleanField(graph.slicerPincer0(), "hFlip", true);
        setIntField(graph.slicerPincer0(), "animIndex", 2);
        setIntField(graph.slicerPincer0(), "animTimer", 3);
        setIntField(graph.solFireball0(), "angle", 0x12);
        setIntField(graph.solFireball0(), "xVelocity", -0x200);
        setObjectField(graph.aquis(), "state", enumValue(graph.aquis(), "State", "SHOOTING"));
        setIntField(graph.aquis(), "timer", 17);
        setIntField(graph.aquis(), "shotsRemaining", 2);
        setBooleanField(graph.aquis(), "shootingFlag", true);
        setIntField(graph.aquis(), "xVelocity", -0x80);
        setIntField(graph.aquis(), "yVelocity", 0x40);
        setIntField(graph.aquisWing(), "wingX", 0x02E4);
        setIntField(graph.aquisWing(), "wingY", 0x0198);
        setBooleanField(graph.aquisWing(), "wingFacingLeft", true);
        setObjectField(graph.turtloid(), "state", enumValue(graph.turtloid(), "State", "PAUSE_BEFORE"));
        setIntField(graph.turtloid(), "timer", 3);
        setIntField(graph.turtloid(), "xVelocity", 0);
        setIntField(graph.turtloid(), "animFrame", 1);
        setIntField(graph.turtloidRider(), "mappingFrame", 3);
        setIntField(graph.turtloidJet(), "animFrame", 7);
        setIntField(graph.turtloidJet(), "animTimer", 0);
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = liveObjects(objectManager, type);
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
    }

    private static List<ObjectInstance> liveObjectsByName(ObjectManager objectManager, String className) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(className) && !object.isDestroyed())
                .toList();
    }

    private static RexonHeadObjectInstance headByIndex(List<RexonHeadObjectInstance> heads, int headIndex) {
        List<RexonHeadObjectInstance> matches = heads.stream()
                .filter(head -> readIntField(head, "headIndex") == headIndex)
                .toList();
        assertEquals(1, matches.size(), "expected one Rexon head index " + headIndex);
        return matches.getFirst();
    }

    private static ShellcrackerClawInstance clawByIndex(List<ShellcrackerClawInstance> claws, int pieceIndex) {
        List<ShellcrackerClawInstance> matches = claws.stream()
                .filter(claw -> readIntField(claw, "pieceIndex") == pieceIndex)
                .toList();
        assertEquals(1, matches.size(), "expected one Shellcracker claw piece " + pieceIndex);
        return matches.getFirst();
    }

    private static void invokePrivate(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + methodName + " on " + target.getClass(), e);
        }
    }

    private static void assertScalarFieldsEqual(Object before, Object restored, String... fields) {
        for (String field : fields) {
            assertEquals(readField(before, field), readField(restored, field),
                    () -> before.getClass().getSimpleName() + "." + field + " must restore exactly");
        }
    }

    private static Object readField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Object readObjectField(Object target, String fieldName) throws Exception {
        return findField(target.getClass(), fieldName).get(target);
    }

    @SuppressWarnings("unchecked")
    private static List<?> readListField(Object target, String fieldName) throws Exception {
        return (List<?>) findField(target.getClass(), fieldName).get(target);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readListFieldUnchecked(Object target, String fieldName) {
        try {
            return (List<Object>) findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Object enclosingInstance, String enumSimpleName, String constantName) {
        for (Class<?> nested : enclosingInstance.getClass().getDeclaredClasses()) {
            if (nested.getSimpleName().equals(enumSimpleName) && nested.isEnum()) {
                return Enum.valueOf((Class<? extends Enum>) nested, constantName);
            }
        }
        throw new AssertionError("Unable to find enum " + enumSimpleName
                + " in " + enclosingInstance.getClass());
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

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
