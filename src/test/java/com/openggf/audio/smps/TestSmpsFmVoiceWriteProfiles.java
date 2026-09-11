package com.openggf.audio.smps;

import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SfxData;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSfxData;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSmpsFmVoiceWriteProfiles {
    private static final DacData EMPTY_DAC = new DacData(new HashMap<>(), new HashMap<>());
    private static final byte[] DISTINCT_VOICE = {
            0x2D,
            0x01, 0x02, 0x03, 0x04,
            0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C,
            0x0D, 0x0E, 0x0F, 0x10,
            0x11, 0x12, 0x13, 0x14,
            0x15, 0x16, 0x17, 0x18
    };

    @Test
    void s2MusicUsesTheShippedAscendingRegisterVoiceSequence() {
        assertEquals(s2Expected(), captureRefresh(s2Music(), Sonic2SmpsSequencerConfig.CONFIG));
    }

    @Test
    void s2LocalSfxUsesTheSameShippedVoiceSequence() {
        assertEquals(s2Expected(), captureRefresh(s2Sfx(), Sonic2SmpsSequencerConfig.CONFIG));
    }

    @Test
    void s3kMusicUsesTheShippedInstrumentRegisterTable() {
        assertEquals(s3kExpected(), captureRefresh(s3kMusic(), Sonic3kSmpsSequencerConfig.CONFIG));
    }

    @Test
    void s3kLocalSfxUsesTheShippedInstrumentRegisterTable() {
        assertEquals(s3kExpected(), captureRefresh(s3kSfx(false), Sonic3kSmpsSequencerConfig.CONFIG));
    }

    @Test
    void s3kGlobalSfxVoiceUsesTheShippedInstrumentRegisterTable() {
        Sonic3kSfxData source = s3kSfx(true);
        source.setGlobalVoiceData(DISTINCT_VOICE.clone());

        assertEquals(s3kExpected(), captureRefresh(source, Sonic3kSmpsSequencerConfig.CONFIG));
    }

    @Test
    void s3kVoiceLookupPreservesRawRomOperatorBytes() {
        Sonic3kSmpsData source = s3kMusic();

        assertArrayEquals(DISTINCT_VOICE, source.getVoice(0));
    }

    @Test
    void s2VolumeRefreshWritesAllTlsAndAddsVolumeOnlyToCarriers() {
        byte[] voice = DISTINCT_VOICE.clone();
        voice[22] = (byte) 0x96;
        voice[23] = (byte) 0x97;
        voice[24] = (byte) 0x98;
        Sonic2SmpsData source = new Sonic2SmpsData(musicBlob(voice), 0);

        // S2's zSetFMTLs writes every TL register; the algorithm mask only
        // controls whether the channel volume is added to that operator.
        assertEquals(List.of("0:40:15", "0:44:98", "0:48:99", "0:4C:9A"),
                captureVolume(source, Sonic2SmpsSequencerConfig.CONFIG, 2));
    }

    @Test
    void s3kVolumeRefreshUsesBitSevenAndItsMiddleRegisterTraversal() {
        byte[] voice = DISTINCT_VOICE.clone();
        voice[21] = 0x15;
        voice[22] = (byte) 0x96;
        voice[23] = 0x17;
        voice[24] = (byte) 0x98;
        Sonic3kSmpsData source = new Sonic3kSmpsData(musicBlob(voice), 0);

        // zSendTL loops over the whole TL table and writes every entry; the
        // bit-7 test only branches past the track-volume add, and the
        // fix_sndbugs=0 path strips the sign bit from what it sends
        // (skdisasm Sound/Z80 Sound Driver.asm:3149-3178). So the two
        // non-carriers 15h and 17h go out unchanged and the two carriers
        // 96h and 98h go out as 16h and 18h plus the track volume, in S3K's
        // middle-register traversal order.
        assertEquals(List.of("0:40:15", "0:48:18", "0:44:17", "0:4C:1A"),
                captureVolume(source, Sonic3kSmpsSequencerConfig.CONFIG, 2));
    }

    private static List<String> captureRefresh(AbstractSmpsData source, SmpsSequencerConfig config) {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        RecordingObserver observer = new RecordingObserver();
        SmpsSequencer sequencer = new SmpsSequencer(source, EMPTY_DAC, synth, config);
        SmpsSequencer.Track fm = sequencer.getTracks().stream()
                .filter(track -> track.type == SmpsSequencer.TrackType.FM)
                .findFirst()
                .orElseThrow();
        synth.setChipWriteObserver(observer);

        sequencer.refreshInstrument(fm);

        return observer.events;
    }

    private static List<String> captureVolume(AbstractSmpsData source, SmpsSequencerConfig config,
            int volumeOffset) {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        RecordingObserver observer = new RecordingObserver();
        SmpsSequencer sequencer = new SmpsSequencer(source, EMPTY_DAC, synth, config);
        SmpsSequencer.Track fm = sequencer.getTracks().stream()
                .filter(track -> track.type == SmpsSequencer.TrackType.FM)
                .findFirst()
                .orElseThrow();
        fm.volumeOffset = volumeOffset;
        synth.setChipWriteObserver(observer);

        sequencer.refreshVolume(fm);

        return observer.events;
    }

    private static Sonic2SmpsData s2Music() {
        byte[] data = musicBlob();
        return new Sonic2SmpsData(data, 0);
    }

    private static Sonic3kSmpsData s3kMusic() {
        byte[] data = musicBlob();
        return new Sonic3kSmpsData(data, 0);
    }

    private static byte[] musicBlob() {
        return musicBlob(DISTINCT_VOICE);
    }

    private static byte[] musicBlob(byte[] voice) {
        byte[] data = new byte[0x180];
        setLe16(data, 0x00, 0x100);
        data[0x02] = 2; // DAC + FM1
        data[0x03] = 0;
        data[0x04] = 1;
        data[0x05] = (byte) 0x80;
        setLe16(data, 0x06, 0x70);
        setLe16(data, 0x0A, 0x80);
        data[0x70] = (byte) 0xF2;
        data[0x80] = (byte) 0xF2;
        System.arraycopy(voice, 0, data, 0x100, voice.length);
        return data;
    }

    private static Sonic2SfxData s2Sfx() {
        byte[] data = sfxBlob(false);
        return new Sonic2SfxData(data, 0, 0, 0);
    }

    private static Sonic3kSfxData s3kSfx(boolean globalVoice) {
        byte[] data = sfxBlob(globalVoice);
        return new Sonic3kSfxData(data, 0, 0, 0);
    }

    private static byte[] sfxBlob(boolean globalVoice) {
        byte[] data = new byte[0x180];
        setLe16(data, 0x00, globalVoice ? 0 : 0x100);
        data[0x02] = 1;
        data[0x03] = 1;
        data[0x04] = (byte) 0x80;
        data[0x05] = 0; // FM1
        setLe16(data, 0x06, 0x80);
        data[0x08] = 0;
        data[0x09] = 0;
        data[0x80] = (byte) 0xF2;
        if (!globalVoice) {
            System.arraycopy(DISTINCT_VOICE, 0, data, 0x100, DISTINCT_VOICE.length);
        }
        return data;
    }

    private static List<String> s2Expected() {
        return List.of(
                "0:B0:2D",
                "0:30:01", "0:34:02", "0:38:03", "0:3C:04",
                "0:50:05", "0:54:06", "0:58:07", "0:5C:08",
                "0:60:09", "0:64:0A", "0:68:0B", "0:6C:0C",
                "0:70:0D", "0:74:0E", "0:78:0F", "0:7C:10",
                "0:80:11", "0:84:12", "0:88:13", "0:8C:14",
                "0:B4:C0",
                "0:40:15", "0:44:16", "0:48:17", "0:4C:18");
    }

    private static List<String> s3kExpected() {
        return List.of(
                "0:B4:C0", "0:B0:2D",
                "0:30:01", "0:38:02", "0:34:03", "0:3C:04",
                "0:50:05", "0:58:06", "0:54:07", "0:5C:08",
                "0:60:09", "0:68:0A", "0:64:0B", "0:6C:0C",
                "0:70:0D", "0:78:0E", "0:74:0F", "0:7C:10",
                "0:80:11", "0:88:12", "0:84:13", "0:8C:14",
                "0:40:15", "0:48:16", "0:44:17", "0:4C:18");
    }

    private static void setLe16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private static final class RecordingObserver implements ChipWriteObserver {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onYm2612Write(int port, int register, int value) {
            events.add("%d:%02X:%02X".formatted(port, register, value));
        }

        @Override
        public void onPsgWrite(int value) {
        }
    }
}
