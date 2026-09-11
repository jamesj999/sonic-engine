package com.openggf.game.sonic3k.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsChipWrite;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.session.SmpsWriteProgram;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSfxData;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The S3K sound driver's {@code cfSetPSGNoise} contract, measured on the wire
 * for the three noise-form effects behind reported AIZ1 audio faults.
 *
 * <p>All 36 S3K effects that carry a PSG form declare {@code $E7}, white noise
 * clocked from PSG3's tone period, and these three are among them: the water
 * splash ({@code sfx_Splash}, {@code $39}), the insta-shield attack
 * ({@code sfx_InstaAttack}, {@code $42}) and the collapse
 * ({@code sfx_Collapse}, {@code $59}).
 *
 * <p>The driver is built with {@code fix_sndbugs = 0}
 * (Sound/Z80 Sound Driver.asm:16), so {@code cfSetPSGNoise} takes the
 * {@code else} branch at :3558-3571: it silences PSG3 by writing {@code 0DFh}
 * <em>first</em>, then reads the operand, stores it to {@code zTrack.PSGNoise},
 * marks the track as noise, and writes the operand itself. The bug-fixed branch
 * would test the operand and the SFX-override bit before writing anything, and
 * would return early on a non-PSG track; the engine models the shipped path.
 *
 * <p>The expected noise byte is read out of the effect's own script in the
 * ROM rather than named here, so this asserts the driver against the ROM data
 * and not against a transcription of it. Nothing here depends on the PSG
 * synth's noise clock or on any audio configuration key: the contract is the
 * ordered byte stream the driver puts on the bus.
 *
 * <p>The pair must be adjacent: retail {@code cfSetPSGNoise} has no call
 * between its writes (:3562-3572). The loader's separate noise silence
 * belongs to admission, before this track's first pass, and cannot be
 * injected again when the first noise latch takes hardware ownership.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kNoiseFormEffectWriteStream {
    private static final double SAMPLE_RATE = 44_100.0;

    /** ROM coordination flag {@code cfSetPSGNoise} (Z80 Sound Driver.asm:2968). */
    private static final int FLAG_SET_PSG_NOISE = 0xF3;

    /** {@code cfSetPSGNoise}'s unconditional "silence PSG3" command (:3560). */
    private static final int SILENCE_PSG3 = 0xDF;

    /** Services allowed before the effect's first track pass must have run. */
    private static final int OPENING_SERVICES = 4;

    @Test
    void splashOpensWithTheRomsSilenceThenNoiseFormPair() {
        assertNoiseFormPair(0x39);
    }

    @Test
    void instaShieldOpensWithTheRomsSilenceThenNoiseFormPair() {
        assertNoiseFormPair(0x42);
    }

    @Test
    void collapseOpensWithTheRomsSilenceThenNoiseFormPair() {
        assertNoiseFormPair(0x59);
    }

    private void assertNoiseFormPair(int sfxId) {
        Rom rom = new Rom();
        if (!rom.open(RomTestUtils.ensureSonic3kRomAvailable().getAbsolutePath())) {
            fail("cannot open the verified S3K ROM");
        }
        try (rom) {
            Sonic3kSmpsLoader loader = new Sonic3kSmpsLoader(rom);
            AbstractSmpsData sfx = loader.loadSfx(sfxId);
            assertTrue(sfx instanceof Sonic3kSfxData,
                    () -> String.format("SFX 0x%02X did not load as S3K SFX data", sfxId));
            int expectedForm = romNoiseForm((Sonic3kSfxData) sfx, sfxId);

            List<Integer> writes = firstServicePsgWrites(rom, loader, sfxId);
            int silenceAt = writes.indexOf(SILENCE_PSG3);
            assertTrue(silenceAt >= 0, () -> String.format(
                    "SFX 0x%02X: cfSetPSGNoise must put its 0DFh PSG3 silence on the bus"
                    + " (Sound/Z80 Sound Driver.asm:3560); stream was %s",
                    sfxId, hex(writes)));
            int formAt = writes.indexOf(expectedForm);
            assertTrue(formAt >= 0, () -> String.format(
                    "SFX 0x%02X: cfSetPSGNoise must write the ROM's own noise operand"
                    + " %02X (:3567); stream was %s",
                    sfxId, expectedForm, hex(writes)));
            assertEquals(silenceAt + 1, formAt, () -> String.format(
                    "SFX 0x%02X: the 0DFh silence immediately precedes the noise operand in"
                    + " cfSetPSGNoise (:3562-3572); stream was %s",
                    sfxId, hex(writes)));
        }
    }

    /**
     * Reads the effect's noise operand from its own PSG track script in the
     * ROM. The ROM documents the operand as lying in {@code 0E0h-0E7h}
     * (Sound/Z80 Sound Driver.asm:3533-3537), which disambiguates the flag
     * byte from any note or duration that happens to equal {@code 0F3h}.
     */
    private int romNoiseForm(Sonic3kSfxData sfx, int sfxId) {
        for (Sonic3kSfxData.TrackEntry track : sfx.getTrackEntries()) {
            if ((track.channelMask & 0x80) == 0) {
                continue;
            }
            for (int at = track.pointer; at + 1 < sfx.dataLength(); at++) {
                int flag = sfx.dataByteAt(at) & 0xFF;
                int operand = sfx.dataByteAt(at + 1) & 0xFF;
                if (flag == FLAG_SET_PSG_NOISE && operand >= 0xE0 && operand <= 0xE7) {
                    return operand;
                }
            }
        }
        return fail(String.format(
                "SFX 0x%02X declares no PSG track carrying a cfSetPSGNoise operand;"
                + " this fixture only covers the noise-form effects", sfxId));
    }

    /**
     * The ordered PSG bytes the driver emits over the effect's opening
     * services, excluding its admission noise silence. The track's first
     * pass, and so {@code cfSetPSGNoise}, runs after admission. A small bound
     * is taken rather than a fixed index so the
     * contract is "the effect opens with this pair", not "frame N does".
     */
    private List<Integer> firstServicePsgWrites(Rom rom, Sonic3kSmpsLoader loader, int sfxId) {
        List<Integer> writes = new ArrayList<>();
        DacData dacData = loader.loadDacData();
        try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                "s3k-noise-form", 0,
                new SmpsPhysicalDevice.Settings(SAMPLE_RATE, false),
                Sonic3kSmpsPhysicalPolicy.INSTANCE, ChipWriteObserver.NONE)) {
            SmpsDriver driver = stream.logicalDriver();
            driver.setRegion(SmpsSequencer.Region.NTSC);
            applyProgram(driver, Sonic3kSmpsPhysicalPolicy.INSTANCE.boot());
            applyProgram(driver, Sonic3kSmpsPhysicalPolicy.INSTANCE.enterDacIdleLoop());
            stream.setChipWriteObserver(new ChipWriteObserver() {
                @Override
                public void onYm2612Write(int port, int register, int value) {
                }

                @Override
                public void onPsgWrite(int value) {
                    writes.add(value & 0xFF);
                }
            });
            AbstractSmpsData sfx = loader.loadSfx(sfxId);
            SmpsSequencer sequencer = new SmpsSequencer(sfx, dacData, driver, () -> { },
                    Sonic3kSmpsSequencerConfig.CONFIG);
            sequencer.setSampleRate(SAMPLE_RATE);
            sequencer.setSfxMode(true);
            driver.addSequencer(sequencer, true);
            // Admission now writes FF. Measure the first track pass rather
            // than treating that setup write as proof the track has run.
            writes.clear();
            for (int service = 0; service < OPENING_SERVICES && writes.isEmpty(); service++) {
                driver.serviceOuterFrame();
            }
        }
        return writes;
    }

    private static void applyProgram(SmpsDriver driver, SmpsWriteProgram program) {
        for (SmpsChipWrite write : program.writes()) {
            if (write instanceof SmpsChipWrite.Ym2612 ym) {
                driver.writeFm(driver, ym.port(), ym.register(), ym.value());
            } else if (write instanceof SmpsChipWrite.Psg psg) {
                driver.writePsg(driver, psg.value());
            }
        }
    }

    private static String hex(List<Integer> writes) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < writes.size(); i++) {
            text.append(i == 0 ? "" : " ").append(String.format("%02X", writes.get(i)));
        }
        return text.append(']').toString();
    }
}
