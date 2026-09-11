package com.openggf.audio.driver;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.Ym2612Chip;
import com.openggf.data.Rom;
import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static com.openggf.tests.RomTestUtils.ensureRomAvailable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compares the engine's hybrid and sample-accurate rendering paths. This is
 * a batching-equivalence test, not a ROM oracle or a test of host fade-command
 * side effects. Fade volume
 * steps only mutate driver state inside {@code processTempoFrame()}, and hybrid
 * chunks never cross a tempo-frame boundary, so hybrid reads must stay
 * PCM-identical to per-sample rendering for the entire fade-out/fade-in window
 * (including fade completion, sequencer removal, and concurrent SFX).
 * <p>
 * Cost note: each invocation synthesizes ~6 renders of 6 s of audio (hybrid +
 * sample-accurate per scenario) through the full FM/PSG stack, so expect tens of
 * seconds of wall-clock time; ROM-gated (skips when the S2 ROM is absent).
 */
public class TestSmpsFadeHybridParity {

    private static final double SAMPLE_RATE = Ym2612Chip.getDefaultOutputRate();
    private static final int BUFFER_FRAMES = 1024;

    private static final int MUSIC_EHZ = 0x81;
    private static final int SFX_RING = 0xB5;

    // ROM zFadeOutMusic defaults: 0x28 steps, 3 frames between steps.
    private static final int FADE_OUT_STEPS = 0x28;
    private static final int FADE_OUT_DELAY = 3;
    private static final int FADE_IN_STEPS = 0x28;
    private static final int FADE_IN_DELAY = 2;

    private static final int PRE_ROLL_FRAMES = (int) (1.5 * SAMPLE_RATE);
    // Fade-out lasts ~steps*(delay+1) tempo frames (~2.7s); render 4.5s after the
    // trigger so completion + sequencer removal + silent tail are all covered.
    private static final int FADE_WINDOW_FRAMES = (int) (4.5 * SAMPLE_RATE);
    private static final int SFX_OFFSET_FRAMES = (int) (0.5 * SAMPLE_RATE);
    // Both fades last well over 1.5s (steps * (delay + 1) tempo frames at 60fps), so
    // chunk renders observed in this window happened while the fade was active.
    private static final int FADE_ACTIVE_PROBE_FRAMES = (int) (1.5 * SAMPLE_RATE);

    private static Rom rom;
    private static Sonic2SmpsLoader loader;
    private static DacData dacData;

    @BeforeAll
    public static void setUpClass() {
        TestEnvironment.resetAll();
        File romFile = ensureRomAvailable();
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
    public static void tearDownClass() {
        SessionManager.clear();
    }

    private record FadeRender(short[] pcm, int hybridChunksDuringFade) {
    }

    @Test
    public void fadeOutWindowHybridMatchesSampleAccurate() {
        assumeTrue(loader != null, "ROM not available, skipping fade parity test");

        FadeRender reference = renderFadeOutScenario(SmpsDriver.ReadMode.SAMPLE_ACCURATE, false, false);
        FadeRender hybrid = renderFadeOutScenario(SmpsDriver.ReadMode.HYBRID, false, false);

        assertTrue(hasNonZeroSample(reference.pcm()), "Fade-out scenario should produce audible PCM");
        assertArrayEquals(reference.pcm(), hybrid.pcm(),
                "Hybrid fade-out window must be sample-identical to the per-sample reference path");
        assertTrue(hybrid.hybridChunksDuringFade() > 0,
                "Hybrid mode should batch-render inside the fade window (fade must not force per-sample fallback)");
    }

    @Test
    public void fadeOutWithSfxHybridMatchesSampleAccurate() {
        assumeTrue(loader != null, "ROM not available, skipping fade parity test");

        FadeRender reference = renderFadeOutScenario(SmpsDriver.ReadMode.SAMPLE_ACCURATE, true, false);
        FadeRender hybrid = renderFadeOutScenario(SmpsDriver.ReadMode.HYBRID, true, false);

        assertArrayEquals(reference.pcm(), hybrid.pcm(),
                "Hybrid fade-out with concurrent SFX must be sample-identical to the per-sample reference path");
        assertTrue(hybrid.hybridChunksDuringFade() > 0,
                "Hybrid mode should batch-render inside the fade window even with concurrent SFX");
    }

    @Test
    public void fadeInWindowHybridMatchesSampleAccurate() {
        assumeTrue(loader != null, "ROM not available, skipping fade parity test");

        FadeRender reference = renderFadeOutScenario(SmpsDriver.ReadMode.SAMPLE_ACCURATE, false, true);
        FadeRender hybrid = renderFadeOutScenario(SmpsDriver.ReadMode.HYBRID, false, true);

        assertArrayEquals(reference.pcm(), hybrid.pcm(),
                "Hybrid fade-in window must be sample-identical to the per-sample reference path");
        assertTrue(hybrid.hybridChunksDuringFade() > 0,
                "Hybrid mode should batch-render inside the fade-in window");
    }

    /**
     * Renders EHZ music, triggers a fade (out or in) after a pre-roll, optionally
     * fires a ring SFX mid-fade, and returns the full PCM stream plus the number of
     * hybrid chunk renders observed inside the fade window.
     */
    private FadeRender renderFadeOutScenario(SmpsDriver.ReadMode mode, boolean withSfx, boolean fadeIn) {
        AbstractSmpsData musicData = loader.loadMusic(MUSIC_EHZ);
        assertNotNull(musicData, "Music data should load");

        SmpsDriver driver = SmpsDriverTestAccess.create(SAMPLE_RATE);
        driver.setRegion(SmpsSequencer.Region.NTSC);
        driver.setReadModeForTesting(mode);

        SmpsSequencer musicSeq = new SmpsSequencer(musicData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
        musicSeq.setSampleRate(SAMPLE_RATE);
        driver.addSequencer(musicSeq, false);

        int totalFrames = PRE_ROLL_FRAMES + FADE_WINDOW_FRAMES;
        short[] pcm = new short[totalFrames * 2];
        short[] buffer = new short[BUFFER_FRAMES * 2];

        int framesRendered = 0;
        boolean fadeTriggered = false;
        boolean sfxTriggered = false;
        int chunksDuringFade = 0;
        while (framesRendered < totalFrames) {
            if (!fadeTriggered && framesRendered >= PRE_ROLL_FRAMES) {
                if (fadeIn) {
                    musicSeq.triggerFadeIn(FADE_IN_STEPS, FADE_IN_DELAY);
                } else {
                    musicSeq.triggerFadeOut(FADE_OUT_STEPS, FADE_OUT_DELAY);
                }
                fadeTriggered = true;
            }
            if (withSfx && !sfxTriggered && framesRendered >= PRE_ROLL_FRAMES + SFX_OFFSET_FRAMES) {
                AbstractSmpsData sfxData = loader.loadSfx(SFX_RING);
                assertNotNull(sfxData, "SFX data should load");
                SmpsSequencer sfxSeq = new SmpsSequencer(sfxData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
                sfxSeq.setSampleRate(SAMPLE_RATE);
                sfxSeq.setSfxMode(true);
                driver.addSequencer(sfxSeq, true);
                sfxTriggered = true;
            }

            int framesToRead = Math.min(BUFFER_FRAMES, totalFrames - framesRendered);
            SmpsDriverTestAccess.read(
                    driver, buffer, framesToRead * 2);
            System.arraycopy(buffer, 0, pcm, framesRendered * 2, framesToRead * 2);
            if (fadeTriggered && framesRendered < PRE_ROLL_FRAMES + FADE_ACTIVE_PROBE_FRAMES) {
                chunksDuringFade += driver.getHybridChunkCountForTesting();
            }
            framesRendered += framesToRead;
        }
        return new FadeRender(pcm, chunksDuringFade);
    }

    private static boolean hasNonZeroSample(short[] pcm) {
        for (short s : pcm) {
            if (s != 0) {
                return true;
            }
        }
        return false;
    }
}
