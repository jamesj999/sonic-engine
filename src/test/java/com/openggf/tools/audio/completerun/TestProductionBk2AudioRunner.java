package com.openggf.tools.audio.completerun;

import com.openggf.Engine;
import com.openggf.GameLoop;
import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioRequestObserver;
import com.openggf.audio.HeadlessSmpsAudioBackend;
import com.openggf.audio.NullAudioBackend;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.RomDetectionService;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

class TestProductionBk2AudioRunner {
    private SonicConfigurationService config;
    private AudioManager audio;
    private PerformanceProfiler profiler;
    private CrossGameFeatureProvider crossGameFeatures;
    private RomManager romManager;
    private EngineContext context;
    private InputHandler input;
    private HeadlessSmpsAudioBackend backend;

    @BeforeEach
    void setUp() {
        config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.AUDIO_ENABLED, true);
        config.setConfigValue(
                SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP, false);
        config.setConfigValue(
                SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP, false);
        audio = AudioManager.getInstance();
        audio.destroy();
        audio.resetState();
        profiler = mock(PerformanceProfiler.class);
        crossGameFeatures = mock(CrossGameFeatureProvider.class);
        romManager = mock(RomManager.class);
        context = new EngineContext(
                config,
                new GraphicsManager(),
                audio,
                romManager,
                profiler,
                mock(DebugOverlayManager.class),
                PlaybackDebugManager.getInstance(),
                mock(RomDetectionService.class),
                crossGameFeatures);
        EngineServices.configure(context);
        input = new InputHandler();
        backend = new HeadlessSmpsAudioBackend(config, profiler);
        audio.setBackend(backend);
    }

    @AfterEach
    void tearDown() {
        Engine.clearGlobalInstance();
        PlaybackDebugManager.getInstance().endSession();
        audio.destroy();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @Test
    void rowsSnapshotPublishStepPresentFinishAdvanceThenCallback()
            throws Exception {
        List<String> order = new ArrayList<>();
        RecordingLoop loop = new RecordingLoop(context, input, order);
        Bk2InputCursor cursor = new Bk2InputCursor(movie(2));
        List<ProductionBk2AudioRunner.RowResult> results =
                new ArrayList<>();

        try (CompleteRunAudioObserverLease observations =
                     CompleteRunAudioObserverLease.acquire(audio)) {
            ProductionBk2AudioRunner.driveRows(
                    audio, loop, input, backend, cursor, observations,
                    row -> {
                        order.add("callback-" + row.absoluteFrame());
                        assertEquals(row.absoluteFrame() + 1,
                                cursor.absoluteFrame());
                        results.add(row);
                    });
        }

        assertEquals(List.of(
                "step-0", "present-0", "callback-0",
                "step-1", "present-1", "callback-1"), order);
        assertEquals(2, results.size());
        assertEquals(0, results.get(0).preRow()
                .logicalSnapshot().commandEntryCount());
        assertTrue(results.get(0).observation()
                .logicalSnapshot().commandEntryCount() > 0);
        assertEquals(
                results.get(0).observation().logicalSnapshot(),
                results.get(1).preRow().logicalSnapshot());
        assertTrue(input.hasLogicalOverride(),
                "the outer runner, not the row driver, owns final cleanup");
    }

    @Test
    void externalOwnerIsRejectedAgainBeforeEveryRow() throws Exception {
        List<String> order = new ArrayList<>();
        RecordingLoop loop = new RecordingLoop(context, input, order);
        Bk2InputCursor cursor = new Bk2InputCursor(movie(2));

        try (CompleteRunAudioObserverLease observations =
                     CompleteRunAudioObserverLease.acquire(audio)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> ProductionBk2AudioRunner.driveRows(
                            audio, loop, input, backend, cursor,
                            observations, row -> loop.externalOwner = true));

            assertTrue(failure.getMessage().contains("another production owner"));
            assertEquals(List.of("step-0", "present-0"), order);
            assertEquals(1, cursor.absoluteFrame());
        }
    }

    @Test
    void replacedInputIdentityFailsBeforePresentation() {
        List<String> order = new ArrayList<>();
        RecordingLoop loop = new RecordingLoop(context, input, order);
        loop.replaceInputDuringStep = true;
        Bk2InputCursor cursor = new Bk2InputCursor(movie(1));

        try (CompleteRunAudioObserverLease observations =
                     CompleteRunAudioObserverLease.acquire(audio)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> ProductionBk2AudioRunner.driveRows(
                            audio, loop, input, backend, cursor,
                            observations, row -> { }));

            assertTrue(failure.getMessage().contains("input handler identity"));
            assertEquals(List.of("step-0"), order);
            assertEquals(0, cursor.absoluteFrame());
        }
    }

    @Test
    void authenticatedConfigurationFailsClosedWithoutMutatingHostFlags() {
        config.setConfigValue(
                SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP, true);
        assertThrows(IllegalStateException.class,
                () -> ProductionBk2AudioRunner.run(
                        context, movie(1), row -> { }));
        assertTrue(config.getBoolean(
                SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP));
        assertNull(Engine.currentGameLoop());

        config.setConfigValue(
                SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP, false);
        config.setConfigValue(
                SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP, true);
        assertThrows(IllegalStateException.class,
                () -> ProductionBk2AudioRunner.run(
                        context, movie(1), row -> { }));
        assertTrue(config.getBoolean(
                SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP));
        assertNull(Engine.currentGameLoop());

        config.setConfigValue(
                SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP, false);
        config.setConfigValue(SonicConfiguration.AUDIO_ENABLED, false);
        assertThrows(IllegalStateException.class,
                () -> ProductionBk2AudioRunner.run(
                        context, movie(1), row -> { }));
        assertFalse(config.getBoolean(SonicConfiguration.AUDIO_ENABLED));
        assertNull(Engine.currentGameLoop());
    }

    @Test
    void emptyMovieIsRejectedBeforeStartupCanCreateAFrameBoundary() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ProductionBk2AudioRunner.run(
                        context, movie(0), row -> { }));

        assertTrue(failure.getMessage().contains("at least one BK2 row"));
        assertNull(Engine.currentGameLoop());
    }

    @Test
    void failedStartupClosesObserverLeaseBeforeProductionResources()
            throws Exception {
        List<String> order = new ArrayList<>();
        when(romManager.getRom()).thenThrow(
                new IllegalStateException("injected startup failure"));
        doAnswer(invocation -> {
            try {
                audio.setRequestObserver(AudioRequestObserver.NONE);
                order.add("resources-after-observer");
            } catch (IllegalStateException observerStillActive) {
                order.add("resources-before-observer");
            }
            return null;
        }).when(crossGameFeatures).resetState();

        assertThrows(RuntimeException.class,
                () -> ProductionBk2AudioRunner.run(
                        context, movie(1), row -> { }));

        assertEquals(List.of("resources-after-observer"), order);
        assertNull(Engine.currentGameLoop());
    }

    @Test
    void cleanupFailuresAreSuppressedOnThePrimaryFailure() {
        IllegalStateException primary =
                new IllegalStateException("primary row failure");
        IllegalStateException overrideCleanup =
                new IllegalStateException("override cleanup failure");
        IllegalStateException leaseCleanup =
                new IllegalStateException("lease cleanup failure");

        Throwable aggregate = ProductionBk2AudioRunner.appendCleanupFailure(
                primary, () -> { throw overrideCleanup; });
        aggregate = ProductionBk2AudioRunner.appendCleanupFailure(
                aggregate, () -> { throw leaseCleanup; });

        assertSame(primary, aggregate);
        assertEquals(List.of(overrideCleanup, leaseCleanup),
                List.of(primary.getSuppressed()));
    }

    @Test
    void sourcePinsFixedStartupRowAndCleanupStructure() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/tools/audio/completerun/"
                        + "ProductionBk2AudioRunner.java"));

        assertOrdered(source,
                "new InputHandler(",
                "new HeadlessSmpsAudioBackend(",
                "CompleteRunAudioObserverLease.acquire(audio)",
                "initializeConfiguredHeadlessSession(input, backend)",
                "driveRows(audio, loop, input, backend, cursor,");
        String rows = source.substring(source.indexOf("static void driveRows"));
        assertOrdered(rows,
                "AudioLogicalSnapshot preSnapshot",
                "audio.captureLogicalSnapshot()",
                "observations.beginRow(",
                "cursor.publish(input)",
                "loop.step()",
                "Engine.presentOuterAudioFrame(",
                "AudioLogicalSnapshot postSnapshot",
                "audio.captureLogicalSnapshot()",
                "observations.finishRow(",
                "cursor.advance()",
                "consumer.accept(");
        assertOrdered(source,
                "installedObservations::close",
                "engine::closeConfiguredHeadlessSession",
                "input::clearLogicalOverride");
    }

    private static void assertOrdered(String source, String... tokens) {
        int cursor = -1;
        for (String token : tokens) {
            int found = source.indexOf(token, cursor + 1);
            assertTrue(found > cursor, "missing or out-of-order token: " + token);
            cursor = found;
        }
    }

    private static Bk2Movie movie(int rows) {
        List<Bk2FrameInput> frames = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            frames.add(new Bk2FrameInput(
                    row, row + 1, row + 2, row == 0,
                    "row-" + row));
        }
        return new Bk2Movie(
                Path.of("production-audio-runner.bk2"), "logkey",
                Map.of(), frames, 1);
    }

    private static final class RecordingLoop extends GameLoop {
        private final AudioManager audio;
        private final InputHandler expectedInput;
        private final List<String> order;
        private int step;
        private boolean externalOwner;
        private boolean replaceInputDuringStep;

        private RecordingLoop(EngineContext context, InputHandler input,
                List<String> order) {
            super(context);
            audio = context.audio();
            expectedInput = input;
            this.order = order;
            setInputHandler(input);
        }

        @Override
        public boolean externalFrameOrInputOwnerActive() {
            return externalOwner;
        }

        @Override
        public void step() {
            assertSame(expectedInput, getInputHandler());
            assertTrue(expectedInput.hasLogicalOverride());
            order.add("step-" + step);
            audio.playMusic(0x81 + step);
            if (replaceInputDuringStep) {
                setInputHandler(new InputHandler());
            }
        }

        @Override
        public void presentOuterFrame(
                boolean modalPicker, boolean frameStepRequested) {
            order.add("present-" + step);
            step++;
        }
    }
}
