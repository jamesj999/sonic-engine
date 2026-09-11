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
 * The noise tails of {@code sfx_Collapse} ($59) and {@code sfx_Dash} ($B6),
 * measured on the wire against their own ROM scripts.
 *
 * <p>Both effects put a long PSG3 noise track alongside a short FM one, and in
 * both the noise track is the part a player hears. Collapse's FM tracks run
 * {@code nC0, $10} and stop, while its PSG3 track loops six times over
 * {@code nB3, $18, smpsNoAttack} raising the attenuation by {@code $03} each
 * pass (Sound/SFX/59 - Collapse.asm:24-36). Dash's FM5 runs {@code nE6, $0F}
 * and stops while its PSG3 track rests {@code $06} and then sounds for
 * {@code $4F} (Sound/SFX/B6 - Dash.asm:14-23).
 *
 * <p>Two defects removed those tails, and this fixture covers both.
 *
 * <p>The first was ownership. A PSG3 noise track owns the noise channel as
 * well as PSG3, because the ROM keeps noise on the PSG3 track slot itself:
 * {@code zStopPSGTrack} decides whether to re-send the stored noise byte by
 * testing {@code PlaybackControl} bit 0 on that track
 * (Sound/Z80 Sound Driver.asm:3520-3527). The driver matched a channel against
 * {@code channelId} alone, so it saw no track on the noise channel, released
 * the lock on the effect's first reconcile, and force-silenced it. Collapse's
 * whole 121-pass tail was silent from its second write onward.
 *
 * <p>The second was the volume tail. {@code zUpdatePSGTrack}'s
 * {@code .note_going} path sends the frequency pair and then the volume on
 * every pass of a sounding note, gated only on {@code PlaybackControl} bit 2
 * and bit 4, with no attack test; a zero {@code VoiceIndex} takes
 * {@code .no_volenv} with {@code c = 0} and still writes
 * (Sound/Z80 Sound Driver.asm:4079-4135). The engine only wrote the
 * attenuation from the note and envelope paths, so Collapse's ramp, carried by
 * {@code smpsPSGAlterVol} across no-attack notes on an envelope-less track,
 * never reached the chip and the effect ended flat.
 *
 * <p>The expected ladder is read out of the effect's own script rather than
 * named here, so this asserts the driver against the ROM data and not against
 * a transcription of it.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kSfxNoiseTailWriteStream {
    private static final double SAMPLE_RATE = 44_100.0;

    /** {@code cfChangePSGVolume} (Z80 Sound Driver.asm:2960, table entry $ECh). */
    private static final int FLAG_ALTER_PSG_VOLUME = 0xEC;

    /** {@code cfRepeatAtPos} (:2967, table entry $F7h). */
    private static final int FLAG_LOOP = 0xF7;

    /** Latch byte for a noise-channel volume write: channel 3, volume, 4-bit level. */
    private static final int NOISE_VOLUME_LATCH = 0xF0;

    /** Generous bound; both effects end well inside it. */
    private static final int SERVICES = 240;

    @Test
    void collapseRingsOutThroughItsRomVolumeLadder() {
        Rom rom = openRom();
        try (rom) {
            Sonic3kSmpsLoader loader = new Sonic3kSmpsLoader(rom);
            Sonic3kSfxData sfx = sfxData(loader, 0x59);
            List<Integer> expected = romVolumeLadder(sfx);
            assertEquals(6, expected.size(),
                    "sfx_Collapse's PSG3 script loops six times"
                    + " (Sound/SFX/59 - Collapse.asm:31-36); read " + expected);

            List<Integer> observed = distinctNoiseVolumes(loader, 0x59);
            assertEquals(expected, observed,
                    "sfx_Collapse must ring out through its own attenuation ramp."
                    + " zUpdatePSGTrack's .note_going tail sends the volume on"
                    + " every pass of a sounding note, including the no-attack"
                    + " passes this effect loops over"
                    + " (Sound/Z80 Sound Driver.asm:4079-4135)");
        }
    }

    @Test
    void collapseFirstNoteWritesItsNoiseVolumeOnce() {
        Rom rom = openRom();
        try (rom) {
            Sonic3kSmpsLoader loader = new Sonic3kSmpsLoader(rom);
            List<int[]> writes = noiseVolumeWrites(loader, 0x59);
            int firstSoundingService = writes.stream()
                    .filter(write -> (write[1] & 0x0F) != 0x0F)
                    .mapToInt(write -> write[0])
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "sfx_Collapse never wrote a sounding noise volume"));
            long writesInService = writes.stream()
                    .filter(write -> write[0] == firstSoundingService)
                    .filter(write -> (write[1] & 0x0F) == 0)
                    .count();

            assertEquals(1, writesInService,
                    "zUpdatePSGTrack has one volume tail after its frequency"
                    + " pair on the new-note path; applying modulation must not"
                    + " add a second attacked-note volume write"
                    + " (Sound/Z80 Sound Driver.asm:4059-4135)");
        }
    }

    @Test
    void dashKeepsItsNoiseTailToTheRomsLength() {
        Rom rom = openRom();
        try (rom) {
            Sonic3kSmpsLoader loader = new Sonic3kSmpsLoader(rom);
            List<Integer> observed = distinctNoiseVolumes(loader, 0xB6);
            assertTrue(observed.size() >= 2 && observed.get(0) < 0x0F, () ->
                    "sfx_Dash's PSG3 noise track must sound before it is silenced;"
                    + " its FM5 stops at $0F ticks while the noise runs $4F"
                    + " (Sound/SFX/B6 - Dash.asm:14-23); levels were " + observed);
            assertEquals(0x0F, observed.get(observed.size() - 1).intValue(),
                    "sfx_Dash ends on the noise channel's silence");

            int silences = 0;
            for (int level : observed) {
                if (level == 0x0F) {
                    silences++;
                }
            }
            assertEquals(1, silences, () ->
                    "sfx_Dash's noise tail must reach the noise channel's silence"
                    + " once, at the end. Releasing the noise channel while the"
                    + " PSG3 track still owns it re-silenced the tail on every"
                    + " pass, chopping the effect"
                    + " (Sound/Z80 Sound Driver.asm:3520-3527); levels were "
                    + observed);

            int last = lastNoiseWriteService(loader, 0xB6);
            assertTrue(last > 60, () ->
                    "sfx_Dash's noise tail is cut short: its last noise write"
                    + " landed at service " + last + ", but the ROM script sounds"
                    + " for $06 + $4F ticks after the FM track has stopped"
                    + " (Sound/SFX/B6 - Dash.asm:19-23). Releasing the noise"
                    + " channel while the PSG3 track still owns it silences the"
                    + " tail (Sound/Z80 Sound Driver.asm:3520-3527)");
        }
    }

    /**
     * The attenuation levels the effect's own PSG script asks for: its header
     * volume, then one entry per {@code smpsPSGAlterVol} the loop applies,
     * clipped to the PSG's four bits the way {@code cfChangePSGVolume} does.
     */
    private List<Integer> romVolumeLadder(Sonic3kSfxData sfx) {
        Sonic3kSfxData.TrackEntry psg = psgTrack(sfx);
        int delta = -1;
        int repeats = -1;
        for (int at = psg.pointer; at + 1 < sfx.dataLength(); at++) {
            int flag = sfx.dataByteAt(at) & 0xFF;
            if (flag == FLAG_ALTER_PSG_VOLUME && delta < 0) {
                delta = sfx.dataByteAt(at + 1) & 0xFF;
            } else if (flag == FLAG_LOOP && repeats < 0 && at + 2 < sfx.dataLength()) {
                repeats = sfx.dataByteAt(at + 2) & 0xFF;
            }
        }
        if (delta < 0 || repeats < 0) {
            fail("the effect's PSG script carries no volume ramp to derive");
        }
        List<Integer> ladder = new ArrayList<>();
        int level = psg.volume & 0xFF;
        for (int step = 0; step <= repeats; step++) {
            ladder.add(Math.min(0x0F, level));
            level += delta;
        }
        return ladder;
    }

    private Sonic3kSfxData.TrackEntry psgTrack(Sonic3kSfxData sfx) {
        for (Sonic3kSfxData.TrackEntry track : sfx.getTrackEntries()) {
            if ((track.channelMask & 0x80) != 0) {
                return track;
            }
        }
        return fail("the effect declares no PSG track");
    }

    /**
     * The noise-channel attenuation levels the driver emits, run-length
     * reduced, with the setup silence dropped.
     *
     * <p>Every noise-form effect opens with a silent noise channel:
     * {@code cfSetPSGNoise} silences PSG3 and the SFX setup silences the noise
     * channel before the track's first pass
     * (Sound/Z80 Sound Driver.asm:3541-3571). That leading {@code $0F} is the
     * ROM's, not a defect, so the ladder proper begins after it.
     */
    private List<Integer> distinctNoiseVolumes(Sonic3kSmpsLoader loader, int sfxId) {
        List<Integer> levels = new ArrayList<>();
        for (int[] write : noiseVolumeWrites(loader, sfxId)) {
            int level = write[1] & 0x0F;
            if (levels.isEmpty() || levels.get(levels.size() - 1) != level) {
                levels.add(level);
            }
        }
        if (!levels.isEmpty() && levels.get(0) == 0x0F) {
            levels.remove(0);
        }
        return levels;
    }

    private int lastNoiseWriteService(Sonic3kSmpsLoader loader, int sfxId) {
        List<int[]> writes = noiseVolumeWrites(loader, sfxId);
        return writes.isEmpty() ? -1 : writes.get(writes.size() - 1)[0];
    }

    /** Every noise-channel volume write, as {@code {service, byte}} pairs. */
    private List<int[]> noiseVolumeWrites(Sonic3kSmpsLoader loader, int sfxId) {
        List<int[]> writes = new ArrayList<>();
        DacData dacData = loader.loadDacData();
        int[] service = { 0 };
        try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                "s3k-noise-tail", 0,
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
                    int byteValue = value & 0xFF;
                    if ((byteValue & 0xF0) == NOISE_VOLUME_LATCH) {
                        writes.add(new int[] { service[0], byteValue });
                    }
                }
            });
            AbstractSmpsData sfx = loader.loadSfx(sfxId);
            SmpsSequencer sequencer = new SmpsSequencer(sfx, dacData, driver, () -> { },
                    Sonic3kSmpsSequencerConfig.CONFIG);
            sequencer.setSampleRate(SAMPLE_RATE);
            sequencer.setSfxMode(true);
            driver.addSequencer(sequencer, true);
            for (int pass = 0; pass < SERVICES; pass++) {
                service[0] = pass;
                driver.serviceOuterFrame();
            }
        }
        return writes;
    }

    private Sonic3kSfxData sfxData(Sonic3kSmpsLoader loader, int sfxId) {
        AbstractSmpsData sfx = loader.loadSfx(sfxId);
        if (!(sfx instanceof Sonic3kSfxData data)) {
            return fail(String.format("SFX 0x%02X did not load as S3K SFX data", sfxId));
        }
        return data;
    }

    private Rom openRom() {
        Rom rom = new Rom();
        if (!rom.open(RomTestUtils.ensureSonic3kRomAvailable().getAbsolutePath())) {
            fail("cannot open the verified S3K ROM");
        }
        return rom;
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
}
