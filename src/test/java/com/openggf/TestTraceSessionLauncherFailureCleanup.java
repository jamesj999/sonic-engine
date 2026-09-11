package com.openggf;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.GameServices;
import com.openggf.game.GameMode;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.trace.catalog.TraceCatalog;
import com.openggf.trace.catalog.TraceEntry;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTraceSessionLauncherFailureCleanup {

    @AfterEach
    void tearDown() {
        Engine.clearGlobalInstance();
        GameServices.playbackDebug().endSession();
        SessionManager.clear();
    }

    @Test
    void callbackAfterEngineTeardownRestoresConfigAndPendingAdmission() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TraceReplaySessionBootstrap.ConfigSnapshot original =
                TraceReplaySessionBootstrap.snapshotGameplayConfig();
        GameServices.configuration().setConfigValue(
                SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        try {
            TraceEntry entry = TraceCatalog.scan(
                            Path.of("src/test/resources/traces"))
                    .stream()
                    .filter(candidate -> "s1".equals(candidate.gameId()))
                    .filter(candidate -> "ghz1_fullrun".equals(
                            candidate.dir().getFileName().toString()))
                    .findFirst()
                    .orElseThrow();
            GameLoop loop = mock(GameLoop.class);
            when(loop.canLaunchGameNow()).thenReturn(true);
            installCurrentLoop(loop);
            ArgumentCaptor<Runnable> callback =
                    ArgumentCaptor.forClass(Runnable.class);
            doNothing().when(loop).launchGameByEntry(
                    any(MasterTitleScreen.GameEntry.class), callback.capture());

            assertTrue(TraceSessionLauncher.launch(entry));
            verify(loop).launchGameByEntry(
                    any(MasterTitleScreen.GameEntry.class), any(Runnable.class));
            Engine.clearGlobalInstance();
            callback.getValue().run();

            assertNull(TraceSessionLauncher.active());
            assertEquals("knuckles", GameServices.configuration().getConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE));
            assertEquals(HardwareReadinessAdmissionPolicy.LIVE,
                    SessionManager.openGameplaySession(new Sonic1GameModule())
                            .hardwareTiming().admissionPolicy());
        } finally {
            TraceReplaySessionBootstrap.restoreGameplayConfig(original);
        }
    }

    @Test
    void replayBootstrapWithoutActiveEngineRestoresConfigAndAdmission() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameServices.configuration().setConfigValue(
                SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        TraceReplaySessionBootstrap.ConfigSnapshot original =
                TraceReplaySessionBootstrap.snapshotGameplayConfig();
        GameServices.configuration().setConfigValue(
                SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        TraceSessionLauncher session = presentationSession(original);
        GameLoop loop = mock(GameLoop.class);
        TitleCardProvider titleCard = mock(TitleCardProvider.class);
        when(titleCard.isComplete()).thenReturn(true);
        when(loop.getTitleCardProvider()).thenReturn(titleCard);
        installCurrentLoop(loop);
        session.beginTitleCardPresentation(noopPresentation());
        assertTrue(TraceSessionLauncher
                .claimTitleCardControlReleaseBarrierIfActive());
        SessionManager.armNextGameplayAdmissionPolicy(
                HardwareReadinessAdmissionPolicy.RECORDED);

        Engine.clearGlobalInstance();
        session.runAdvanceTickIfActive(GameMode.LEVEL, 0);

        assertNull(TraceSessionLauncher.active());
        assertEquals("knuckles", GameServices.configuration().getConfigValue(
                SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals(HardwareReadinessAdmissionPolicy.LIVE,
                SessionManager.openGameplaySession(new Sonic1GameModule())
                        .hardwareTiming().admissionPolicy());
    }

    @Test
    void preparedReplayBootstrapFailureReturnsToMasterTitleAndCleansSession()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameServices.configuration().setConfigValue(
                SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        TraceReplaySessionBootstrap.ConfigSnapshot original =
                TraceReplaySessionBootstrap.snapshotGameplayConfig();
        GameServices.configuration().setConfigValue(
                SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        TraceSessionLauncher session = presentationSession(original);
        GameLoop loop = mock(GameLoop.class);
        TitleCardProvider titleCard = mock(TitleCardProvider.class);
        when(titleCard.isComplete()).thenReturn(true);
        when(loop.getTitleCardProvider()).thenReturn(titleCard);
        installCurrentLoop(loop);
        session.beginTitleCardPresentation(noopPresentation());
        assertTrue(TraceSessionLauncher
                .claimTitleCardControlReleaseBarrierIfActive());
        SessionManager.armNextGameplayAdmissionPolicy(
                HardwareReadinessAdmissionPolicy.RECORDED);

        session.runAdvanceTickIfActive(GameMode.LEVEL, 0);

        assertNull(TraceSessionLauncher.active());
        assertEquals("knuckles", GameServices.configuration().getConfigValue(
                SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals(HardwareReadinessAdmissionPolicy.LIVE,
                SessionManager.openGameplaySession(new Sonic1GameModule())
                        .hardwareTiming().admissionPolicy());
        verify(loop).returnToMasterTitle();
    }

    private static TraceSessionLauncher presentationSession(
            TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot) {
        TraceEntry entry = TraceCatalog.scan(Path.of("src/test/resources/traces"))
                .stream()
                .filter(candidate -> "s1".equals(candidate.gameId()))
                .filter(candidate -> "ghz1_fullrun".equals(
                        candidate.dir().getFileName().toString()))
                .findFirst()
                .orElseThrow();
        return new TraceSessionLauncher(
                entry, null, List.of(), null, configSnapshot);
    }

    private static TraceSessionLauncher.TitleCardPresentation noopPresentation() {
        return new TraceSessionLauncher.TitleCardPresentation() {
            @Override
            public void prepareLevel() {
            }

            @Override
            public void enterTitleCard() {
            }
        };
    }

    private static Engine installCurrentLoop(GameLoop loop) {
        try {
            Engine engine = mock(Engine.class);
            Field loopField = Engine.class.getDeclaredField("gameLoop");
            loopField.setAccessible(true);
            loopField.set(engine, loop);
            Field instanceField = Engine.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, engine);
            return engine;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
