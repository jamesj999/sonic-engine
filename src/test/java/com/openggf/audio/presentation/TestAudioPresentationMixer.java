package com.openggf.audio.presentation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestAudioPresentationMixer {

    @Test
    void mixesVoicesInListOrderAndSaturatesOnce() {
        AudioPresentationMixer mixer = new AudioPresentationMixer(2);
        PresentationVoiceSource voices = fixedVoices(
                voice(1, 20_000, -20_000),
                voice(2, 20_000, -20_000));

        short[] out = mixer.mix(voices, 2);

        assertArrayEquals(new short[] {32767, -32768, 32767, -32768}, out);
    }

    @Test
    void invokesVoicesInListOrder() {
        List<Long> invocationOrder = new ArrayList<>();
        AudioPresentationMixer mixer = new AudioPresentationMixer(1);

        mixer.mix(fixedVoices(
                recordingVoice(1, invocationOrder),
                recordingVoice(2, invocationOrder),
                recordingVoice(3, invocationOrder)), 1);

        assertEquals(List.of(1L, 2L, 3L), invocationOrder);
    }

    @Test
    void reusesOutputAndWideAccumulationBuffers() {
        AudioPresentationMixer mixer = new AudioPresentationMixer(4);
        PresentationVoiceSource voices = fixedVoices();

        assertSame(mixer.mix(voices, 4), mixer.mix(voices, 4));
    }

    /**
     * The accumulation, per-voice scratch and output buffers are allocated once
     * at full capacity and reused, so a shorter frame following a longer one
     * must see none of the longer frame's residue and must not re-emit a voice
     * that has since been removed. Fractional frame rates (48000/60 is exact,
     * but 48000/50 and every capture-lease rate are not) alternate packet
     * lengths every frame, so this is the ordinary case, not an edge case.
     */
    @Test
    void shorterFrameAfterALongerOneCarriesNoReusedScratchResidue() {
        AudioPresentationMixer mixer = new AudioPresentationMixer(4);
        PresentationVoice keptVoice = voice(1, 7, -7);

        short[] grown = mixer.mix(
                fixedVoices(keptVoice, voice(2, 1_000, -1_000)), 4);
        assertArrayEquals(new short[] {
                1_007, -1_007, 1_007, -1_007,
                1_007, -1_007, 1_007, -1_007}, grown);

        short[] shrunk = mixer.mix(fixedVoices(keptVoice), 2);

        assertArrayEquals(new short[] {7, -7, 7, -7},
                java.util.Arrays.copyOf(shrunk, 4),
                "the shorter frame must be the new mix alone, with no residue "
                        + "from the longer frame's accumulation or scratch");
    }

    @Test
    void rejectsFramesBeyondDeclaredCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioPresentationMixer(2).mix(fixedVoices(), 3));
    }

    @Test
    void throwingVoiceCannotLeakItsPartialContribution() {
        List<PresentationVoice> failed = new ArrayList<>();
        PresentationVoice writesThenThrows = voiceThatWritesThenThrows(30_000, 30_000);
        short[] out = new AudioPresentationMixer(1, failed::add)
                .mix(fixedVoices(voice(1, 100, 200), writesThenThrows), 1);

        assertArrayEquals(new short[] {100, 200}, out);
        assertEquals(List.of(writesThenThrows), failed);
    }

    @Test
    void continuesMixingAfterAVoiceFails() {
        PresentationVoice writesThenThrows = voiceThatWritesThenThrows(30_000, 30_000);

        short[] out = new AudioPresentationMixer(1, failed -> { })
                .mix(fixedVoices(voice(1, 100, 200), writesThenThrows, voice(3, -50, 100)), 1);

        assertArrayEquals(new short[] {50, 300}, out);
    }

    private static PresentationVoice voice(long id, long left, long right) {
        return new TestVoice(id) {
            @Override
            public void mixInto(long[] accumulation, int stereoFrames) {
                for (int frame = 0; frame < stereoFrames; frame++) {
                    int sample = frame * 2;
                    accumulation[sample] += left;
                    accumulation[sample + 1] += right;
                }
            }
        };
    }

    private static PresentationVoice recordingVoice(long id, List<Long> invocationOrder) {
        return new TestVoice(id) {
            @Override
            public void mixInto(long[] accumulation, int stereoFrames) {
                invocationOrder.add(voiceId());
            }
        };
    }

    private static PresentationVoice voiceThatWritesThenThrows(long left, long right) {
        return new TestVoice(2) {
            @Override
            public void mixInto(long[] accumulation, int stereoFrames) {
                accumulation[0] += left;
                accumulation[1] += right;
                throw new IllegalStateException("render failure");
            }
        };
    }

    private static PresentationVoiceSource fixedVoices(PresentationVoice... voices) {
        return new PresentationVoiceSource() {
            @Override
            public int orderedVoiceCount() {
                return voices.length;
            }

            @Override
            public PresentationVoice orderedVoiceAt(int index) {
                return voices[index];
            }
        };
    }

    private abstract static class TestVoice implements PresentationVoice {
        private final long id;

        private TestVoice(long id) {
            this.id = id;
        }

        @Override
        public long voiceId() {
            return id;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean isComplete() {
            return false;
        }

        @Override
        public void stop() {
        }

        @Override
        public PresentationVoiceSnapshot snapshot() {
            return null;
        }
    }
}
