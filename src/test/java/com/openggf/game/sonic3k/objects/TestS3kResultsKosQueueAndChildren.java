package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.resources.KosinskiModuleQueue;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kResultsKosQueueAndChildren {

    @Test
    void existingQueueDelaysPublicationUntilThreeActualResultsArchivesAndTwelveSstsExist()
            throws Exception {
        HeadlessTestFixture fixture = fixture();
        KosinskiModuleQueue queue = fixture.gameplayMode().getKosinskiModuleQueue();
        assertTrue(queue.enqueue(GameServices.rom().getRom(),
                Sonic3kConstants.ART_KOSM_RESULTS_GENERAL_ADDR, 0x2000));

        S3kResultsScreenObjectInstance root = createResults();
        GameServices.level().getObjectManager().addDynamicObject(root);

        assertEquals(0, com.openggf.game.GameServices.hardwareTiming()
                        .incompleteCount(com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE),
                "allocation alone must not execute Obj_LevelResultsInit");
        root.update(0, fixture.sprite());

        // The results screen's three KosM loads are scheduled through the
        // hardware-timing service, so that is where they are observable; the
        // pre-enqueued archive above stays on the gameplay queue and is what makes
        // this a "queue already busy" case.
        assertEquals(3, com.openggf.game.GameServices.hardwareTiming()
                        .incompleteCount(com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE),
                "Obj_LevelResultsInit queues three Kosinski module loads");
        assertEquals(Sonic3kConstants.ART_KOSM_RESULTS_GENERAL_ADDR + 2,
                queue.activeSourceAddress(),
                "Process_Kos_Module_Queue_Init consumes the active archive header immediately");
        assertEquals(List.of(0x2000),
                queue.queuedArchives().stream()
                        .map(KosinskiModuleQueue.ArchiveState::destinationVramBytes).toList());

        // Publication is gated on HardwareWorkKind.KOS_MODULE_QUEUE, which is the
        // engine's Kos_modules_left: S3kKosModuleQueue enforces the ROM's four-deep
        // FIFO over that one kind, and every S3K KosM consumer shares it. The
        // gameplay-scoped KosinskiModuleQueue above is a separate owner used only by
        // the PLC loader and FBZ, so its pending archive is deliberately not part of
        // this gate (see the KosM ownership section of the merge status doc).
        int guard = 0;
        while (com.openggf.game.GameServices.hardwareTiming()
                .incompleteCount(com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE) > 0) {
            assertEquals(0, GameServices.level().getCurrentAct(),
                    "Obj_LevelResultsCreate may not publish while its own KosM loads are pending");
            assertTrue(resultChildren().isEmpty());
            assertEquals(0, root.activeResultsFrames(),
                    "Obj_LevelResultsCreate must return without consuming the 360-frame wait");
            fixture.stepFrame(false, false, false, false, false);
            assertTrue(++guard < 64, "results KosM work must complete");
        }
        assertTrue(queue.isIdle());

        fixture.stepFrame(false, false, false, false, false);
        assertEquals(1, GameServices.level().getCurrentAct(),
                "Events_fg_5 publication must occur only after the real child allocation pass");
        List<S3kResultsElementObjectInstance> children = resultChildren();
        assertEquals(12, children.size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
                children.stream().map(S3kResultsElementObjectInstance::entryIndex).toList());
        assertTrue(children.stream().allMatch(child -> child.parentResults() == root));
        assertTrue(children.stream().allMatch(child -> child.getSlotIndex() > root.getSlotIndex()));
        assertEquals(children.stream().map(AbstractObjectInstance::getSlotIndex).sorted().toList(),
                children.stream().map(AbstractObjectInstance::getSlotIndex).toList(),
                "CreateNewSprite4 must preserve native ObjArray_LevResults order in ascending SST slots");
        assertEquals(0, root.activeResultsFrames(),
                "the successful creation dispatch still returns before Obj_LevelResultsWait");

        for (int i = 0; i < 70; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertFalse(root.hasPlayedResultsMusic());
        fixture.stepFrame(false, false, false, false, false);
        assertTrue(root.hasPlayedResultsMusic(),
                "act-clear music begins on Wait dispatch 71, excluding every Kos/allocation wait");
    }

    @Test
    void firstAllocateObjectAfterCurrentFailureRetriesWithoutEarlyPublication() throws Exception {
        HeadlessTestFixture fixture = fixture();
        S3kResultsScreenObjectInstance root = createResults();
        ObjectManager manager = GameServices.level().getObjectManager();
        manager.addDynamicObject(root);

        awaitResultsArt(fixture, root);
        assertTrue(fixture.gameplayMode().getKosinskiModuleQueue().isIdle());

        // Fill immediately before Obj_LevelResultsCreate executes. Ordinary
        // placement objects may retire while the KosM jobs complete.
        List<SlotFiller> fillers = new ArrayList<>();
        while (true) {
            SlotFiller filler = ObjectConstructionContext.construct(TestEnvironment.objectServices(),
                    SlotFiller::new);
            manager.addDynamicObject(filler);
            if (filler.isDestroyed()) {
                break;
            }
            fillers.add(filler);
        }
        fixture.stepFrame(false, false, false, false, false);
        assertEquals(0, GameServices.level().getCurrentAct());
        assertTrue(resultChildren().isEmpty());
        assertFalse(((Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider())
                .getFbzEvents().isEventsFg5());

        fillers.stream()
                .sorted(Comparator.comparingInt(AbstractObjectInstance::getSlotIndex).reversed())
                .limit(12)
                .forEach(manager::removeDynamicObject);
        fixture.stepFrame(false, false, false, false, false);

        assertEquals(1, GameServices.level().getCurrentAct());
        assertEquals(12, resultChildren().size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
    void laterCreateNewSprite4FailurePublishesItsPrefixButLeavesNativeResidualCount(
            int availablePrefixSlots) throws Exception {
        HeadlessTestFixture fixture = fixture();
        S3kResultsScreenObjectInstance root = createResults();
        ObjectManager manager = GameServices.level().getObjectManager();
        manager.addDynamicObject(root);
        awaitResultsArt(fixture, root);

        List<SlotFiller> fillers = fillEveryDynamicSlot(manager);
        fillers.stream()
                .sorted(Comparator.comparingInt(AbstractObjectInstance::getSlotIndex).reversed())
                .limit(availablePrefixSlots)
                .forEach(manager::removeDynamicObject);
        fixture.stepFrame(false, false, false, false, false);

        assertEquals(1, GameServices.level().getCurrentAct(),
                "a failure after the initial allocation still advances/publishes");
        List<S3kResultsElementObjectInstance> prefix = resultChildren();
        assertEquals(availablePrefixSlots, prefix.size());
        assertEquals(12, root.nativeChildrenRemaining(),
                "ROM $30 is not reduced to the successfully allocated prefix");
        prefix.forEach(root::childExited);
        assertEquals(12 - availablePrefixSlots, root.nativeChildrenRemaining(),
                "the unallocated suffix leaves Obj_LevelResultsWait2 permanently residual");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rewindRestoresQueuePhaseExactChildSlotsAndParentLinksWithoutDuplication(
            boolean inPlace) throws Exception {
        HeadlessTestFixture fixture = fixture();
        ObjectManager manager = GameServices.level().getObjectManager();
        S3kResultsScreenObjectInstance root = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new S3kResultsScreenObjectInstance(PlayerCharacter.TAILS_ALONE, 1));
        manager.addDynamicObject(root);
        awaitResultsArt(fixture, root);
        fixture.stepFrame(false, false, false, false, false);
        List<S3kResultsElementObjectInstance> capturedChildren = resultChildren();
        assertEquals(12, capturedChildren.size());
        List<Integer> capturedSlots = capturedChildren.stream()
                .map(AbstractObjectInstance::getSlotIndex).toList();

        KosinskiModuleQueue queue = fixture.gameplayMode().getKosinskiModuleQueue();
        assertTrue(queue.enqueue(GameServices.rom().getRom(),
                Sonic3kConstants.ART_KOSM_SS_RESULTS_ADDR, 0x4000));
        fixture.stepFrame(false, false, false, false, false);
        assertEquals(KosinskiModuleQueue.Phase.DECOMPRESSION_IN_PROGRESS, queue.phase());
        KosinskiModuleQueue.Snapshot capturedQueue = queue.capture();
        CompositeSnapshot snapshot = fixture.gameplayMode().getRewindRegistry().capture();

        if (!inPlace) {
            manager.setRewindInPlaceRestoreEnabledForTest(false);
        }
        queue.processNativeFrame();
        fixture.gameplayMode().getRewindRegistry().restore(snapshot);

        S3kResultsScreenObjectInstance restoredRoot = manager.getActiveObjects().stream()
                .filter(S3kResultsScreenObjectInstance.class::isInstance)
                .map(S3kResultsScreenObjectInstance.class::cast)
                .findFirst().orElseThrow();
        List<S3kResultsElementObjectInstance> restoredChildren = resultChildren();
        assertEquals(12, restoredChildren.size(), "restore must not duplicate the SST family");
        assertEquals(capturedSlots, restoredChildren.stream()
                .map(AbstractObjectInstance::getSlotIndex).toList());
        assertTrue(restoredChildren.stream().allMatch(child -> child.parentResults() == restoredRoot));
        assertEquals(capturedQueue, queue.capture(),
                "object reconstruction must not enqueue results art after queue restore");
        assertEquals(PlayerCharacter.TAILS_ALONE, restoredRoot.resultsCharacter());
        assertEquals(1, restoredRoot.resultsAct());
        assertTrue(restoredRoot.hasLoadedResultsArt(),
                "derived renderer art must rebuild from restored character/act scalars");
        if (!inPlace) {
            assertNotSame(root, restoredRoot);
        }
    }

    /** Stops at completed art, before the next Obj_LevelResultsCreate dispatch. */
    private static void awaitResultsArt(HeadlessTestFixture fixture,
                                        S3kResultsScreenObjectInstance root) {
        root.update(GameServices.level().getObjectManager().getVblaCounter(), fixture.sprite());
        int guard = 0;
        while (GameServices.hardwareTiming().incompleteCount(
                com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE) > 0) {
            assertTrue(resultChildren().isEmpty(), "pending art must not publish result children");
            fixture.stepFrame(false, false, false, false, false);
            assertTrue(++guard < 64, "results KosM work must complete");
        }
        assertTrue(resultChildren().isEmpty(), "child allocation belongs to the next dispatch");
    }

    private static HeadlessTestFixture fixture() {
        return HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 0)
                .startPosition((short) 0x2EE1, (short) 0x0540)
                .startPositionIsCentre()
                .build();
    }

    private static S3kResultsScreenObjectInstance createResults() {
        return ObjectConstructionContext.construct(TestEnvironment.objectServices(),
                () -> new S3kResultsScreenObjectInstance(PlayerCharacter.SONIC_ALONE, 0));
    }

    private static List<S3kResultsElementObjectInstance> resultChildren() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(S3kResultsElementObjectInstance.class::isInstance)
                .map(S3kResultsElementObjectInstance.class::cast)
                .sorted(Comparator.comparingInt(AbstractObjectInstance::getSlotIndex))
                .toList();
    }

    private static List<SlotFiller> fillEveryDynamicSlot(ObjectManager manager) {
        List<SlotFiller> fillers = new ArrayList<>();
        while (true) {
            SlotFiller filler = ObjectConstructionContext.construct(TestEnvironment.objectServices(),
                    SlotFiller::new);
            manager.addDynamicObject(filler);
            if (filler.isDestroyed()) {
                return fillers;
            }
            fillers.add(filler);
        }
    }

    private static final class SlotFiller extends AbstractObjectInstance {
        private SlotFiller() {
            super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "ResultsSlotFiller");
            setRomWorldPositioned(false);
        }

        @Override
        public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {
        }
    }
}
