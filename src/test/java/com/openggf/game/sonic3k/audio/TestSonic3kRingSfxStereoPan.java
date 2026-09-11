package com.openggf.game.sonic3k.audio;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverTestAccess;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROM-backed stereo evidence for the S3K ring SFX pair.
 *
 * <p>{@code Sound_33_FM4} carries {@code smpsPan panRight} and {@code
 * Sound_34_FM5} carries {@code smpsPan panLeft} ({@code skdisasm/Sound/SFX/33 -
 * Ring (Right).asm}, {@code 34 - Ring (Left).asm}); the driver's {@code
 * cfPanningAMSFMS} writes that byte to YM2612 {@code $B4+} (bit 7 = L, bit 6 =
 * R; {@code Z80 Sound Driver.asm:3010-3025}). Each id on its own is therefore a
 * one-sided sound by design, and the ROM only reaches both speakers because
 * the driver alternates the two ids on every raw ring request
 * ({@code zPlaySound_CheckRing}, {@code Z80 Sound Driver.asm:1919-1925}). This
 * class pins both halves: the per-id side through the sequencer and the
 * Nuked-OPN2 facade, and the alternation when the special stage's raw
 * {@code sfx_RingRight} request ({@code sonic3k.asm:12189-12222}) is played
 * through the audio manager with the real S3K profile map.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kRingSfxStereoPan {

    private static final double RATE = 44_100.0;
    private static final int MAX_FRAMES = (int) RATE;
    private static final int PAN_MASK = 0xC0;
    private static final int PAN_LEFT = 0x80;
    private static final int PAN_RIGHT = 0x40;
    /** Loud side must carry at least this many times the quiet side's AC energy. */
    private static final double DOMINANCE_RATIO = 100.0;

    private Sonic3kSmpsLoader loader;
    private DacData dac;

    @BeforeEach
    void setUp() {
        Rom rom = TestEnvironment.currentRom();
        loader = new Sonic3kSmpsLoader(rom);
        dac = loader.loadDacData();
    }

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
    }

    @Test
    void ringRightIdRendersOnTheRightChannelOnly() {
        Render render = render(Sonic3kSfx.RING_RIGHT.id);

        assertEquals(PAN_RIGHT, render.panBits, "sfx_RingRight must key FM4 to R only");
        assertTrue(render.rightEnergy > DOMINANCE_RATIO * render.leftEnergy,
                () -> "right channel must dominate: " + render);
        assertTrue(render.rightEnergy > 0, () -> "the SFX must be audible: " + render);
    }

    @Test
    void ringLeftIdRendersOnTheLeftChannelOnly() {
        Render render = render(Sonic3kSfx.RING_LEFT.id);

        assertEquals(PAN_LEFT, render.panBits, "sfx_RingLeft must key FM5 to L only");
        assertTrue(render.leftEnergy > DOMINANCE_RATIO * render.rightEnergy,
                () -> "left channel must dominate: " + render);
        assertTrue(render.leftEnergy > 0, () -> "the SFX must be audible: " + render);
    }

    @Test
    void specialStageRawRingRequestAlternatesBothSpeakers() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new AudioTestFixtures.RecordingAudioBackend());
        audio.setAudioProfile(new Sonic3kAudioProfile());
        audio.setSoundMap(new Sonic3kAudioProfile().getSoundMap());
        audio.setRom(TestEnvironment.currentRom());

        // Two consecutive special-stage ring collects: loc_984C always sends
        // sfx_RingRight, and the driver's toggle turns that into $34 then $33.
        audio.playSfx(Sonic3kSfx.RING_RIGHT.id);
        audio.playSfx(Sonic3kSfx.RING_RIGHT.id);

        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < audio.commandTimeline().entries().size(); i++) {
            var command = audio.commandTimeline().entryAt(i).command();
            if (command instanceof com.openggf.audio.rewind.AudioCommand.PlaySfx play) {
                ids.add(play.sfxId());
            }
        }
        assertEquals(List.of(Sonic3kSfx.RING_LEFT.id, Sonic3kSfx.RING_RIGHT.id), ids,
                "consecutive raw ring requests must alternate left then right");
    }

    private record Render(int panBits, double leftEnergy, double rightEnergy) {
    }

    private Render render(int sfxId) {
        AbstractSmpsData data = loader.loadSfx(sfxId);
        assertNotNull(data, () -> "SFX " + Integer.toHexString(sfxId) + " must load from the ROM");
        SmpsDriver driver = SmpsDriverTestAccess.create(RATE);
        driver.setRegion(SmpsSequencer.Region.NTSC);
        int[] panBits = {-1};
        SmpsDriverTestAccess.setChipWriteObserver(driver, new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
                if (register >= 0xB4 && register <= 0xB6 && (value & PAN_MASK) != 0) {
                    panBits[0] = value & PAN_MASK;
                }
            }

            @Override
            public void onPsgWrite(int value) {
            }
        });
        SmpsSequencer seq = new SmpsSequencer(data, dac, driver, () -> { }, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.setSampleRate(RATE);
        seq.setSfxMode(true);
        driver.addSequencer(seq, true);

        short[] frame = new short[2];
        double[] left = new double[MAX_FRAMES];
        double[] right = new double[MAX_FRAMES];
        int frames = 0;
        while (frames < MAX_FRAMES && !driver.isComplete()) {
            SmpsDriverTestAccess.read(driver, frame, 2);
            left[frames] = frame[0];
            right[frames] = frame[1];
            frames++;
        }
        assertTrue(frames > 0, "the SFX must render at least one frame");
        // The Nuked-OPN2 ladder leaves a DC bias on a keyed-off side, so
        // compare AC energy rather than raw sample energy.
        return new Render(panBits[0], acEnergy(left, frames), acEnergy(right, frames));
    }

    private static double acEnergy(double[] samples, int frames) {
        double mean = 0;
        for (int i = 0; i < frames; i++) {
            mean += samples[i];
        }
        mean /= frames;
        double energy = 0;
        for (int i = 0; i < frames; i++) {
            double v = samples[i] - mean;
            energy += v * v;
        }
        return energy / frames;
    }
}
