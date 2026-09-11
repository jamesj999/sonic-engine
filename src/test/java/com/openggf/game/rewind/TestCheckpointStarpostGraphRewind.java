package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1LamppostObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LamppostTwirlInstance;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.CheckpointDongleInstance;
import com.openggf.game.sonic2.objects.CheckpointObjectInstance;
import com.openggf.game.sonic2.objects.CheckpointStarInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.objects.Sonic3kStarPostBonusStarChild;
import com.openggf.game.sonic3k.objects.Sonic3kStarPostObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kStarPostStarChild;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
class TestCheckpointStarpostGraphRewind {

    private static final ObjectSpawn S1_FAR_LAMP =
            new ObjectSpawn(0x0040, 0x0120, Sonic1ObjectIds.LAMPPOST, 0, 0, false, 10);
    private static final ObjectSpawn S1_NEAR_LAMP =
            new ObjectSpawn(0x0100, 0x0180, Sonic1ObjectIds.LAMPPOST, 1, 0, false, 11);
    private static final ObjectSpawn S2_FAR_CHECKPOINT =
            new ObjectSpawn(0x0050, 0x0130, Sonic2ObjectIds.CHECKPOINT, 0, 0, false, 20);
    private static final ObjectSpawn S2_NEAR_CHECKPOINT =
            new ObjectSpawn(0x0120, 0x0170, Sonic2ObjectIds.CHECKPOINT, 1, 0, false, 21);
    private static final ObjectSpawn S3K_FAR_STARPOST =
            new ObjectSpawn(0x0060, 0x0120, Sonic3kObjectIds.STAR_POST, 0, 0, false, 30);
    private static final ObjectSpawn S3K_NEAR_STARPOST =
            new ObjectSpawn(0x0140, 0x0190, Sonic3kObjectIds.STAR_POST, 1, 0, false, 31);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void sonic1LamppostTwirlRestoresFreshAndRelinksNearestParent() {
        Harness harness = Harness.create(new Sonic1ObjectRegistry(), List.of(S1_FAR_LAMP, S1_NEAR_LAMP));
        ObjectManager objectManager = harness.objectManager();
        Sonic1LamppostObjectInstance nearParent =
                liveParentAt(objectManager, Sonic1LamppostObjectInstance.class, S1_NEAR_LAMP.x());
        Sonic1LamppostObjectInstance farParent =
                liveParentAt(objectManager, Sonic1LamppostObjectInstance.class, S1_FAR_LAMP.x());
        Sonic1LamppostTwirlInstance before = objectManager.createDynamicObject(
                () -> new Sonic1LamppostTwirlInstance(nearParent));
        setIntField(before, "lifetime", 0);
        setIntField(before, "angle", 0x70);
        setIntField(before, "currentX", 0x02B0);
        setIntField(before, "currentY", 0x01C0);

        ObjectRefId beforeId = objectId(objectManager, before);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(before);
        Sonic1LamppostTwirlInstance replacement = objectManager.createDynamicObject(
                () -> new Sonic1LamppostTwirlInstance(farParent));

        rewindRegistry.restore(snapshot);

        Sonic1LamppostTwirlInstance restored = only(objectManager, Sonic1LamppostTwirlInstance.class);
        Sonic1LamppostObjectInstance restoredNearParent =
                liveParentAt(objectManager, Sonic1LamppostObjectInstance.class, S1_NEAR_LAMP.x());
        assertNotSame(before, restored, "restore must recreate the removed S1 twirl");
        assertNotSame(replacement, restored, "restore must drop unrelated post-snapshot S1 twirls");
        assertEquals(beforeId, objectId(objectManager, restored), "S1 twirl rewind identity must be preserved");
        assertSame(restoredNearParent, readObjectField(restored, "parent"),
                "S1 twirl must relink to the nearest live lamppost, not the first live parent");
        assertScalarFieldsEqual(before, restored,
                "centerX", "centerY", "lifetime", "angle", "currentX", "currentY", "finished");

        int mappingFrameBeforeCompletion = readIntField(restoredNearParent, "mappingFrame");
        restored.update(0, null);
        assertTrue((Boolean) readObjectField(restored, "finished"),
                "near-expiry restored S1 twirl must continue to completion");
        // ROM Lamp_Finish (79 Lamppost.asm:122-123) is a plain rts: the pole's own
        // mapping frame is never touched by the twirl completing, so it must be
        // unchanged here too -- only the twirlActive bookkeeping flag changes.
        // (A prior version of onTwirlComplete() forced mappingFrame to FRAME_RED,
        // which produced a second, wrongly-centered ball stacked next to the
        // twirl's own frozen orbit ball -- the "lamppost head duplicated and
        // X-offset" bug; see Sonic1LamppostObjectInstance.onTwirlComplete().)
        assertFalse((Boolean) readObjectField(restoredNearParent, "twirlActive"),
                "restored S1 twirl must call back into the relinked lamppost on expiry");
        assertEquals(mappingFrameBeforeCompletion, readIntField(restoredNearParent, "mappingFrame"),
                "relinked lamppost pole's mapping frame must be untouched by twirl completion, matching ROM's Lamp_Finish no-op");
    }

    /**
     * Bug repro (S1 bug-triage row 5, docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md):
     * "Lamppost head sometimes stops duplicated and X-offset." Drives a real
     * activation through {@link Sonic1LamppostObjectInstance#update} (not direct
     * field pokes) so the twirl child spawns exactly as gameplay would trigger
     * it, then runs it all the way through completion and asserts there is
     * exactly one visible ball -- the twirl's own, resting near (within its
     * {@code SWING_RADIUS}) the parent's X -- rather than a second, exactly
     * centered ball from the pole itself re-showing a ball frame.
     */
    @Test
    void sonic1LamppostTwirlEndsAsOneCenteredBallNotADuplicate() {
        // CheckpointState.saveCheckpoint() (invoked by Sonic1LamppostObjectInstance.activate())
        // reads GameServices.camera() directly, so a real gameplay-mode session must be
        // active -- the mockCameraAtOrigin()-based Harness the other tests in this class
        // use doesn't wire up GameServices at all.
        com.openggf.game.session.EngineServices.configure(
                com.openggf.game.session.EngineContext.fromLegacySingletonsForBootstrap());
        com.openggf.tests.TestEnvironment.activeGameplayMode();
        try {
            ObjectSpawn lampSpawn = new ObjectSpawn(0x0100, 0x0180, Sonic1ObjectIds.LAMPPOST, 1, 0, false, 40);
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = com.openggf.game.GameServices.camera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public com.openggf.game.RespawnState checkpointState() { return new com.openggf.game.CheckpointState(); }
                @Override public com.openggf.level.WaterSystem waterSystem() { return new com.openggf.level.WaterSystem(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(lampSpawn), new Sonic1ObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(0);

            runLamppostTwirlToCompletionAndAssertNoDuplicateBall(objectManager, lampSpawn);
        } finally {
            com.openggf.game.session.SessionManager.clear();
        }
    }

    private void runLamppostTwirlToCompletionAndAssertNoDuplicateBall(
            ObjectManager objectManager, ObjectSpawn lampSpawn) {

        Sonic1LamppostObjectInstance lamppost =
                liveParentAt(objectManager, Sonic1LamppostObjectInstance.class, lampSpawn.x());
        // Player centered exactly on the lamppost: dx=0, dy=0 satisfies both
        // ROM activation-zone checks (79 Lamppost.asm:76-86).
        com.openggf.tests.TestablePlayableSprite player =
                new com.openggf.tests.TestablePlayableSprite("sonic", (short) lampSpawn.x(), (short) lampSpawn.y());

        // Drive well past the twirl's full lifetime (33 invocations of the
        // .twirl position code -- lamp_time starts at 32 and counts down to
        // -1 with a bpl check, and the transition invocation itself still
        // falls through into .twirl, 79 Lamppost.asm:105,126-134) so it
        // reaches Lamp_Finish and freezes.
        for (int frame = 0; frame < 50; frame++) {
            objectManager.update(0, player, List.of(), frame, false);
        }

        assertEquals(1, readIntField(lamppost, "mappingFrame"),
                "activated lamppost pole must stay pole-only (frame 1) -- ROM never re-shows a ball on the pole itself");
        List<Sonic1LamppostTwirlInstance> twirls = liveObjects(objectManager, Sonic1LamppostTwirlInstance.class);
        assertEquals(1, twirls.size(), "exactly one twirl/ball visual must exist for a single activated lamppost");
        Sonic1LamppostTwirlInstance twirl = twirls.getFirst();
        assertTrue((Boolean) readObjectField(twirl, "finished"), "twirl must have completed its orbit by frame 50");
        // ROM's 33rd (final) Lamp_Twirl invocation is phase-identical to its 1st
        // (obAngle steps by -0x10 each call and 32 is an exact multiple of the
        // 16-step/revolution cycle), so CalcSine's input lands on the exact
        // 0xC0 LUT boundary: cos(0xC0)=0, sin(0xC0)=-1 -> offset (0, -12) from
        // the twirl center (79 Lamppost.asm:126-144). Assert the exact ROM
        // terminal offset, not just "within the swing radius" -- that looser
        // bound previously masked a one-step-too-many off-by-one that left the
        // ball resting ~4.6px left and ~0.9px short of directly overhead.
        int centerX = readIntField(twirl, "centerX");
        int centerY = readIntField(twirl, "centerY");
        assertEquals(centerX, readIntField(twirl, "currentX"),
                "terminal twirl ball must land with zero X offset from the twirl center (ROM CalcSine input 0xC0 -> cos=0)");
        assertEquals(centerY - 12, readIntField(twirl, "currentY"),
                "terminal twirl ball must rest exactly 12px above the twirl center (ROM CalcSine input 0xC0 -> sin=-1, "
                        + "offset = 0x0C00*sin>>16 = -12)");
    }

    @Test
    void sonic2CheckpointChildrenRestoreFreshAndRelinkExactCenterParent() {
        Harness harness = Harness.create(new Sonic2ObjectRegistry(), List.of(S2_FAR_CHECKPOINT, S2_NEAR_CHECKPOINT));
        ObjectManager objectManager = harness.objectManager();
        CheckpointObjectInstance nearParent =
                liveParentAt(objectManager, CheckpointObjectInstance.class, S2_NEAR_CHECKPOINT.x());
        CheckpointObjectInstance farParent =
                liveParentAt(objectManager, CheckpointObjectInstance.class, S2_FAR_CHECKPOINT.x());
        CheckpointDongleInstance beforeDongle = objectManager.createDynamicObject(
                () -> new CheckpointDongleInstance(nearParent));
        CheckpointStarInstance beforeStar = objectManager.createDynamicObject(
                () -> new CheckpointStarInstance(nearParent, 0x80));
        setIntField(beforeDongle, "lifetime", 1);
        setIntField(beforeDongle, "angle", 0x50);
        setIntField(beforeDongle, "currentX", 0x02C0);
        setIntField(beforeDongle, "currentY", 0x0188);
        setIntField(beforeStar, "angle", 0x0123);
        setIntField(beforeStar, "lifetime", 0x0081);
        setIntField(beforeStar, "animFrame", 5);
        setIntField(beforeStar, "currentX", 0x02D0);
        setIntField(beforeStar, "currentY", 0x0160);
        setIntField(beforeStar, "mappingFrame", 2);
        setBooleanField(beforeStar, "collisionEnabled", true);

        ObjectRefId dongleId = objectId(objectManager, beforeDongle);
        ObjectRefId starId = objectId(objectManager, beforeStar);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(beforeDongle);
        objectManager.removeDynamicObject(beforeStar);
        CheckpointDongleInstance replacementDongle = objectManager.createDynamicObject(
                () -> new CheckpointDongleInstance(farParent));
        CheckpointStarInstance replacementStar = objectManager.createDynamicObject(
                () -> new CheckpointStarInstance(farParent, 0));

        rewindRegistry.restore(snapshot);

        CheckpointDongleInstance restoredDongle = only(objectManager, CheckpointDongleInstance.class);
        CheckpointStarInstance restoredStar = only(objectManager, CheckpointStarInstance.class);
        CheckpointObjectInstance restoredNearParent =
                liveParentAt(objectManager, CheckpointObjectInstance.class, S2_NEAR_CHECKPOINT.x());
        assertNotSame(beforeDongle, restoredDongle, "restore must recreate the removed S2 dongle");
        assertNotSame(beforeStar, restoredStar, "restore must recreate the removed S2 star");
        assertNotSame(replacementDongle, restoredDongle, "restore must drop unrelated post-snapshot dongles");
        assertNotSame(replacementStar, restoredStar, "restore must drop unrelated post-snapshot stars");
        assertEquals(dongleId, objectId(objectManager, restoredDongle), "S2 dongle identity must be preserved");
        assertEquals(starId, objectId(objectManager, restoredStar), "S2 star identity must be preserved");
        assertSame(restoredNearParent, readObjectField(restoredDongle, "parent"),
                "S2 dongle must relink to the checkpoint whose center exactly matches its captured spawn");
        assertSame(restoredNearParent, readObjectField(restoredStar, "parentCheckpoint"),
                "S2 star must relink to the checkpoint whose center exactly matches its captured spawn");
        assertScalarFieldsEqual(beforeDongle, restoredDongle,
                "centerX", "centerY", "lifetime", "angle", "currentX", "currentY");
        assertScalarFieldsEqual(beforeStar, restoredStar,
                "centerX", "centerY", "angle", "lifetime", "animFrame",
                "currentX", "currentY", "mappingFrame", "collisionEnabled");

        restoredDongle.update(0, null);
        assertTrue(restoredDongle.isDestroyed(), "near-expiry restored S2 dongle must finish after update");
        assertEquals(2, readIntField(restoredNearParent, "animId"),
                "restored S2 dongle must call back into the relinked checkpoint on expiry");
    }

    @Test
    void sonic3kStarpostChildrenRestoreFreshAndRelinkNearestParent() {
        Harness harness = Harness.create(new Sonic3kObjectRegistry(), List.of(S3K_FAR_STARPOST, S3K_NEAR_STARPOST));
        ObjectManager objectManager = harness.objectManager();
        Sonic3kStarPostObjectInstance nearParent =
                liveParentAt(objectManager, Sonic3kStarPostObjectInstance.class, S3K_NEAR_STARPOST.x());
        Sonic3kStarPostObjectInstance farParent =
                liveParentAt(objectManager, Sonic3kStarPostObjectInstance.class, S3K_FAR_STARPOST.x());
        Sonic3kStarPostStarChild beforeStar = objectManager.createDynamicObject(
                () -> new Sonic3kStarPostStarChild(nearParent));
        Sonic3kStarPostBonusStarChild beforeBonus = objectManager.createDynamicObject(
                () -> new Sonic3kStarPostBonusStarChild(
                        nearParent, 0x40, Sonic3kStarPostObjectInstance.BonusStarVariant.RED));
        setIntField(beforeStar, "lifetime", 0);
        setIntField(beforeStar, "angle", 0xA0);
        setIntField(beforeStar, "currentX", 0x0300);
        setIntField(beforeStar, "currentY", 0x01A8);
        setIntField(beforeBonus, "angle", 0x0155);
        setIntField(beforeBonus, "lifetime", 0x0082);
        setIntField(beforeBonus, "animFrame", 7);
        setIntField(beforeBonus, "currentX", 0x0310);
        setIntField(beforeBonus, "currentY", 0x0170);
        setIntField(beforeBonus, "mappingFrame", 2);
        setBooleanField(beforeBonus, "collisionEnabled", true);

        ObjectRefId starId = objectId(objectManager, beforeStar);
        ObjectRefId bonusId = objectId(objectManager, beforeBonus);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(beforeStar);
        objectManager.removeDynamicObject(beforeBonus);
        Sonic3kStarPostStarChild replacementStar = objectManager.createDynamicObject(
                () -> new Sonic3kStarPostStarChild(farParent));
        Sonic3kStarPostBonusStarChild replacementBonus = objectManager.createDynamicObject(
                () -> new Sonic3kStarPostBonusStarChild(
                        farParent, 0, Sonic3kStarPostObjectInstance.BonusStarVariant.YELLOW));

        rewindRegistry.restore(snapshot);

        Sonic3kStarPostStarChild restoredStar = only(objectManager, Sonic3kStarPostStarChild.class);
        Sonic3kStarPostBonusStarChild restoredBonus = only(objectManager, Sonic3kStarPostBonusStarChild.class);
        Sonic3kStarPostObjectInstance restoredNearParent =
                liveParentAt(objectManager, Sonic3kStarPostObjectInstance.class, S3K_NEAR_STARPOST.x());
        assertNotSame(beforeStar, restoredStar, "restore must recreate the removed S3K star child");
        assertNotSame(beforeBonus, restoredBonus, "restore must recreate the removed S3K bonus star");
        assertNotSame(replacementStar, restoredStar, "restore must drop unrelated post-snapshot S3K stars");
        assertNotSame(replacementBonus, restoredBonus, "restore must drop unrelated post-snapshot S3K bonus stars");
        assertEquals(starId, objectId(objectManager, restoredStar), "S3K star identity must be preserved");
        assertEquals(bonusId, objectId(objectManager, restoredBonus), "S3K bonus star identity must be preserved");
        assertSame(restoredNearParent, readObjectField(restoredStar, "parent"),
                "S3K star child must relink to the nearest live starpost, not the first live parent");
        assertSame(restoredNearParent, readObjectField(restoredBonus, "parentStarPost"),
                "S3K bonus star must relink to the nearest live starpost, not the first live parent");
        assertScalarFieldsEqual(beforeStar, restoredStar,
                "centerX", "centerY", "lifetime", "angle", "currentX", "currentY");
        assertScalarFieldsEqual(beforeBonus, restoredBonus,
                "variant", "centerX", "centerY", "angle", "lifetime", "animFrame",
                "currentX", "currentY", "mappingFrame", "collisionEnabled");

        restoredStar.update(0, null);
        assertTrue(restoredStar.isDestroyed(), "near-expiry restored S3K star must finish after update");
        assertEquals(2, readIntField(restoredNearParent, "animId"),
                "restored S3K star must call back into the relinked starpost on expiry");
    }

    /**
     * Bug repro (docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md row 5's tracked
     * follow-up): before the bounded relink fix, {@code recreateForRewind} picked
     * whichever live lamppost was geometrically NEAREST at restore time with no
     * distance check, so a twirl whose true parent was torn down before capture (e.g.
     * an off-screen unload or act-transition object-table rebuild) could silently
     * reattach to a completely different, unrelated lamppost still loaded elsewhere in
     * the act -- exactly the "wrong lamppost" scenario the follow-up note describes.
     */
    @Test
    void s1LamppostTwirlDropsWhenTrueParentAbsentEvenWithADifferentLamppostStillLive() {
        // Only the far lamppost is registered with this ObjectManager -- it survives
        // the restore. The true (near) parent is constructed but deliberately never
        // added to the manager, modeling it having been torn down before the twirl's
        // own rewind capture (mirroring missingS1LamppostDropsTwirlCleanly's "gone
        // entirely" case, but with a different, genuinely-live lamppost also present).
        Harness harness = Harness.create(new Sonic1ObjectRegistry(), List.of(S1_FAR_LAMP));
        ObjectManager objectManager = harness.objectManager();
        Sonic1LamppostObjectInstance trueParent = new Sonic1LamppostObjectInstance(S1_NEAR_LAMP);
        Sonic1LamppostTwirlInstance before = objectManager.createDynamicObject(
                () -> new Sonic1LamppostTwirlInstance(trueParent));

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(before);

        rewindRegistry.restore(snapshot);

        assertEquals(0, liveObjects(objectManager, Sonic1LamppostTwirlInstance.class).size(),
                "S1 twirl must drop, not silently adopt the far lamppost, when its true "
                        + "parent is absent at restore -- even though a different lamppost "
                        + "is genuinely live and would otherwise win an unbounded nearest search");
    }

    @Test
    void missingS1LamppostDropsTwirlCleanly() {
        Harness harness = Harness.create(new Sonic1ObjectRegistry(), List.of());
        ObjectManager objectManager = harness.objectManager();
        Sonic1LamppostObjectInstance externalParent = new Sonic1LamppostObjectInstance(S1_NEAR_LAMP);
        Sonic1LamppostTwirlInstance before = objectManager.createDynamicObject(
                () -> new Sonic1LamppostTwirlInstance(externalParent));

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(before);

        rewindRegistry.restore(snapshot);

        assertEquals(0, liveObjects(objectManager, Sonic1LamppostTwirlInstance.class).size(),
                "S1 twirl must be dropped when no live lamppost can be relinked");
    }

    @Test
    void missingS2CheckpointDropsDongleCleanly() {
        Harness harness = Harness.create(new Sonic2ObjectRegistry(), List.of());
        ObjectManager objectManager = harness.objectManager();
        CheckpointObjectInstance externalParent = new CheckpointObjectInstance(S2_NEAR_CHECKPOINT, "Checkpoint");
        CheckpointDongleInstance before = objectManager.createDynamicObject(
                () -> new CheckpointDongleInstance(externalParent));

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(before);

        rewindRegistry.restore(snapshot);

        assertEquals(0, liveObjects(objectManager, CheckpointDongleInstance.class).size(),
                "S2 dongle must be dropped when no live checkpoint can be relinked");
    }

    @Test
    void missingS3kStarpostDropsBonusStarCleanly() {
        Harness harness = Harness.create(new Sonic3kObjectRegistry(), List.of());
        ObjectManager objectManager = harness.objectManager();
        Sonic3kStarPostObjectInstance externalParent = new Sonic3kStarPostObjectInstance(S3K_NEAR_STARPOST);
        Sonic3kStarPostBonusStarChild before = objectManager.createDynamicObject(
                () -> new Sonic3kStarPostBonusStarChild(
                        externalParent, 0x80, Sonic3kStarPostObjectInstance.BonusStarVariant.BLUE));

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(before);

        rewindRegistry.restore(snapshot);

        assertEquals(0, liveObjects(objectManager, Sonic3kStarPostBonusStarChild.class).size(),
                "S3K bonus star must be dropped when no live starpost can be relinked");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(ObjectRegistry registry, List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    registry,
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

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T liveParentAt(
            ObjectManager objectManager,
            Class<T> type,
            int centerX) {
        List<T> matches = liveObjects(objectManager, type).stream()
                .filter(parent -> {
                    if (parent instanceof Sonic1LamppostObjectInstance lamp) {
                        return lamp.getCenterX() == centerX;
                    }
                    if (parent instanceof CheckpointObjectInstance checkpoint) {
                        return checkpoint.getCenterX() == centerX;
                    }
                    if (parent instanceof Sonic3kStarPostObjectInstance starPost) {
                        return starPost.getCenterX() == centerX;
                    }
                    return false;
                })
                .toList();
        assertEquals(1, matches.size(), "expected one live parent " + type.getSimpleName()
                + " at center X " + centerX);
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
                .toList();
    }

    private static void assertScalarFieldsEqual(Object before, Object restored, String... fields) {
        for (String field : fields) {
            assertEquals(readObjectField(before, field), readObjectField(restored, field),
                    () -> before.getClass().getSimpleName() + "." + field + " must restore exactly");
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
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
