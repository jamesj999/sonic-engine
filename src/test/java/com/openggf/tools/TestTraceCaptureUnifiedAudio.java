package com.openggf.tools;

import com.openggf.GameLoop;
import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioManagerTestDiagnostics;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.GameSound;
import com.openggf.audio.HeadlessSmpsAudioBackend;
import com.openggf.audio.LiveCaptureAudioHandle;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.SegaPcmSpec;
import com.openggf.audio.presentation.AudioPresentationProducer;
import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.presentation.PresentationVoiceSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.capture.CaptureException;
import com.openggf.capture.CaptureRecorder;
import com.openggf.capture.CapturedFrame;
import com.openggf.capture.DrainPcmAudioTap;
import com.openggf.capture.VideoFrameGrabber;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.data.Rom;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.game.GameMode;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.tests.TestEnvironment;
import com.openggf.trace.replay.TraceReplayDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline trace capture runs over the same unified presentation producer as
 * live recording.
 *
 * <p>The capture drivers own the headless outer-frame audio boundary:
 * {@code GameLoop.step()} presents nothing, each presented outer framebuffer
 * frame calls {@link HeadlessGameBoot#presentHeadlessOuterAudioFrame()} exactly
 * once, and the packet is drained exactly once afterwards (captured or
 * discarded during clip fast-forward). Nothing here installs a deterministic
 * runtime, replaces the producer, or opens an audio device.
 */
class TestTraceCaptureUnifiedAudio {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FPS = 60;
    private static final int FRAME_SAMPLES = SAMPLE_RATE / FPS;
    private static final int PCM_ADDRESS = 0x40;

    private AudioManager audio;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        audio = AudioManager.getInstance();
        audio.endCaptureMode();
        audio.resetState();
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @AfterEach
    void tearDown() {
        audio.endCaptureMode();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        TestEnvironment.resetAll();
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void sessionStartAndFinishAttachAndDetachOneUnifiedHandle() throws Exception {
        audio.setBackend(headlessBackend());
        int leasesBefore = leaseCount();     // also realizes the lazy producer
        Object producerBefore = producer(audio);
        assertNotNull(producerBefore, "the producer must already exist");

        RecordingCaptureRecorder recorder = recorder();
        TraceCaptureSession session = session(recorder);

        session.start(320, 224, SAMPLE_RATE);

        assertEquals(leasesBefore + 1, leaseCount(),
                "start attaches exactly one unified capture lease");
        // The recorder samples the live lease count as it opens, so opening
        // the recorder first would record leases=leasesBefore here.
        assertEquals(List.of("recorder-start(leases=" + (leasesBefore + 1) + ")"),
                recorder.events,
                "the lease is already attached when the recorder opens");
        assertSame(producerBefore, producer(audio),
                "start must not replace the authoritative producer");

        session.finish();

        assertEquals(leasesBefore, leaseCount(),
                "finish detaches exactly the compatibility lease");
        assertEquals(List.of("recorder-start(leases=" + (leasesBefore + 1) + ")",
                        "recorder-stop(leases=" + (leasesBefore + 1) + ")"),
                recorder.events,
                "recorder.stop() runs before endCaptureMode(), so the lease is"
                        + " still attached inside stop()");
        assertSame(producerBefore, producer(audio),
                "finish must not replace the authoritative producer");
    }

    @Test
    void sessionFailureClosesCaptureHandleAndRecorder() throws Exception {
        audio.setBackend(headlessBackend());
        int leasesBefore = leaseCount();
        RecordingCaptureRecorder recorder = recorder();
        recorder.failOnStop = true;
        TraceCaptureSession session = session(recorder);
        session.start(320, 224, SAMPLE_RATE);

        assertThrows(CaptureException.class, session::finish);

        assertEquals(List.of("recorder-start(leases=" + (leasesBefore + 1) + ")",
                        "recorder-stop(leases=" + (leasesBefore + 1) + ")"),
                recorder.events,
                "the recorder stop attempt still runs first");
        assertEquals(leasesBefore, leaseCount(),
                "a failing recorder stop still closes the capture lease");
        assertThrows(IllegalStateException.class,
                () -> audio.drainCaptureFrame(new short[FRAME_SAMPLES * 2]),
                "the compatibility lease is gone after a failed finish");
    }

    @Test
    void eachCapturedFramebufferExplicitlyTicksOneHeadlessOuterAudioBoundary() {
        audio.setBackend(headlessBackend());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        InputHandler input = mock(InputHandler.class);
        when(input.logical()).thenReturn(LogicalInputSnapshot.neutral());
        GameLoop loop = new GameLoop(input);
        loop.setGameMode(GameMode.LEGAL_DISCLAIMER);
        audio.beginCaptureMode(SAMPLE_RATE, FPS);

        loop.step();

        assertEquals(0, AudioManagerTestDiagnostics
                        .shadowParitySnapshot(audio).presentedFrames(),
                "a GameLoop.step() in this mode presents no audio (the"
                        + " per-mode proof for every GameMode lives in"
                        + " TestGameLoopAudioPresentationModes)");

        HeadlessGameBoot.presentHeadlessOuterAudioFrame();

        var parity = AudioManagerTestDiagnostics.shadowParitySnapshot(audio);
        assertEquals(1, parity.presentedFrames(),
                "each captured framebuffer ticks exactly one outer boundary");
        assertEquals(1, parity.forwardFrames());
        assertEquals(FRAME_SAMPLES,
                audio.drainCaptureFrame(new short[FRAME_SAMPLES * 2]));

        audio.endCaptureMode();
    }

    /**
     * The tool's per-outer-frame audio cadence, exercised through the helper
     * the capture loops delegate to.
     *
     * <p>Scope note: this drives
     * {@link TraceCaptureTool.HeadlessOuterAudioFrames} directly, because
     * {@code driveClip}/{@code driveAndCapture} need a GL context, a ROM, a
     * loaded trace, and ffmpeg. Loop-body <em>wiring</em> (which branch calls
     * which helper method) is therefore not asserted here. What makes the
     * cadence-multiplying regression non-silent is the helper's own
     * present/drain alternation guard, pinned at the end of this test: moving
     * the presentation into the per-simulation-step body throws on the first
     * fast-forward frame with more than one simulation step.
     */
    @Test
    void toolFastForwardDrainsOrDiscardsEveryPresentedPacketWithoutBacklog() {
        audio.setBackend(headlessBackend());
        audio.beginCaptureMode(SAMPLE_RATE, FPS);
        LiveCaptureAudioHandle lease = attachedLease();
        TraceCaptureTool.HeadlessOuterAudioFrames frames =
                new TraceCaptureTool.HeadlessOuterAudioFrames(
                        new DrainPcmAudioTap(audio));

        short[] captured = new short[16384];
        int presented = 0;
        for (int outerFrame = 0; outerFrame < 30; outerFrame++) {
            // Simulation-only steps enqueue commands but must not present.
            audio.stopAllSfx();
            audio.stopAllSfx();
            frames.presentOuterFrame();
            presented++;
            if (outerFrame < 20) {
                assertEquals(FRAME_SAMPLES, frames.discardPresented(),
                        "fast-forward discards exactly one presented packet");
            } else {
                assertEquals(FRAME_SAMPLES, frames.drainCaptured(captured),
                        "the capture window drains exactly one presented packet");
            }
            assertEquals(presented, AudioManagerTestDiagnostics
                            .shadowParitySnapshot(audio).presentedFrames(),
                    "simulation steps must not multiply audio cadence");
        }

        assertEquals(30, frames.presentedFrames());
        assertEquals(30, frames.drainedFrames());
        assertEquals((long) 30 * FRAME_SAMPLES, lease.totalStereoFrames(),
                "one clocked packet per presented outer frame, no backlog");

        // Presenting again before the packet is drained is exactly the shape a
        // per-simulation-step presentation would take. It must be rejected, not
        // absorbed, so the regression cannot ship as silent extra cadence.
        frames.presentOuterFrame();
        assertThrows(IllegalStateException.class, frames::presentOuterFrame,
                "a second presentation before the drain would multiply the"
                        + " capture audio cadence");
        assertEquals(31, frames.presentedFrames(),
                "the rejected presentation never reached the producer");
        assertEquals(31, AudioManagerTestDiagnostics
                        .shadowParitySnapshot(audio).presentedFrames(),
                "the rejected presentation never reached the producer");
        assertEquals(FRAME_SAMPLES, frames.discardPresented());
        assertThrows(IllegalStateException.class, frames::discardPresented,
                "a drain with nothing presented would emit a stale or silent"
                        + " packet into the recording");

        audio.endCaptureMode();
    }

    /**
     * The loop <em>wiring</em> the cadence test above cannot reach.
     *
     * <p>{@code driveAndCapture}, {@code driveClip}, and
     * {@code TraceCaptureSession.stepAndCapture()} all need a GL context, a
     * ROM, a loaded trace, and (for the tool) ffmpeg, so no test can execute
     * them. Without this guard, deleting the presentation from a loop body — or
     * moving it into the per-simulation-step body — leaves every test in this
     * range green while every captured video goes silent or gains N packets per
     * frame. This is the same source-structure guard style the repo already
     * uses for architectural invariants that no runtime test can observe (see
     * {@code TestAudioPresentationArchitectureGuard},
     * {@code TestNoDirectMapMutationsInGameplay}).
     */
    @Test
    void captureLoopsWireExactlyOnePresentAndOneDrainPerOuterFrame()
            throws Exception {
        // Comments are stripped first so a javadoc {@link ...#present...()}
        // reference can never stand in for a real call site.
        String tool = stripComments(Files.readString(Path.of(
                "src/main/java/com/openggf/tools/TraceCaptureTool.java")));
        String session = stripComments(Files.readString(Path.of(
                "src/main/java/com/openggf/tools/TraceCaptureSession.java")));

        // --- the single presentation primitive -----------------------------
        assertEquals(1, count(tool, "presentHeadlessOuterAudioFrame()"),
                "the tool presents through exactly one call site");
        assertTrue(methodBody(tool, "void presentOuterFrame()")
                        .contains("presentHeadlessOuterAudioFrame()"),
                "that call site is HeadlessOuterAudioFrames.presentOuterFrame,"
                        + " which owns the present/drain alternation guard");

        // --- driveAndCapture: present -> render -> grab -> drain -> submit --
        String driveAndCapture = methodBody(tool, "private long driveAndCapture(");
        assertEquals(1, count(driveAndCapture, "audioFrames.presentOuterFrame()"),
                "exactly one presentation per captured outer frame");
        assertEquals(1, count(driveAndCapture, "audioFrames.drainCaptured("),
                "exactly one drain of that packet");
        assertEquals(0, count(driveAndCapture, "audioFrames.discardPresented("));
        assertOrder(driveAndCapture, "audioFrames.presentOuterFrame()",
                "grabber.grab()", "audioFrames.drainCaptured(",
                "recorder.submit(");
        // Bound to the presented-frame branch, not merely to the loop body: a
        // presentation hoisted next to driveOneFrame(...) would fire on every
        // simulation step, including the cursor-only rows that never present.
        String capturedFrames = blockAfter(driveAndCapture,
                "shouldCompareGameplayStateForReplay(phase)) {");
        assertEquals(1, count(capturedFrames, "audioFrames.presentOuterFrame()"),
                "the presentation belongs to the presented-frame branch");
        assertEquals(1, count(capturedFrames, "audioFrames.drainCaptured("),
                "so does its drain");

        // --- driveClip: present once, then drain on BOTH branches ----------
        String driveClip = methodBody(tool, "private long driveClip(");
        assertEquals(1, count(driveClip, "audioFrames.presentOuterFrame()"),
                "one presentation per outer frame the clip treats as presented,"
                        + " inside or outside the capture window");
        String presentedFrames = blockAfter(driveClip,
                "shouldCompareGameplayStateForReplay(phase)) {");
        assertEquals(1, count(presentedFrames, "audioFrames.presentOuterFrame()"),
                "the presentation belongs to the presented-frame branch, not to"
                        + " the per-simulation-step body");
        assertEquals(1, count(presentedFrames, "audioFrames.drainCaptured("),
                "the capture window drains that packet exactly once");
        assertEquals(1, count(presentedFrames, "audioFrames.discardPresented("),
                "and outside the window it is discarded exactly once, so no"
                        + " stale packet is carried into the clip");
        String capturingBranch = blockAfter(driveClip, "if (capturing) {");
        assertEquals(1, count(capturingBranch, "audioFrames.drainCaptured("),
                "the capture branch drains the presented packet exactly once");
        assertEquals(0, count(capturingBranch, "audioFrames.discardPresented("));
        String fastForwardBranch = driveClip.substring(
                driveClip.indexOf(capturingBranch) + capturingBranch.length());
        assertEquals(1, count(fastForwardBranch, "audioFrames.discardPresented("),
                "the fast-forward branch discards the presented packet exactly"
                        + " once, so no stale packet reaches the clip start");
        assertOrder(driveClip, "audioFrames.presentOuterFrame()",
                "audioFrames.drainCaptured(", "audioFrames.discardPresented(");

        // --- the simulation step itself presents nothing --------------------
        // The step lives in TraceReplayDrive, shared with the benchmark tool.
        // That makes these checks stricter rather than weaker: a presentation
        // added there would multiply the audio cadence for every CLI driver at
        // once, on each simulation step rather than each outer frame.
        String drive = stripComments(Files.readString(Path.of(
                "src/main/java/com/openggf/tools/TraceReplayDrive.java")));
        String driveOneFrame = methodBody(drive, "static DriveOutcome driveOneFrame(");
        assertEquals(1, count(driveOneFrame, "frameDriver.beginTraceRow("),
                "the shared drive must arm timing authority once per represented row");
        for (String captureLoop : List.of(driveAndCapture, driveClip)) {
            assertEquals(0, count(captureLoop, "beginTraceRow("),
                    "capture loops delegate row authority to the shared drive");
        }
        String benchmark = stripComments(Files.readString(Path.of(
                "src/main/java/com/openggf/tools/TraceBenchmarkTool.java")));
        String benchmarkIteration = methodBody(
                benchmark, "private static BenchmarkReport.Iteration runIteration(");
        assertEquals(0, count(benchmarkIteration, "beginTraceRow("),
                "benchmark delegates row authority to the shared drive");
        assertFalse(driveOneFrame.contains("paletteRegistry.beginFrame()"),
                "setup-only admission must precede capture palette-frame mutation");
        assertTrue(driveOneFrame.contains("TraceReplayDrive::beginPaletteFrame"),
                "palette-frame mutation must be supplied as an admitted-gameplay callback");
        for (String forbidden : List.of("audioFrames", "presentOuterFrame",
                "presentHeadlessOuterAudioFrame", "drainCaptured",
                "discardPresented")) {
            assertEquals(0, count(driveOneFrame, forbidden),
                    "a simulation-only step must not touch audio cadence: "
                            + forbidden);
        }

        // --- the session drives the same boundary explicitly ---------------
        assertEquals(1, count(session, "presentHeadlessOuterAudioFrame()"),
                "the session presents from exactly one call site");
        String stepAndCapture =
                methodBody(session, "public boolean stepAndCapture()");
        assertEquals(1, count(stepAndCapture,
                        "HeadlessGameBoot.presentHeadlessOuterAudioFrame()"),
                "exactly one outer-frame presentation per captured frame");
        assertEquals(1, count(stepAndCapture, "audioTap.drain("),
                "exactly one drain of that packet");
        assertOrder(stepAndCapture, "loop.step()",
                "HeadlessGameBoot.presentHeadlessOuterAudioFrame()",
                "grabber.grab()", "audioTap.drain(", "recorder.submit(");
    }

    private static int count(String source, String needle) {
        int found = 0;
        for (int at = source.indexOf(needle); at >= 0;
                at = source.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
    }

    /** Asserts the needles appear in the given order, each exactly once. */
    private static void assertOrder(String body, String... needles) {
        int previous = -1;
        String previousNeedle = null;
        for (String needle : needles) {
            int at = body.indexOf(needle);
            assertTrue(at >= 0, "missing call: " + needle);
            assertTrue(at > previous,
                    needle + " must follow " + previousNeedle);
            previous = at;
            previousNeedle = needle;
        }
    }

    /** The brace-delimited body of the first declaration matching the head. */
    private static String methodBody(String source, String declarationHead) {
        int at = source.indexOf(declarationHead);
        assertTrue(at >= 0, "declaration not found: " + declarationHead);
        return blockFrom(source, source.indexOf('{', at));
    }

    /** The brace-delimited block introduced by the first {@code head}. */
    private static String blockAfter(String source, String head) {
        int at = source.indexOf(head);
        assertTrue(at >= 0, "block not found: " + head);
        return blockFrom(source, at + head.length() - 1);
    }

    /**
     * Brace-matches from {@code openBrace}, skipping braces that appear inside
     * string/char literals, so the extracted body is the real block rather
     * than ending at the first stray brace in a message.
     */
    private static String blockFrom(String source, int openBrace) {
        assertTrue(openBrace >= 0 && source.charAt(openBrace) == '{',
                "expected a block opening brace");
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '"' || current == '\'') {
                index = skipLiteral(source, index, current);
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(openBrace, index + 1);
            }
        }
        throw new AssertionError("unbalanced block");
    }

    private static int skipLiteral(String source, int start, char quote) {
        for (int index = start + 1; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '\\') {
                index++;
            } else if (current == quote) {
                return index;
            }
        }
        throw new AssertionError("unterminated literal");
    }

    /** Replaces every comment with a space, leaving literals untouched. */
    private static String stripComments(String source) {
        StringBuilder stripped = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '"' || current == '\'') {
                int end = skipLiteral(source, index, current);
                stripped.append(source, index, end + 1);
                index = end;
                continue;
            }
            if (current == '/' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    int end = source.indexOf('\n', index);
                    assertTrue(end >= 0, "unterminated line comment");
                    stripped.append(' ');
                    index = end - 1;
                    continue;
                }
                if (next == '*') {
                    int end = source.indexOf("*/", index + 2);
                    assertTrue(end >= 0, "unterminated block comment");
                    stripped.append(' ');
                    index = end + 1;
                    continue;
                }
            }
            stripped.append(current);
        }
        return stripped.toString();
    }

    @Test
    void traceFramesContainFinalSmpsWavAndPcmPackets() throws Exception {
        audio.setBackend(headlessBackend());
        AudioTestFixtures.StubSmpsLoader loader =
                new AudioTestFixtures.StubSmpsLoader();
        AbstractSmpsData music = new AudioTestFixtures.StubSmpsData("music");
        music.setId(0x81);
        loader.musicResults.put(0x81, music);
        byte[] segaPcm = rampPcm(2_000);
        Rom rom = mock(Rom.class);
        when(rom.readBytes(PCM_ADDRESS, segaPcm.length)).thenReturn(segaPcm);
        audio.setAudioProfile(new TraceCaptureProfile(
                loader, new SegaPcmSpec(PCM_ADDRESS, segaPcm.length, 8_000)));
        audio.setRom(rom);
        AudioManagerTestDiagnostics.registerFallbackSfxAsset(
                audio, "sfx/jump.wav", rampPcm(1_000), SAMPLE_RATE);

        audio.beginCaptureMode(SAMPLE_RATE, FPS);
        DrainPcmAudioTap tap = new DrainPcmAudioTap(audio);
        LiveCaptureAudioHandle speaker =
                AudioManagerTestDiagnostics.attachPresentationCapture(audio, FPS);

        audio.playMusic(0x81);
        audio.playSfx("JUMP");
        audio.playSegaPcm();
        // Admission is production work done by the offline presentation, not
        // by the test: present once (SILENT drains the command queue without
        // rendering, so the data-free stub voice is not swept as complete
        // before it can be primed), then prime the voice it admitted.
        audio.presentFrame(PresentationMode.SILENT);
        assertNotNull(AudioManagerTestDiagnostics.primeAdmittedSmpsMusic(audio),
                "the offline presentation must admit the SMPS music voice");

        AudioPresentationSnapshot presentation =
                audio.captureLogicalSnapshot().presentation();
        assertNotNull(presentation.activeMusic(), "SMPS music voice");
        assertNotNull(presentation.rawPcmVoiceId(), "raw PCM voice");
        assertTrue(hasSampleAsset(presentation, "sfx/jump.wav"),
                "fallback WAV SFX voice");

        HeadlessGameBoot.presentHeadlessOuterAudioFrame();
        short[] pcmBuffer = new short[16384];
        int sampleCount = tap.drain(pcmBuffer);
        CapturedFrame frame = new CapturedFrame(
                new byte[320 * 224 * 4], 320, 224, pcmBuffer, sampleCount, 0);

        assertEquals(FRAME_SAMPLES, sampleCount);
        assertFalse(allZero(frame.pcm(), sampleCount * 2),
                "trace frames carry the final mixed SMPS/WAV/PCM packet");
        short[] speakerPcm = new short[FRAME_SAMPLES * 2];
        assertEquals(FRAME_SAMPLES, speaker.drainPresentationFrame(speakerPcm));
        short[] capturedPcm = new short[FRAME_SAMPLES * 2];
        System.arraycopy(frame.pcm(), 0, capturedPcm, 0, capturedPcm.length);
        assertArrayEquals(speakerPcm, capturedPcm,
                "trace capture and speaker see one identical final packet");

        speaker.close();
        audio.endCaptureMode();
    }

    @Test
    void traceCaptureAndLiveCaptureCannotDestructivelyDrainEachOther()
            throws Exception {
        audio.setBackend(headlessBackend());
        byte[] segaPcm = rampPcm(4_000);
        Rom rom = mock(Rom.class);
        when(rom.readBytes(PCM_ADDRESS, segaPcm.length)).thenReturn(segaPcm);
        audio.setAudioProfile(new TraceCaptureProfile(
                null, new SegaPcmSpec(PCM_ADDRESS, segaPcm.length, SAMPLE_RATE)));
        audio.setRom(rom);

        audio.beginCaptureMode(SAMPLE_RATE, FPS);
        LiveCaptureAudioHandle live = audio.beginLiveCaptureAudio(FPS);
        DrainPcmAudioTap offline = new DrainPcmAudioTap(audio);
        audio.playSegaPcm();

        // Offline first, then live.
        HeadlessGameBoot.presentHeadlessOuterAudioFrame();
        short[] offlineFirst = new short[FRAME_SAMPLES * 2];
        short[] liveSecond = new short[FRAME_SAMPLES * 2];
        assertEquals(FRAME_SAMPLES, offline.drain(offlineFirst));
        assertEquals(FRAME_SAMPLES, live.drainPresentationFrame(liveSecond));
        assertFalse(allZero(offlineFirst, offlineFirst.length));
        assertArrayEquals(offlineFirst, liveSecond,
                "an offline drain must not consume the live view");

        // Live first, then offline.
        HeadlessGameBoot.presentHeadlessOuterAudioFrame();
        short[] liveFirst = new short[FRAME_SAMPLES * 2];
        short[] offlineSecond = new short[FRAME_SAMPLES * 2];
        assertEquals(FRAME_SAMPLES, live.drainPresentationFrame(liveFirst));
        assertEquals(FRAME_SAMPLES, offline.drain(offlineSecond));
        assertFalse(allZero(liveFirst, liveFirst.length));
        assertArrayEquals(liveFirst, offlineSecond,
                "a live drain must not consume the offline view");

        live.close();
        audio.endCaptureMode();
    }

    // --- fixtures ---------------------------------------------------------

    private TraceCaptureSession session(CaptureRecorder recorder) {
        return new TraceCaptureSession(
                mock(GameLoop.class), mock(TraceReplayDriver.class),
                new FixedGrabber(320, 224), new DrainPcmAudioTap(audio),
                recorder, FPS);
    }

    private RecordingCaptureRecorder recorder() {
        return new RecordingCaptureRecorder(this::leaseCount);
    }

    private HeadlessSmpsAudioBackend headlessBackend() {
        return new HeadlessSmpsAudioBackend(
                SonicConfigurationService.getInstance(),
                PerformanceProfiler.getInstance());
    }

    private LiveCaptureAudioHandle attachedLease() throws AssertionError {
        try {
            Field field = AudioManager.class.getDeclaredField(
                    "offlineCaptureHandle");
            field.setAccessible(true);
            return (LiveCaptureAudioHandle) field.get(audio);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private int leaseCount() {
        AudioPresentationProducer.TransactionFingerprint fingerprint =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        return fingerprint.captureCount();
    }

    private static Object producer(AudioManager audio) throws Exception {
        Field field = AudioManager.class.getDeclaredField("shadowProducer");
        field.setAccessible(true);
        return field.get(audio);
    }

    private static boolean hasSampleAsset(
            AudioPresentationSnapshot presentation, String assetId) {
        for (PresentationVoiceSnapshot voice : presentation.voices()) {
            if (voice instanceof PresentationVoiceSnapshot.Sample sample
                    && assetId.equals(sample.assetId())) {
                return true;
            }
        }
        return false;
    }

    private static byte[] rampPcm(int length) {
        byte[] pcm = new byte[length];
        for (int index = 0; index < length; index++) {
            pcm[index] = (byte) (index % 251);
        }
        return pcm;
    }

    private static boolean allZero(short[] samples, int length) {
        for (int index = 0; index < length; index++) {
            if (samples[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static final class FixedGrabber implements VideoFrameGrabber {
        private final int width;
        private final int height;

        private FixedGrabber(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public byte[] grab() { return new byte[width * height * 4]; }
    }

    /**
     * Records lifecycle ordering without opening an encoder. Each event
     * carries the capture-lease count observed <em>inside</em> the recorder
     * call, so "lease before recorder" is an observed ordering rather than an
     * inference from state read after {@code start()} returned.
     */
    private static final class RecordingCaptureRecorder extends CaptureRecorder {
        private final List<String> events = new ArrayList<>();
        private final IntSupplier leaseCount;
        private boolean failOnStop;

        private RecordingCaptureRecorder(IntSupplier leaseCount) {
            super(null, com.openggf.capture.BackpressurePolicy.BLOCK, 1,
                    Path.of("target"), "test", "stamp");
            this.leaseCount = leaseCount;
        }

        @Override
        public void start(int width, int height, int fps, int sampleRate) {
            events.add("recorder-start(leases=" + leaseCount.getAsInt() + ")");
        }

        @Override
        public void submit(CapturedFrame frame) {
            events.add("recorder-submit");
        }

        @Override
        public Path stop() throws CaptureException {
            events.add("recorder-stop(leases=" + leaseCount.getAsInt() + ")");
            if (failOnStop) {
                throw new CaptureException("injected recorder stop failure");
            }
            return outputFile();
        }
    }

    private record TraceCaptureProfile(SmpsLoader loader, SegaPcmSpec spec)
            implements GameAudioProfile {
        @Override public SmpsLoader createSmpsLoader(Rom rom) { return loader; }
        @Override public SmpsSequencerConfig getSequencerConfig() {
            return new SmpsSequencerConfig.Builder().build();
        }
        @Override public int getSpeedShoesOnCommandId() { return -1; }
        @Override public int getSpeedShoesOffCommandId() { return -1; }
        @Override public int getInvincibilityMusicId() { return -1; }
        @Override public int getExtraLifeMusicId() { return -1; }
        @Override public int getDrowningMusicId() { return -1; }
        @Override public Map<GameSound, Integer> getSoundMap() { return Map.of(); }
        @Override public SegaPcmSpec getSegaPcmSpec() { return spec; }
        @Override public byte[] loadSegaPcm(Object rom) throws java.io.IOException {
            return com.openggf.game.audio.SegaPcmRomReader.read(rom, getSegaPcmSpec());
        }
    }
}
