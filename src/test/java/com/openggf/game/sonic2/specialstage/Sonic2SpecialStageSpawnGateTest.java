package com.openggf.game.sonic2.specialstage;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.SpecialStageStartupPolicy;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Sonic2SpecialStageSpawnGateTest {

    private Rom rom;
    private Sonic2SpecialStageManager manager;

    @BeforeEach
    void bootSpecialStage() throws Exception {
        Path romPath = Path.of("s2.gen");
        assumeTrue(Files.isRegularFile(romPath), "s2.gen ROM required for spawn-gate tests");

        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();

        rom = new Rom();
        assertTrue(rom.open(romPath.toAbsolutePath().toString()), "s2.gen should open");
        TestEnvironment.configureRomFixture(rom);
        GraphicsManager.getInstance().initHeadless();

        GameServices.configuration()
                .setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GameServices.configuration()
                .setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

        Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
        provider.initializeStage(0, SpecialStageStartupPolicy.TRACE_ACCURATE);
        provider.setLagCompensation(0);
        manager = provider.getManager();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
        if (rom != null) {
            rom.close();
        }
    }

    @Test
    void spawnedPlayersStayInInitUntilSecondDrawingIndexWrapRunsObj09AndObj10() {
        assertEquals(2, manager.getPlayers().size(),
                "players stay constructed while their ROM object slots are absent");
        assertTrue(manager.getPlayers().stream().noneMatch(Sonic2SpecialStagePlayer::isSpawned));

        for (int frame = 0; frame < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; frame++) {
            manager.update();

            Sonic2SpecialStageComparisonState state = manager.captureComparisonState();
            assertNull(state.sonic(), "Sonic must be absent after suppressed update " + frame);
            assertNull(state.tails(), "Tails must be absent after suppressed update " + frame);
        }
        assertEquals(Sonic2SpecialStageIntro.Phase.ROM_STARTUP,
                manager.getIntro().getCurrentPhase());

        for (int step = 23; step <= 31; step++) {
            manager.update();

            Sonic2SpecialStageComparisonState presentButUninitialized = manager.captureComparisonState();
            assertNotNull(presentButUninitialized.sonic(), "Sonic id is present at replay step " + step);
            assertNotNull(presentButUninitialized.tails(), "Tails id is present at replay step " + step);
            assertEquals("INIT", presentButUninitialized.sonic().routine());
            assertEquals("INIT", presentButUninitialized.tails().routine());
            assertEquals(0, presentButUninitialized.sonic().ssY());
            assertEquals(0, presentButUninitialized.tails().ssY());
            assertEquals((step - 22) % 5, presentButUninitialized.drawingIndex());
            assertEquals(step < 27
                            ? Sonic2SpecialStageManager.PlayerBootstrapPhase.WAIT_FIRST_DRAWING_WRAP
                            : Sonic2SpecialStageManager.PlayerBootstrapPhase.WAIT_SECOND_DURATION_WRAP,
                    manager.captureRewindSnapshot().playerBootstrapPhase);
            Sonic2SpecialStageSnapshot bootstrapSnapshot = manager.captureRewindSnapshot();
            assertEquals(step <= 27 ? -1 : presentButUninitialized.drawingIndex(),
                    bootstrapSnapshot.lastDrawingIndex,
                    "only the second startup wait may run SSObjectsManager at step " + step);
            if (step == 26) {
                assertEquals(-1, bootstrapSnapshot.objectManager.lastProcessedSegment(),
                        "the first drawing-index-4 wait must not allocate a segment");
            } else if (step == 31) {
                assertEquals(0, bootstrapSnapshot.objectManager.lastProcessedSegment(),
                        "the second drawing-index-4 wait must allocate segment zero");
            }
            assertTrue(manager.getPlayers().stream().allMatch(Sonic2SpecialStagePlayer::isSpawned));
        }

        manager.update();

        Sonic2SpecialStageComparisonState initialized = manager.captureComparisonState();
        assertEquals("NORMAL", initialized.sonic().routine());
        assertEquals("NORMAL", initialized.tails().routine());
        assertEquals(0x80, initialized.sonic().ssY());
        assertEquals(0x80, initialized.tails().ssY());
        assertEquals(0x6E, initialized.sonic().ssZ());
        assertEquals(0x80, initialized.tails().ssZ());
        assertEquals(Sonic2SpecialStageManager.PlayerBootstrapPhase.INITIALIZED,
                manager.captureRewindSnapshot().playerBootstrapPhase);
        assertEquals(Sonic2SpecialStageIntro.Phase.FADE_FROM_WHITE,
                manager.getIntro().getCurrentPhase());
    }

    @Test
    void rewindIntoPreRollRestoresUnspawnedPlayersWithoutChangingTopology() {
        for (int frame = 0; frame < 7; frame++) {
            manager.update();
        }

        Sonic2SpecialStagePlayer sonic = manager.getSonicPlayer();
        Sonic2SpecialStagePlayer tails = manager.getTailsPlayer();
        List<Sonic2SpecialStagePlayer.PlayerType> topology = manager.getPlayers().stream()
                .map(Sonic2SpecialStagePlayer::getPlayerType)
                .toList();
        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();

        for (int frame = 7; frame <= Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; frame++) {
            manager.update();
        }
        assertNotNull(manager.captureComparisonState().sonic());
        assertNotNull(manager.captureComparisonState().tails());

        manager.restoreRewindSnapshot(snapshot);

        assertEquals(2, manager.getPlayers().size());
        assertEquals(topology, manager.getPlayers().stream()
                .map(Sonic2SpecialStagePlayer::getPlayerType)
                .toList());
        assertSame(sonic, manager.getSonicPlayer(), "rewind must preserve Sonic's constructed slot");
        assertSame(tails, manager.getTailsPlayer(), "rewind must preserve Tails' constructed slot");
        assertTrue(manager.getPlayers().stream().noneMatch(Sonic2SpecialStagePlayer::isSpawned));
        assertNull(manager.captureComparisonState().sonic());
        assertNull(manager.captureComparisonState().tails());

        for (int frame = 7; frame < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; frame++) {
            manager.update();
        }
        assertNull(manager.captureComparisonState().sonic(),
                "restored final pre-roll update must still compare absent");
        assertNull(manager.captureComparisonState().tails(),
                "restored final pre-roll update must still compare absent");

        manager.update();
        assertNotNull(manager.captureComparisonState().sonic());
        assertNotNull(manager.captureComparisonState().tails());
    }

    @Test
    void rewindRestoresSecondWrapBootstrapPhaseAndPreInitPlayerFields() {
        for (int frame = 0; frame < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; frame++) {
            manager.update();
        }
        for (int step = 23; step <= 28; step++) {
            manager.update();
        }

        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        assertEquals(Sonic2SpecialStageManager.PlayerBootstrapPhase.WAIT_SECOND_DURATION_WRAP,
                snapshot.playerBootstrapPhase);
        assertEquals(Sonic2SpecialStagePlayer.RoutineState.INIT, manager.getSonicPlayer().getRoutine());
        assertEquals(0, manager.getSonicPlayer().getSSYPos());

        for (int step = 29; step <= 32; step++) {
            manager.update();
        }
        assertEquals(Sonic2SpecialStagePlayer.RoutineState.NORMAL, manager.getSonicPlayer().getRoutine());

        manager.restoreRewindSnapshot(snapshot);

        assertEquals(Sonic2SpecialStageManager.PlayerBootstrapPhase.WAIT_SECOND_DURATION_WRAP,
                manager.captureRewindSnapshot().playerBootstrapPhase);
        assertEquals(Sonic2SpecialStagePlayer.RoutineState.INIT, manager.getSonicPlayer().getRoutine());
        assertEquals(0, manager.getSonicPlayer().getSSYPos());
        assertEquals(0, manager.getSonicPlayer().getSSZPos());
        assertEquals(0, manager.getSonicPlayer().getAngle());

        for (int step = 29; step <= 32; step++) {
            manager.update();
        }
        assertEquals(Sonic2SpecialStagePlayer.RoutineState.NORMAL, manager.getSonicPlayer().getRoutine());
        assertEquals(0x80, manager.getSonicPlayer().getSSYPos());
    }

    @Test
    void secondWrapInitializesPlayerSlotsBeforeActiveObjectExecution() {
        List<String> observedOrder = new ArrayList<>();
        manager.getObjectManager().getActiveObjects().add(new Sonic2SpecialStageRing() {
            @Override
            public void update(
                    int currentTrackFrame,
                    boolean trackFlipped,
                    int speedFactor,
                    boolean drawingIndex4) {
                observedOrder.add("objects:"
                        + manager.getSonicPlayer().getRoutine() + ":"
                        + manager.getTailsPlayer().getRoutine() + ":track="
                        + manager.captureComparisonState().trackAnimFrame());
            }
        });

        for (int frame = 0; frame < Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; frame++) {
            manager.update();
        }
        for (int step = 23; step <= 32; step++) {
            manager.update();
        }
        observedOrder.add("players:" + manager.getSonicPlayer().getRoutine() + ":"
                + manager.getTailsPlayer().getRoutine());

        assertEquals(List.of("objects:NORMAL:NORMAL:track=2", "players:NORMAL:NORMAL"), observedOrder,
                "RunObjects-equivalent ring execution must follow Obj09/Obj10 scalar initialization");
    }

    @Test
    void playerSpawnBoundaryIsIndependentFromInitialSpeedPromotion() throws Exception {
        Field speedPromotionPending = Sonic2SpecialStageManager.class
                .getDeclaredField("speedPromotionPending");
        speedPromotionPending.setAccessible(true);
        speedPromotionPending.setBoolean(manager, false);

        for (int frame = 0; frame <= Sonic2SpecialStageIntro.PRE_ROLL_FRAMES; frame++) {
            manager.update();
        }

        assertNotNull(manager.captureComparisonState().sonic());
        assertNotNull(manager.captureComparisonState().tails());
        assertEquals(0, manager.captureComparisonState().speedFactor(),
                "consuming the speed latch must not consume the player-spawn boundary");
    }
}
