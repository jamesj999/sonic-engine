package com.openggf.audio;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsSequencerFadeTiming {

    @Test
    void fadeInCompletionUsesFrameClockInsteadOfMusicTempoTicks() {
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .tempoModBase(0x100)
                .fadeInSteps(2)
                .fadeInDelay(1)
                .build();
        SmpsSequencer sequencer = new SmpsSequencer(
                new MinimalMusicData(1),
                AudioTestFixtures.EMPTY_DAC,
                new VirtualSynthesizer(),
                AudioManager.getInstance(),
                config);
        sequencer.setSampleRate(60.0);

        int[] callbacks = {0};
        sequencer.setOnFadeComplete(() -> callbacks[0]++);
        sequencer.triggerFadeIn();

        sequencer.advanceBatch(4);

        assertEquals(1, callbacks[0],
                "Fade-in completion should follow configured frame delay, not wait for a music tempo tick");
    }

    @Test
    void s3kAllowsSfxWhenPreviousMusicRestoreFadeStarts() {
        assertFalse(new Sonic3kAudioProfile().blocksSfxDuringMusicRestoreFadeIn(),
                "S3K clears zFadeToPrevFlag when zFadeInToPrevious starts, before fade-in completes");
    }

    @Test
    void sonic2KeepsSfxBlockedUntilRestoreFadeCompletes() {
        assertTrue(new Sonic2AudioProfile().blocksSfxDuringMusicRestoreFadeIn(),
                "S2 gates SFX on 1upPlaying OR FadeInFlag");
    }

    private static final class MinimalMusicData extends AbstractSmpsData {
        private final int configuredTempo;

        private MinimalMusicData(int tempo) {
            super(new byte[0], 0);
            this.configuredTempo = tempo;
            this.tempo = tempo;
        }

        @Override
        protected void parseHeader() {
        }

        @Override
        public int getTempo() {
            return configuredTempo;
        }

        @Override
        public byte[] getVoice(int voiceId) {
            return new byte[25];
        }

        @Override
        public byte[] getPsgEnvelope(int id) {
            return new byte[0];
        }

        @Override
        public int read16(int offset) {
            return 0;
        }

        @Override
        public int getBaseNoteOffset() {
            return 0;
        }
    }
}
