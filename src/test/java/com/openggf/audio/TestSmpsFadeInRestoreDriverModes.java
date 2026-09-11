package com.openggf.audio;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two shapes of the restore-to-previous fade, one per driver family.
 *
 * <p>The S1/S2 and S3K drivers both silence the resumed song while it fades
 * back in, but they do it with different {@code PlaybackControl} bits, and the
 * S3K disassembly's own inline comment on the instruction that does it is
 * mislabelled. Getting this wrong is inaudible in a unit test and very audible
 * in game, so both shapes are pinned here.
 *
 * <p>S1/S2 {@code cfFadeInToPrevious} sets bit 1, "track at rest" in those
 * drivers, on every playing FM and PSG track and note-offs the PSG ones
 * ({@code s2.sounddriver.asm:3107, :3131-3132};
 * {@code s1.sounddriver.asm:2193, :2211-2212}).
 *
 * <p>S3K {@code zFadeInToPrevious} ORs {@code 84h} over every track and then
 * clears bit 2 again on the FM ones
 * ({@code skdisasm Sound/Z80 Sound Driver.asm:2761-2770}). In that driver bit 2
 * is "SFX is overriding this track" and bit 4 is "track is resting"
 * ({@code Driver.asm:25, :27}; {@code zRestTrack} at {@code :4220-4223} sets
 * bit 4 and then tests bit 2), so {@code 84h} is bits 7 and 2 -- playing plus
 * overriding. The routine's comment calling it "playing and resting" names the
 * wrong bit. S3K therefore rests nothing: it leaves the PSG tracks marked
 * overridden, which is what mutes them, releases the FM tracks and attenuates
 * only those, by {@code 40h}.
 */
class TestSmpsFadeInRestoreDriverModes {

    private static final double SAMPLE_RATE = 44_100.0;
    /** S3K {@code FadeInSteps}, and the FM volume delta at :2769. */
    private static final int S3K_FADE_IN_STEPS = 0x40;

    @Test
    void sonic2SelectsTheRestingShape() {
        assertEquals(SmpsSequencerConfig.FadeInRestore.REST_TRACKS,
                Sonic2SmpsSequencerConfig.CONFIG.getFadeInRestore(),
                "S1/S2 cfFadeInToPrevious rests the tracks (s2.sounddriver.asm:3107)");
    }

    @Test
    void sonic3kSelectsTheOverridingShape() {
        assertEquals(SmpsSequencerConfig.FadeInRestore.OVERRIDE_PSG,
                Sonic3kSmpsSequencerConfig.CONFIG.getFadeInRestore(),
                "S3K zFadeInToPrevious silences by the overriding bit, not the"
                        + " resting one (Sound/Z80 Sound Driver.asm:2761-2770)");
    }

    /**
     * The S3K restore, driven through the real ROM-backed sequencer: the PSG
     * tracks come out overridden and unattenuated, the FM tracks come out
     * released and attenuated by {@code 40h}, and nothing is rested.
     */
    @Test
    void sonic3kRestoreOverridesThePsgTracksAndRestsNothing() {
        String romProperty = System.getProperty("s3k.rom.path");
        assumeTrue(romProperty != null, "an explicit S3K ROM path is required");

        Rom rom = new Rom();
        assumeTrue(rom.open(romProperty), "the S3K ROM must open");
        try (rom) {
            Sonic3kSmpsLoader loader = new Sonic3kSmpsLoader(rom);
            AbstractSmpsData song = Objects.requireNonNull(
                    loader.loadMusic(Sonic3kMusic.AIZ1.id),
                    "the S3K level music must load from the ROM");
            DacData dac = Objects.requireNonNull(loader.loadDacData(),
                    "S3K DAC data must load from the ROM");

            try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                    "s3k-fade-in-restore", 0,
                    new SmpsPhysicalDevice.Settings(SAMPLE_RATE, false),
                    LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE,
                    ChipWriteObserver.NONE)) {
                SmpsDriver driver = stream.logicalDriver();
                SmpsSequencer sequencer = new SmpsSequencer(song, dac, driver,
                        () -> { }, Sonic3kSmpsSequencerConfig.CONFIG);
                sequencer.setSampleRate(SAMPLE_RATE);
                driver.addSequencer(sequencer, false);
                for (int update = 0; update < 240; update++) {
                    driver.serviceOuterFrame();
                }

                SmpsSequencerSnapshot before = sequencer.captureSnapshot();
                sequencer.triggerFadeIn(S3K_FADE_IN_STEPS,
                        Sonic3kSmpsSequencerConfig.CONFIG.getFadeInDelay());
                SmpsSequencerSnapshot after = sequencer.captureSnapshot();

                int fmSeen = 0;
                int psgSeen = 0;
                for (SmpsTrackSnapshot track : after.tracks()) {
                    if (track.type() == SmpsSequencer.TrackType.DAC) {
                        continue;
                    }
                    SmpsTrackSnapshot original = match(before, track);
                    if (original == null) {
                        continue;
                    }
                    assertFalse(track.resting() && !original.resting(),
                            "S3K's restore rests no track; bit 4 is the resting"
                                    + " bit and 84h does not set it");
                    if (track.type() == SmpsSequencer.TrackType.FM) {
                        fmSeen++;
                        assertFalse(track.overridden(),
                                "res 2 clears the overriding bit on FM tracks"
                                        + " (Sound/Z80 Sound Driver.asm:2767)");
                        assertEquals(original.volumeOffset() + S3K_FADE_IN_STEPS,
                                track.volumeOffset(),
                                "FM tracks are attenuated by 40h (:2769)");
                    } else {
                        psgSeen++;
                        assertTrue(track.overridden(),
                                "PSG tracks keep the overriding bit set by 84h,"
                                        + " which is what mutes them (:2763)");
                        assertEquals(original.volumeOffset(), track.volumeOffset(),
                                "S3K leaves the PSG volumes alone");
                    }
                }
                assertNotEquals(0, fmSeen, "the song must declare FM tracks");
                assertNotEquals(0, psgSeen, "the song must declare PSG tracks");
            }
        }
    }

    private static SmpsTrackSnapshot match(SmpsSequencerSnapshot snapshot,
            SmpsTrackSnapshot track) {
        for (SmpsTrackSnapshot candidate : snapshot.tracks()) {
            if (candidate.type() == track.type()
                    && candidate.channelId() == track.channelId()) {
                return candidate;
            }
        }
        return null;
    }
}
