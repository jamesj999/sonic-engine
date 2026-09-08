package com.openggf.audio.presentation;

import com.openggf.audio.StreamedMusicPort;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.presentation.AudioPresentationCommand.FadeMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.SetSpeedShoes;
import com.openggf.audio.presentation.AudioPresentationCommand.PushMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.RestoreMusicOverride;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestStreamedAudioVoiceRegistry {

    @Test
    void sessionFadeAlsoFadesStreamedMusicAndRetainsClearedSpeedShoes() {
        SmpsDriverSession session = mock(SmpsDriverSession.class);
        Fixture fixture = new Fixture(config(1, 0), false, session);
        fixture.registry.apply(new ReplaceMusic(
                fixture.factory.streamedStockOverride(1, 0x81)));
        fixture.registry.apply(new SetSpeedShoes(true));
        assertTrue(fixture.registry.snapshot().speedShoesEnabled());
        // The session has already applied the retail host fade policy when
        // presentation receives this command (S1 clears speed shoes here).
        when(session.speedShoesEnabled()).thenReturn(false);

        fixture.registry.apply(new FadeMusic(40, 3));

        assertEquals(1, fixture.port.fadeOutCount);
        assertEquals(40, fixture.port.lastFadeSteps);
        assertEquals(3, fixture.port.lastFadeDelay);
        assertFalse(fixture.registry.snapshot().speedShoesEnabled());
    }

    @Test
    void streamedRestoreKeepsItsFadeBoundaryWhenSmpsSessionAlsoOwnsSfx() {
        SmpsDriverSession session = mock(SmpsDriverSession.class);
        when(session.suppressesSfxDuringOverride()).thenReturn(true);
        when(session.releasesSfxSuppressionAtRestore()).thenReturn(false);
        when(session.isMusicFadingIn()).thenReturn(false);
        Fixture fixture = new Fixture(config(2, 0), true, session);
        fixture.replaceAndPush();

        fixture.registry.apply(new RestoreMusicOverride());

        assertEquals(1, fixture.registry.orderedVoiceCount(),
                "streamed PCM must remain in the mix beside a SMPS session");
        assertTrue(fixture.registry.areSfxRequestsBlocked(),
                "an idle SMPS fade must not release the streamed cursor's fade block");
        fixture.renderFrame();
        assertTrue(fixture.registry.areSfxRequestsBlocked());
        fixture.renderFrame();
        assertFalse(fixture.registry.areSfxRequestsBlocked());
    }

    @Test
    void completionSweepRetiresFiniteStreamedCursors() {
        Fixture fixture = new Fixture(config(1, 0), false);
        fixture.port.finite = true;

        for (int index = 0; index < 20; index++) {
            fixture.registry.apply(new ReplaceMusic(
                    fixture.factory.streamedStockOverride(index + 1, 0x81)));
            fixture.renderFrame();
            assertEquals(0, fixture.registry.orderedVoiceCount());
            assertEquals(0, fixture.factory.streamedCursorCountForTesting());
        }
    }

    @Test
    void restoreFadeUsesConfiguredCadenceAndAfterFadeReleaseBoundary() {
        Fixture fixture = new Fixture(config(1, 0), true);
        fixture.replaceAndPush();

        fixture.registry.apply(new RestoreMusicOverride());

        assertTrue(fixture.registry.snapshot().sfxBlocked());
        assertEquals(1, fixture.port.fadeInCount);
        assertEquals(1, fixture.port.lastFadeSteps);
        assertEquals(0, fixture.port.lastFadeDelay);
        fixture.renderFrame();
        assertFalse(fixture.registry.snapshot().sfxBlocked());
    }

    @Test
    void restoreFadeCanReleaseSfxImmediatelyWhileFadeContinues() {
        Fixture fixture = new Fixture(config(2, 1), false);
        fixture.replaceAndPush();

        fixture.registry.apply(new RestoreMusicOverride());

        assertFalse(fixture.registry.snapshot().sfxBlocked());
        assertEquals(1, fixture.port.fadeInCount);
        assertTrue(fixture.port.fadeActive());
    }

    @Test
    void snapshotWithActiveStreamedOverrideKeepsOverrideSfxBlockAfterRender() {
        Fixture fixture = new Fixture(config(1, 0), true);
        fixture.replaceAndPush();
        AudioPresentationSnapshot snapshot = fixture.registry.snapshot();
        assertTrue(!snapshot.overrideStack().isEmpty());
        assertTrue(snapshot.sfxBlocked());

        fixture.registry.restore(snapshot, fixture.factory);
        fixture.renderFrame();

        AudioPresentationSnapshot restored = fixture.registry.snapshot();
        assertTrue(!restored.overrideStack().isEmpty());
        assertTrue(restored.sfxBlocked(),
                "an active override block is not a completed restore fade");
    }

    private static SmpsSequencerConfig config(
            int steps, int delay) {
        return new SmpsSequencerConfig.Builder()
                .fadeInSteps(steps)
                .fadeInDelay(delay)
                .build();
    }

    private static final class Fixture {
        private final RecordingPort port = new RecordingPort();
        private final AudioPresentationSourceFactory factory;
        private final AudioVoiceRegistry registry;

        private Fixture(SmpsSequencerConfig config, boolean blockSfxDuringFade) {
            this(config, blockSfxDuringFade, null);
        }

        private Fixture(SmpsSequencerConfig config, boolean blockSfxDuringFade,
                        SmpsDriverSession session) {
            SmpsCoordFlagHandlerOwner handlers =
                    new SmpsCoordFlagHandlerOwner(
                            new SmpsCoordFlagRuntimeState());
            factory = new AudioPresentationSourceFactory(
                    () -> true, handlers,
                    AudioPresentationSourceFactory.Settings.defaults());
            factory.installStreamedMusicPort(port);
            factory.setStreamedRestoreConfig(config, blockSfxDuringFade);
            registry = new AudioVoiceRegistry(factory, factory, handlers,
                    ignored -> { }, session);
        }

        private void replaceAndPush() {
            registry.apply(new ReplaceMusic(
                    factory.streamedStockOverride(1, 0x81)));
            registry.apply(new PushMusicOverride(
                    factory.streamedStockOverride(2, 0x82)));
        }

        private void renderFrame() {
            registry.beginRendering();
            try {
                if (registry.orderedVoiceCount() > 0) {
                    registry.orderedVoiceAt(0).mixInto(new long[2], 1);
                }
            } finally {
                registry.endRendering();
            }
        }
    }

    private static final class RecordingPort implements StreamedMusicPort {
        private State state;
        private boolean finite;
        private int fadeInCount;
        private int fadeOutCount;
        private int lastFadeSteps;
        private int lastFadeDelay;

        @Override public int outputRate() { return 48_000; }
        @Override public boolean hasStockOverride(int musicId) { return true; }
        @Override public boolean isCurrentStockOverride(int musicId) {
            return state != null && state.logicalMusicId() == musicId;
        }
        @Override public void playStockOverride(int musicId) {
            state = new State(new TrackRef("stock", Integer.toString(musicId)),
                    musicId, 0, 0, FadeState.idle(), 1);
        }
        @Override public boolean hasSource() { return state != null; }
        @Override public int mixInto(short[] output, int frames) {
            if (state == null) return 0;
            if (finite) {
                state = null;
            } else {
                state = new State(state.track(), state.logicalMusicId(),
                        state.sourceFramePosition() + frames, state.pauseMask(),
                        state.fade(), state.rate());
            }
            return frames;
        }
        @Override public void pause(int reason) { }
        @Override public void resume(int reason) { }
        @Override public void fadeOut(int steps, int stepDelay) {
            fadeOutCount++;
            lastFadeSteps = steps;
            lastFadeDelay = stepDelay;
        }
        @Override public void fadeIn(int steps, int stepDelay) {
            fadeInCount++;
            lastFadeSteps = steps;
            lastFadeDelay = stepDelay;
            state = withFade(new FadeState(0, steps, stepDelay,
                    stepDelay, 1.0f / steps));
        }
        @Override public void advanceFade() {
            if (!fadeActive()) return;
            FadeState fade = state.fade();
            if (fade.delayCounter() > 0) {
                state = withFade(new FadeState(fade.gain(),
                        fade.remainingSteps(), fade.stepDelay(),
                        fade.delayCounter() - 1, fade.stepAmount()));
                return;
            }
            int remaining = fade.remainingSteps() - 1;
            state = withFade(remaining == 0 ? FadeState.idle()
                    : new FadeState(fade.gain() + fade.stepAmount(), remaining,
                    fade.stepDelay(), fade.stepDelay(), fade.stepAmount()));
        }
        @Override public boolean fadeActive() {
            return state != null && state.fade().remainingSteps() > 0;
        }
        @Override public boolean fadeAtFullGain() {
            return state != null && !fadeActive() && state.fade().gain() == 1;
        }
        @Override public void setSpeedMultiplier(int multiplier) {
            state = new State(state.track(), state.logicalMusicId(),
                    state.sourceFramePosition(), state.pauseMask(), state.fade(),
                    multiplier == 1 ? 1 : 1.25);
        }
        @Override public void stop() { state = null; }
        @Override public void reset() { state = null; }
        @Override public Optional<State> captureState() {
            return Optional.ofNullable(state);
        }
        @Override public boolean restoreState(State restored) {
            state = restored;
            return true;
        }
        @Override public void close() { state = null; }

        private State withFade(FadeState fade) {
            return new State(state.track(), state.logicalMusicId(),
                    state.sourceFramePosition(), state.pauseMask(), fade,
                    state.rate());
        }
    }
}
