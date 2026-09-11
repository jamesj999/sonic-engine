package com.openggf.game.sonic1.events;

import com.openggf.camera.Camera;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sonic 1 ending sequence: Sonic runs left across the ending's stripped-down
 * GHZ from X=$620 to the End_MoveSonic handoff at X=$A0
 * (docs/s1disasm/sonic.asm:3762-3805).
 *
 * <p>{@code End_MainLoop} (docs/s1disasm/sonic.asm:3662-3680) is not
 * {@code Level_MainLoop}: it never calls {@code SignpostArtLoad}
 * (docs/s1disasm/sonic.asm:3035, 3186-3208). Running that tail slot here locked
 * the left camera boundary to {@code v_limitright2-$100} = $400 on the first
 * frame — the ending's right boundary is only $500
 * (docs/s1disasm/_inc/LevelSizeArray.asm:50) while the camera starts pinned
 * there — and Sonic stalled against it at $410, softlocking the cutscene.
 */
@RequiresRom(SonicGame.SONIC_1)
class TestSonic1EndingSequenceHeadless {
    private static final int ZONE_ENDING_REGISTRY_INDEX = 7;
    private static final int ACT_GOOD_ENDING = 0;
    /** ROM: move.w #320/2,(v_player+obX).w — End_MoveSon3. */
    private static final int HANDOFF_X = 0xA0;
    private static final int MAX_FRAMES = 400;

    private static SharedLevel sharedLevel;
    private HeadlessTestFixture fixture;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(
                SonicGame.SONIC_1, ZONE_ENDING_REGISTRY_INDEX, ACT_GOOD_ENDING);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
    }

    @Test
    void leftCameraBoundaryStaysAtTheLevelSizeArrayValue() {
        Camera camera = fixture.camera();
        fixture.stepIdleFrames(1);
        assertEquals(0, camera.getMinX() & 0xFFFF,
                "SignpostArtLoad must not run in the ending sequence's own main loop");
    }

    @Test
    void sonicRunsLeftAndStopsAtTheEndMoveSonicHandoff() {
        AbstractPlayableSprite sonic = fixture.sprite();
        assertEquals(0x620, sonic.getCentreX() & 0xFFFF, "ending start location");

        // The whole run-left / skid / stop choreography, well inside the
        // ~285 frames it takes; a stall against a locked boundary leaves Sonic
        // far to the right of the handoff.
        fixture.stepIdleFrames(MAX_FRAMES);

        assertEquals(HANDOFF_X, sonic.getCentreX() & 0xFFFF,
                "Sonic never reached the End_MoveSonic handoff; stalled at X=0x"
                        + Integer.toHexString(sonic.getCentreX() & 0xFFFF));
        assertEquals(0, sonic.getGSpeed(), "End_MoveSon2 clears ground speed");
    }
}
