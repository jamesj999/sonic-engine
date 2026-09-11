package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.data.Rom;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceData;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.FadeManager;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.TestEnvironment;
import com.openggf.trace.SpecialStageTraceData;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.catalog.TraceEntry;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Session-lifecycle integration test for the live special-stage visual trace
 * session. Drives the committed MVP trace through the SAME runtime path the
 * live picker uses per engine frame — {@link TraceSessionLauncher}'s SS API +
 * {@code GameLoop.doEnterSpecialStage} (which {@code finishSpecialStageLaunch}
 * calls) + {@code GameLoop.updateSpecialStageMode()} — and asserts:
 * <ul>
 *   <li>the loop enters {@link GameMode#SPECIAL_STAGE};</li>
 *   <li>each non-lag row steps the provider's {@code update()} exactly once and
 *       each lag row skips it (counts computed from the trace's lag column at
 *       runtime, never hardcoded);</li>
 *   <li>the session ends (fade-out armed) exactly at the recorded
 *       stage-finished frame.</li>
 * </ul>
 *
 * <p>The static {@link TraceSessionLauncher#launch} master-title boot is
 * Engine-level (GLFW / master-title screen) and is exercised by the manual jar
 * smoke instead; this test scopes to the per-frame driving contract, which is
 * where all the SS-specific logic lives.
 */
class TestSpecialStageVisualTraceSession {

    private static final Path TRACE_DIRECTORY =
            Path.of("src", "test", "resources", "traces", "s2", "special_stage");

    @AfterEach
    void tearDown() {
        setStaticActiveSession(null);
        SessionManager.clear();
    }

    @Test
    void sonicOneStandaloneRowsUseTheirTypedFourteenColumnParser()
            throws Exception {
        Path directory = Path.of("src", "test", "resources", "traces",
                "s1", "special_stage");

        TraceRunSpecialStageRows rows = TraceRunSpecialStageRows.load(
                "s1_special_stage", directory);

        assertEquals("s1", rows.metadata().game());
        assertTrue(rows.rowCount() > 0);
        assertEquals(rows.rowCount() - 1, rows.terminalRow().orElseThrow());
        assertFalse(rows.hardwareTimingSchedule().edges().size() > 0);
        rows.admission(0);
    }

    @Test
    void sonicTwoStandaloneRowsUseTheSharedProfilePolymorphicView()
            throws Exception {
        TraceRunSpecialStageRows rows = TraceRunSpecialStageRows.load(
                "s2_special_stage", TRACE_DIRECTORY);
        SpecialStageTraceData typed = SpecialStageTraceData.load(TRACE_DIRECTORY);

        assertEquals("s2", rows.metadata().game());
        assertEquals(typed.frameCount(), rows.rowCount());
        assertEquals(typed.stageFinishedFrame().orElseThrow(),
                rows.terminalRow().orElseThrow());
        assertEquals(typed.getFrame(0).lag(),
                !rows.admission(0).executeGameplay());
    }

    @Test
    void sonicThreeStandaloneRowsPreserveRecordedLagAdmission()
            throws Exception {
        Path directory = Path.of("src", "test", "resources", "traces",
                "s3k", "special_stage");
        TraceRunSpecialStageRows rows = TraceRunSpecialStageRows.load(
                "s3k_special_stage", directory);
        S3kSpecialStageTraceData typed =
                S3kSpecialStageTraceData.load(directory);
        int lagRow = java.util.stream.IntStream.range(0, typed.frameCount())
                .filter(index -> typed.getFrame(index).lag())
                .findFirst()
                .orElseThrow();

        assertFalse(rows.admission(lagRow).executeGameplay());
        assertEquals(typed.getFrame(lagRow).lag(),
                !rows.admission(lagRow).executeGameplay());
    }

    @Test
    void visualSessionPacesLagRowsAndEndsAtStageFinished() throws Exception {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        assumeTrue(romFile != null && Files.exists(Path.of("s2.gen")),
                "s2.gen ROM required for the SS visual-session lifecycle test");

        // Boot headless graphics + ROM fixture + recorded team (mirrors the
        // headless replay harness bootstrap).
        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();
        Rom rom = new Rom();
        rom.open(romFile.getAbsolutePath());
        TestEnvironment.configureRomFixture(rom);
        GraphicsManager.getInstance().initHeadless();

        SpecialStageTraceData trace = SpecialStageTraceData.load(TRACE_DIRECTORY);
        TraceMetadata meta = trace.metadata();
        Path bk2 = TRACE_DIRECTORY.resolve(meta.sourceBk2());
        Bk2Movie movie = new Bk2MovieLoader().load(bk2);
        TraceEntry entry = new TraceEntry(TRACE_DIRECTORY, "s2", 0, 0,
                trace.frameCount(), meta.bk2FrameOffset(), 0, null, bk2, meta);
        TraceSessionLauncher session =
                new TraceSessionLauncher(entry, movie, trace, null);

        int stageFinished = trace.stageFinishedFrame().orElse(trace.frameCount() - 1);
        int expectedLag = 0;
        int expectedNonLag = 0;
        for (int i = 0; i <= stageFinished; i++) {
            if (trace.getFrame(i).lag()) {
                expectedLag++;
            } else {
                expectedNonLag++;
            }
        }

        GameLoop loop = new GameLoop(new InputHandler());
        int ssIndex = meta.specialStageIndex() != null ? meta.specialStageIndex() : 0;
        SpecialStageProvider provider = spy(new Sonic2SpecialStageProvider());

        // Mark active before entering (as finishSpecialStageLaunch does) so
        // GameLoop suppresses SS rewind capture for the session lifetime.
        setStaticActiveSession(session);
        TraceSessionLauncher.enterSpecialStageTrace(loop, provider, ssIndex);

        assertEquals(GameMode.SPECIAL_STAGE, loop.getCurrentGameMode(),
                "SS trace session must enter SPECIAL_STAGE mode");
        assertEquals(FadeManager.FadeState.HOLD_BLACK,
                GameServices.fade().getState(),
                "trace-accurate startup must stay opaque until the ROM reveal boundary");
        assertFalse(provider.isEntryPresentationReady(),
                "trace-accurate startup must retain the PRE_ROLL observation");

        int stepped = 0;
        int skipped = 0;
        int safetyCap = trace.frameCount() + 16;
        int iterations = 0;
        boolean revealObserved = false;
        while (!fadeStarted(session) && iterations < safetyCap) {
            boolean skip = session.shouldSkipCurrentSpecialStageTick();
            GameLoopTestStep.invoke(loop, "updateSpecialStageMode", new Class<?>[0]);
            if (!revealObserved && provider.isEntryPresentationReady()) {
                revealObserved = true;
                assertEquals(FadeManager.FadeState.FADING_FROM_BLACK,
                        GameServices.fade().getState(),
                        "music/reveal boundary must replace the opaque trace hold atomically");
            }
            if (skip) {
                skipped++;
            } else {
                stepped++;
            }
            iterations++;
        }

        assertTrue(fadeStarted(session),
                "session must arm its fade-out end within the trace length");
        assertTrue(revealObserved, "trace startup must eventually reach its reveal boundary");
        assertEquals(stageFinished, ssCursor(session),
                "session must end exactly at the recorded stage-finished frame");
        assertEquals(expectedNonLag, stepped,
                "stepped frames must equal the trace's non-lag row count through finish");
        assertEquals(expectedLag, skipped,
                "skipped frames must equal the trace's lag row count through finish");
        verify(provider, times(expectedNonLag)).update();
    }

    private static boolean fadeStarted(TraceSessionLauncher session) {
        try {
            Field field = TraceSessionLauncher.class
                    .getDeclaredField("fadeStarted");
            field.setAccessible(true);
            return field.getBoolean(session);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int ssCursor(TraceSessionLauncher session) {
        try {
            Field field = TraceSessionLauncher.class.getDeclaredField("ssCursor");
            field.setAccessible(true);
            return field.getInt(session);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setStaticActiveSession(TraceSessionLauncher value) {
        try {
            Field field = TraceSessionLauncher.class.getDeclaredField("activeSession");
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
