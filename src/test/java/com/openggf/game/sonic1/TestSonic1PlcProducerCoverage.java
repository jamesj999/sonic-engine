package com.openggf.game.sonic1;

import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic1.credits.Sonic1CreditsManager;
import com.openggf.game.sonic1.events.Sonic1LevelEventManager;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenManager;
import com.openggf.game.sonic1.objects.Sonic1EggPrisonObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ResultsScreenObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1FixedEndCardSlot;
import com.openggf.game.sonic1.objects.Sonic1SignpostObjectInstance;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageProvider;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.level.resources.NemesisPlcPatternCounts;
import com.openggf.level.resources.PlcParser;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Executes the S1-owned credits producer and records the complete descriptor
 * sequence it leaves in the real native FIFO.  This deliberately compares ROM
 * descriptors rather than {@code isBusy()}: identical queue length is not
 * sufficient evidence that a producer selected the right PLC.
 */
@RequiresRom(SonicGame.SONIC_1)
class TestSonic1PlcProducerCoverage {
    private static final int[] CREDITS_NATIVE_ZONES = {0, 2, 4, 1, 3, 5, 5, 0, 1};

    @BeforeEach
    void createSonic1Services() {
        GameServices.module().createGame(TestEnvironment.currentRom());
    }

    @Test
    void titleScreenOwnerPublishesMainBeforePresentationBegins() throws Exception {
        Sonic1TitleScreenManager.getInstance().initialize();
        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);

        assertEquals(expectedDescriptors(0), queue.capture().queuedEntries(),
                "S1 title initialization must replace the native Main PLC before presentation");
    }

    @Test
    void titleScreenRetriesRejectedMainReplacementOnceTheDecoderBecomesIdle() throws Exception {
        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
        queue.append(0);
        queue.prepare();
        assertNotNull(queue.capture().activeEntry(), "fixture must force a native replace rejection");

        Sonic1TitleScreenManager manager = Sonic1TitleScreenManager.getInstance();
        manager.initialize();
        drainActive(queue);
        manager.update(org.mockito.Mockito.mock(com.openggf.control.InputHandler.class));

        List<NemesisPlcQueueSnapshot.Entry> expected = expectedDescriptors(0);
        assertEquals(expected, queue.capture().queuedEntries(),
                "S1 title retry must publish Main once the decoder is idle");
        manager.update(org.mockito.Mockito.mock(com.openggf.control.InputHandler.class));
        assertEquals(expected, queue.capture().queuedEntries(),
                "S1 title retry must not duplicate its successful replacement");
    }

    @ParameterizedTest(name = "title-card progression zone {0} maps to native animal PLC {1}")
    @MethodSource("titleCardZones")
    void titleCardOwnerPublishesExplodeThenNativeZoneAnimalAtExitEdge(int progressionZone, int animalPlc)
            throws Exception {
        // Card_ChangeArt runs from the fixed title-card slot under
        // ExecuteObjects, 60 Card_Wait frames plus 9 Card_MoveOut frames after
        // Level_StartGame arms the level-name element
        // (docs/s1disasm/_incObj/34 Title Cards.asm:122-168,
        // docs/s1disasm/sonic.asm:2984-2995).
        Sonic1LevelEventManager events = new Sonic1LevelEventManager();
        events.armTitleCardArtReloadAtLevelStart(
                progressionZone, 0, CREDITS_NATIVE_ZONES[progressionZone]);

        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
        for (int frame = 0; frame < 69; frame++) {
            events.updateFixedInLevelObjectsBeforeDynamicObjects();
            assertEquals(List.of(), queue.capture().queuedEntries(),
                    "Card_ChangeArt must not publish before the level-name element reaches card_finalX");
        }
        events.updateFixedInLevelObjectsBeforeDynamicObjects();

        assertEquals(expectedDescriptors(2, animalPlc), queue.capture().queuedEntries(),
                "Card_ChangeArt must publish explode then its native-zone animal PLC at text exit");
        events.updateFixedInLevelObjectsBeforeDynamicObjects();
        assertEquals(expectedDescriptors(2, animalPlc), queue.capture().queuedEntries(),
                "the deleted card element must not publish twice");
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> titleCardZones() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(0, 21),
                org.junit.jupiter.params.provider.Arguments.of(1, 23),
                org.junit.jupiter.params.provider.Arguments.of(2, 25),
                org.junit.jupiter.params.provider.Arguments.of(3, 22),
                org.junit.jupiter.params.provider.Arguments.of(4, 24),
                org.junit.jupiter.params.provider.Arguments.of(5, 26));
    }

    @Test
    void levelInitOwnerClearsThenPublishesHeaderPrimaryAndMain2BeforeTitleCard() throws Exception {
        LevelLoadContext context = new LevelLoadContext();
        com.openggf.level.Level level = org.mockito.Mockito.mock(com.openggf.level.Level.class);
        org.mockito.Mockito.when(level.getZoneIndex()).thenReturn(0);
        context.setLevel(level);
        Sonic1LevelInitProfile profile = new Sonic1LevelInitProfile(
                new com.openggf.game.sonic1.events.Sonic1LevelEventManager(),
                new Sonic1SwitchManager(), new Sonic1ConveyorState());
        profile.levelLoadSteps(context).stream()
                .filter(step -> step.name().equals("QueueInitialPlcs"))
                .findFirst().orElseThrow().execute();

        int primary = primaryForNativeZone(0);
        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
        assertEquals(expectedDescriptors(primary, 1), queue.capture().queuedEntries(),
                "S1 Level must commit ClearPLC/header-primary/Main2 through the lifecycle owner");
    }

    @Test
    void bothNormalEndActOwnersReplaceResultsPlcAtTheirActualHandoff() throws Exception {
        AbstractPlayableSprite player = org.mockito.Mockito.mock(AbstractPlayableSprite.class);
        for (Object owner : List.of(new Sonic1SignpostObjectInstance(new ObjectSpawn(0, 0, 0, 0, 0, false, 0)),
                new Sonic1EggPrisonObjectInstance(new ObjectSpawn(0, 0, 0, 0, 0, false, 0)))) {
            ObjectManager[] objectManagerRef = new ObjectManager[1];
            TestObjectServices services = new TestObjectServices() {
                @Override
                public ObjectManager objectManager() {
                    return objectManagerRef[0];
                }
            }.withGameModule(GameServices.module());
            objectManagerRef[0] = new ObjectManager(
                    List.of(), null, 0, null, null, null, null, services);
            ObjectConstructionContext.with(services, () -> {
                ((com.openggf.level.objects.AbstractObjectInstance) owner).setServices(services);
                try {
                    java.lang.reflect.Method handoff = owner.getClass().getDeclaredMethod(
                            "triggerGotThroughAct", AbstractPlayableSprite.class);
                    handoff.setAccessible(true);
                    handoff.invoke(owner, player);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("normal end-act owner handoff unavailable", failure);
                }
            });
            Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
            assertEquals(expectedDescriptors(16), queue.capture().queuedEntries(),
                    owner.getClass().getSimpleName() + " must replace results art at GotThroughAct");
        }
    }

    @Test
    void duplicateSignpostsShareTheFixedNativeEndcardSlot() throws Exception {
        ObjectManager[] objectManagerRef = new ObjectManager[1];
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManagerRef[0];
            }
        }.withGameModule(GameServices.module());
        ObjectManager objects = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        objectManagerRef[0] = objects;
        Sonic1ResultsScreenObjectInstance existing = Sonic1FixedEndCardSlot.claim(
                services,
                new Sonic1FixedEndCardSlot.ResultsData(30, 0, 2, false))
                .requireCard();
        existing.markResultsPlcCommitted();

        Sonic1PlcService queue =
                GameServices.module().getGameService(Sonic1PlcService.class);
        queue.replaceQueued(16);
        List<NemesisPlcQueueSnapshot.Entry> expected = queue.capture().queuedEntries();

        Sonic1SignpostObjectInstance duplicate = new Sonic1SignpostObjectInstance(
                new ObjectSpawn(0x2960, 0x04B1, 0x0D, 0, 0, false, 0));
        duplicate.setServices(services);
        AbstractPlayableSprite player = org.mockito.Mockito.mock(AbstractPlayableSprite.class);
        java.lang.reflect.Method handoff = Sonic1SignpostObjectInstance.class
                .getDeclaredMethod("triggerGotThroughAct", AbstractPlayableSprite.class);
        handoff.setAccessible(true);
        handoff.invoke(duplicate, player);

        assertEquals(expected, queue.capture().queuedEntries(),
                "the second GotThroughAct must not replace the fixed v_endcard PLC again");
        assertEquals(1, objects.getActiveObjects().stream()
                .filter(Sonic1ResultsScreenObjectInstance.class::isInstance)
                .count());
    }

    @Test
    void signpostObservesRetainedGiantRingPlayerAsDeletedSstAndOwnsGotThroughAct()
            throws Exception {
        GameStateManager gameState = new GameStateManager();
        gameState.setBigRingCollected(true);
        ObjectManager[] objectManagerRef = new ObjectManager[1];
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManagerRef[0];
            }

            @Override
            public GameStateManager gameState() {
                return gameState;
            }

            @Override
            public int currentAct() {
                return 0;
            }
        }.withGameModule(GameServices.module());
        ObjectManager objects = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        objectManagerRef[0] = objects;

        Sonic1SignpostObjectInstance signpost = new Sonic1SignpostObjectInstance(
                new ObjectSpawn(0x2560, 0x04A2, 0x0D, 0, 0, false, 0));
        signpost.setServices(services);
        Field routine = Sonic1SignpostObjectInstance.class.getDeclaredField("routineState");
        routine.setAccessible(true);
        routine.setInt(signpost, 2);

        AbstractPlayableSprite player = org.mockito.Mockito.mock(AbstractPlayableSprite.class);
        org.mockito.Mockito.when(player.isNativeSlotPresent()).thenReturn(false);
        org.mockito.Mockito.when(player.getRingCount()).thenReturn(50);

        signpost.update(1, player);

        verify(player).setAnimationId(Sonic1AnimationIds.NULL.id());
        verify(player).setForcedAnimationId(Sonic1AnimationIds.NULL.id());

        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
        assertEquals(expectedDescriptors(16), queue.capture().queuedEntries());
        Sonic1ResultsScreenObjectInstance card = objects.getActiveObjects().stream()
                .filter(Sonic1ResultsScreenObjectInstance.class::isInstance)
                .map(Sonic1ResultsScreenObjectInstance.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Sonic1FixedEndCardSlot.SLOT, card.getSlotIndex());
        Field specialStageAfter = Sonic1ResultsScreenObjectInstance.class
                .getDeclaredField("specialStageAfter");
        specialStageAfter.setAccessible(true);
        assertTrue(specialStageAfter.getBoolean(card));
    }

    @Test
    void rejectedResultsReplacementKeepsFixedCardUncommittedUntilSingleRetry()
            throws Exception {
        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
        queue.append(0);
        queue.prepare();
        assertNotNull(queue.capture().activeEntry());

        ObjectManager[] objectManagerRef = new ObjectManager[1];
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManagerRef[0];
            }
        }.withGameModule(GameServices.module());
        ObjectManager objects = new ObjectManager(
                List.of(), null, 0, null, null, null, null, services);
        objectManagerRef[0] = objects;
        Sonic1SignpostObjectInstance signpost = new Sonic1SignpostObjectInstance(
                new ObjectSpawn(0, 0, 0x0D, 0, 0, false, 0));
        signpost.setServices(services);
        AbstractPlayableSprite player = org.mockito.Mockito.mock(AbstractPlayableSprite.class);
        java.lang.reflect.Method handoff = Sonic1SignpostObjectInstance.class
                .getDeclaredMethod("triggerGotThroughAct", AbstractPlayableSprite.class);
        handoff.setAccessible(true);

        handoff.invoke(signpost, player);

        Sonic1ResultsScreenObjectInstance pending = objects.getActiveObjects().stream()
                .filter(Sonic1ResultsScreenObjectInstance.class::isInstance)
                .map(Sonic1ResultsScreenObjectInstance.class::cast)
                .findFirst().orElseThrow();
        assertEquals(false, pending.isResultsPlcCommitted());
        assertNotNull(queue.capture().activeEntry(),
                "rejected NewPLC must leave the existing decoder untouched");

        drainActive(queue);
        handoff.invoke(signpost, player);

        assertTrue(pending.isResultsPlcCommitted());
        assertEquals(expectedDescriptors(16), queue.capture().queuedEntries());
        assertEquals(1, objects.getActiveObjects().stream()
                .filter(Sonic1ResultsScreenObjectInstance.class::isInstance)
                .count());
    }

    @Test
    void specialStageResultsOwnerReplacesMainThenAppendsResultsArt() throws Exception {
        new Sonic1SpecialStageProvider().resetForResults();
        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
        assertEquals(expectedDescriptors(0, 27), queue.capture().queuedEntries(),
                "S1 special-stage results boundary must replace Main then append result art");
    }

    @Test
    void specialStageResultsRetriesRejectedBatchExactlyOnceAfterActiveDecode() throws Exception {
        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
        queue.append(0);
        queue.prepare();
        assertNotNull(queue.capture().activeEntry(), "fixture must force a native transaction rejection");

        Sonic1SpecialStageProvider provider = new Sonic1SpecialStageProvider();
        provider.resetForResults();
        drainActive(queue);
        provider.onEnterResults();

        List<NemesisPlcQueueSnapshot.Entry> expected = expectedDescriptors(0, 27);
        assertEquals(expected, queue.capture().queuedEntries(),
                "S1 results retry must publish Main then result art once idle");
        provider.onEnterResults();
        assertEquals(expected, queue.capture().queuedEntries(),
                "S1 results retry must not duplicate its successful batch");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void specialStageResultsRewindRestoresPendingRetryAndCompletedNoDuplicateDirection() throws Exception {
        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
        queue.append(0);
        queue.prepare();
        Sonic1SpecialStageProvider provider = new Sonic1SpecialStageProvider();
        provider.resetForResults();
        RewindSnapshottable adapter = (RewindSnapshottable) provider.rewindAdapter().orElseThrow();
        Object pending = adapter.capture();

        drainActive(queue);
        provider.onEnterResults();
        List<NemesisPlcQueueSnapshot.Entry> expected = expectedDescriptors(0, 27);
        assertEquals(expected, queue.capture().queuedEntries(), "retry must succeed after the pending capture");

        adapter.restore(pending);
        queue.clearQueued();
        provider.onEnterResults();
        assertEquals(expected, queue.capture().queuedEntries(),
                "restoring pending state must re-arm the provider-owned results retry");

        Object completed = adapter.capture();
        queue.clearQueued();
        adapter.restore(completed);
        provider.onEnterResults();
        assertEquals(List.of(), queue.capture().queuedEntries(),
                "restoring completed state must not resubmit results PLC work");
    }

    @Test
    void creditsOwnerPrequeuesEveryTextPageInNativeRomZoneOrder() throws Exception {
        Sonic1CreditsManager credits = new Sonic1CreditsManager();
        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);

        for (int credit = 0; credit < CREDITS_NATIVE_ZONES.length; credit++) {
            setCreditsNumber(credits, credit);
            credits.onReturnToText();

            int primary = primaryForNativeZone(CREDITS_NATIVE_ZONES[credit]);
            assertEquals(expectedDescriptors(primary, 1), queue.capture().queuedEntries(),
                    "credits page " + credit + " must publish ClearPLC, primary AddPLC, Main2 AddPLC");
        }
    }

    @Test
    void everyAuditedS1CueParsesAndPreservesItsExactAppendOrReplaceDescriptorOrder()
            throws Exception {
        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
        // This covers the concrete producer IDs in the audit.  Owner-to-facade
        // wiring is guarded by TestPlcProducerCoverageGuard; this assertion
        // catches an ID/table regression even where a visual owner cannot be
        // booted headlessly (title card and boss presentation paths).
        int[] appendIds = {1, 2, 17, 21, 22, 23, 24, 25, 26, 27, 30, 31};
        for (int plcId : appendIds) {
            queue.transact(Sonic1PlcService.clear(), Sonic1PlcService.appendOperation(plcId));
            assertEquals(expectedDescriptors(plcId), queue.capture().queuedEntries(),
                    "S1 audited append PLC " + plcId);
        }
        for (int plcId : new int[] {0, 16}) {
            queue.transact(Sonic1PlcService.replace(plcId));
            assertEquals(expectedDescriptors(plcId), queue.capture().queuedEntries(),
                    "S1 audited replace PLC " + plcId);
        }
    }

    private static void setCreditsNumber(Sonic1CreditsManager credits, int value) throws Exception {
        Field field = Sonic1CreditsManager.class.getDeclaredField("creditsNum");
        field.setAccessible(true);
        field.setInt(credits, value);
    }

    private static void drainActive(Sonic1PlcService queue) {
        while (queue.capture().activeEntry() != null) {
            queue.serviceFastVBlank();
        }
    }

    private static int primaryForNativeZone(int zone) throws IOException {
        int header = Sonic1Constants.LEVEL_HEADERS_ADDR + zone * 16;
        return GameServices.rom().getRom().readByte(header) & 0xFF;
    }

    private static List<NemesisPlcQueueSnapshot.Entry> expectedDescriptors(int... ids)
            throws IOException {
        List<NemesisPlcQueueSnapshot.Entry> result = new ArrayList<>();
        for (int id : ids) {
            PlcParser.PlcDefinition definition = PlcParser.parse(GameServices.rom().getRom(),
                    Sonic1Constants.ART_LOAD_CUES_ADDR, id);
            List<Integer> counts = NemesisPlcPatternCounts.derive(GameServices.rom().getRom(), definition);
            for (int index = 0; index < definition.entries().size(); index++) {
                PlcParser.PlcEntry entry = definition.entries().get(index);
                int count = counts.get(index);
                result.add(new NemesisPlcQueueSnapshot.Entry(entry.romAddr(), entry.tileIndex(), count, count));
            }
        }
        return result;
    }
}
