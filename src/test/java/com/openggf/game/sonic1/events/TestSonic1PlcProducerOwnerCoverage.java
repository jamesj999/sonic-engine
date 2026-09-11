package com.openggf.game.sonic1.events;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.level.resources.NemesisPlcPatternCounts;
import com.openggf.level.resources.PlcParser;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the concrete S1 DLE owners at their audited camera boundaries.
 * The queue assertions are deliberately after the owner update: a literal
 * service submission would not prove the boss/event transition remained wired.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(SingletonResetExtension.class)
@RequiresRom(SonicGame.SONIC_1)
class TestSonic1PlcProducerOwnerCoverage {
    @BeforeEach
    void setUp() throws Exception {
        GameServices.module().createGame(TestEnvironment.currentRom());
        TestEnvironment.activeGameplayMode();
        // SBZ2's real threshold first spawns the false floor, then publishes
        // PLC 30.  Supply the normal manager dependency so the test reaches
        // the production queue call rather than bypassing the preceding owner
        // action.
        Field objectManager = GameServices.level().getClass().getDeclaredField("objectManager");
        objectManager.setAccessible(true);
        objectManager.set(GameServices.level(), org.mockito.Mockito.mock(ObjectManager.class));
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventRoutes")
    void eventOwnerSubmitsBossCueAtItsNativeThreshold(EventRoute route) throws Exception {
        Sonic1ZoneEvents owner = route.owner().get();
        owner.init();
        owner.setEventRoutine(route.routine());
        GameServices.camera().setX((short) route.cameraX());
        GameServices.camera().setY((short) route.cameraY());

        if (owner instanceof Sonic1SBZEvents sbz && route.finalZone()) {
            sbz.updateFZ();
        } else {
            owner.update(route.act());
        }

        Sonic1PlcService queue = GameServices.module().getGameService(Sonic1PlcService.class);
        assertEquals(expectedDescriptors(route.plcId()), queue.capture().queuedEntries(),
                route.name() + " must leave the owner-selected ROM descriptor in the FIFO");
    }

    private static Stream<EventRoute> eventRoutes() {
        return Stream.of(
                new EventRoute("GHZ3 boss", Sonic1GHZEvents::new, 2, 2, 0x2960, 0, 17, false),
                new EventRoute("LZ3 boss", Sonic1LZEvents::new, 2, 0, 0x1CA0, 0, 17, false),
                new EventRoute("MZ3 boss", Sonic1MZEvents::new, 2, 0, 0x17F0, 0, 17, false),
                new EventRoute("SLZ3 boss", Sonic1SLZEvents::new, 2, 2, 0x2000, 0, 17, false),
                new EventRoute("SYZ3 boss", Sonic1SYZEvents::new, 2, 2, 0x2C00, 0, 17, false),
                new EventRoute("SBZ2 false-floor boundary", Sonic1SBZEvents::new, 1, 2, 0x1EB0, 0, 30, false),
                new EventRoute("FZ art boundary", Sonic1SBZEvents::new, 0, 0, 0x2148, 0, 31, true));
    }

    private static List<NemesisPlcQueueSnapshot.Entry> expectedDescriptors(int plcId)
            throws IOException {
        PlcParser.PlcDefinition definition = PlcParser.parse(GameServices.rom().getRom(),
                Sonic1Constants.ART_LOAD_CUES_ADDR, plcId);
        List<Integer> counts = NemesisPlcPatternCounts.derive(GameServices.rom().getRom(), definition);
        List<NemesisPlcQueueSnapshot.Entry> expected = new ArrayList<>();
        for (int i = 0; i < definition.entries().size(); i++) {
            PlcParser.PlcEntry entry = definition.entries().get(i);
            int count = counts.get(i);
            expected.add(new NemesisPlcQueueSnapshot.Entry(entry.romAddr(), entry.tileIndex(), count, count));
        }
        return expected;
    }

    private record EventRoute(String name, Supplier<Sonic1ZoneEvents> owner, int act,
                              int routine, int cameraX, int cameraY, int plcId,
                              boolean finalZone) {
    }
}
