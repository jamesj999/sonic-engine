package com.openggf.capture;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioManagerTestDiagnostics;
import com.openggf.audio.AudioTestFixtures.RecordingAudioBackend;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.GameSound;
import com.openggf.audio.LiveCaptureAudioHandle;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.SegaPcmSpec;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DrainPcmAudioTapTest {

    private static final int PCM_ADDRESS = 0x40;

    /**
     * {@code beginCaptureMode} now rejects a lease whose frame rate is not the
     * presentation producer's, and the producer takes its rate from the shared
     * configuration singleton. Surefire reuses forks, so pin the configuration
     * rather than inheriting whatever an earlier class in this JVM left behind:
     * these tests hardcode 48 kHz / 60 fps.
     */
    @BeforeEach
    void resetSharedConfiguration() {
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @AfterEach
    void tearDown() {
        AudioManager audio = AudioManager.getInstance();
        audio.endCaptureMode();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void drainsExactlyOneFramesPcmFromCaptureMode() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new RecordingAudioBackend());
        audio.beginCaptureMode(48000, 60);
        audio.presentFrame(PresentationMode.FORWARD);

        DrainPcmAudioTap tap = new DrainPcmAudioTap(audio);
        short[] target = new short[2048];
        assertEquals(800, tap.drain(target), "one 48k/60 frame = 800 stereo samples");

        audio.endCaptureMode();
    }

    @Test
    void drainsTheUnifiedPacketWithoutConsumingTheSpeakerView()
            throws Exception {
        AudioManager audio = audioWithAudibleRawPcm();
        audio.beginCaptureMode(48_000, 60);
        LiveCaptureAudioHandle speaker =
                AudioManagerTestDiagnostics.attachPresentationCapture(audio, 60);
        audio.playSegaPcm();
        audio.presentFrame(PresentationMode.FORWARD);

        DrainPcmAudioTap tap = new DrainPcmAudioTap(audio);
        short[] offline = new short[1600];
        short[] speakerPcm = new short[1600];
        assertEquals(800, tap.drain(offline));
        assertEquals(800, speaker.drainPresentationFrame(speakerPcm));

        assertFalse(allZero(offline), "the tap sees the rendered unified packet");
        assertArrayEquals(speakerPcm, offline,
                "offline tap and speaker are independent views of one packet");

        speaker.close();
        audio.endCaptureMode();
    }

    @Test
    void secondDrainWithinOneFrameYieldsClockedSilence() throws Exception {
        AudioManager audio = audioWithAudibleRawPcm();
        audio.beginCaptureMode(48_000, 60);
        audio.playSegaPcm();
        audio.presentFrame(PresentationMode.FORWARD);

        DrainPcmAudioTap tap = new DrainPcmAudioTap(audio);
        short[] first = new short[1600];
        assertEquals(800, tap.drain(first));
        assertFalse(allZero(first));

        short[] second = new short[1600];
        java.util.Arrays.fill(second, (short) 0x1234);
        assertEquals(800, tap.drain(second),
                "the clock still yields one frame of samples");
        assertTrue(allZero(second), "a repeat drain never replays stale PCM");

        audio.endCaptureMode();
    }

    private static AudioManager audioWithAudibleRawPcm() throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new RecordingAudioBackend());
        byte[] pcm = rampPcm(4_000);
        Rom rom = mock(Rom.class);
        when(rom.readBytes(PCM_ADDRESS, pcm.length)).thenReturn(pcm);
        audio.setAudioProfile(new RawPcmProfile(
                new SegaPcmSpec(PCM_ADDRESS, pcm.length, 48_000)));
        audio.setRom(rom);
        return audio;
    }

    private static byte[] rampPcm(int length) {
        byte[] pcm = new byte[length];
        for (int index = 0; index < length; index++) {
            pcm[index] = (byte) (index % 251);
        }
        return pcm;
    }

    private static boolean allZero(short[] samples) {
        for (short sample : samples) {
            if (sample != 0) {
                return false;
            }
        }
        return true;
    }

    private record RawPcmProfile(SegaPcmSpec spec) implements GameAudioProfile {
        @Override public SmpsLoader createSmpsLoader(Rom rom) { return null; }
        @Override public SmpsSequencerConfig getSequencerConfig() { return null; }
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
