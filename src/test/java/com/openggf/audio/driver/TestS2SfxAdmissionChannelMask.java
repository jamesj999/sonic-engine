package com.openggf.audio.driver;

import com.openggf.audio.AudioManager;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.openggf.tests.RomTestUtils.ensureSonic2RomAvailable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestS2SfxAdmissionChannelMask {
    private static final int SFX_RING_LEFT = 0xCE;
    private static final int FM4 = 3;

    private static Rom rom;
    private static Sonic2SmpsLoader loader;
    private static DacData dacData;

    @BeforeAll
    static void setUpClass() {
        TestEnvironment.resetAll();
        File romFile = ensureSonic2RomAvailable();
        if (romFile == null) {
            return;
        }
        rom = new Rom();
        if (!rom.open(romFile.getAbsolutePath())) {
            rom = null;
            return;
        }
        loader = new Sonic2SmpsLoader(rom);
        dacData = loader.loadDacData();
    }

    @AfterAll
    static void tearDownClass() {
        SessionManager.clear();
    }

    @Test
    void acceptedRingLeftOwnsFm4BeforeMusicFirstService() {
        assumeTrue(loader != null, "Sonic 2 ROM is required");

        SmpsDriver driver = SmpsDriverTestAccess.create(60.0);
        SmpsSequencer music = modulatedFm4Music(driver);
        SmpsSequencer.Track musicFm4 = music.trackAt(0);
        driver.addSequencer(music, false);
        driver.serviceOuterFrame();
        assertTrue(musicFm4.modEnabled,
                "synthetic music must enter an active modulation sustain");

        AbstractSmpsData ringLeft = loader.loadSfx(SFX_RING_LEFT);
        assertNotNull(ringLeft);
        SmpsSequencer sfx = new SmpsSequencer(
                ringLeft, dacData, driver, AudioManager.getInstance(),
                Sonic2SmpsSequencerConfig.CONFIG);
        sfx.setSfxPriority(0x70);
        assertEquals(1, sfx.trackCount());
        assertEquals(SmpsSequencer.TrackType.FM, sfx.trackAt(0).type);
        assertEquals(FM4, sfx.trackAt(0).channelId,
                "ROM SFX CE is the left-speaker FM4 ring program");

        List<String> allWrites = new ArrayList<>();
        List<String> musicFm4FrequencyWrites = new ArrayList<>();
        List<String> sfxFm4FrequencyWrites = new ArrayList<>();
        boolean[] musicService = {false};
        boolean[] sfxService = {false};
        driver.setServiceObserver(new SmpsDriverServiceObserver() {
            @Override
            public void onServiceBegin(ServiceEvent event) {
                musicService[0] = event.kind() == ServiceKind.SEQUENCER_TICK
                        && !event.sequencer().sfx();
                sfxService[0] = event.kind() == ServiceKind.SEQUENCER_TICK
                        && event.sequencer().sfx();
            }

            @Override
            public void onServiceEnd(
                    ServiceEvent event,
                    com.openggf.audio.rewind.SmpsDriverSnapshot snapshot) {
                musicService[0] = false;
                sfxService[0] = false;
            }
        });
        SmpsDriverTestAccess.setChipWriteObserver(driver, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
                String write = port + ":" + Integer.toHexString(register)
                        + ":" + Integer.toHexString(value);
                allWrites.add(write);
                if (port == 1 && (register == 0xA4 || register == 0xA0)) {
                    if (musicService[0]) {
                        musicFm4FrequencyWrites.add(write);
                    }
                    if (sfxService[0]) {
                        sfxFm4FrequencyWrites.add(write);
                    }
                }
            }

            @Override
            public void onPsgWrite(int value) {
                allWrites.add("PSG:" + Integer.toHexString(value));
            }
        });

        PreparedSfxAdmission admission = driver.prepareNewSfxAdmission(
                sfx, 0, sfx.trackCount());
        assertEquals(1 << FM4, admission.affectedFmMask());
        sfx.beginSfxAdmission();
        driver.commitSfxAdmission(admission);

        assertTrue(musicFm4.overridden,
                "accepted SFX must install the ROM playback-control override immediately");
        assertTrue(allWrites.isEmpty(),
                "S2 Sound_PlaySFX admission changes RAM ownership but writes no chip registers");

        int accumulatorBefore = musicFm4.modAccumulator;
        driver.serviceOuterFrame();

        assertTrue(musicFm4.overridden);
        assertNotEquals(accumulatorBefore, musicFm4.modAccumulator,
                "overridden music continues advancing its hidden modulation state");
        assertTrue(musicFm4FrequencyWrites.isEmpty(),
                "music-first S2 service must suppress FM4 A4/A0 while SFX owns the channel");
        assertFalse(sfxFm4FrequencyWrites.isEmpty(),
                "the admitted ROM CE program still owns and writes FM4 in its later SFX pass");

        int remainingFrames = 64;
        while (musicFm4.overridden && remainingFrames-- > 0) {
            driver.serviceOuterFrame();
        }
        assertFalse(musicFm4.overridden,
                "CE completion must release FM4 back to music");

        assertTrue(musicFm4.resting,
                "cfStopTrack's FM SFX tail sets the released music track at rest "
                        + "(s2.sounddriver.asm:3548-3553: res 2, set 1)");

        musicFm4FrequencyWrites.clear();
        driver.serviceOuterFrame();
        assertTrue(musicFm4FrequencyWrites.isEmpty(),
                "the released track stays at rest, so zDoModulation returns before "
                        + "zFMUpdateFreq (s2.sounddriver.asm:989-991) and no A4/A0 pair "
                        + "is resent until the track's next note");
    }

    private static SmpsSequencer modulatedFm4Music(SmpsDriver driver) {
        SmpsSequencer sequencer = new SmpsSequencer(
                syntheticS2MusicData(), dacData, driver,
                AudioManager.getInstance(), Sonic2SmpsSequencerConfig.CONFIG);
        sequencer.setSampleRate(60.0);
        return sequencer;
    }

    private static Sonic2SmpsData syntheticS2MusicData() {
        byte[] data = new byte[0x80];
        setLe16(data, 0x00, 0x60);
        data[0x02] = 5; // DAC + FM1-FM4
        data[0x03] = 0;
        data[0x04] = 1;
        data[0x05] = (byte) 0xFF;
        for (int offset = 0x06; offset < 0x16; offset += 4) {
            setLe16(data, offset, 0x7F00);
        }
        setLe16(data, 0x16, 0x40); // FM4
        byte[] program = {
                (byte) 0xF0, 1, 1, 1, 8,
                (byte) 0x90, 0x7F,
                (byte) 0xF2
        };
        System.arraycopy(program, 0, data, 0x40, program.length);
        return new Sonic2SmpsData(data, 0);
    }

    private static void setLe16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }
}
