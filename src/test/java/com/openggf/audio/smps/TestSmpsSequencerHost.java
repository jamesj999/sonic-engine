package com.openggf.audio.smps;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.synth.VirtualSynthesizer;
import org.junit.jupiter.api.Test;

class TestSmpsSequencerHost {

    @Test
    void serviceCallbacksUseLogicalHostAndPreserveExactEvent() {
        RecordingHost host = new RecordingHost();
        MusicData data = new MusicData();
        SmpsSequencer sequencer = new SmpsSequencer(
                data, AudioTestFixtures.EMPTY_DAC,
                new VirtualSynthesizer(), host, () -> {},
                new SmpsSequencerConfig.Builder().tempoOnFirstTick(true).build(),
                SmpsSourceDescriptor.from(data),
                SmpsSequencer.SourceDescriptorTrust.LEGACY_RECOMPUTE);

        sequencer.serviceOuterFrame();

        assertSame(host.beginEvent, host.endEvent);
    }

    private static final class RecordingHost implements SmpsSequencerHost {
        private SmpsDriverServiceObserver.ServiceEvent beginEvent;
        private SmpsDriverServiceObserver.ServiceEvent endEvent;

        @Override
        public SmpsDriverServiceObserver.ServiceEvent beginSequencerService(
                SmpsSequencer sequencer,
                SmpsDriverServiceObserver.ServiceKind kind) {
            beginEvent = new SmpsDriverServiceObserver.ServiceEvent(7,
                    SmpsDriverServiceObserver.DriverIdentity.unspecified(),
                    new SmpsDriverServiceObserver.SequencerIdentity(3,
                            sequencer.getSourceDescriptor(), false), kind);
            return beginEvent;
        }

        @Override
        public void endSequencerService(
                SmpsDriverServiceObserver.ServiceEvent event) {
            endEvent = event;
        }

        @Override public void reconcileInactiveSfxTracks(SmpsSequencer sequencer) { }
        @Override public byte[] s1SpecialSfxVoiceForBug(int voiceId) { return null; }
        @Override public boolean isContinuousSfxFlagSet() { return false; }
        @Override public void clearContinuousSfxId() { }
        @Override public void clearContinuousSfxFlag() { }
        @Override public boolean decrementContSfxLoopCnt() { return true; }
    }

    private static final class MusicData extends AbstractSmpsData {
        private MusicData() {
            super(new byte[0], 0);
        }

        @Override protected void parseHeader() { dividingTiming = 1; tempo = 1; }
        @Override public byte[] getVoice(int voiceId) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }
}
