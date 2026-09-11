package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kMhzCutsceneGraphRewind {
    private static final String P2_STOPPER_CLASS =
            "com.openggf.game.sonic3k.objects.Mhz1CutsceneKnucklesInstance$Mhz1CutscenePlayerTwoStopper";
    private static final String ROUTE_SWITCH_CLASS =
            "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesRouteSwitchChild";
    private static final String LIFT_CLASS =
            "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesLiftChild";

    private static final ObjectSpawn BUTTON_NEAR_DOOR =
            new ObjectSpawn(0x0180, 0x05B0, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0x31);
    private static final ObjectSpawn BUTTON_CAPTURED =
            new ObjectSpawn(0x0300, 0x05B0, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0x32);
    private static final ObjectSpawn CUTSCENE_KNUCKLES_CAPTURED =
            new ObjectSpawn(0x0608, 0x066C, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0x37);
    private static final ObjectSpawn CUTSCENE_KNUCKLES_PEER_CAPTURED =
            new ObjectSpawn(0x0610, 0x066C, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0, 0, false, 0x38);
    private static final ObjectSpawn OWNER_NEAR_STOPPER =
            new ObjectSpawn(0x0100, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0x33);
    private static final ObjectSpawn OWNER_CAPTURED =
            new ObjectSpawn(0x0180, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0x34);
    private static final ObjectSpawn MHZ2_PARENT_DISTRACTOR =
            new ObjectSpawn(0x0200, 0x0680, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0x35);
    private static final ObjectSpawn MHZ2_PARENT_CAPTURED =
            new ObjectSpawn(0x02D8, 0x0680, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0x36);

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void mhzCutsceneHelpersRestoreFreshWithCapturedParentRefsAndScalars() {
        Harness harness = Harness.create(List.of(
                BUTTON_NEAR_DOOR,
                BUTTON_CAPTURED,
                OWNER_NEAR_STOPPER,
                OWNER_CAPTURED,
                MHZ2_PARENT_DISTRACTOR,
                MHZ2_PARENT_CAPTURED));
        ObjectManager objectManager = harness.objectManager();

        Mhz1CutsceneButtonInstance buttonDistractor =
                objectBySpawn(objectManager, Mhz1CutsceneButtonInstance.class, BUTTON_NEAR_DOOR);
        Mhz1CutsceneButtonInstance buttonCaptured =
                objectBySpawn(objectManager, Mhz1CutsceneButtonInstance.class, BUTTON_CAPTURED);
        Mhz1CutsceneKnucklesInstance ownerDistractor =
                objectBySpawn(objectManager, Mhz1CutsceneKnucklesInstance.class, OWNER_NEAR_STOPPER);
        Mhz1CutsceneKnucklesInstance ownerCaptured =
                objectBySpawn(objectManager, Mhz1CutsceneKnucklesInstance.class, OWNER_CAPTURED);
        CutsceneKnucklesMhz2Instance routeParentDistractor =
                objectBySpawn(objectManager, CutsceneKnucklesMhz2Instance.class, MHZ2_PARENT_DISTRACTOR);
        CutsceneKnucklesMhz2Instance routeParentCaptured =
                objectBySpawn(objectManager, CutsceneKnucklesMhz2Instance.class, MHZ2_PARENT_CAPTURED);

        Mhz1CutsceneDoorInstance door = objectManager.createDynamicObject(
                () -> new Mhz1CutsceneDoorInstance(buttonCaptured));
        setObjectField(buttonCaptured, "spawnedDoor", door);
        CutsceneKnucklesMhz1Instance spawnedKnuckles = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesMhz1Instance(CUTSCENE_KNUCKLES_CAPTURED, buttonCaptured));
        setObjectField(buttonCaptured, "spawnedKnuckles", spawnedKnuckles);
        CutsceneKnucklesMhz1PeerInstance peer = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesMhz1PeerInstance(
                        CUTSCENE_KNUCKLES_PEER_CAPTURED, spawnedKnuckles));
        AbstractObjectInstance stopper = objectManager.createDynamicObject(
                () -> newChild(P2_STOPPER_CLASS, Mhz1CutsceneKnucklesInstance.class, ownerCaptured));
        AbstractObjectInstance routeSwitch = objectManager.createDynamicObject(
                () -> newChild(ROUTE_SWITCH_CLASS, CutsceneKnucklesMhz2Instance.class, routeParentCaptured));

        setDoorState(door, 0x03A4, 0x05E8, -0x0100, "MOVING", 23);
        setBooleanField(stopper, "locked", true);
        setBooleanField(routeSwitch, "knucklesRoute", true);

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId buttonDistractorId = requireId(captureTable, buttonDistractor);
        ObjectRefId buttonCapturedId = requireId(captureTable, buttonCaptured);
        ObjectRefId ownerDistractorId = requireId(captureTable, ownerDistractor);
        ObjectRefId ownerCapturedId = requireId(captureTable, ownerCaptured);
        ObjectRefId routeParentDistractorId = requireId(captureTable, routeParentDistractor);
        ObjectRefId routeParentCapturedId = requireId(captureTable, routeParentCaptured);
        ObjectRefId doorId = requireId(captureTable, door);
        ObjectRefId spawnedKnucklesId = requireId(captureTable, spawnedKnuckles);
        ObjectRefId peerId = requireId(captureTable, peer);
        ObjectRefId stopperId = requireId(captureTable, stopper);
        ObjectRefId routeSwitchId = requireId(captureTable, routeSwitch);

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(door);
        objectManager.removeDynamicObject(spawnedKnuckles);
        objectManager.removeDynamicObject(peer);
        objectManager.removeDynamicObject(stopper);
        objectManager.removeDynamicObject(routeSwitch);
        Mhz1CutsceneButtonInstance divergentButton = objectManager.createDynamicObject(
                () -> new Mhz1CutsceneButtonInstance(new ObjectSpawn(
                        0x0390, 0x05B0, Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON, 0, 0, false, 0x41)));
        Mhz1CutsceneDoorInstance divergentDoor = objectManager.createDynamicObject(
                () -> new Mhz1CutsceneDoorInstance(divergentButton));
        setObjectField(divergentButton, "spawnedDoor", divergentDoor);
        CutsceneKnucklesMhz1Instance divergentSpawnedKnuckles = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesMhz1Instance(new ObjectSpawn(
                        0x0388, 0x066C, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x1C, 0, false, 0x44),
                        divergentButton));
        setObjectField(divergentButton, "spawnedKnuckles", divergentSpawnedKnuckles);
        CutsceneKnucklesMhz1PeerInstance divergentPeer = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesMhz1PeerInstance(new ObjectSpawn(
                        0x0398, 0x066C, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0, 0, false, 0x45),
                        divergentSpawnedKnuckles));
        Mhz1CutsceneKnucklesInstance divergentOwner = objectManager.createDynamicObject(
                () -> new Mhz1CutsceneKnucklesInstance(new ObjectSpawn(
                        0x0080, 0x0580, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0x42)));
        AbstractObjectInstance divergentStopper = objectManager.createDynamicObject(
                () -> newChild(P2_STOPPER_CLASS, Mhz1CutsceneKnucklesInstance.class, divergentOwner));
        CutsceneKnucklesMhz2Instance divergentRouteParent = objectManager.createDynamicObject(
                () -> new CutsceneKnucklesMhz2Instance(new ObjectSpawn(
                        0x0400, 0x0680, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0x20, 0, false, 0x43)));
        AbstractObjectInstance divergentRouteSwitch = objectManager.createDynamicObject(
                () -> newChild(ROUTE_SWITCH_CLASS, CutsceneKnucklesMhz2Instance.class, divergentRouteParent));

        registry.restore(snapshot);

        assertEquals(2, countLive(objectManager, Mhz1CutsceneButtonInstance.class),
                "restore must keep the captured MHZ1 cutscene buttons without duplicate doors");
        assertEquals(2, countLive(objectManager, Mhz1CutsceneKnucklesInstance.class),
                "restore must keep the captured MHZ1 cutscene owners without duplicate stoppers");
        assertEquals(2, countLive(objectManager, CutsceneKnucklesMhz2Instance.class),
                "restore must keep the captured MHZ2 cutscene parents without duplicate route switches");
        assertEquals(1, countLive(objectManager, Mhz1CutsceneDoorInstance.class),
                "restore must recreate exactly one captured MHZ1 cutscene door");
        assertEquals(1, countLive(objectManager, CutsceneKnucklesMhz1Instance.class),
                "restore must recreate exactly one captured MHZ1 spawned Knuckles actor");
        assertEquals(1, countLive(objectManager, CutsceneKnucklesMhz1PeerInstance.class),
                "restore must recreate exactly one captured MHZ1 peering Knuckles child");
        assertEquals(1, countLive(objectManager, childClass(P2_STOPPER_CLASS)),
                "restore must recreate exactly one captured P2 stopper");
        assertEquals(1, countLive(objectManager, childClass(ROUTE_SWITCH_CLASS)),
                "restore must recreate exactly one captured route-switch child");

        Mhz1CutsceneButtonInstance restoredButton =
                objectById(objectManager, Mhz1CutsceneButtonInstance.class, buttonCapturedId);
        Mhz1CutsceneKnucklesInstance restoredOwner =
                objectById(objectManager, Mhz1CutsceneKnucklesInstance.class, ownerCapturedId);
        CutsceneKnucklesMhz2Instance restoredRouteParent =
                objectById(objectManager, CutsceneKnucklesMhz2Instance.class, routeParentCapturedId);
        Mhz1CutsceneDoorInstance restoredDoor =
                objectById(objectManager, Mhz1CutsceneDoorInstance.class, doorId);
        CutsceneKnucklesMhz1Instance restoredSpawnedKnuckles =
                objectById(objectManager, CutsceneKnucklesMhz1Instance.class, spawnedKnucklesId);
        CutsceneKnucklesMhz1PeerInstance restoredPeer =
                objectById(objectManager, CutsceneKnucklesMhz1PeerInstance.class, peerId);
        AbstractObjectInstance restoredStopper =
                objectById(objectManager, childClass(P2_STOPPER_CLASS), stopperId);
        AbstractObjectInstance restoredRouteSwitch =
                objectById(objectManager, childClass(ROUTE_SWITCH_CLASS), routeSwitchId);

        assertNotSame(door, restoredDoor, "door must be recreated, not reused stale");
        assertNotSame(spawnedKnuckles, restoredSpawnedKnuckles,
                "spawned Knuckles must be recreated, not reused stale");
        assertNotSame(peer, restoredPeer, "peer child must be recreated, not reused stale");
        assertNotSame(stopper, restoredStopper, "P2 stopper must be recreated, not reused stale");
        assertNotSame(routeSwitch, restoredRouteSwitch, "route switch must be recreated, not reused stale");
        assertNotSame(divergentDoor, restoredDoor, "restore must drop divergent door");
        assertNotSame(divergentSpawnedKnuckles, restoredSpawnedKnuckles,
                "restore must drop divergent spawned Knuckles");
        assertNotSame(divergentPeer, restoredPeer, "restore must drop divergent peer child");
        assertNotSame(divergentStopper, restoredStopper, "restore must drop divergent P2 stopper");
        assertNotSame(divergentRouteSwitch, restoredRouteSwitch, "restore must drop divergent route switch");

        assertSame(restoredButton, readObjectField(restoredDoor, "parent"),
                "door parent must relink to the captured restored button, not the nearest/first live button");
        assertSame(restoredDoor, readObjectField(restoredButton, "spawnedDoor"),
                "button spawnedDoor must relink to the captured restored door");
        assertSame(restoredSpawnedKnuckles, readObjectField(restoredButton, "spawnedKnuckles"),
                "button spawnedKnuckles must relink to the captured restored Knuckles actor");
        assertSame(restoredButton, readObjectField(restoredSpawnedKnuckles, "parentButton"),
                "spawned Knuckles parentButton must relink to the captured restored button");
        assertSame(restoredSpawnedKnuckles, readObjectField(restoredPeer, "parent"),
                "peer parent must relink to the captured restored Knuckles actor");
        assertSame(restoredOwner, readObjectField(restoredStopper, "owner"),
                "P2 stopper owner must relink to the captured restored Knuckles owner");
        assertSame(restoredRouteParent, readObjectField(restoredRouteSwitch, "parent"),
                "route-switch child parent must relink to the captured restored MHZ2 cutscene parent");

        assertDoorState(restoredDoor, 0x03A4, 0x05E8, -0x0100, "MOVING", 23);
        assertTrue(readBooleanField(restoredStopper, "locked"),
                "P2 stopper locked scalar must restore exactly");
        assertTrue(readBooleanField(restoredRouteSwitch, "knucklesRoute"),
                "route-switch knucklesRoute scalar must restore exactly");

        assertNotNull(objectById(objectManager, Mhz1CutsceneButtonInstance.class, buttonDistractorId));
        assertNotNull(objectById(objectManager, Mhz1CutsceneKnucklesInstance.class, ownerDistractorId));
        assertNotNull(objectById(objectManager, CutsceneKnucklesMhz2Instance.class, routeParentDistractorId));
    }

    @Test
    void mhz2LiftChildRestoresFreshWithCapturedPlayerReference() {
        TestablePlayableSprite capturedPlayer = player("old-sonic", 0x03D8, 0x0680);
        Harness harness = Harness.create(List.of(MHZ2_PARENT_CAPTURED), capturedPlayer, List.of());
        ObjectManager objectManager = harness.objectManager();
        CutsceneKnucklesMhz2Instance parent =
                objectBySpawn(objectManager, CutsceneKnucklesMhz2Instance.class, MHZ2_PARENT_CAPTURED);
        AbstractObjectInstance lift = objectManager.createDynamicObject(
                () -> newLiftChild(parent, capturedPlayer));
        setBooleanField(lift, "initialized", true);

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId parentId = requireId(captureTable, parent);
        ObjectRefId liftId = requireId(captureTable, lift);
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(lift);
        TestablePlayableSprite divergentPlayer = player("divergent-sonic", 0x0400, 0x0690);
        AbstractObjectInstance divergentLift = objectManager.createDynamicObject(
                () -> newLiftChild(parent, divergentPlayer));
        TestablePlayableSprite restoredPlayer = player("new-sonic", 0x03E0, 0x0670);
        harness.setPlayers(restoredPlayer, List.of());

        registry.restore(snapshot);

        CutsceneKnucklesMhz2Instance restoredParent =
                objectById(objectManager, CutsceneKnucklesMhz2Instance.class, parentId);
        AbstractObjectInstance restoredLift = objectById(objectManager, childClass(LIFT_CLASS), liftId);
        assertEquals(1, countLive(objectManager, childClass(LIFT_CLASS)),
                "restore must recreate exactly one captured MHZ2 lift child");
        assertNotSame(lift, restoredLift, "lift child must be recreated, not reused stale");
        assertNotSame(divergentLift, restoredLift, "restore must drop divergent lift child");
        assertSame(restoredParent, readObjectField(restoredLift, "parent"),
                "lift child parent must relink to the restored MHZ2 cutscene parent");
        assertSame(restoredPlayer, readObjectField(restoredLift, "player"),
                "lift child player must resolve through the current live player identity");
        assertTrue(readBooleanField(restoredLift, "initialized"),
                "lift child initialized scalar must restore exactly");
    }

    @Test
    void mhzCutsceneHelpersUseRewindRecreatableWithoutExplicitDynamicCodecs() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Mhz1CutsceneDoorInstance.class),
                "MHZ1 cutscene door must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Mhz1CutsceneButtonInstance.class),
                "MHZ1 cutscene button must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CutsceneKnucklesMhz1Instance.class),
                "MHZ1 spawned Knuckles actor must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CutsceneKnucklesMhz1PeerInstance.class),
                "MHZ1 peering Knuckles child must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(childClass(P2_STOPPER_CLASS)),
                "MHZ1 P2 stopper must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(childClass(ROUTE_SWITCH_CLASS)),
                "MHZ2 route-switch child must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(childClass(LIFT_CLASS)),
                "MHZ2 lift child must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Mhz1CutsceneDoorInstance.class.getName()),
                "MHZ1 cutscene door must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Mhz1CutsceneButtonInstance.class.getName()),
                "MHZ1 cutscene button must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        CutsceneKnucklesMhz1Instance.class.getName()),
                "MHZ1 spawned Knuckles actor must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        CutsceneKnucklesMhz1PeerInstance.class.getName()),
                "MHZ1 peering Knuckles child must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(P2_STOPPER_CLASS),
                "MHZ1 P2 stopper must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(ROUTE_SWITCH_CLASS),
                "MHZ2 route-switch child must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(LIFT_CLASS),
                "MHZ2 lift child must not keep an explicit S3K dynamic codec");
    }

    @Test
    void capturedMhzCutsceneChildFailsLoudlyWhenParentHasNoRewindIdentity() {
        assertMissingReferenceFails(() -> {
            Harness harness = Harness.create();
            Mhz1CutsceneButtonInstance unmanaged = new Mhz1CutsceneButtonInstance(BUTTON_CAPTURED);
            unmanaged.setServices(harness.services());
            harness.objectManager().createDynamicObject(() -> new Mhz1CutsceneDoorInstance(unmanaged));
            return harness.objectManager();
        });

        assertMissingReferenceFails(() -> {
            Harness harness = Harness.create(List.of(BUTTON_CAPTURED));
            Mhz1CutsceneButtonInstance button =
                    objectBySpawn(harness.objectManager(), Mhz1CutsceneButtonInstance.class, BUTTON_CAPTURED);
            CutsceneKnucklesMhz1Instance unmanaged =
                    new CutsceneKnucklesMhz1Instance(CUTSCENE_KNUCKLES_CAPTURED, button);
            unmanaged.setServices(harness.services());
            setObjectField(button, "spawnedKnuckles", unmanaged);
            return harness.objectManager();
        });

        assertMissingReferenceFails(() -> {
            Harness harness = Harness.create();
            Mhz1CutsceneButtonInstance unmanaged = new Mhz1CutsceneButtonInstance(BUTTON_CAPTURED);
            unmanaged.setServices(harness.services());
            harness.objectManager().createDynamicObject(
                    () -> new CutsceneKnucklesMhz1Instance(CUTSCENE_KNUCKLES_CAPTURED, unmanaged));
            return harness.objectManager();
        });

        assertMissingReferenceFails(() -> {
            Harness harness = Harness.create();
            CutsceneKnucklesMhz1Instance unmanaged =
                    new CutsceneKnucklesMhz1Instance(CUTSCENE_KNUCKLES_CAPTURED);
            unmanaged.setServices(harness.services());
            harness.objectManager().createDynamicObject(
                    () -> new CutsceneKnucklesMhz1PeerInstance(
                            CUTSCENE_KNUCKLES_PEER_CAPTURED, unmanaged));
            return harness.objectManager();
        });

        assertMissingReferenceFails(() -> {
            Harness harness = Harness.create();
            Mhz1CutsceneKnucklesInstance unmanaged = new Mhz1CutsceneKnucklesInstance(OWNER_CAPTURED);
            unmanaged.setServices(harness.services());
            harness.objectManager().createDynamicObject(
                    () -> newChild(P2_STOPPER_CLASS, Mhz1CutsceneKnucklesInstance.class, unmanaged));
            return harness.objectManager();
        });

        assertMissingReferenceFails(() -> {
            Harness harness = Harness.create();
            CutsceneKnucklesMhz2Instance unmanaged = new CutsceneKnucklesMhz2Instance(MHZ2_PARENT_CAPTURED);
            unmanaged.setServices(harness.services());
            harness.objectManager().createDynamicObject(
                    () -> newChild(ROUTE_SWITCH_CLASS, CutsceneKnucklesMhz2Instance.class, unmanaged));
            return harness.objectManager();
        });
    }

    @Test
    void capturedMhz2LiftChildFailsLoudlyWhenPlayerMissingOnRestore() {
        TestablePlayableSprite capturedPlayer = player("old-sonic", 0x03D8, 0x0680);
        Harness harness = Harness.create(List.of(MHZ2_PARENT_CAPTURED), capturedPlayer, List.of());
        CutsceneKnucklesMhz2Instance parent =
                objectBySpawn(harness.objectManager(), CutsceneKnucklesMhz2Instance.class, MHZ2_PARENT_CAPTURED);
        harness.objectManager().createDynamicObject(() -> newLiftChild(parent, capturedPlayer));
        RewindRegistry registry = new RewindRegistry();
        registry.register(harness.objectManager().rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        harness.setPlayers(null, List.of());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> registry.restore(snapshot));
        assertTrue(thrown.getMessage().contains("Missing required player reference"),
                "missing live player identity must fail loudly");
    }

    private record Harness(ObjectManager objectManager, StubObjectServices services, TestCamera camera) {
        static Harness create() {
            return create(List.of());
        }

        static Harness create(List<ObjectSpawn> spawns) {
            return create(spawns, null, List.of());
        }

        static Harness create(
                List<ObjectSpawn> spawns,
                AbstractPlayableSprite focusedPlayer,
                List<? extends PlayableEntity> sidekicks) {
            ObjectManager[] holder = new ObjectManager[1];
            int cameraX = initialCameraX(spawns);
            TestCamera camera = new TestCamera(cameraX);
            camera.setFocusedSprite(focusedPlayer);
            List<? extends PlayableEntity> copiedSidekicks = List.copyOf(sidekicks);
            StubObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public List<PlayableEntity> sidekicks() { return List.copyOf(copiedSidekicks); }
                @Override public ObjectPlayerQuery playerQuery() {
                    return new ObjectPlayerQuery(camera::getFocusedSprite, () -> copiedSidekicks);
                }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public int romZoneId() { return Sonic3kZoneIds.ZONE_MHZ; }
                @Override public int featureZoneId() { return Sonic3kZoneIds.ZONE_MHZ; }
            };
            services.zoneRuntimeRegistry().install(new MhzZoneRuntimeState(0, PlayerCharacter.SONIC_AND_TAILS));
            ObjectManager objectManager = new ObjectManager(
                    spawns, new MhzTestRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(cameraX);
            objectManager.setRewindInPlaceRestoreEnabledForTest(false);
            return new Harness(objectManager, services, camera);
        }

        void setPlayers(AbstractPlayableSprite focusedPlayer, List<? extends PlayableEntity> sidekicks) {
            camera.setFocusedSprite(focusedPlayer);
        }
    }

    private static final class TestCamera extends Camera {
        private final short x;
        private AbstractPlayableSprite focusedSprite;

        private TestCamera(int x) {
            this.x = (short) x;
        }

        @Override public void setFocusedSprite(AbstractPlayableSprite sprite) { focusedSprite = sprite; }
        @Override public AbstractPlayableSprite getFocusedSprite() { return focusedSprite; }
        @Override public short getX() { return x; }
        @Override public short getY() { return 0; }
        @Override public short getWidth() { return 0x1000; }
        @Override public short getHeight() { return 0x1000; }
        @Override public boolean isVerticalWrapEnabled() { return false; }
    }

    private static int initialCameraX(List<ObjectSpawn> spawns) {
        return spawns.stream().mapToInt(ObjectSpawn::x).min().orElse(0) & 0xFF80;
    }

    private static final class MhzTestRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_MHZ;
        }
    }

    @FunctionalInterface
    private interface ObjectManagerFactory {
        ObjectManager create();
    }

    private static void assertMissingReferenceFails(ObjectManagerFactory factory) {
        ObjectManager objectManager = factory.create();
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, registry::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "capturing an unmanaged parent/owner reference must fail loudly");
    }

    private static void setDoorState(Mhz1CutsceneDoorInstance door, int x, int y, int yVel,
            String state, int waitTimer) {
        Object motion = readObjectField(door, "motion");
        setIntField(motion, "x", x);
        setIntField(motion, "y", y);
        setIntField(motion, "yVel", yVel);
        setEnumField(door, "state", state);
        setIntField(door, "waitTimer", waitTimer);
    }

    private static void assertDoorState(Mhz1CutsceneDoorInstance door, int x, int y, int yVel,
            String state, int waitTimer) {
        Object motion = readObjectField(door, "motion");
        assertEquals(x, readIntField(motion, "x"), "door motion.x must restore exactly");
        assertEquals(y, readIntField(motion, "y"), "door motion.y must restore exactly");
        assertEquals(yVel, readIntField(motion, "yVel"), "door motion.yVel must restore exactly");
        assertEquals(state, String.valueOf(readObjectField(door, "state")),
                "door state must restore exactly");
        assertEquals(waitTimer, readIntField(door, "waitTimer"),
                "door waitTimer must restore exactly");
    }

    private static AbstractObjectInstance newChild(String className, Class<?> parentType, Object parent) {
        try {
            Class<? extends AbstractObjectInstance> type = childClass(className);
            Constructor<? extends AbstractObjectInstance> ctor = type.getDeclaredConstructor(parentType);
            ctor.setAccessible(true);
            return ctor.newInstance(parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct " + className, e);
        }
    }

    private static AbstractObjectInstance newLiftChild(
            CutsceneKnucklesMhz2Instance parent,
            AbstractPlayableSprite player) {
        try {
            Class<? extends AbstractObjectInstance> type = childClass(LIFT_CLASS);
            Constructor<? extends AbstractObjectInstance> ctor =
                    type.getDeclaredConstructor(CutsceneKnucklesMhz2Instance.class, AbstractPlayableSprite.class);
            ctor.setAccessible(true);
            return ctor.newInstance(parent, player);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct " + LIFT_CLASS, e);
        }
    }

    private static TestablePlayableSprite player(String code, int x, int y) {
        return new TestablePlayableSprite(code, (short) x, (short) y);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends AbstractObjectInstance> childClass(String className) {
        try {
            return (Class<? extends AbstractObjectInstance>) Class.forName(className)
                    .asSubclass(AbstractObjectInstance.class);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static int countLive(ObjectManager objectManager, Class<?> type) {
        return (int) objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .count();
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object.getClass() == type && id.equals(table.idFor(object))) {
                return type.cast(object);
            }
        }
        throw new AssertionError("No live " + type.getName() + " with id " + id);
    }

    private static <T extends ObjectInstance> T objectBySpawn(
            ObjectManager objectManager, Class<T> type, ObjectSpawn spawn) {
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object.getClass() == type && spawn.equals(object.getSpawn())) {
                return type.cast(object);
            }
        }
        throw new AssertionError("No live " + type.getName() + " with spawn " + spawn);
    }

    private static ObjectRefId requireId(RewindIdentityTable table, ObjectInstance object) {
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass().getName());
        return id;
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
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

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
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
    private static void setEnumField(Object target, String fieldName, String value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            Class<? extends Enum> enumType = (Class<? extends Enum>) field.getType().asSubclass(Enum.class);
            field.set(target, Enum.valueOf(enumType, value));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write enum " + fieldName + " on " + target.getClass(), e);
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

}
