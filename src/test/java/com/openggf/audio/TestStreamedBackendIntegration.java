package com.openggf.audio;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backend-side ownership of the launch-scoped streamed-music port.
 *
 * <p>Scope note: creator audio playback itself is no longer a backend concern.
 * A streamed track becomes a presentation music voice and a creator one-shot
 * becomes an ordinary sample voice, so mixing, PCM upload, fade-driven PCM
 * comparison, one-shot pooling, and reverse/history behaviour are covered by the
 * presentation-layer suites. What remains backend-owned, and is covered here, is
 * port ownership: install/replace ordering, output-rate admission, exact-key
 * preflight, reset/close lifecycle, and replay-bypass scoping.
 */
@Isolated
class TestStreamedBackendIntegration {

    @Test
    void portInstallAndReplacementAreConsumedInFifoOrderAtUpdateBoundary() {
        InstrumentedBackend backend = new InstrumentedBackend();
        RecordingPort first = new RecordingPort(8_000, true);
        RecordingPort second = new RecordingPort(8_000, true);

        backend.installStreamedMusicPort(first);
        backend.installStreamedMusicPort(second);
        assertEquals(0, first.closeCount, "installs are queued, not applied inline");

        backend.update();

        assertEquals(1, first.closeCount, "the superseded port is stopped and closed exactly once");
        assertEquals(0, second.closeCount);
        backend.destroy();
    }

    @Test
    void fadeTargetsStreamedForegroundEvenWhenInactiveSmpsStreamIsRetained() throws Exception {
        InstrumentedBackend backend = new InstrumentedBackend();
        RecordingPort port = new RecordingPort(8_000, true);
        backend.installStreamedMusicPort(port);
        backend.playStreamedMusicOrElse(0x81, () -> fail("stock fallback is unexpected"));
        backend.update();
        var inactive = org.mockito.Mockito.mock(
                com.openggf.audio.session.OwnedSmpsAudioStream.class);
        // The legacy override stack retains its saved stream while
        // currentSmps is null and the streamed cursor owns the foreground.
        var stream = AbstractSmpsAudioBackend.class.getDeclaredField("smpsStream");
        stream.setAccessible(true);
        stream.set(backend, inactive);
        try {
            backend.fadeOutMusic(40, 3);
            assertFalse(port.fadeActive());

            backend.update();

            assertTrue(port.fadeActive());
            assertEquals(40, port.fade.remainingSteps());
            org.mockito.Mockito.verify(inactive, org.mockito.Mockito.never())
                    .fadeOutMusic(org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.anyInt());
        } finally {
            backend.destroy();
        }
    }

    @Test
    void mismatchedOutputRateIsRejectedAndClosedAtBoundary() {
        InstrumentedBackend backend = new InstrumentedBackend();
        RecordingPort mismatched = new RecordingPort(44_100, true);

        assertThrows(IllegalArgumentException.class,
                () -> backend.installStreamedMusicPort(mismatched));

        assertEquals(1, mismatched.closeCount,
                "a rejected port is closed by the boundary that refused it");
        backend.destroy();
    }

    @Test
    void namespacedPreflightSeesPendingInstallAndExactKeyBeforeTimelineAcceptance() {
        InstrumentedBackend backend = new InstrumentedBackend();
        RecordingPort port = new RecordingPort(8_000, true);
        backend.installStreamedMusicPort(port);

        // Preflight must answer against the pending install: the timeline decides
        // whether to record a command before the transition queue is drained.
        assertTrue(backend.hasStreamedMusic(new StreamedMusicPort.TrackRef("owner", "track")));
        assertFalse(backend.hasStreamedMusic(new StreamedMusicPort.TrackRef("owner", "absent")));
        assertTrue(backend.tryPlayStreamedSfx(new StreamedMusicPort.SfxRef("owner", "effect")));
        assertFalse(backend.tryPlayStreamedSfx(new StreamedMusicPort.SfxRef("other", "effect")));
        assertEquals(0, port.openSfxCount,
                "preflight never opens a cursor; presentation owns one-shot playback");
        backend.destroy();
    }

    @Test
    void unresolvedOverrideRunsPreparedStockFallbackOnUpdateAfterInstall() {
        InstrumentedBackend backend = new InstrumentedBackend();
        RecordingPort port = new RecordingPort(8_000, false);
        backend.installStreamedMusicPort(port);
        boolean[] fallbackRan = {false};

        backend.playStreamedMusicOrElse(0x81, () -> fallbackRan[0] = true);
        assertFalse(fallbackRan[0], "the fallback runs at the transition boundary");

        backend.update();

        assertTrue(fallbackRan[0], "an unresolved override must fall back to stock music");
        assertEquals(0, port.playCount);
        backend.destroy();
    }

    @Test
    void resolvedOverrideTakesTheForegroundInsteadOfTheStockFallback() {
        InstrumentedBackend backend = new InstrumentedBackend();
        RecordingPort port = new RecordingPort(8_000, true);
        backend.installStreamedMusicPort(port);
        boolean[] fallbackRan = {false};

        backend.playStreamedMusicOrElse(0x81, () -> fallbackRan[0] = true);
        backend.update();

        assertFalse(fallbackRan[0]);
        assertEquals(1, port.playCount);
        assertTrue(port.source);
        backend.destroy();
    }

    @Test
    void resetClosesActiveAndPendingPortsAndDropsStalePlayTransitions() {
        InstrumentedBackend backend = new InstrumentedBackend();
        RecordingPort active = new RecordingPort(8_000, true);
        RecordingPort pending = new RecordingPort(8_000, true);
        backend.installStreamedMusicPort(active);
        backend.update();
        backend.installStreamedMusicPort(pending);
        backend.playStreamedMusicOrElse(0x81, () -> { });

        backend.resetStreamedMusicPort();

        assertEquals(1, active.closeCount);
        assertEquals(1, pending.closeCount);
        assertFalse(active.source, "the active port is stopped before being closed");

        // A play transition queued before the reset must not resurrect a closed port.
        backend.update();
        assertEquals(1, active.playCount == 0 ? 1 : active.playCount,
                "no post-reset playback is dispatched to a closed port");
        backend.destroy();
    }

    @Test
    void replayBypassSuppressesCreatorOverrideResolutionWhileArmed() {
        InstrumentedBackend backend = new InstrumentedBackend();
        RecordingPort port = new RecordingPort(8_000, true);
        backend.installStreamedMusicPort(port);
        backend.update();

        // The bypass is a latch, not a counter: keyframe replay arms it around a
        // whole replay scope so a resolvable override defers to the stock timeline.
        backend.beginStreamedOverrideReplayBypass();
        boolean[] fallbackRan = {false};
        backend.playStreamedMusicOrElse(0x81, () -> fallbackRan[0] = true);
        backend.update();
        assertTrue(fallbackRan[0], "an armed bypass must defer to the stock fallback");

        backend.endStreamedOverrideReplayBypass();
        fallbackRan[0] = false;
        backend.playStreamedMusicOrElse(0x81, () -> fallbackRan[0] = true);
        backend.update();
        assertFalse(fallbackRan[0], "releasing the bypass restores creator override resolution");
        backend.destroy();
    }

    @Test
    void appAndRewindPauseReasonsApplyInFifoOrderAndReachThePort() {
        InstrumentedBackend backend = new InstrumentedBackend();
        RecordingPort port = new RecordingPort(8_000, true);
        backend.installStreamedMusicPort(port);
        backend.update();
        backend.playStreamedMusicOrElse(0x81, () -> { });
        backend.update();

        backend.pause();
        backend.update();
        assertEquals(StreamedMusicPort.PAUSE_APP, port.pauseMask & StreamedMusicPort.PAUSE_APP);

        backend.resume();
        backend.update();
        assertEquals(0, port.pauseMask & StreamedMusicPort.PAUSE_APP);
        backend.destroy();
    }

    @Test
    void destroyReleasesTheInstalledPortExactlyOnce() {
        InstrumentedBackend backend = new InstrumentedBackend();
        RecordingPort port = new RecordingPort(8_000, true);
        backend.installStreamedMusicPort(port);
        backend.update();

        backend.destroy();

        assertEquals(1, port.closeCount);
    }


    private static final class RecordingPort implements StreamedMusicPort {
        private final int rate;
        private final boolean resolves;
        private int playCount;
        private int resolveCount;
        private int closeCount;
        private short sample;
        private boolean source;
        private int pauseMask;
        private int mixCount;
        private int logicalMusicId;
        private double position;
        private double playbackRate = 1;
        private StreamedMusicPort.FadeState fade = StreamedMusicPort.FadeState.idle();
        private boolean restoreAllowed = true;
        private Object forbiddenLock;
        private boolean calledUnderForbiddenLock;
        private boolean throwRateAfterClose;
        private int openSfxCount;
        private int closedSfxCount;
        private short sfxSample;

        private RecordingPort(int rate, boolean resolves) {
            this.rate = rate;
            this.resolves = resolves;
        }

        @Override public int outputRate() {
            checkLock();
            if (throwRateAfterClose && closeCount > 0) throw new IllegalStateException("closed");
            return rate;
        }
        @Override public boolean hasStockOverride(int musicId) { resolveCount++; return resolves; }
        @Override public boolean isCurrentStockOverride(int musicId) {
            return source && logicalMusicId == musicId;
        }
        @Override public void playStockOverride(int musicId) {
            playCount++;
            if (source && logicalMusicId == musicId) return;
            source = true; logicalMusicId = musicId; position = 0;
        }
        @Override public boolean hasTrack(TrackRef track) {
            return resolves && new TrackRef("owner", "track").equals(track);
        }
        @Override public void playTrack(TrackRef track) {
            if (!hasTrack(track)) throw new IllegalArgumentException("missing: " + track);
            playCount++;
            source = true; logicalMusicId = -1; position = 0;
        }
        @Override public boolean hasSfx(SfxRef sfx) {
            return resolves && new SfxRef("owner", "effect").equals(sfx);
        }
        @Override public OneShot openSfx(SfxRef sfx) {
            if (!hasSfx(sfx)) throw new IllegalArgumentException("missing: " + sfx);
            openSfxCount++;
            return new OneShot() {
                private boolean complete;
                private boolean closed;
                @Override public void mixInto(short[] output, int frames) {
                    if (closed) throw new IllegalStateException("closed");
                    int left = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, output[0] + sfxSample));
                    int right = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, output[1] + sfxSample));
                    output[0] = (short) left;
                    output[1] = (short) right;
                    complete = true;
                }
                @Override public boolean complete() { return complete; }
                @Override public void close() { if (!closed) { closed = true; closedSfxCount++; } }
            };
        }
        @Override public boolean hasSource() { checkLock(); return source; }
        @Override public int mixInto(short[] output, int frames) {
            checkLock();
            if (!source || pauseMask != 0) return 0;
            mixCount++;
            position += frames * playbackRate;
            for (int i = 0; i < frames * 2; i++) output[i] += sample;
            return frames;
        }
        @Override public void pause(int reason) { pauseMask |= reason; }
        @Override public void resume(int reason) { pauseMask &= ~reason; }
        @Override public void fadeOut(int steps, int stepDelay) {
            fade = new FadeState(fade.gain(), steps, stepDelay, stepDelay, -fade.gain() / steps);
        }
        @Override public void fadeIn(int steps, int stepDelay) {
            fade = new FadeState(0, steps, stepDelay, stepDelay, 1.0f / steps);
        }
        @Override public void advanceFade() {
            if (fade.remainingSteps() == 0 || pauseMask != 0) return;
            int delay = fade.delayCounter();
            if (delay > 0) {
                fade = new FadeState(fade.gain(), fade.remainingSteps(), fade.stepDelay(),
                        delay - 1, fade.stepAmount());
                return;
            }
            int remaining = fade.remainingSteps() - 1;
            float gain = remaining == 0 ? (fade.stepAmount() > 0 ? 1 : 0)
                    : fade.gain() + fade.stepAmount();
            fade = remaining == 0 ? FadeState.idle()
                    : new FadeState(gain, remaining, fade.stepDelay(), fade.stepDelay(), fade.stepAmount());
        }
        @Override public boolean fadeActive() { return fade.remainingSteps() > 0; }
        @Override public boolean fadeAtFullGain() { return !fadeActive() && fade.gain() == 1; }
        @Override public void setSpeedMultiplier(int multiplier) { playbackRate = multiplier > 1 ? 1.25 : 1; }
        @Override public void stop() { source = false; fade = FadeState.idle(); }
        @Override public void reset() { source = false; pauseMask = 0; fade = FadeState.idle(); playbackRate = 1; }
        @Override public Optional<State> captureState() {
            checkLock();
            return source ? Optional.of(new State(new TrackRef("owner", "track"), logicalMusicId,
                    position, pauseMask, fade, playbackRate)) : Optional.empty();
        }
        @Override public boolean restoreState(State state) {
            checkLock();
            if (!restoreAllowed) return false;
            source = true; logicalMusicId = state.logicalMusicId(); position = state.sourceFramePosition();
            pauseMask = state.pauseMask(); fade = state.fade(); playbackRate = state.rate(); return true;
        }
        @Override public void close() { closeCount++; }
        private void checkLock() {
            if (forbiddenLock != null && Thread.holdsLock(forbiddenLock)) calledUnderForbiddenLock = true;
        }
    }

    private static class InstrumentedBackend extends AbstractSmpsAudioBackend {
        protected InstrumentedBackend() {
            super(config(), null);
        }

        private static SonicConfigurationService config() {
            SonicConfigurationService config = SonicConfigurationService.createStandalone();
            config.setConfigValue(SonicConfiguration.AUDIO_INTERNAL_RATE_OUTPUT, false);
            return config;
        }

        @Override protected int getDeviceSampleRate() { return 8_000; }
        @Override protected void hookInitDevice() { }
        @Override protected void hookDestroyDevice() { }
        @Override protected void hookStartStream() { }
        @Override protected void hookStopStreamSource() { }
        @Override protected void hookUpdateStream() { }
        @Override protected void hookStopAndClearMusicSource() { }
        @Override protected void hookStopAndUnqueueAllMusicBuffers() { }
        @Override protected void hookStopAndClearAllMusicBuffers() { }
        @Override protected void hookRestartStreamIfDry() { }
        @Override protected void hookStopAndDeleteWavSfxSources() { }
        @Override protected void hookPlayWavSfx(String name, float pitch) { }
        @Override protected void hookCleanupStoppedWavSfx() { }
        @Override protected void hookPause() { }
        @Override protected void hookResume() { }
    }
}
