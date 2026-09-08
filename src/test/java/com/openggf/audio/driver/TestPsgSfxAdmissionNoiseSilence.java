package com.openggf.audio.driver;

import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSfxData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestPsgSfxAdmissionNoiseSilence {
    @Test
    void zeroNoiseOperandWritesOneNoiseSilenceAfterAdmission() {
        // Retail cfSetPSGNoise writes DF, FF for operand zero (:3562-3572).
        // The following rest adds DF through zSilencePSGChannel (:4231-4235).
        // Include the following rest so an injected FF cannot masquerade as the
        // command's own, identical FF while pushing a duplicate after it.
        byte[] bytes = {0, 0, 1, 1,
                (byte) 0x80, (byte) 0xC0, 10, 0, 0, 0,
                (byte) 0xF3, 0, (byte) 0x80, 1, (byte) 0xF2};
        List<Integer> writes = new ArrayList<>();
        SmpsDriver driver = SmpsDriverTestAccess.create(44_100, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
            }

            @Override
            public void onPsgWrite(int value) {
                writes.add(value);
            }
        });
        try {
            SmpsSequencer sfx = new SmpsSequencer(
                    new Sonic3kSfxData(bytes, 0, 0, 0), new DacData(Map.of(), Map.of()),
                    driver, () -> { }, Sonic3kSmpsSequencerConfig.CONFIG);
            driver.addSequencer(sfx, true);
            assertEquals(List.of(0xFF), writes, "admission owns its separate noise silence");
            writes.clear();

            for (int service = 0; service < 4 && writes.isEmpty(); service++) {
                driver.serviceOuterFrame();
            }

            assertEquals(List.of(0xDF, 0xFF, 0xDF), writes.subList(0, 3),
                    "zero noise form's DF, FF pair is immediately followed by the rest's DF");
        } finally {
            SmpsDriverTestAccess.close(driver);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0x80, 0xA0})
    void retailPsgAdmissionSilencesNoiseForPsgOneAndTwo(int channel) {
        // zGetSFXChannelPointers.is_psg has no PSG3 condition (:2131-2136).
        assertEquals(List.of(0xFF), admitPsg(channel, Sonic3kSmpsSequencerConfig.CONFIG));
    }

    @ParameterizedTest
    @ValueSource(ints = {0x80, 0xA0})
    void defaultProfileDoesNotAddAdmissionWrites(int channel) {
        assertEquals(List.of(), admitPsg(channel, new SmpsSequencerConfig.Builder().build()));
    }

    @Test
    void consecutivePsgHeadersSilenceThePreviouslyInitializedChannel() {
        assertEquals(List.of(0xFF, 0xBF, 0xFF), admitHeaders(0xA0, 0x80));
        assertEquals(List.of(0xFF, 0x9F, 0xFF), admitHeaders(0x80, 0xA0));
    }

    @Test
    void interveningFmHeaderReplacesThePreviousPsgHeader() {
        assertEquals(List.of(0xFF, 0xFF), admitHeaders(0xA0, 0x04, 0x80));
    }

    @Test
    void rawPsgVoiceControlsMapToToneChannelsInHeaderOrder() {
        SmpsDriver driver = SmpsDriverTestAccess.create(44_100);
        try {
            SmpsSequencer sequencer = createSequencer(driver, 0x80, 0xA0, 0xC0);
            assertEquals(List.of(0, 1, 2), sequencer.getTracks().stream()
                    .map(track -> track.channelId).toList());
        } finally {
            SmpsDriverTestAccess.close(driver);
        }
    }

    @Test
    void headerOrderProjectionUsesLiveTrackIdentitiesAcrossSnapshotRestore() {
        SmpsDriver driver = SmpsDriverTestAccess.create(44_100);
        try {
            SmpsSequencer sequencer = createSequencer(driver, 0xA0, 0x80);
            assertEquals(List.of(0, 1), sequencer.getTracks().stream()
                    .map(track -> track.channelId).toList(),
                    "runtime walk remains in fixed channel-RAM order");
            assertEquals(List.of(1, 0), sequencer.getSfxHeaderOrderTracks().stream()
                    .map(track -> track.channelId).toList());
            assertSame(sequencer.getTracks().get(1),
                    sequencer.getSfxHeaderOrderTracks().get(0));

            var snapshot = sequencer.captureSnapshot();
            sequencer.restoreSnapshot(snapshot);

            assertEquals(List.of(1, 0), sequencer.getSfxHeaderOrderTracks().stream()
                    .map(track -> track.channelId).toList());
            assertSame(sequencer.getTracks().get(1),
                    sequencer.getSfxHeaderOrderTracks().get(0));

            SmpsSequencer appendedSource = createSequencer(driver, 0xC0);
            SmpsSequencer.Track appended = appendedSource.getTracks().get(0);
            sequencer.addTrack(appended);
            var appendedSnapshot = sequencer.captureSnapshot();
            assertEquals(List.of(1, 0, 2), sequencer.getSfxHeaderOrderTracks().stream()
                    .map(track -> track.channelId).toList());
            assertSame(appended, sequencer.getSfxHeaderOrderTracks().get(2));

            sequencer.restoreSnapshot(snapshot);
            assertEquals(List.of(1, 0), sequencer.getSfxHeaderOrderTracks().stream()
                    .map(track -> track.channelId).toList());

            SmpsSequencer equivalent = createSequencer(driver, 0xA0, 0x80);
            equivalent.restoreSnapshot(appendedSnapshot);
            assertEquals(List.of(1, 0, 2), equivalent.getSfxHeaderOrderTracks().stream()
                    .map(track -> track.channelId).toList());
            assertSame(equivalent.getTracks().get(2),
                    equivalent.getSfxHeaderOrderTracks().get(2));
        } finally {
            SmpsDriverTestAccess.close(driver);
        }
    }

    private List<Integer> admitPsg(int channel, SmpsSequencerConfig config) {
        // One silent synthetic header pointing at F2. Its incoming first-header
        // IX still belongs to pre-existing slot RAM and remains unmodelled.
        byte[] bytes = {0, 0, 1, 1,
                (byte) 0x80, (byte) channel, 10, 0, 0, 0, (byte) 0xF2};
        List<Integer> writes = new ArrayList<>();
        SmpsDriver driver = SmpsDriverTestAccess.create(44_100, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
            }

            @Override
            public void onPsgWrite(int value) {
                writes.add(value);
            }
        });
        try {
            SmpsSequencer sfx = new SmpsSequencer(
                    new Sonic3kSfxData(bytes, 0, 0, 0), new DacData(Map.of(), Map.of()),
                    driver, () -> { }, config);
            driver.addSequencer(sfx, true);
            return writes;
        } finally {
            SmpsDriverTestAccess.close(driver);
        }
    }

    private List<Integer> admitHeaders(int... voiceControls) {
        List<Integer> writes = new ArrayList<>();
        SmpsDriver driver = SmpsDriverTestAccess.create(44_100, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
            }

            @Override
            public void onPsgWrite(int value) {
                writes.add(value);
            }
        });
        try {
            SmpsSequencer sequencer = createSequencer(driver, voiceControls);
            driver.addSequencer(sequencer, true);
            return writes;
        } finally {
            SmpsDriverTestAccess.close(driver);
        }
    }

    private SmpsSequencer createSequencer(SmpsDriver driver, int... voiceControls) {
        int streamStart = 4 + voiceControls.length * 6;
        byte[] bytes = new byte[streamStart + voiceControls.length];
        bytes[2] = 1;
        bytes[3] = (byte) voiceControls.length;
        for (int index = 0; index < voiceControls.length; index++) {
            int header = 4 + index * 6;
            bytes[header] = (byte) 0x80;
            bytes[header + 1] = (byte) voiceControls[index];
            int pointer = streamStart + index;
            bytes[header + 2] = (byte) pointer;
            bytes[header + 3] = (byte) (pointer >>> 8);
            bytes[pointer] = (byte) 0xF2;
        }
        return new SmpsSequencer(new Sonic3kSfxData(bytes, 0, 0, 0),
                new DacData(Map.of(), Map.of()), driver, () -> { },
                Sonic3kSmpsSequencerConfig.CONFIG);
    }
}
