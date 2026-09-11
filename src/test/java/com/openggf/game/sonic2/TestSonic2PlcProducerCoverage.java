package com.openggf.game.sonic2;

import com.openggf.game.GameServices;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.events.Sonic2ARZEvents;
import com.openggf.game.sonic2.events.Sonic2CNZEvents;
import com.openggf.game.sonic2.events.Sonic2CPZEvents;
import com.openggf.game.sonic2.events.Sonic2DEZEvents;
import com.openggf.game.sonic2.events.Sonic2EHZEvents;
import com.openggf.game.sonic2.events.Sonic2HTZEvents;
import com.openggf.game.sonic2.events.Sonic2MCZEvents;
import com.openggf.game.sonic2.events.Sonic2MTZEvents;
import com.openggf.game.sonic2.events.Sonic2OOZEvents;
import com.openggf.game.sonic2.events.Sonic2WFZEvents;
import com.openggf.game.sonic2.events.Sonic2ZoneEvents;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.game.sonic2.titlescreen.TitleScreenManager;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageIntro;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.game.sonic2.objects.EggPrisonObjectInstance;
import com.openggf.game.sonic2.objects.SignpostObjectInstance;
import com.openggf.game.sonic2.titlecard.TitleCardManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.resources.NemesisPlcPatternCounts;
import com.openggf.level.resources.PlcParser;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Native FIFO descriptor coverage for every currently represented S2 producer cue. */
@RequiresRom(SonicGame.SONIC_2)
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(SingletonResetExtension.class)
class TestSonic2PlcProducerCoverage {
    @BeforeEach
    void createSonic2Services() {
        GameServices.module().createGame(TestEnvironment.currentRom());
    }

    @AfterEach
    void closeGameplaySession() {
        SessionManager.clear();
    }

    /**
     * Runs each actual dynamic-event owner at the ROM threshold recorded in
     * the audit.  These are deliberately not request-facade calls: each case
     * advances the production state machine and observes the native FIFO that
     * its own {@code requestSonic2Plc} publication leaves behind.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("eventRoutes")
    void dynamicEventOwnerPublishesAuditedCueAndLeavesEagerArtAvailable(EventRoute route)
            throws Exception {
        RuntimeFixture fixture = installRuntime(route.romZone());
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        var prepared = fixture.provider().preparePlcs(route.plcId());
        Sonic2ZoneEvents owner = route.owner().get();
        owner.init(route.act());
        owner.setEventRoutine(route.routine());
        GameServices.camera().setX((short) route.cameraX());
        GameServices.camera().setY((short) route.cameraY());

        route.beforeUpdate().accept(owner);
        owner.update(route.act(), 0);

        assertEquals(expectedDescriptors(route.plcId()), queue.capture().queuedEntries(),
                route.name() + " must submit its ROM LoadPLC descriptor through the native FIFO");
        assertDoesNotThrow(() -> fixture.provider().preflightPreparedPlc(prepared),
                route.name() + " eager art must be immediately usable after its owner transition");
        prepared.sheets().forEach(sheet -> assertEquals(true,
                fixture.provider().getRenderer(sheet.key()) != null,
                route.name() + " must publish the prepared renderer " + sheet.key()));
    }

    private static Stream<EventRoute> eventRoutes() {
        return Stream.of(
                route("EHZ boss arena", Sonic2ZoneConstants.ROM_ZONE_EHZ,
                        Sonic2EHZEvents::new, 1, 2, 0x28F0, 0, Sonic2Constants.PLC_EHZ_BOSS),
                route("MTZ boss arena", Sonic2ZoneConstants.ROM_ZONE_MTZ_3,
                        Sonic2MTZEvents::new, 2, 4, 0x2A80, 0, Sonic2Constants.PLC_MTZ_BOSS),
                route("HTZ boss arena", Sonic2ZoneConstants.ROM_ZONE_HTZ,
                        Sonic2HTZEvents::new, 1, 14, 0x2EDF, 0, Sonic2Constants.PLC_HTZ_BOSS),
                route("OOZ boss arena", Sonic2ZoneConstants.ROM_ZONE_OOZ,
                        Sonic2OOZEvents::new, 1, 2, 0x2880, 0, Sonic2Constants.PLC_OOZ_BOSS),
                route("MCZ boss arena", Sonic2ZoneConstants.ROM_ZONE_MCZ,
                        Sonic2MCZEvents::new, 1, 2, 0x20F0, 0, Sonic2Constants.PLC_MCZ_BOSS),
                route("CNZ boss arena", Sonic2ZoneConstants.ROM_ZONE_CNZ,
                        Sonic2CNZEvents::new, 1, 2, 0x2890, 0, Sonic2Constants.PLC_CNZ_BOSS),
                route("CPZ boss arena", Sonic2ZoneConstants.ROM_ZONE_CPZ,
                        Sonic2CPZEvents::new, 1, 2, 0x2A20, 0, Sonic2Constants.PLC_CPZ_BOSS),
                route("DEZ Mecha Sonic", Sonic2ZoneConstants.ROM_ZONE_DEZ,
                        Sonic2DEZEvents::new, 0, 0, 0x140, 0, Sonic2Constants.PLC_FIERY_EXPLOSION),
                route("DEZ boss", Sonic2ZoneConstants.ROM_ZONE_DEZ,
                        Sonic2DEZEvents::new, 0, 4, 0x300, 0, Sonic2Constants.PLC_DEZ_BOSS),
                route("ARZ boss arena", Sonic2ZoneConstants.ROM_ZONE_ARZ,
                        Sonic2ARZEvents::new, 1, 0, 0x2810, 0, Sonic2Constants.PLC_ARZ_BOSS),
                new EventRoute("WFZ boss PLC", Sonic2ZoneConstants.ROM_ZONE_WFZ,
                        Sonic2WFZEvents::new, 0, 0, 0x2880, 0x400,
                        Sonic2Constants.PLC_WFZ_BOSS,
                        owner -> ((Sonic2WFZEvents) owner).setWfzSubRoutine(0)),
                new EventRoute("WFZ Tornado PLC", Sonic2ZoneConstants.ROM_ZONE_WFZ,
                        Sonic2WFZEvents::new, 0, 0, 0, 0x500,
                        Sonic2Constants.PLC_TORNADO,
                        owner -> ((Sonic2WFZEvents) owner).setWfzSubRoutine(2)));
    }

    private static EventRoute route(String name, int zone, Supplier<Sonic2ZoneEvents> owner,
                                    int act, int routine, int x, int y, int plcId) {
        return new EventRoute(name, zone, owner, act, routine, x, y, plcId, ignored -> { });
    }

    @Test
    void titleScreenOwnerPublishesStd1BeforePresentationBegins() throws Exception {
        TitleScreenManager.getInstance().initialize();
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);

        assertEquals(expectedDescriptors(0), queue.capture().queuedEntries(),
                "S2 title initialization must replace the native Std1 PLC before presentation");
    }

    @Test
    void titleScreenRetriesRejectedStd1ReplacementOnceTheDecoderBecomesIdle() throws Exception {
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        queue.append(0);
        queue.prepare();
        assertNotNull(queue.capture().activeEntry(), "fixture must force a native replace rejection");

        TitleScreenManager manager = TitleScreenManager.getInstance();
        manager.initialize();
        drainActive(queue);
        manager.update(org.mockito.Mockito.mock(com.openggf.control.InputHandler.class));

        List<NemesisPlcQueueSnapshot.Entry> expected = expectedDescriptors(0);
        assertEquals(expected, queue.capture().queuedEntries(),
                "S2 title retry must publish Std1 once the decoder is idle");
        manager.update(org.mockito.Mockito.mock(com.openggf.control.InputHandler.class));
        assertEquals(expected, queue.capture().queuedEntries(),
                "S2 title retry must not duplicate its successful replacement");
    }

    @Test
    void titleCardOwnerPublishesWaterThenZoneAnimalAtTextExit() throws Exception {
        TitleCardManager card = new TitleCardManager();
        card.beginOmittedPresentationExitTail(0, 0);
        for (int frame = 0; frame < 100 && !card.isComplete(); frame++) {
            card.update();
        }

        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        assertEquals(expectedDescriptors(Sonic2Constants.PLC_STD_WATER,
                Sonic2Constants.PLC_ANIMALS_EHZ), queue.capture().queuedEntries(),
                "Obj34 must publish standard-water then the zone animal PLC at its text-exit edge");
    }

    @Test
    void levelInitOwnerClearsThenPublishesHeaderPrimaryStd2AndTailsLifeArt() throws Exception {
        LevelLoadContext context = new LevelLoadContext();
        Level level = org.mockito.Mockito.mock(Level.class);
        org.mockito.Mockito.when(level.getZoneIndex()).thenReturn(0);
        context.setLevel(level);
        Sonic2LevelInitProfile profile = new Sonic2LevelInitProfile(new Sonic2LevelEventManager(),
                () -> OptionalInt.of(9));
        profile.levelLoadSteps(context).stream()
                .filter(step -> step.name().equals("QueueInitialPlcs"))
                .findFirst().orElseThrow().execute();

        int primary = GameServices.rom().getRom().readByte(Sonic2Constants.LEVEL_DATA_DIR) & 0xFF;
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        assertEquals(expectedDescriptors(primary, Sonic2Constants.PLC_STD2, 9),
                queue.capture().queuedEntries(),
                "S2 Level must commit ClearPLC/header-primary/Std2/life through its lifecycle owner");
    }

    @Test
    void initialPresentationOwnerServicesHeaderSecondaryAcrossTheTitleCardLeaveLoop()
            throws Exception {
        Sonic2PlcService queue =
                GameServices.module().getGameService(Sonic2PlcService.class);
        queue.transact(
                Sonic2PlcService.clearOperation(),
                Sonic2PlcService.appendOperation(Sonic2Constants.PLC_EHZ1),
                Sonic2PlcService.appendOperation(Sonic2Constants.PLC_STD2));

        Sonic2LevelInitProfile.completeInitialPresentationPlcs(
                GameServices.rom().getRom(), queue, 0);

        // loadZoneBlockMaps appends the header secondary (docs/s2disasm/s2.asm:20103-20110)
        // while still inside the title-card sequence, and the 25-frame leave loop
        // (s2.asm:5060-5066) services it before Level_MainLoop (s2.asm:5082-5087).
        assertEquals(List.of(), queue.capture().queuedEntries(),
                "the header secondary must be appended AND serviced by the title-card leave loop");
    }

    @Test
    void specialStageIntroOwnerPublishesBombsAtWait2OneShotGate() throws Exception {
        Sonic2SpecialStageIntro intro = new Sonic2SpecialStageIntro();
        intro.initialize(0, 50);
        Field phase = Sonic2SpecialStageIntro.class.getDeclaredField("currentPhase");
        phase.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object wait2 = Enum.valueOf((Class) phase.getType(), "WAIT2");
        phase.set(intro, wait2);
        Field timer = Sonic2SpecialStageIntro.class.getDeclaredField("phaseTimer");
        timer.setAccessible(true);
        timer.setInt(intro, 30);
        intro.update();

        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        assertEquals(expectedDescriptors(Sonic2Constants.PLC_SPECIAL_STAGE_BOMBS),
                queue.capture().queuedEntries(),
                "special-stage WAIT2 completion must append Bombs before advancing its one-shot phase");
    }

    @Test
    void bothNormalEndActOwnersReplaceResultsPlcAtTheirActualHandoff() throws Exception {
        TestObjectServices services = new TestObjectServices().withGameModule(GameServices.module());
        SignpostObjectInstance signpost = new SignpostObjectInstance(new ObjectSpawn(0, 0, 0, 0, 0, false, 0),
                "signpost");
        EggPrisonObjectInstance eggPrison = new EggPrisonObjectInstance(new ObjectSpawn(0, 0, 0, 0, 0, false, 0),
                "capsule");
        invokeResultsOwner(signpost, services, "spawnResultsScreen",
                org.mockito.Mockito.mock(com.openggf.sprites.playable.AbstractPlayableSprite.class));
        assertEquals(expectedDescriptors(38), GameServices.module().getGameService(Sonic2PlcService.class)
                .capture().queuedEntries(), "S2 signpost must replace results art at Load_EndOfAct");

        invokeResultsOwner(eggPrison, services, "triggerEndOfAct");
        assertEquals(expectedDescriptors(38), GameServices.module().getGameService(Sonic2PlcService.class)
                .capture().queuedEntries(), "S2 EggPrison must replace results art at Load_EndOfAct");
    }

    @Test
    void specialStageResultsOwnerReplacesStd1BeforeResultsLoop() throws Exception {
        new Sonic2SpecialStageProvider().resetForResults();
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        assertEquals(expectedDescriptors(0), queue.capture().queuedEntries(),
                "S2 special-stage results boundary must replace Std1 before the results loop");
    }

    @Test
    void specialStageResultsRetriesRejectedReplacementExactlyOnceAfterActiveDecode() throws Exception {
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        queue.append(0);
        queue.prepare();
        assertNotNull(queue.capture().activeEntry(), "fixture must force a native transaction rejection");

        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        provider.resetForResults();
        drainActive(queue);
        provider.onEnterResults();

        List<NemesisPlcQueueSnapshot.Entry> expected = expectedDescriptors(0);
        assertEquals(expected, queue.capture().queuedEntries(),
                "S2 results retry must publish Std1 once the decoder is idle");
        provider.onEnterResults();
        assertEquals(expected, queue.capture().queuedEntries(),
                "S2 results retry must not duplicate its successful replacement");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void specialStageResultsRewindRestoresPendingRetryAndCompletedNoDuplicateDirection() throws Exception {
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        queue.append(0);
        queue.prepare();
        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        provider.resetForResults();
        RewindSnapshottable adapter = (RewindSnapshottable) provider.rewindAdapter().orElseThrow();
        Object pending = adapter.capture();

        drainActive(queue);
        provider.onEnterResults();
        List<NemesisPlcQueueSnapshot.Entry> expected = expectedDescriptors(0);
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
    void everyAuditedS2CuePublishesItsRomDescriptorsInOperationOrder() throws Exception {
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        int[] appendIds = {1, 2, 9, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49,
                50, 51, 52, 55, 57, 58, 59, 61, 62, 63, 64, 65};
        for (int plcId : appendIds) {
            queue.transact(Sonic2PlcService.clearOperation(), Sonic2PlcService.appendOperation(plcId));
            assertEquals(expectedDescriptors(plcId), queue.capture().queuedEntries(),
                    "S2 audited append PLC " + plcId);
        }
        for (int plcId : new int[] {0, 38, 66}) {
            queue.transact(Sonic2PlcService.replaceOperation(plcId));
            assertEquals(expectedDescriptors(plcId), queue.capture().queuedEntries(),
                    "S2 audited replace PLC " + plcId);
        }
    }

    @Test
    void ordinaryBossOwnerContractKeepsCapsuleThenAnimalThenExplosionOrder() throws Exception {
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        int[] animalIds = {50, 52, 59, 51, 57, 58, 52, 55};
        for (int animal : animalIds) {
            queue.transact(Sonic2PlcService.clearOperation(),
                    Sonic2PlcService.appendOperation(Sonic2Constants.PLC_CAPSULE),
                    Sonic2PlcService.appendOperation(animal),
                    Sonic2PlcService.appendOperation(Sonic2Constants.PLC_EXPLOSION));
            assertEquals(expectedDescriptors(Sonic2Constants.PLC_CAPSULE, animal,
                    Sonic2Constants.PLC_EXPLOSION), queue.capture().queuedEntries(),
                    "ordinary boss animal cue " + animal);
        }
    }

    private static List<NemesisPlcQueueSnapshot.Entry> expectedDescriptors(int... ids)
            throws IOException {
        List<NemesisPlcQueueSnapshot.Entry> result = new ArrayList<>();
        for (int id : ids) {
            PlcParser.PlcDefinition definition = PlcParser.parse(GameServices.rom().getRom(),
                    Sonic2Constants.ART_LOAD_CUES_ADDR, id);
            List<Integer> counts = NemesisPlcPatternCounts.derive(GameServices.rom().getRom(), definition);
            for (int index = 0; index < definition.entries().size(); index++) {
                PlcParser.PlcEntry entry = definition.entries().get(index);
                int count = counts.get(index);
                result.add(new NemesisPlcQueueSnapshot.Entry(entry.romAddr(), entry.tileIndex(), count, count));
            }
        }
        return result;
    }

    private static void drainActive(Sonic2PlcService queue) {
        while (queue.capture().activeEntry() != null) {
            queue.serviceNormalVBlank();
        }
    }

    private static void invokeResultsOwner(Object owner, TestObjectServices services, String method,
                                           Object... arguments) throws Exception {
        ObjectConstructionContext.with(services, () -> {
            ((com.openggf.level.objects.AbstractObjectInstance) owner).setServices(services);
            try {
                Class<?>[] types = new Class<?>[arguments.length];
                for (int index = 0; index < arguments.length; index++) {
                    types[index] = com.openggf.sprites.playable.AbstractPlayableSprite.class;
                }
                java.lang.reflect.Method handoff = owner.getClass().getDeclaredMethod(method, types);
                handoff.setAccessible(true);
                handoff.invoke(owner, arguments);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("normal end-act owner handoff unavailable", failure);
            }
        });
    }

    private static RuntimeFixture installRuntime(int romZone) throws Exception {
        GraphicsManager.getInstance().initHeadless();
        GameplayModeContext gameplay = TestEnvironment.activeGameplayMode();
        Sonic2ObjectArtProvider provider =
                (Sonic2ObjectArtProvider) GameServices.module().getObjectArtProvider();
        provider.loadArtForZone(romZone);
        LevelManager manager = gameplay.getLevelManager();
        setObjectRenderManager(manager, new ObjectRenderManager(provider));
        gameplay.attachLevelManagers(
                gameplay.getWaterSystem(), gameplay.getParallaxManager(),
                gameplay.getTerrainCollisionManager(), gameplay.getCollisionSystem(),
                gameplay.getSpriteManager(), manager);
        setCurrentLevel(manager, gameplay);
        manager.refreshObjectArtPatterns();
        return new RuntimeFixture(provider);
    }

    private static void setObjectRenderManager(LevelManager manager, ObjectRenderManager renderManager)
            throws ReflectiveOperationException {
        Field field = LevelManager.class.getDeclaredField("objectRenderManager");
        field.setAccessible(true);
        field.set(manager, renderManager);
    }

    private static void setCurrentLevel(LevelManager manager, GameplayModeContext gameplay)
            throws ReflectiveOperationException {
        Level level = org.mockito.Mockito.mock(Level.class);
        Field field = LevelManager.class.getDeclaredField("level");
        field.setAccessible(true);
        field.set(manager, level);
        gameplay.getWorldSession().setCurrentLevel(level);
    }

    private record EventRoute(String name, int romZone, Supplier<Sonic2ZoneEvents> owner,
                              int act, int routine, int cameraX, int cameraY, int plcId,
                              Consumer<Sonic2ZoneEvents> beforeUpdate) {
    }

    private record RuntimeFixture(Sonic2ObjectArtProvider provider) {
    }
}
