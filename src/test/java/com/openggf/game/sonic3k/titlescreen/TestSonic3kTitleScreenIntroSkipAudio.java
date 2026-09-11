package com.openggf.game.sonic3k.titlescreen;

import com.openggf.audio.AudioRequestObserver;
import com.openggf.control.InputHandler;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * Execution coverage for the reported "music goes dead between the SEGA/Sonic intro
 * and the title screen" issue in Sonic 3 &amp; Knuckles.
 *
 * <p>Records the ordered numeric audio requests leaving
 * {@link Sonic3kTitleScreenManager} through {@link AudioRequestObserver}, the
 * disabled-by-default observation seam at the public request boundary, while the
 * intro is skipped at two different points.
 */
@RequiresRom(SonicGame.SONIC_3K)
@ExtendWith(SingletonResetExtension.class)
public class TestSonic3kTitleScreenIntroSkipAudio {

    private static final int MAX_FRAMES = 2000;

    private final List<String> requests = new ArrayList<>();

    private Sonic3kTitleScreenManager manager;
    private InputHandler input;
    private int jumpKey;

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(RomTestUtils.ensureSonic3kRomAvailable() != null,
                "S3K ROM required");
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.graphics().initHeadless();
        GameServices.audio().setRequestObserver(this::record);

        manager = new Sonic3kTitleScreenManager();
        manager.initialize();

        input = new InputHandler();
        // Keep the gamepad path out of confirmPressed(); logical() then reports neutral.
        input.setLogicalOverride(com.openggf.control.LogicalInputSnapshot.neutral());
        jumpKey = GameServices.configuration().getInt(SonicConfiguration.JUMP);
    }

    @AfterEach
    public void tearDown() {
        GameServices.audio().setRequestObserver(AudioRequestObserver.NONE);
        SessionManager.clear();
    }

    private void record(AudioRequestObserver.RequestClass requestClass, int rawSoundId) {
        requests.add(String.format(Locale.ROOT, "%s:0x%02X%s", requestClass, rawSoundId,
                label(rawSoundId)));
    }

    private static String label(int id) {
        if (id == Sonic3kSmpsConstants.CMD_SEGA) {
            return "(CMD_SEGA)";
        }
        if (id == Sonic3kSmpsConstants.CMD_STOP_SEGA) {
            return "(CMD_STOP_SEGA/stop-all)";
        }
        if (id == Sonic3kMusic.TITLE.id) {
            return "(TITLE music)";
        }
        return "";
    }

    private boolean sawTitleMusic() {
        return requests.stream().anyMatch(r -> r.contains("(TITLE music)"));
    }

    private boolean sawStopSega() {
        return requests.stream().anyMatch(r -> r.contains("(CMD_STOP_SEGA"));
    }

    /** Runs frames until {@code pressWhen} is true, then presses jump for one frame. */
    private void runUntilAndPress(java.util.function.BooleanSupplier pressWhen, String scenario) {
        boolean pressed = false;
        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            boolean pressThisFrame = !pressed && pressWhen.getAsBoolean();
            if (pressThisFrame) {
                input.handleKeyEvent(jumpKey, GLFW_PRESS);
                pressed = true;
            }
            manager.update(input);
            input.update();
            if (pressThisFrame) {
                input.handleKeyEvent(jumpKey, GLFW_RELEASE);
                // Let the post-skip phases run a while so any later music request lands.
                for (int tail = 0; tail < 240; tail++) {
                    manager.update(input);
                    input.update();
                }
                break;
            }
        }
        assertTrue(pressed, scenario + ": never reached the press condition");
        System.out.println("=== " + scenario + " audio request order ===");
        for (int i = 0; i < requests.size(); i++) {
            System.out.println("  [" + i + "] " + requests.get(i));
        }
        System.out.println("=== end " + scenario + " ===");
    }

    @Test
    public void skipDuringSonicAnimationLeavesTheTitleMusicPlaying() {
        // The TITLE music request is emitted exactly when PAL_TRANSITION completes
        // and SONIC_ANIMATION begins, so it is the observable marker for that phase.
        runUntilAndPress(this::sawTitleMusic, "skip during SONIC_ANIMATION");
        assertRomIntroAudioContract("skip during SONIC_ANIMATION");
    }

    @Test
    public void skipDuringPalTransitionLeavesTheTitleMusicPlaying() {
        // The first CMD_STOP_SEGA is emitted on entry to PAL_TRANSITION.
        runUntilAndPress(this::sawStopSega, "skip during PAL_TRANSITION");
        assertRomIntroAudioContract("skip during PAL_TRANSITION");
    }

    /**
     * The ROM's intro emits exactly three audio requests, in one order, however
     * the intro is left.
     *
     * <p>{@code cmd_SEGA} follows {@code Pal_FadeFromBlack}
     * (sonic3k.asm:5485). {@code Wait_SegaS3K} is left either by its timeout or
     * by a Start press, and both exits run the single {@code cmd_StopSEGA} at
     * {@code loc_3FE4} (:5493-5500). {@code mus_TitleScreen} then starts just
     * before {@code Wait_TitleS3K} (:5529-5530). A Start press inside
     * {@code Wait_TitleS3K} branches to {@code loc_4090} and issues no sound
     * command at all (:5541-5546), so nothing may follow the music.
     *
     * <p>Gating the skip's stop on "the chant once played" rather than "the
     * chant is still playing" put a second stop-all after the music and left
     * the title screen silent; that is what this pins.
     */
    private void assertRomIntroAudioContract(String scenario) {
        long stops = requests.stream().filter(r -> r.contains("(CMD_STOP_SEGA")).count();
        assertEquals(1, stops, scenario
                + ": the ROM runs cmd_StopSEGA once, at loc_3FE4 (sonic3k.asm:5498-5500);"
                + " requests=" + requests);

        long titleRequests = requests.stream().filter(r -> r.contains("(TITLE music)")).count();
        assertEquals(1, titleRequests, scenario
                + ": the ROM starts mus_TitleScreen once (sonic3k.asm:5529-5530);"
                + " requests=" + requests);

        int titleIndex = -1;
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).contains("(TITLE music)")) {
                titleIndex = i;
            }
        }
        assertTrue(titleIndex >= 0, scenario + ": expected a TITLE music request");
        assertEquals(requests.size() - 1, titleIndex, scenario
                + ": nothing may follow the title music, because Wait_TitleS3K's Start"
                + " branch to loc_4090 issues no sound command (sonic3k.asm:5541-5546);"
                + " requests=" + requests);
    }
}
