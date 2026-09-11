package com.openggf.audio;

import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.output.NoDeviceAudioSink;
import com.openggf.audio.output.OpenAlPcmSink;
import com.openggf.audio.presentation.AudioPresentationCommand;
import com.openggf.audio.presentation.AudioPresentationCommand.MusicVoiceEntry;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.ResetRingAlternation;
import com.openggf.audio.presentation.AudioPresentationCommand.StartSampleSfx;
import com.openggf.audio.presentation.AudioPresentationCommandQueue;
import com.openggf.audio.presentation.AudioPresentationDependencyResolver;
import com.openggf.audio.presentation.AudioPresentationFrameView;
import com.openggf.audio.presentation.AudioPresentationMixer;
import com.openggf.audio.presentation.AudioPresentationProducer;
import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.DecodedPcm;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.presentation.PresentationVoice;
import com.openggf.audio.presentation.PresentationVoiceSnapshot;
import com.openggf.audio.presentation.SampleBackedVoice;
import com.openggf.audio.presentation.SmpsSfxInstantiation;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steady-state cost evidence for the unified audio presentation.
 *
 * <p>These tests replace the earlier "warmed producer allocates nothing"
 * spot check, which measured a 2,000-frame loop that had never been executed
 * before in that shape. Under a full-suite ordering the JVM could still be
 * compiling — or deoptimizing — that measured loop, and a deoptimization
 * materializes scalar-replaced objects onto the heap, which the per-thread
 * allocation counter attributes to the measuring thread. That produced the
 * documented 80/176-byte full-suite-only failures even though nothing on the
 * presentation path allocates.
 *
 * <p>The evidence here is therefore three-layered:
 *
 * <ol>
 *   <li>the measured workload runs in a dedicated method that is invoked
 *       10,000 frames' worth before measurement, so it is compiled at method
 *       entry rather than being OSR-compiled inside the measured window, and
 *       the probe call site itself is warmed with a discarded measured run;</li>
 *   <li>the byte-counter assertion is exact (zero) and is skipped only when
 *       the JVM cannot supply per-thread allocation counts at all;</li>
 *   <li>identity and bound assertions always run. They compare the concrete
 *       mixer, producer, registry, queue, history, capture and speaker-FIFO
 *       storage instances before and after the run, so "no allocation" is
 *       proved structurally as well as statistically.</li>
 * </ol>
 *
 * <p>The identity evidence deliberately includes
 * {@code AudioPresentationFrameView.samples}, which is the one storage
 * reference on the whole presentation path that is <em>not</em> {@code final}:
 * the producer rebinds it every frame to whichever preallocated buffer owns
 * that mode's PCM. Every other captured reference is a {@code private final}
 * array whose identity and length javac already guarantees, so a per-frame copy
 * introduced anywhere between the mixer and the taps (for example
 * {@code return Arrays.copyOf(output, samples)} in the mixer) would be visible
 * only here. These checks therefore also run per frame across all three
 * presentation modes in the one-hour test, where nothing is being measured.
 */
class TestAudioPresentationAllocationBudget {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_RATE = 60;
    private static final int PACKET_FRAMES = SAMPLE_RATE / FRAME_RATE;
    private static final int WARM_FRAMES = 10_000;
    private static final int MEASURED_FRAMES = 10_000;
    /** Frames warmed through the probe call site itself. */
    private static final int PROBE_WARM_FRAMES = 1_000;
    private static final int ONE_HOUR_FRAMES = 3_600 * FRAME_RATE;
    /**
     * Steady-state ordered-voice bound for the one-hour workload: one looping
     * music voice plus the at most two overlapping 2,048-frame one-shots that
     * a deferred (held-rewind or SILENT) start can produce. Chosen well below
     * the registry's fixed ordered-voice array capacity so that a one-shot
     * retirement leak fails this test instead of saturating the array.
     */
    private static final int STEADY_STATE_ORDERED_VOICES = 4;

    @Test
    void historicalRepeatedPlaybackBenchmarkRemainsOptIn() {
        assertTrue(repeatedPlaybackBenchmarkIsOptIn(),
                "historical multi-repetition evidence must not become an "
                        + "ordinary steady-state budget assertion");
    }

    private static boolean repeatedPlaybackBenchmarkIsOptIn() {
        EnabledIfSystemProperty optIn =
                TestSmpsRepeatedPlaybackBenchmark.class.getAnnotation(
                        EnabledIfSystemProperty.class);
        return optIn != null
                && "openggf.audio.repeatedPlaybackBenchmark".equals(
                        optIn.named())
                && "true".equals(optIn.matches());
    }

    // ---------------------------------------------------------------
    // 1. Warmed forward mixing allocates nothing per frame
    // ---------------------------------------------------------------

    @Test
    void warmedForwardMixAllocatesNoPerFrameVoiceOrPacketArrays() {
        FakeDevice device = new FakeDevice(SAMPLE_RATE);
        OpenAlPcmSink sink = new OpenAlPcmSink(device, failure -> {
            throw new AssertionError(failure);
        }, () -> 0L, warning -> {
        });
        Fixture fixture = fixture(sink);
        fixture.producer.setHistoryArmed(true);
        fixture.producer.attachCapture(FRAME_RATE);
        fixture.producer.attachCapture(FRAME_RATE);
        fixture.admitMixedVoices();
        assertEquals(5, fixture.registry.orderedVoiceCount(),
                "the measured mix must traverse several live voices");

        long frame = 0;
        // Warm every buffer, every branch, and the compiled shape of the
        // measured method itself before anything is counted.
        for (int chunk = 0; chunk < (WARM_FRAMES - PROBE_WARM_FRAMES) / 100;
                chunk++) {
            presentForwardFrames(fixture.producer, frame, 100);
            frame += 100;
        }
        AudioBenchmarkMemoryProbe probe = AudioBenchmarkMemoryProbe.create();
        long warmFrame = frame;
        probe.measureTimedRun(() -> presentForwardFrames(
                fixture.producer, warmFrame, PROBE_WARM_FRAMES));
        frame += PROBE_WARM_FRAMES;
        assertEquals(WARM_FRAMES, frame, "the warm phase presents 10,000 frames");
        // The only non-final storage reference on the presentation path: a
        // per-frame copy anywhere between the mixer and the taps shows up
        // here and nowhere else.
        assertSame(fixture.mixerOutput(), fixture.frameViewSamples(),
                "a FORWARD present must publish the mixer's own output buffer");

        Structure before = fixture.structure();
        long measuredFrame = frame;
        AudioBenchmarkMemoryProbe.RunResult result = probe.measureTimedRun(
                () -> presentForwardFrames(
                        fixture.producer, measuredFrame, MEASURED_FRAMES));
        Structure after = fixture.structure();

        // Structural evidence always runs, even without a byte counter.
        assertNoStructuralGrowth(before, after);
        assertSame(fixture.mixerOutput(), fixture.frameViewSamples(),
                "10,000 measured FORWARD presents must still publish the "
                        + "mixer's own output buffer, never a per-frame copy");
        assertEquals(5, fixture.registry.orderedVoiceCount(),
                "looping voices survive the measured run");
        // Prove the drain claim rather than asserting an empty queue nothing
        // ever filled. Submitted outside the measured window so the drain can
        // never be mistaken for measured allocation.
        fixture.submit(new ResetRingAlternation(true));
        assertEquals(1, fixture.commands.size(),
                "a submitted command waits for a forward boundary");
        fixture.producer.present(
                measuredFrame + MEASURED_FRAMES, PresentationMode.FORWARD);
        assertEquals(0, fixture.commands.size(),
                "a forward boundary drains every pending command");
        fixture.producer.close();

        Assumptions.assumeTrue(result.allocatedBytesSupported(),
                "this JVM cannot report per-thread allocated bytes");
        assertEquals(0L, result.allocatedBytes(),
                "warmed producer allocated in its per-frame presentation loop");
    }

    // ---------------------------------------------------------------
    // 2. A one-hour no-device run stays bounded
    // ---------------------------------------------------------------

    @Test
    void oneHourNoDeviceRunKeepsAllQueueAndVoiceCountsBounded() {
        Fixture fixture = fixture(new NoDeviceAudioSink(SAMPLE_RATE));
        fixture.producer.setHistoryArmed(true);
        fixture.producer.attachCapture(FRAME_RATE);
        fixture.admitMusic();

        Structure before = fixture.structure();
        Object mixerOutput = fixture.mixerOutput();
        Object silenceBuffer = field(fixture.producer, "silence");
        Object reverseBuffer = field(fixture.producer, "reversePcm");
        int maxOrderedVoices = 0;
        int maxSampleSfxVoices = 0;
        int maxQueueSize = 0;
        int maxHistoryFrames = 0;
        int viewRebinds = 0;
        // Counts voices the registry actually admitted, by observing distinct
        // ids in the live ordered-voice traversal. Ids are strictly increasing
        // and used once, so every new high-water id is one real admission.
        // Counting submissions instead would be a tautology on the loop below.
        long highestAdmittedSfxId = 0;
        long admittedSfx = 0;
        long submittedSfx = 0;
        long nextSfxVoiceId = 1_000;
        for (int frame = 0; frame < ONE_HOUR_FRAMES; frame++) {
            if (frame % 7 == 0) {
                fixture.submit(StartSampleSfx.fromVoice(
                        SampleBackedVoice.oneShot(nextSfxVoiceId++, 1,
                                fixture.sfxPcm, SAMPLE_RATE, 1.0f, 1.0f)));
                submittedSfx++;
            }
            if (frame % 601 == 0) {
                fixture.submit(new ResetRingAlternation(frame % 2 == 0));
            }
            maxQueueSize = Math.max(maxQueueSize, fixture.commands.size());
            if (frame % 3_600 == 3_599) {
                // One held-rewind minute boundary per simulated minute.
                fixture.producer.beginReverse(1.0);
                for (int reverse = 0; reverse < FRAME_RATE; reverse++) {
                    fixture.producer.present(frame, PresentationMode.REVERSE);
                    if (frameViewSamples(fixture.producer) != reverseBuffer) {
                        viewRebinds++;
                    }
                }
                fixture.producer.endReverse();
            } else if (frame % 900 == 0) {
                fixture.producer.present(frame, PresentationMode.SILENT);
                if (frameViewSamples(fixture.producer) != silenceBuffer) {
                    viewRebinds++;
                }
            } else {
                fixture.producer.present(frame, PresentationMode.FORWARD);
                if (frameViewSamples(fixture.producer) != mixerOutput) {
                    viewRebinds++;
                }
            }
            int orderedVoices = fixture.registry.orderedVoiceCount();
            for (int index = 0; index < orderedVoices; index++) {
                PresentationVoice voice = fixture.registry.orderedVoiceAt(index);
                if (voice.voiceId() > highestAdmittedSfxId
                        && voice.voiceId() >= 1_000) {
                    highestAdmittedSfxId = voice.voiceId();
                    admittedSfx++;
                }
            }
            maxOrderedVoices = Math.max(maxOrderedVoices, orderedVoices);
            maxSampleSfxVoices = Math.max(
                    maxSampleSfxVoices, sampleSfxCount(fixture.registry));
            maxHistoryFrames = Math.max(
                    maxHistoryFrames, historyStoredFrames(fixture.producer));
        }
        // The last simulated minute ends inside held rewind, which defers
        // command application by design; one ordinary forward boundary must
        // still drain every deferred command.
        int queuedAfterReverse = fixture.commands.size();
        fixture.producer.present(ONE_HOUR_FRAMES, PresentationMode.FORWARD);
        Structure after = fixture.structure();

        assertNoStructuralGrowth(before, after);
        assertEquals(0, viewRebinds,
                viewRebinds + " of the run's presents published PCM that was "
                        + "not the preallocated buffer owning that mode");
        assertEquals(30_858, submittedSfx,
                "the run must submit a sample start every seventh frame");
        // Real admissions, not submissions: a registry or queue that silently
        // refused every sample start would leave this at zero.
        assertTrue(admittedSfx > 30_000,
                "the registry only admitted " + admittedSfx + " of "
                        + submittedSfx + " submitted sample voices");
        assertEquals(0, warnedRejectionCount(fixture.registry),
                "no sample voice may be refused admission across the run");
        assertTrue(maxOrderedVoices > 1,
                "the run must actually admit and retire sample voices");
        // Steady-state bound, deliberately far below the registry's fixed
        // ORDERED_VOICE_CAPACITY (MAX_SAMPLE_SFX_VOICES + 3): asserting the
        // array capacity would be unbreachable by construction and would
        // absorb a total one-shot-retirement leak instead of catching it.
        // One looping music voice plus the at most two 2,048-frame one-shots
        // that can overlap when a held-rewind or SILENT frame defers a start.
        assertTrue(maxOrderedVoices <= STEADY_STATE_ORDERED_VOICES,
                "ordered voices peaked at " + maxOrderedVoices
                        + ", above the steady-state bound "
                        + STEADY_STATE_ORDERED_VOICES);
        assertTrue(maxSampleSfxVoices
                        < AudioVoiceRegistry.MAX_SAMPLE_SFX_VOICES,
                "sample voices must retire, but the slot table peaked at "
                        + maxSampleSfxVoices);
        // Bound the queue below its droppable-admission ceiling, not at its
        // array length: a producer that stopped draining would pin the queue
        // at NORMAL_CAPACITY (droppable starts are then silently refused), so
        // this is the bound a real regression would break.
        int normalCapacity = AudioPresentationCommandQueue.CAPACITY
                - AudioPresentationCommandQueue.STRUCTURAL_RESERVE;
        assertTrue(maxQueueSize > 0,
                "the run must actually queue commands");
        assertTrue(maxQueueSize < normalCapacity,
                "command queue peaked at " + maxQueueSize
                        + ", at or past its droppable-admission ceiling "
                        + normalCapacity);
        assertTrue(queuedAfterReverse > 0,
                "held rewind must defer the commands submitted inside it");
        assertTrue(queuedAfterReverse < normalCapacity,
                "held rewind left " + queuedAfterReverse + " queued commands");
        assertEquals(0, fixture.commands.size(),
                "one forward boundary drains everything held rewind deferred");
        assertEquals(SAMPLE_RATE, maxHistoryFrames,
                "history must saturate at its ring capacity");
        assertTrue(historyStoredFrames(fixture.producer) <= SAMPLE_RATE,
                "history never exceeds its ring capacity");
        assertEquals(1, captureCount(fixture.producer),
                "an undrained capture lease neither multiplies nor leaks");
        fixture.producer.close();
    }

    // ---------------------------------------------------------------
    // 3. A stalled device and a stalled tap never block the producer
    // ---------------------------------------------------------------

    @Test
    void stalledDeviceAndCaptureContinueWithoutProducerBlocking() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            FakeDevice device = new FakeDevice(SAMPLE_RATE);
            // The device is never updated, so nothing ever consumes the
            // speaker FIFO: this is the stalled-device case.
            OpenAlPcmSink sink = new OpenAlPcmSink(device, failure -> {
                throw new AssertionError(failure);
            }, () -> 0L, warning -> {
            });
            Fixture fixture = fixture(sink);
            fixture.producer.setHistoryArmed(true);
            LiveCaptureAudioHandle stalledTap =
                    fixture.producer.attachCapture(FRAME_RATE);
            fixture.admitMixedVoices();
            Object tapPending = tapPending(fixture.producer, 0);

            Structure before = fixture.structure();
            for (int frame = 0; frame < 20_000; frame++) {
                fixture.producer.present(frame, PresentationMode.FORWARD);
            }
            Structure after = fixture.structure();

            assertNoStructuralGrowth(before, after);
            assertSame(fixture.mixerOutput(), fixture.frameViewSamples(),
                    "a saturated speaker FIFO must not make the producer copy "
                            + "its packet");
            // The device is only ever touched from OpenAlPcmSink#updateDevice,
            // which the render thread drives; nothing here calls it. This is
            // the guard that the presentation path itself performs no device
            // I/O: a regression that pumped the device from accept() would
            // block the producer on the audio device and fail here.
            assertEquals(0, device.updateCalls,
                    "the presentation path must never drive the device");
            assertTrue(device.enqueuedFrames.isEmpty(),
                    "the presentation path must never enqueue to the device");
            assertTrue(sink.droppedStereoFrames() > 0,
                    "speaker-only PCM is discarded rather than backpressured");
            assertTrue(sink.queuedStereoFrames() > SAMPLE_RATE,
                    "the stalled FIFO must genuinely saturate, not idle at "
                            + sink.queuedStereoFrames() + " frames");
            assertTrue(sink.queuedStereoFrames() <= SAMPLE_RATE * 2,
                    "the speaker FIFO stays inside its two-second bound");
            assertSame(tapPending, tapPending(fixture.producer, 0),
                    "a never-drained tap keeps its one preallocated packet");

            // The stalled tap is still usable and holds only the freshest
            // producer packet, not a growing backlog.
            short[] drained =
                    new short[stalledTap.maxStereoFramesPerPacket() * 2];
            assertEquals(PACKET_FRAMES,
                    stalledTap.drainPresentationFrame(drained));
            assertNotEquals(0, nonZeroCount(drained),
                    "the tap still receives audible final PCM after the stall");
            stalledTap.close();
            fixture.producer.close();
        });
    }

    // ---------------------------------------------------------------
    // 4. Overflow never loses structural state and never grows storage
    // ---------------------------------------------------------------

    @Test
    void commandAndDeferredOverflowPreserveStructuralState() {
        Fixture fixture = fixture(new NoDeviceAudioSink(SAMPLE_RATE));
        Object queueEntriesBefore = field(fixture.commands, "entries");
        Object deferredBefore = field(fixture.registry, "deferredRemovals");
        // One recording applier for both paths. Overflow can force a
        // synchronous owner-boundary drain from inside submit(), so an applier
        // installed only on applyPending would silently miss the commands that
        // drain took, and the ordering evidence below would be incomplete.
        List<Boolean> applied = new ArrayList<>();
        java.util.function.Consumer<AudioPresentationCommand> recorder =
                command -> {
                    if (command instanceof ResetRingAlternation reset) {
                        applied.add(reset.ringLeft());
                    }
                    fixture.registry.apply(command);
                };

        // Overflow both queue regions: 224 droppable normal starts plus every
        // one of the 32 reserved structural slots, then enough further
        // structural commands that the queue is full with no droppable left to
        // evict, which is the only path that reaches the synchronous
        // owner-boundary drain.
        for (int index = 0;
                index < AudioPresentationCommandQueue.CAPACITY
                        - AudioPresentationCommandQueue.STRUCTURAL_RESERVE;
                index++) {
            fixture.submit(StartSampleSfx.fromVoice(
                    SampleBackedVoice.oneShot(10_000 + index, 1,
                            fixture.sfxPcm, SAMPLE_RATE, 1.0f, 1.0f)), recorder);
        }
        assertEquals(AudioPresentationCommandQueue.CAPACITY
                        - AudioPresentationCommandQueue.STRUCTURAL_RESERVE,
                fixture.commands.size());
        List<Boolean> expectedRingLeft = new ArrayList<>();
        int structuralSubmissions = AudioPresentationCommandQueue.CAPACITY
                + AudioPresentationCommandQueue.STRUCTURAL_RESERVE;
        int drainedInsideSubmit = -1;
        for (int index = 0; index < structuralSubmissions; index++) {
            boolean ringLeft = index % 2 == 0;
            expectedRingLeft.add(ringLeft);
            int appliedBefore = applied.size();
            fixture.submit(new ResetRingAlternation(ringLeft), recorder);
            if (applied.size() > appliedBefore && drainedInsideSubmit < 0) {
                drainedInsideSubmit = index;
            }
        }
        assertEquals(AudioPresentationCommandQueue.CAPACITY,
                drainedInsideSubmit,
                "a full queue with no droppable command left must drain "
                        + "synchronously at its owner boundary");

        fixture.commands.applyPending(recorder);

        assertEquals(0, fixture.commands.size());
        assertSame(queueEntriesBefore, field(fixture.commands, "entries"),
                "queue overflow must reuse its fixed ledger array");
        assertEquals(expectedRingLeft.size(), applied.size(),
                "no structural command may be dropped by overflow");
        assertEquals(expectedRingLeft, applied,
                "structural commands apply exactly once in original order");
        assertEquals(0, fixture.registry.orderedVoiceCount(),
                "only droppable sample starts may be evicted for structure");
        assertEquals(expectedRingLeft.get(expectedRingLeft.size() - 1),
                fixture.registry.snapshot().ringLeft(),
                "the last structural command owns the final state");

        // Deferred-mutation overflow: more removals than the fixed 64-entry
        // list can hold collapse into exactly one deterministic sweep.
        fixture.admitMixedVoices();
        int liveVoices = fixture.registry.orderedVoiceCount();
        fixture.registry.beginRendering();
        for (int index = 0;
                index <= AudioVoiceRegistry.MAX_DEFERRED_MUTATIONS; index++) {
            fixture.registry.deferRemoval(90_000 + index);
        }
        assertTrue(fixture.registry.completionSweepRequired(),
                "exceeding the deferred list must request a sweep");
        assertEquals(liveVoices, fixture.registry.orderedVoiceCount(),
                "render traversal cannot mutate storage");
        fixture.registry.endRendering();

        assertEquals(1, fixture.registry.completionSweepCount(),
                "deferred overflow collapses into one sweep, never a resize");
        assertEquals(liveVoices, fixture.registry.orderedVoiceCount(),
                "unrelated deferred ids must not retire live voices");
        assertSame(deferredBefore, field(fixture.registry, "deferredRemovals"),
                "deferred overflow must reuse its fixed list");
        assertEquals(AudioVoiceRegistry.MAX_DEFERRED_MUTATIONS,
                ((long[]) field(fixture.registry, "deferredRemovals")).length);
        fixture.producer.close();
    }

    // ---------------------------------------------------------------
    // Measured workload
    // ---------------------------------------------------------------

    /**
     * The one measured shape. Warm-up and measurement both call this method so
     * the loop is compiled at method entry before it is ever counted.
     */
    private static void presentForwardFrames(
            AudioPresentationProducer producer, long startFrame, int frames) {
        for (int index = 0; index < frames; index++) {
            producer.present(startFrame + index, PresentationMode.FORWARD);
        }
    }

    // ---------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------

    private static Fixture fixture(AudioPresentationSink sink) {
        DecodedPcm musicPcm = ramp("music/loop.wav", 4_096);
        DecodedPcm sfxPcm = ramp("sfx/short.wav", 2_048);
        Map<String, DecodedPcm> assets = new LinkedHashMap<>();
        assets.put(musicPcm.assetId(), musicPcm);
        assets.put(sfxPcm.assetId(), sfxPcm);
        AudioPresentationDependencyResolver resolver =
                new AudioPresentationDependencyResolver() {
                    @Override
                    public DecodedPcm resolvePcm(String assetId) {
                        DecodedPcm pcm = assets.get(assetId);
                        if (pcm == null) {
                            throw new AssertionError("no PCM for " + assetId);
                        }
                        return pcm;
                    }

                };
        AudioVoiceRegistry registry = new AudioVoiceRegistry(
                noSmps(), resolver,
                new SmpsCoordFlagHandlerOwner(new SmpsCoordFlagRuntimeState()),
                warning -> {
                });
        AudioPresentationCommandQueue commands =
                new AudioPresentationCommandQueue(registry::isRendering);
        AudioPresentationMixer mixer = new AudioPresentationMixer(
                PACKET_FRAMES, registry::onVoiceFailure);
        AudioPresentationProducer producer = new AudioPresentationProducer(
                SAMPLE_RATE, FRAME_RATE, SAMPLE_RATE, 0, registry, commands,
                mixer, sink);
        return new Fixture(
                registry, commands, mixer, producer, sink, musicPcm, sfxPcm);
    }

    private static SmpsSfxInstantiation noSmps() {
        return new SmpsSfxInstantiation() {
            @Override
            public com.openggf.audio.smps.SmpsSequencer instantiateCached(
                    com.openggf.audio.presentation.ResolvedSmpsSfxSource source,
                    com.openggf.audio.driver.SmpsDriver currentOwner) {
                throw new AssertionError("no SMPS SFX expected");
            }

        };
    }

    private record Fixture(
            AudioVoiceRegistry registry,
            AudioPresentationCommandQueue commands,
            AudioPresentationMixer mixer,
            AudioPresentationProducer producer,
            AudioPresentationSink sink,
            DecodedPcm musicPcm,
            DecodedPcm sfxPcm) {

        private void submit(AudioPresentationCommand command) {
            submit(command, registry::apply);
        }

        private void submit(
                AudioPresentationCommand command,
                java.util.function.Consumer<AudioPresentationCommand> applier) {
            commands.submit(command, () -> true, applier);
        }

        private Object mixerOutput() {
            return field(mixer, "output");
        }

        private Object frameViewSamples() {
            return TestAudioPresentationAllocationBudget
                    .frameViewSamples(producer);
        }

        private void admitMusic() {
            submit(new ReplaceMusic(MusicVoiceEntry.fromVoice(
                    0x81, AudioSourceDescriptor.baseMusic(0x81),
                    SampleBackedVoice.loopingMusic(
                            1, musicPcm, SAMPLE_RATE, 1.0f))));
            commands.applyPending(registry::apply);
        }

        /**
         * One looping music voice plus four looping sample voices. Looping
         * voices never complete, so the measured traversal keeps a stable,
         * non-trivial voice count for the whole run.
         */
        private void admitMixedVoices() {
            admitMusic();
            for (int index = 0; index < 4; index++) {
                submit(StartSampleSfx.fromVoice(new SampleBackedVoice(
                        10 + index, index, sfxPcm, SAMPLE_RATE,
                        1.0 + index * 0.25, 1 << 16, true)));
            }
            commands.applyPending(registry::apply);
        }

        private Structure structure() {
            Map<String, Object> identities = new LinkedHashMap<>();
            Map<String, Long> sizes = new LinkedHashMap<>();
            putArray(identities, sizes, "mixer.accumulation",
                    field(mixer, "accumulation"));
            putArray(identities, sizes, "mixer.voiceScratch",
                    field(mixer, "voiceScratch"));
            putArray(identities, sizes, "mixer.output", field(mixer, "output"));
            putArray(identities, sizes, "producer.silence",
                    field(producer, "silence"));
            putArray(identities, sizes, "producer.reversePcm",
                    field(producer, "reversePcm"));
            putArray(identities, sizes, "producer.captures",
                    field(producer, "captures"));
            identities.put("producer.frameView", field(producer, "frameView"));
            identities.put("producer.history", field(producer, "history"));
            putArray(identities, sizes, "history.samples",
                    field(field(producer, "history"), "samples"));
            putArray(identities, sizes, "registry.orderedVoices",
                    field(registry, "orderedVoices"));
            putArray(identities, sizes, "registry.sampleSfx",
                    field(registry, "sampleSfx"));
            putArray(identities, sizes, "registry.overrideStack",
                    field(registry, "overrideStack"));
            putArray(identities, sizes, "registry.deferredRemovals",
                    field(registry, "deferredRemovals"));
            putArray(identities, sizes, "registry.warnedRejectionVoiceIds",
                    field(registry, "warnedRejectionVoiceIds"));
            putArray(identities, sizes, "commands.entries",
                    field(commands, "entries"));
            if (sink instanceof OpenAlPcmSink speaker) {
                Object fifo = field(speaker, "fifo");
                identities.put("speaker.fifo", fifo);
                putArray(identities, sizes, "speaker.fifo.samples",
                        field(fifo, "samples"));
                putArray(identities, sizes, "speaker.packetScratch",
                        field(speaker, "packetScratch"));
                putArray(identities, sizes, "speaker.deviceScratch",
                        field(speaker, "deviceScratch"));
            }
            Object[] captures = (Object[]) field(producer, "captures");
            for (int index = 0; index < captures.length; index++) {
                if (captures[index] != null) {
                    identities.put("capture[" + index + "]", captures[index]);
                    putArray(identities, sizes, "capture[" + index + "].pending",
                            field(captures[index], "pending"));
                }
            }
            return new Structure(identities, sizes);
        }
    }

    private record Structure(
            Map<String, Object> identities, Map<String, Long> sizes) {
    }

    private static void putArray(
            Map<String, Object> identities,
            Map<String, Long> sizes,
            String name,
            Object array) {
        identities.put(name, array);
        sizes.put(name, (long) java.lang.reflect.Array.getLength(array));
    }

    private static void assertNoStructuralGrowth(
            Structure before, Structure after) {
        assertEquals(before.identities().keySet(), after.identities().keySet(),
                "the presentation must not gain or lose owned storage");
        for (Map.Entry<String, Object> entry : before.identities().entrySet()) {
            assertSame(entry.getValue(), after.identities().get(entry.getKey()),
                    entry.getKey()
                            + " must remain the same preallocated instance");
        }
        for (Map.Entry<String, Long> entry : before.sizes().entrySet()) {
            assertEquals(entry.getValue(), after.sizes().get(entry.getKey()),
                    entry.getKey() + " must not grow");
        }
    }

    // ---------------------------------------------------------------
    // Reflection helpers
    // ---------------------------------------------------------------

    /**
     * Resolved once: the one-hour test reads it after every one of its
     * ~220,000 presents, and a per-call {@code getDeclaredField} would both
     * dominate that loop and allocate a fresh {@link Field} copy each time.
     */
    private static final Field FRAME_VIEW_FIELD =
            declaredField(AudioPresentationProducer.class, "frameView");
    private static final Field FRAME_VIEW_SAMPLES_FIELD =
            declaredField(AudioPresentationFrameView.class, "samples");

    private static Field declaredField(Class<?> owner, String name) {
        try {
            Field declared = owner.getDeclaredField(name);
            declared.setAccessible(true);
            return declared;
        } catch (NoSuchFieldException missing) {
            throw new AssertionError(missing);
        }
    }

    /**
     * The live PCM buffer the producer's synchronous view currently publishes.
     * This is the only storage reference on the presentation path that is not
     * {@code final}, so it is the only one whose identity a per-frame copy can
     * change.
     */
    private static Object frameViewSamples(AudioPresentationProducer producer) {
        try {
            return FRAME_VIEW_SAMPLES_FIELD.get(FRAME_VIEW_FIELD.get(producer));
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        }
    }

    private static int sampleSfxCount(AudioVoiceRegistry registry) {
        return (int) field(registry, "sampleSfxCount");
    }

    private static int warnedRejectionCount(AudioVoiceRegistry registry) {
        return (int) field(registry, "warnedRejectionCount");
    }

    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field declared = type.getDeclaredField(name);
                declared.setAccessible(true);
                return declared.get(target);
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError(
                "no field " + name + " on " + target.getClass());
    }

    private static int captureCount(AudioPresentationProducer producer) {
        return (int) field(producer, "captureCount");
    }

    private static int historyStoredFrames(AudioPresentationProducer producer) {
        return (int) field(field(producer, "history"), "storedFrames");
    }

    private static Object tapPending(
            AudioPresentationProducer producer, int index) {
        return field(((Object[]) field(producer, "captures"))[index], "pending");
    }

    private static int nonZeroCount(short[] samples) {
        int nonZero = 0;
        for (short sample : samples) {
            if (sample != 0) {
                nonZero++;
            }
        }
        return nonZero;
    }

    private static DecodedPcm ramp(String assetId, int frames) {
        short[] samples = new short[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            samples[frame * 2] = (short) ((frame * 97) % 20_000 - 10_000);
            samples[frame * 2 + 1] = (short) (10_000 - (frame * 61) % 20_000);
        }
        return new DecodedPcm(assetId, 2, SAMPLE_RATE, samples);
    }

    /** Device that accepts nothing until it is explicitly updated. */
    private static final class FakeDevice implements OpenAlPcmSink.Device {
        private final int sampleRate;
        private final List<Integer> enqueuedFrames = new ArrayList<>();
        private int updateCalls;
        private int queuedBuffers;

        private FakeDevice(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        @Override
        public int initialize() {
            return sampleRate;
        }

        @Override
        public void enqueue(
                short[] stereoPcm, int stereoFrames, int rate) {
            enqueuedFrames.add(stereoFrames);
            queuedBuffers++;
        }

        @Override
        public int update() {
            updateCalls++;
            return queuedBuffers;
        }

        @Override
        public void flush() {
            queuedBuffers = 0;
        }

        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void close() {
        }
    }
}
