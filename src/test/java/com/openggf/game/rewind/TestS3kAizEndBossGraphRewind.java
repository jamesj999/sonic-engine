package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.AizEndBossArmChild;
import com.openggf.game.sonic3k.objects.AizEndBossBombChild;
import com.openggf.game.sonic3k.objects.AizEndBossFlameChild;
import com.openggf.game.sonic3k.objects.AizEndBossFlameColumnChild;
import com.openggf.game.sonic3k.objects.AizEndBossInstance;
import com.openggf.game.sonic3k.objects.AizEndBossPropellerChild;
import com.openggf.game.sonic3k.objects.AizEndBossShipChild;
import com.openggf.game.sonic3k.objects.AizEndBossSmokeChild;
import com.openggf.game.sonic3k.objects.AizEndBossWaterfallChild;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS3kAizEndBossGraphRewind {

    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x0100, 0x0100, Sonic3kObjectIds.AIZ_END_BOSS, 0, 0, false, 10);
    private static final SonicConfigurationService DEFAULT_CONFIGURATION =
            createDefaultConfiguration();

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void aizEndBossGraphRestoresWithoutDropsDoublesOrStaleReferences() throws Exception {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        AizEndBossGraph before = AizEndBossGraph.spawnRepresentativeFamily(objectManager);

        Map<Class<?>, Integer> beforeCounts = before.counts();
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeDynamicChildren(objectManager);
        AizEndBossGraph replacement = AizEndBossGraph.spawnRepresentativeFamily(objectManager);
        assertNotEquals(beforeIds.get("ship"), objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(replacement.ship()));

        rewindRegistry.restore(snapshot);

        AizEndBossGraph restored = AizEndBossGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, restored.counts(),
                "restore must not drop or duplicate any AIZ end-boss graph object");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured dynamic object identities");

        assertAllReferencesPointAtRestoredGraph(restored);
        assertRestoredObjectsAreFresh(before, restored);
        assertImportantScalarsRestored(before, restored);
    }

    @Test
    void missingRequiredObjectReferencesStillFailWhenTargetHasNoRewindIdentity() {
        Harness externalHarness = Harness.createWithBoss();
        AizEndBossGraph external = AizEndBossGraph.spawnRepresentativeFamily(externalHarness.objectManager());
        RequiredReferenceFixture fixture = new RequiredReferenceFixture(external.flame());
        RewindCaptureContext context =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture, context));
        assertEquals(true, thrown.getMessage().contains("no registered id for object reference"),
                "non-null required object references must still require registered rewind identities");
    }

    @Test
    void waterfallSubtypesUseRomChildSlotOrderAndRestoreThroughRewind() throws Exception {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        AizEndBossInstance boss = only(objectManager, AizEndBossInstance.class);

        // Occupy two slots after the boss: CreateChild1_Normal scans forward
        // for the first free slot, rather than assuming bossSlot + 1.
        AizEndBossFlameColumnChild occupiedA = objectManager.createDynamicObject(
                () -> new AizEndBossFlameColumnChild(boss));
        AizEndBossFlameColumnChild occupiedB = objectManager.createDynamicObject(
                () -> new AizEndBossFlameColumnChild(boss));
        assertEquals(occupiedA.getSlotIndex() + 1, occupiedB.getSlotIndex(),
                "test fixture must occupy contiguous forward slots");

        invokeBooleanArg(boss, "beginEmerge", false);
        AizEndBossWaterfallChild emerge = only(objectManager, AizEndBossWaterfallChild.class);
        assertEquals(0, emerge.getSpawn().subtype(),
                "ChildObjDat_69D2E starts subtype 0 for the first emerge");
        int highestOccupiedSlot = Math.max(occupiedA.getSlotIndex(), occupiedB.getSlotIndex());
        assertEquals(highestOccupiedSlot + 1, emerge.getSlotIndex(),
                "CreateChild1_Normal must allocate the exact first free slot after occupied slots");
        int emergeX = emerge.getX();
        int emergeY = emerge.getY();
        assertEquals(0x24, emerge.getMappingFrameForTest(),
                "init dispatch must preserve the ObjDat initial mapping");
        boss.getState().x += 0x40;
        boss.getState().y += 0x20;

        emerge.update(0, null);
        assertEquals(emergeX, emerge.getX());
        assertEquals(0x24, emerge.getMappingFrameForTest(),
                "init-only dispatch must not animate the emerge child");
        for (int frame = 1; frame <= 13; frame++) {
            emerge.update(frame, null);
        }
        assertEquals(emergeX, emerge.getX(), "emerge child must retain CreateChild1_Normal x snapshot");
        assertEquals(emergeY, emerge.getY(), "emerge child must retain CreateChild1_Normal y snapshot");
        assertTrue(emerge.isDestroyed(),
                "subtype 0 must follow Go_Delete_Sprite at the emerge animation F4 callback");

        objectManager.removeDynamicObject(emerge);
        invokeNoArg(boss, "beginReSubmerge");
        AizEndBossWaterfallChild drop = only(objectManager, AizEndBossWaterfallChild.class);
        assertEquals(2, drop.getSpawn().subtype(),
                "AIZEndBoss_StartSubmerge writes subtype 2 after allocation");
        assertEquals(highestOccupiedSlot + 1, drop.getSlotIndex(),
                "the re-submerge splash must use the exact first free slot after occupied slots");

        drop.update(0, null);
        assertEquals(0x24, drop.getMappingFrameForTest(),
                "init dispatch must preserve the drop child's initial mapping");
        for (int frame = 1; frame <= 13; frame++) {
            drop.update(frame, null);
        }
        assertTrue(drop.isDroppingForTest(),
                "subtype 2 must enter the independent falling-drop routine at the F4 callback");
        assertEquals(0x800, drop.getYVelocityForTest(),
                "AIZEndBossWaterfall_StartDrop uses 8:8 velocity $800");

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();
        ObjectRefId capturedId = objectManager.captureIdentityContext()
                .requireIdentityTable().idFor(drop);
        int capturedFrame = drop.getMappingFrameForTest();
        int capturedY = drop.getY();
        assertNotEquals(0x24, capturedFrame, "rewind fixture must capture a non-default animation frame");

        objectManager.removeDynamicObject(drop);
        rewindRegistry.restore(snapshot);

        AizEndBossWaterfallChild restored = only(objectManager, AizEndBossWaterfallChild.class);
        assertEquals(capturedId, objectManager.captureIdentityContext()
                .requireIdentityTable().idFor(restored),
                "rewind must preserve the native splash object's identity");
        assertEquals(2, restored.getSpawn().subtype());
        assertEquals(capturedFrame, restored.getMappingFrameForTest());
        assertEquals(capturedY, restored.getY());

        // A restored falling child must continue from the same ROM state as
        // the original, not merely recreate its identity and current position.
        assertEquals(capturedFrame, drop.getMappingFrameForTest());
        for (int frame = 0; frame < 13; frame++) {
            restored.update(frame, null);
            drop.update(frame, null);
            assertEquals(drop.getY(), restored.getY());
            assertEquals(drop.getMappingFrameForTest(), restored.getMappingFrameForTest());
        }
        assertTrue(drop.isDestroyed(), "original falling child must reach its delete callback");
        assertTrue(restored.isDestroyed(), "restored falling child must reach its delete callback");
    }

    @Test
    void reEmergeUsesSubtypeZeroAndKeepsItsSpawnSnapshot() throws Exception {
        Harness harness = Harness.createWithBoss();
        ObjectManager objectManager = harness.objectManager();
        AizEndBossInstance boss = only(objectManager, AizEndBossInstance.class);

        invokeBooleanArg(boss, "beginEmerge", true);
        AizEndBossWaterfallChild child = only(objectManager, AizEndBossWaterfallChild.class);
        assertEquals(0, child.getSpawn().subtype(), "re-emerge also uses ChildObjDat subtype 0");
        int x = child.getX();
        int y = child.getY();
        assertEquals(0x24, child.getMappingFrameForTest());
        boss.getState().x += 0x30;
        boss.getState().y += 0x18;
        child.update(0, null);
        assertEquals(x, child.getX());
        assertEquals(y, child.getY());
        assertEquals(0x24, child.getMappingFrameForTest(),
                "re-emerge init dispatch must not advance the animation");
    }

    @Test
    void waterfallUsesRomPaletteZeroAndInitialMappingFrame() {
        Harness harness = Harness.createWithBoss();
        AizEndBossInstance boss = only(harness.objectManager(), AizEndBossInstance.class);
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_END_BOSS)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        AizEndBossWaterfallChild child = new AizEndBossWaterfallChild(boss, 0);
        child.setServices(new TestObjectServices().withLevelManager(levelManager));
        child.appendRenderCommands(new java.util.ArrayList<>());
        verifyNoInteractions(renderer);
        child.update(0, null);
        child.appendRenderCommands(new java.util.ArrayList<>());
        verifyNoInteractions(renderer);
        child.update(1, null);
        child.appendRenderCommands(new java.util.ArrayList<>());

        verify(renderer).drawFrameIndex(eq(0x24), eq(0x100), eq(0x100),
                eq(false), eq(false), eq(0));
    }

    private static void assertAllReferencesPointAtRestoredGraph(AizEndBossGraph graph) {
        assertSame(graph.ship(), readObjectField(graph.boss(), "shipChild"));
        assertSame(graph.leftArm(), readObjectField(graph.boss(), "leftArm"));
        assertSame(graph.rightArm(), readObjectField(graph.boss(), "rightArm"));

        assertSame(graph.boss(), readObjectField(graph.ship(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.column(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.leftArm(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.rightArm(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.leftPropeller(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.rightPropeller(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.flame(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.bomb(), "boss"));
        assertSame(graph.boss(), readObjectField(graph.smoke(), "boss"));

        assertSame(graph.boss(), readObjectField(graph.ship(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.leftArm(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.rightArm(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.leftPropeller(), "parent"));
        assertSame(graph.boss(), readObjectField(graph.rightPropeller(), "parent"));

        assertSame(graph.leftArm(), readObjectField(graph.leftPropeller(), "arm"));
        assertSame(graph.rightArm(), readObjectField(graph.rightPropeller(), "arm"));
        assertSame(graph.leftPropeller(), readObjectField(graph.leftArm(), "propeller"));
        assertSame(graph.rightPropeller(), readObjectField(graph.rightArm(), "propeller"));
        assertSame(graph.leftPropeller(), readObjectField(graph.flame(), "propeller"));
    }

    private static void assertRestoredObjectsAreFresh(AizEndBossGraph before, AizEndBossGraph restored) {
        assertNotSame(before.ship(), restored.ship());
        assertNotSame(before.column(), restored.column());
        assertNotSame(before.leftArm(), restored.leftArm());
        assertNotSame(before.rightArm(), restored.rightArm());
        assertNotSame(before.leftPropeller(), restored.leftPropeller());
        assertNotSame(before.rightPropeller(), restored.rightPropeller());
        assertNotSame(before.flame(), restored.flame());
        assertNotSame(before.bomb(), restored.bomb());
        assertNotSame(before.smoke(), restored.smoke());
    }

    private static void assertImportantScalarsRestored(AizEndBossGraph before, AizEndBossGraph restored) {
        assertEquals(readIntField(before.boss(), "angle"), readIntField(restored.boss(), "angle"));
        assertEquals(readIntField(before.boss(), "flags38"), readIntField(restored.boss(), "flags38"));
        assertEquals(readIntField(before.boss(), "waitTimer"), readIntField(restored.boss(), "waitTimer"));
        assertEquals(readObjectField(before.boss(), "waitCallback"), readObjectField(restored.boss(), "waitCallback"));
        assertEquals(readBooleanField(before.boss(), "renderActivated"),
                readBooleanField(restored.boss(), "renderActivated"));

        assertArmScalarsRestored(before.leftArm(), restored.leftArm());
        assertArmScalarsRestored(before.rightArm(), restored.rightArm());
        assertPropellerScalarsRestored(before.leftPropeller(), restored.leftPropeller());
        assertPropellerScalarsRestored(before.rightPropeller(), restored.rightPropeller());

        assertEquals(readIntField(before.flame(), "angle"), readIntField(restored.flame(), "angle"));
        assertEquals(readIntField(before.flame(), "currentX"), readIntField(restored.flame(), "currentX"));
        assertEquals(readIntField(before.flame(), "currentY"), readIntField(restored.flame(), "currentY"));
        assertEquals(readIntField(before.flame(), "animTimer"), readIntField(restored.flame(), "animTimer"));
        assertEquals(readIntField(before.flame(), "mappingFrame"), readIntField(restored.flame(), "mappingFrame"));

        assertEquals(readIntField(before.bomb(), "currentX"), readIntField(restored.bomb(), "currentX"));
        assertEquals(readIntField(before.bomb(), "currentY"), readIntField(restored.bomb(), "currentY"));
        assertEquals(readIntField(before.bomb(), "xVel"), readIntField(restored.bomb(), "xVel"));
        assertEquals(readIntField(before.bomb(), "yVel"), readIntField(restored.bomb(), "yVel"));
        assertEquals(readIntField(before.bomb(), "lifetime"), readIntField(restored.bomb(), "lifetime"));
        assertEquals(readBooleanField(before.bomb(), "hitFloor"), readBooleanField(restored.bomb(), "hitFloor"));

        assertEquals(readIntField(before.smoke(), "posX"), readIntField(restored.smoke(), "posX"));
        assertEquals(readIntField(before.smoke(), "posY"), readIntField(restored.smoke(), "posY"));
        assertEquals(readBooleanField(before.smoke(), "moving"), readBooleanField(restored.smoke(), "moving"));
        assertEquals(readIntField(before.smoke(), "animTimer"), readIntField(restored.smoke(), "animTimer"));
    }

    private static void assertArmScalarsRestored(AizEndBossArmChild before, AizEndBossArmChild restored) {
        assertEquals(readIntField(before, "offsetX"), readIntField(restored, "offsetX"));
        assertEquals(readIntField(before, "offsetY"), readIntField(restored, "offsetY"));
        assertEquals(readIntField(before, "subtype"), readIntField(restored, "subtype"));
        assertEquals(readIntField(before, "routine"), readIntField(restored, "routine"));
        assertEquals(readIntField(before, "mappingFrame"), readIntField(restored, "mappingFrame"));
        assertEquals(readIntField(before, "waitTimer"), readIntField(restored, "waitTimer"));
    }

    private static void assertPropellerScalarsRestored(
            AizEndBossPropellerChild before,
            AizEndBossPropellerChild restored) {
        assertEquals(readIntField(before, "subtype"), readIntField(restored, "subtype"));
        assertEquals(readIntField(before, "routine"), readIntField(restored, "routine"));
        assertEquals(readIntField(before, "stepCounter"), readIntField(restored, "stepCounter"));
        assertEquals(readIntField(before, "childDx"), readIntField(restored, "childDx"));
        assertEquals(readIntField(before, "childDy"), readIntField(restored, "childDy"));
        assertEquals(readObjectField(before, "animCallback"), readObjectField(restored, "animCallback"));
    }

    private record Harness(ObjectManager objectManager) {
        static Harness createWithBoss() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public SonicConfigurationService configuration() { return DEFAULT_CONFIGURATION; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(BOSS_SPAWN),
                    new Sonic3kObjectRegistry(),
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

    private record AizEndBossGraph(
            AizEndBossInstance boss,
            AizEndBossShipChild ship,
            AizEndBossFlameColumnChild column,
            AizEndBossArmChild leftArm,
            AizEndBossArmChild rightArm,
            AizEndBossPropellerChild leftPropeller,
            AizEndBossPropellerChild rightPropeller,
            AizEndBossFlameChild flame,
            AizEndBossBombChild bomb,
            AizEndBossSmokeChild smoke) {

        static AizEndBossGraph spawnRepresentativeFamily(ObjectManager objectManager) {
            AizEndBossInstance boss = only(objectManager, AizEndBossInstance.class);
            invokeNoArg(boss, "spawnShipChild");
            invokeNoArg(boss, "spawnArmChildren");
            invokeNoArg(boss, "spawnFlameColumnChild");
            spawnPropellersViaArmInit(objectManager, boss);
            AizEndBossGraph graph = fromConstructionChildren(objectManager, boss);
            AizEndBossFlameChild flame = objectManager.createDynamicObject(
                    () -> new AizEndBossFlameChild(boss, graph.leftPropeller(), 4));
            setIntField(flame, "animTimer", 17);
            setIntField(flame, "mappingFrame", 0x0D);
            AizEndBossBombChild bomb = objectManager.createDynamicObject(
                    () -> new AizEndBossBombChild(boss, flame.getX(), flame.getY(), 0x0C));
            setIntField(bomb, "lifetime", 0x55);
            setBooleanField(bomb, "hitFloor", true);
            AizEndBossSmokeChild smoke = objectManager.createDynamicObject(
                    () -> new AizEndBossSmokeChild(boss, bomb.getX() + 3, bomb.getY() + 5, true));
            setIntField(smoke, "animTimer", 6);
            setIntField(smoke, "mappingFrame", 0x13);
            seedBossScalars(boss);
            return fromLiveObjects(objectManager);
        }

        static AizEndBossGraph fromLiveObjects(ObjectManager objectManager) {
            AizEndBossInstance boss = only(objectManager, AizEndBossInstance.class);
            AizEndBossGraph partial = fromConstructionChildren(objectManager, boss);
            return new AizEndBossGraph(
                    boss,
                    partial.ship(),
                    partial.column(),
                    partial.leftArm(),
                    partial.rightArm(),
                    partial.leftPropeller(),
                    partial.rightPropeller(),
                    only(objectManager, AizEndBossFlameChild.class),
                    only(objectManager, AizEndBossBombChild.class),
                    only(objectManager, AizEndBossSmokeChild.class));
        }

        private static AizEndBossGraph fromConstructionChildren(
                ObjectManager objectManager,
                AizEndBossInstance boss) {
            List<AizEndBossArmChild> arms = liveObjects(objectManager, AizEndBossArmChild.class);
            List<AizEndBossPropellerChild> propellers =
                    liveObjects(objectManager, AizEndBossPropellerChild.class);
            assertEquals(2, arms.size(), "expected exactly two live AIZ end-boss arms");
            assertEquals(2, propellers.size(), "expected exactly two live AIZ end-boss propellers");
            AizEndBossArmChild leftArm = armBySubtype(arms, 0);
            AizEndBossArmChild rightArm = armBySubtype(arms, 1);
            AizEndBossPropellerChild leftPropeller = propellerForArm(propellers, leftArm);
            AizEndBossPropellerChild rightPropeller = propellerForArm(propellers, rightArm);
            return new AizEndBossGraph(
                    boss,
                    only(objectManager, AizEndBossShipChild.class),
                    only(objectManager, AizEndBossFlameColumnChild.class),
                    leftArm,
                    rightArm,
                    leftPropeller,
                    rightPropeller,
                    null,
                    null,
                    null);
        }

        Map<Class<?>, Integer> counts() {
            Map<Class<?>, Integer> counts = new LinkedHashMap<>();
            for (ObjectInstance object : objects()) {
                counts.merge(object.getClass(), 1, Integer::sum);
            }
            return counts;
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            var table = objectManager.captureIdentityContext().requireIdentityTable();
            ids.put("boss", table.idFor(boss));
            ids.put("ship", table.idFor(ship));
            ids.put("column", table.idFor(column));
            ids.put("leftArm", table.idFor(leftArm));
            ids.put("rightArm", table.idFor(rightArm));
            ids.put("leftPropeller", table.idFor(leftPropeller));
            ids.put("rightPropeller", table.idFor(rightPropeller));
            ids.put("flame", table.idFor(flame));
            ids.put("bomb", table.idFor(bomb));
            ids.put("smoke", table.idFor(smoke));
            return ids;
        }

        void removeDynamicChildren(ObjectManager objectManager) {
            for (ObjectInstance object : dynamicChildren()) {
                objectManager.removeDynamicObject(object);
            }
        }

        private List<ObjectInstance> objects() {
            return List.of(boss, ship, column, leftArm, rightArm, leftPropeller, rightPropeller,
                    flame, bomb, smoke);
        }

        private List<ObjectInstance> dynamicChildren() {
            return List.of(ship, column, leftArm, rightArm, leftPropeller, rightPropeller,
                    flame, bomb, smoke);
        }
    }

    private static void spawnPropellersViaArmInit(ObjectManager objectManager, AizEndBossInstance boss) {
        boss.getState().lastUpdatedVIntRunCount = 1;
        armBySubtype(liveObjects(objectManager, AizEndBossArmChild.class), 0)
                .update(1, null);
        armBySubtype(liveObjects(objectManager, AizEndBossArmChild.class), 1)
                .update(1, null);
        boss.getState().lastUpdatedVIntRunCount = 2;
        for (AizEndBossPropellerChild propeller :
                liveObjects(objectManager, AizEndBossPropellerChild.class)) {
            propeller.update(2, null);
        }
    }

    private static void seedBossScalars(AizEndBossInstance boss) {
        setIntField(boss, "angle", 4);
        setIntField(boss, "flags38", 0x0A);
        setIntField(boss, "waitTimer", 0x35);
        setObjectField(boss, "waitCallback", enumConstant(boss, "WaitCallback", "BEGIN_RETREAT"));
        setBooleanField(boss, "renderActivated", true);
    }

    private static AizEndBossArmChild armBySubtype(List<AizEndBossArmChild> arms, int subtype) {
        List<AizEndBossArmChild> matches = arms.stream()
                .filter(arm -> readIntField(arm, "subtype") == subtype)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one AIZ end-boss arm subtype " + subtype);
        return matches.getFirst();
    }

    private static AizEndBossPropellerChild propellerForArm(
            List<AizEndBossPropellerChild> propellers,
            AizEndBossArmChild arm) {
        List<AizEndBossPropellerChild> matches = propellers.stream()
                .filter(propeller -> readObjectField(propeller, "arm") == arm)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one propeller for arm subtype "
                + readIntField(arm, "subtype"));
        return matches.getFirst();
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
                .sorted(Comparator.comparingInt(ObjectInstance::getX)
                        .thenComparingInt(ObjectInstance::getY)
                        .thenComparingInt(System::identityHashCode))
                .toList();
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            return field.getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
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

    private static void invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + methodName + " on " + target.getClass(), e);
        }
    }

    private static void invokeBooleanArg(Object target, String methodName, boolean value) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, boolean.class);
            method.setAccessible(true);
            method.invoke(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + methodName + " on " + target.getClass(), e);
        }
    }

    private static Object enumConstant(Object outerInstance, String enumSimpleName, String constantName) {
        for (Class<?> nested : outerInstance.getClass().getDeclaredClasses()) {
            if (nested.getSimpleName().equals(enumSimpleName)) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object constant = Enum.valueOf((Class<? extends Enum>) nested.asSubclass(Enum.class), constantName);
                return constant;
            }
        }
        throw new AssertionError("Unable to find enum " + enumSimpleName + " on "
                + outerInstance.getClass());
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

    private static SonicConfigurationService createDefaultConfiguration() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(
                TestSessionOutputPaths.diagnostics("rewind")
                        .resolve("rewind-aiz-endboss-graph-config"));
        config.resetToDefaults();
        return config;
    }
}
