package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.GameServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2ObjectArtProvider;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.resources.NemesisPlcPatternCounts;
import com.openggf.level.resources.PlcParser;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes each ordinary boss's actual killing-hit hook, not a PLC facade. */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(SingletonResetExtension.class)
@RequiresRom(SonicGame.SONIC_2)
class TestSonic2BossPlcProducerCoverage {
    @BeforeEach
    void setUp() throws Exception {
        GameServices.module().createGame(TestEnvironment.currentRom());
        GameplayModeContext gameplay = TestEnvironment.activeGameplayMode();
        GraphicsManager.getInstance().initHeadless();
        Sonic2ObjectArtProvider provider =
                (Sonic2ObjectArtProvider) GameServices.module().getObjectArtProvider();
        provider.loadArtForZone(Sonic2ZoneConstants.ROM_ZONE_EHZ);
        LevelManager manager = gameplay.getLevelManager();
        Field render = LevelManager.class.getDeclaredField("objectRenderManager");
        render.setAccessible(true);
        render.set(manager, new ObjectRenderManager(provider));
        Field level = LevelManager.class.getDeclaredField("level");
        level.setAccessible(true);
        Level liveLevel = org.mockito.Mockito.mock(Level.class);
        level.set(manager, liveLevel);
        gameplay.getWorldSession().setCurrentLevel(liveLevel);
        manager.refreshObjectArtPatterns();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void rewindRetainsPendingDefeatEntryWithoutReplayingTheKillingHit() throws Exception {
        var captured = com.openggf.game.rewind.GenericFieldCapturer
                .defaultObjectSubclassCapturedFieldsForAudit(Sonic2EHZBossInstance.class)
                .stream().map(java.lang.reflect.Field::getName).toList();
        assertTrue(captured.contains("defeatEntryPending"),
                "rewind schema must capture rejected defeat-entry work");
        assertTrue(captured.contains("defeatEntryPrepared"),
                "rewind schema must capture successful defeat-entry publication");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bosses")
    void killingHitOwnerAppendsCapsulePlc(BossRoute route) throws Exception {
        TestObjectServices services = new TestObjectServices()
                .withGameModule(GameServices.module())
                .withLevelManager(GameServices.level())
                .withCamera(GameServices.camera())
                .withGameState(GameServices.gameState());
        ObjectConstructionContext.with(services, () -> route.invoke().accept(services));
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        assertEquals(expectedDescriptors(Sonic2Constants.PLC_CAPSULE), queue.capture().queuedEntries(),
                route.name() + " must append the capsule PLC from its real killing-hit owner");
        assertPreparedRenderersPublished(Sonic2Constants.PLC_CAPSULE);
    }

    /**
     * Drives the eight distinct post-defeat handoffs that call the ROM
     * LoadPLC_AnimalExplosion helper.  This deliberately enters the concrete
     * owner method at its native timer/tertiary boundary; asserting merely
     * that the shared request façade accepts IDs would miss every one of
     * these game-specific handoffs.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("animalExplosionRoutes")
    void postDefeatOwnerAppendsZoneAnimalThenExplosionAtNativeBoundary(BossRoute route)
            throws Exception {
        TestObjectServices services = new TestObjectServices()
                .withGameModule(GameServices.module())
                .withLevelManager(GameServices.level())
                .withCamera(GameServices.camera())
                .withGameState(GameServices.gameState());
        ObjectConstructionContext.with(services, () -> route.invoke().accept(services));
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        assertEquals(expectedDescriptors(route.animalPlc(), Sonic2Constants.PLC_EXPLOSION),
                queue.capture().queuedEntries(),
                route.name() + " must append its zone animal before the explosion PLC");
        assertPreparedRenderersPublished(route.animalPlc(), Sonic2Constants.PLC_EXPLOSION);
    }

    /**
     * A full native FIFO rejects the first attempt.  Each concrete handoff
     * must retain its semantic gate until that submission succeeds, then
     * publish precisely one animal/explosion batch on retry.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("animalExplosionRetryRoutes")
    void postDefeatAnimalHandoffRetriesRejectedBatchExactlyOnce(RetryRoute route) throws Exception {
        TestObjectServices services = new TestObjectServices()
                .withGameModule(GameServices.module())
                .withLevelManager(GameServices.level())
                .withCamera(GameServices.camera())
                .withGameState(GameServices.gameState());
        Sonic2PlcService queue = GameServices.module().getGameService(Sonic2PlcService.class);
        fillQueueToNativeCapacity(queue);

        AbstractObjectInstance[] created = new AbstractObjectInstance[1];
        ObjectConstructionContext.with(services, () -> created[0] = route.create().get());
        AbstractObjectInstance boss = created[0];
        route.prepare().accept(boss, services);
        route.invoke().accept(boss);
        assertEquals(15, queue.capture().queuedEntries().size(),
                route.name() + " must leave a rejected batch unpublished");

        queue.clearQueued();
        route.invoke().accept(boss);
        List<NemesisPlcQueueSnapshot.Entry> expected = expectedDescriptors(
                route.animalPlc(), Sonic2Constants.PLC_EXPLOSION);
        assertEquals(expected, queue.capture().queuedEntries(),
                route.name() + " must retry its retained native handoff");

        route.invoke().accept(boss);
        assertEquals(expected, queue.capture().queuedEntries(),
                route.name() + " must not duplicate the successful handoff");
    }

    private static void assertPreparedRenderersPublished(int... plcIds) throws Exception {
        Sonic2ObjectArtProvider provider =
                (Sonic2ObjectArtProvider) GameServices.module().getObjectArtProvider();
        provider.preparePlcs(plcIds).sheets().forEach(sheet ->
                assertEquals(true, provider.getRenderer(sheet.key()) != null,
                        "owner publication must leave eager renderer available for " + sheet.key()));
    }

    private static Stream<BossRoute> bosses() {
        return Stream.of(
                boss("EHZ", s -> { Sonic2EHZBossInstance b = new Sonic2EHZBossInstance(spawn()); b.setServices(s); b.onDefeatStarted(); }),
                boss("HTZ", s -> { Sonic2HTZBossInstance b = new Sonic2HTZBossInstance(spawn()); b.setServices(s); b.onDefeatStarted(); }),
                boss("ARZ", s -> { Sonic2ARZBossInstance b = new Sonic2ARZBossInstance(spawn()); b.setServices(s); b.onDefeatStarted(); }),
                boss("MCZ", s -> { Sonic2MCZBossInstance b = new Sonic2MCZBossInstance(spawn()); b.setServices(s); b.onDefeatStarted(); }),
                boss("CNZ", s -> { Sonic2CNZBossInstance b = new Sonic2CNZBossInstance(spawn()); b.setServices(s); b.onDefeatStarted(); }),
                boss("CPZ", s -> { Sonic2CPZBossInstance b = new Sonic2CPZBossInstance(spawn()); b.setServices(s); b.onDefeatStarted(); }),
                boss("MTZ", TestSonic2BossPlcProducerCoverage::startMtzDefeat),
                boss("OOZ", s -> { Sonic2OOZBossInstance b = new Sonic2OOZBossInstance(spawn()); b.setServices(s); b.onDefeatStarted(); }));
    }

    private static Stream<BossRoute> animalExplosionRoutes() {
        return Stream.of(
                handoff("EHZ flying-off tertiary-2 expiry", Sonic2Constants.PLC_ANIMALS_EHZ,
                        TestSonic2BossPlcProducerCoverage::invokeEhzHandoff),
                handoff("HTZ defeated flee boundary", Sonic2Constants.PLC_ANIMALS_HTZ_MTZ_WFZ,
                        s -> invokeHandoff(new Sonic2HTZBossInstance(spawn()), s,
                                "updateDefeated", new String[] {"defeatTimer"}, new int[] {-0x3B})),
                handoff("MCZ hover-down countdown 0x18", Sonic2Constants.PLC_ANIMALS_MCZ,
                        s -> invokeHandoff(new Sonic2MCZBossInstance(spawn()), s,
                                "updateSubAHoverDown", new String[] {"countdown"}, new int[] {0x17})),
                handoff("CNZ defeat-bounce countdown 0x18", Sonic2Constants.PLC_ANIMALS_CNZ,
                        s -> invokeHandoff(new Sonic2CNZBossInstance(spawn()), s,
                                "updateDefeatBounce", new String[] {"bossCountdown"}, new int[] {0x17})),
                handoff("CPZ level-music handoff", Sonic2Constants.PLC_ANIMALS_CPZ,
                        s -> invokeHandoff(new Sonic2CPZBossInstance(spawn()), s,
                                "updateMainStopExploding", new String[] {"defeatTimer"}, new int[] {0x2F})),
                handoff("MTZ first flee pass", Sonic2Constants.PLC_ANIMALS_HTZ_MTZ_WFZ,
                        s -> invokeHandoff(new Sonic2MTZBossInstance(spawn()), s,
                                "updateSub12Flee", new String[0], new int[0])),
                handoff("OOZ defeated-flag handoff", Sonic2Constants.PLC_ANIMALS_OOZ,
                        s -> invokeHandoff(new Sonic2OOZBossInstance(spawn()), s,
                                "updateMainDefeated", new String[] {"bossCountdown"}, new int[] {0}, 0)),
                handoff("ARZ defeated ascent countdown 0x18", Sonic2Constants.PLC_ANIMALS_ARZ,
                        TestSonic2BossPlcProducerCoverage::invokeArzHandoff));
    }

    private static Stream<RetryRoute> animalExplosionRetryRoutes() {
        return Stream.of(
                retry("EHZ flying-off tertiary-2 expiry", Sonic2Constants.PLC_ANIMALS_EHZ,
                        () -> new Sonic2EHZBossInstance(spawn()), TestSonic2BossPlcProducerCoverage::prepareEhz,
                        boss -> invokeNoArgs(boss, "updateSubAFlyingOff")),
                retry("HTZ defeated flee boundary", Sonic2Constants.PLC_ANIMALS_HTZ_MTZ_WFZ,
                        () -> new Sonic2HTZBossInstance(spawn()), (boss, services) -> prepare(boss, services,
                                "defeatTimer", -0x3B),
                        boss -> invokeNoArgs(boss, "updateDefeated")),
                retry("MCZ hover-down countdown 0x18", Sonic2Constants.PLC_ANIMALS_MCZ,
                        () -> new Sonic2MCZBossInstance(spawn()), (boss, services) -> prepare(boss, services,
                                "countdown", 0x17),
                        boss -> invokeNoArgs(boss, "updateSubAHoverDown")),
                retry("CNZ defeat-bounce countdown 0x18", Sonic2Constants.PLC_ANIMALS_CNZ,
                        () -> new Sonic2CNZBossInstance(spawn()), (boss, services) -> prepare(boss, services,
                                "bossCountdown", 0x17),
                        boss -> invokeNoArgs(boss, "updateDefeatBounce")),
                retry("CPZ level-music handoff", Sonic2Constants.PLC_ANIMALS_CPZ,
                        () -> new Sonic2CPZBossInstance(spawn()), (boss, services) -> prepare(boss, services,
                                "defeatTimer", 0x2F),
                        boss -> invokeNoArgs(boss, "updateMainStopExploding")),
                retry("MTZ first flee pass", Sonic2Constants.PLC_ANIMALS_HTZ_MTZ_WFZ,
                        () -> new Sonic2MTZBossInstance(spawn()), (boss, services) -> prepare(boss, services),
                        boss -> invokeNoArgs(boss, "updateSub12Flee")),
                retry("OOZ defeated-flag handoff", Sonic2Constants.PLC_ANIMALS_OOZ,
                        () -> new Sonic2OOZBossInstance(spawn()), (boss, services) -> prepare(boss, services,
                                "bossCountdown", 0),
                        boss -> invoke(boss, "updateMainDefeated", 0)),
                retry("ARZ defeated ascent countdown 0x18", Sonic2Constants.PLC_ANIMALS_ARZ,
                        () -> new Sonic2ARZBossInstance(spawn()), (boss, services) -> prepare(boss, services,
                                "bossCountdown", 0x17),
                        boss -> invoke(boss, "updateMainSubA",
                                new Class<?>[] {com.openggf.sprites.playable.AbstractPlayableSprite.class},
                                org.mockito.Mockito.mock(com.openggf.sprites.playable.AbstractPlayableSprite.class)))
        );
    }

    private static BossRoute boss(String name, Consumer<TestObjectServices> invoke) {
        return new BossRoute(name, invoke, -1);
    }

    private static BossRoute handoff(String name, int animalPlc, Consumer<TestObjectServices> invoke) {
        return new BossRoute(name, invoke, animalPlc);
    }

    private static RetryRoute retry(String name, int animalPlc,
                                    Supplier<? extends AbstractObjectInstance> create,
                                    RetryPreparation prepare, Consumer<AbstractObjectInstance> invoke) {
        return new RetryRoute(name, animalPlc, create, prepare, invoke);
    }

    private static ObjectSpawn spawn() {
        return new ObjectSpawn(0, 0, 0, 0, 0, false, 0);
    }

    private static void startMtzDefeat(TestObjectServices services) {
        Sonic2MTZBossInstance boss = new Sonic2MTZBossInstance(spawn());
        boss.setServices(services);
        boss.onDefeatStarted();
        try {
            Method apply = Sonic2MTZBossInstance.class
                    .getDeclaredMethod("applyPendingHitReactionAfterMove");
            apply.setAccessible(true);
            apply.invoke(boss);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("MTZ's production post-move defeat boundary is unavailable", e);
        }
    }

    private static void invokeHandoff(Object boss, TestObjectServices services, String method,
                                      String[] fields, int[] values, Object... arguments) {
        try {
            ((com.openggf.level.objects.AbstractObjectInstance) boss).setServices(services);
            for (int index = 0; index < fields.length; index++) {
                Field field = boss.getClass().getDeclaredField(fields[index]);
                field.setAccessible(true);
                field.setInt(boss, values[index]);
            }
            Class<?>[] types = new Class<?>[arguments.length];
            for (int index = 0; index < arguments.length; index++) {
                types[index] = arguments[index] instanceof Integer ? int.class
                        : arguments[index] == null ? com.openggf.sprites.playable.AbstractPlayableSprite.class
                        : arguments[index].getClass();
            }
            Method target = boss.getClass().getDeclaredMethod(method, types);
            target.setAccessible(true);
            target.invoke(boss, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to drive production handoff " + method, failure);
        }
    }

    private static void invokeEhzHandoff(TestObjectServices services) {
        Sonic2EHZBossInstance boss = new Sonic2EHZBossInstance(spawn());
        try {
            boss.setServices(services);
            Field waitTimer = Sonic2EHZBossInstance.class.getDeclaredField("waitTimer");
            waitTimer.setAccessible(true);
            waitTimer.setInt(boss, 0);
            Field state = findField(boss.getClass(), "state");
            Object bossState = state.get(boss);
            Field tertiary = bossState.getClass().getField("routineTertiary");
            tertiary.setInt(bossState, 2);
            Method method = Sonic2EHZBossInstance.class.getDeclaredMethod("updateSubAFlyingOff");
            method.setAccessible(true);
            method.invoke(boss);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to drive EHZ's tertiary-2 handoff", failure);
        }
    }

    private static void invokeArzHandoff(TestObjectServices services) {
        Sonic2ARZBossInstance boss = new Sonic2ARZBossInstance(spawn());
        try {
            boss.setServices(services);
            Field countdown = Sonic2ARZBossInstance.class.getDeclaredField("bossCountdown");
            countdown.setAccessible(true);
            countdown.setInt(boss, 0x17);
            Method method = Sonic2ARZBossInstance.class.getDeclaredMethod("updateMainSubA",
                    com.openggf.sprites.playable.AbstractPlayableSprite.class);
            method.setAccessible(true);
            method.invoke(boss, org.mockito.Mockito.mock(com.openggf.sprites.playable.AbstractPlayableSprite.class));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to drive ARZ's defeated-ascent handoff", failure);
        }
    }

    private static void prepareEhz(AbstractObjectInstance boss, TestObjectServices services) {
        try {
            boss.setServices(services);
            Field waitTimer = Sonic2EHZBossInstance.class.getDeclaredField("waitTimer");
            waitTimer.setAccessible(true);
            waitTimer.setInt(boss, 0);
            Field state = findField(boss.getClass(), "state");
            Object bossState = state.get(boss);
            Field tertiary = bossState.getClass().getField("routineTertiary");
            tertiary.setInt(bossState, 2);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to prepare EHZ's tertiary-2 handoff", failure);
        }
    }

    private static void prepare(AbstractObjectInstance boss, TestObjectServices services,
                                Object... fieldAndValues) {
        try {
            boss.setServices(services);
            for (int index = 0; index < fieldAndValues.length; index += 2) {
                Field field = findField(boss.getClass(), (String) fieldAndValues[index]);
                field.setInt(boss, (Integer) fieldAndValues[index + 1]);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to prepare production retry handoff", failure);
        }
    }

    private static void invokeNoArgs(AbstractObjectInstance boss, String method) {
        invoke(boss, method);
    }

    private static void invoke(AbstractObjectInstance boss, String method, Object... arguments) {
        Class<?>[] types = new Class<?>[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            types[index] = arguments[index] instanceof Integer ? int.class : arguments[index].getClass();
        }
        invoke(boss, method, types, arguments);
    }

    private static void invoke(AbstractObjectInstance boss, String method, Class<?>[] types,
                               Object... arguments) {
        try {
            Method target = boss.getClass().getDeclaredMethod(method, types);
            target.setAccessible(true);
            target.invoke(boss, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to drive production retry handoff " + method, failure);
        }
    }

    private static void fillQueueToNativeCapacity(Sonic2PlcService queue) throws IOException {
        while (queue.capture().queuedEntries().size() < 15) {
            int before = queue.capture().queuedEntries().size();
            try {
                queue.append(Sonic2Constants.PLC_CAPSULE);
            } catch (IllegalStateException ignored) {
                break;
            }
            if (queue.capture().queuedEntries().size() == before) {
                throw new AssertionError("test fixture could not fill the native PLC FIFO");
            }
        }
        assertEquals(15, queue.capture().queuedEntries().size(),
                "fixture must force the producer's append preflight to reject");
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
            try {
                Field field = candidate.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue to the state-owning base class.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static List<NemesisPlcQueueSnapshot.Entry> expectedDescriptors(int... ids) throws IOException {
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

    private record BossRoute(String name, Consumer<TestObjectServices> invoke, int animalPlc) {
    }

    private record RetryRoute(String name, int animalPlc,
                              Supplier<? extends AbstractObjectInstance> create,
                              RetryPreparation prepare, Consumer<AbstractObjectInstance> invoke) {
    }

    @FunctionalInterface
    private interface RetryPreparation {
        void accept(AbstractObjectInstance boss, TestObjectServices services);
    }
}
