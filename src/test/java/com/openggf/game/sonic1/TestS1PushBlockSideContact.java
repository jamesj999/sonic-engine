package com.openggf.game.sonic1;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.credits.DemoInputPlayer;
import com.openggf.game.sonic1.credits.Sonic1CreditsDemoData;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceReplayBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the MZ2 credits demo trace divergence at frame 341
 * (TestS1Credits01Mz2TraceReplay): the engine trailed the recording by one
 * pixel across the lava slide. Expected X is read from the current fixture
 * row, not hardcoded — the pre-v5 literals quoted in this file's history
 * (0x0E1A / 0x0E19) belong to a recording that has since been re-timed.
 *
 * <p>ROM behaviour: when an MZ Push Block (object 0x33) is in solidState 4
 * (falling) or solidState 6 (sliding-to-align), ROM's loc_C1AA / loc_C1F2
 * paths return WITHOUT ever calling Solid_ChkEnter
 * (docs/s1disasm/_incObj/33 Pushable Blocks.asm:255-289). On the very next
 * frame after lava landing the block enters with obSolid==0 + inMotion=true,
 * and ROM's loc_C218 path then calls Solid_ChkEnter which sets obSolid=2.
 * Only the frame AFTER THAT does loc_C186's state-2 path
 * (ExitPlatform → MvSonicOnPtfm) finally fire the platform-rider carry.
 *
 * <p>Bug reproduced before fix: the engine called {@code checkpointAll()}
 * unconditionally inside {@code Sonic1PushBlockObjectInstance.updateActive()}.
 * That meant on the same frame as the lava landing (state 4 → 0 transition),
 * the engine's solid contact resolution would have established a riding state
 * via the STANDING contact result. Then on the immediately next frame,
 * {@code processInlineRidingObject} fired {@code shiftX(deltaX)} —
 * one frame TOO EARLY compared to ROM's MvSonicOnPtfm cadence — producing a
 * permanent -1 X drift in the player position throughout the lava-slide
 * sequence.
 *
 * <p>Fix: skip {@code checkpointAll()} when the block enters the frame in
 * solidState 4 or 6, mirroring ROM's loc_C1AA / loc_C1F2 paths.
 *
 * <p>Test approach: replay the MZ2 credits demo input up through frame 341
 * (the first divergence frame) and assert the player's X matches the ROM
 * trace recording. Frames before 341 are also checked to catch any unrelated
 * regressions.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1PushBlockSideContact {

    private static final int CREDITS_DEMO_IDX = 1; // MZ Act 2

    /**
     * Trace row index for the lava-slide frame this regression guards.
     *
     * <p>The expected X is read from the fixture itself
     * ({@code src/test/resources/traces/s1/credits_01_mz2/physics.csv.gz}, row
     * index {@value #FRAME_341}) rather than hardcoded. The v5 fixture
     * republish re-timed this recording by one row relative to the pre-v5
     * capture — the old literal 0x0E1A is now row 340, and row 341 records
     * 0x0E18 — so a hardcoded number silently asserted the previous
     * recording. Reading the row keeps this test pinned to the same ROM
     * evidence {@code TestS1Credits01Mz2TraceReplay} compares against, using
     * the same "compare after stepping row i" convention.
     */
    private static final int FRAME_341 = 341;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private DemoInputPlayer demoPlayer;
    private TraceData trace;

    @BeforeAll
    public static void loadLevel() throws Exception {
        int zone = Sonic1CreditsDemoData.DEMO_ZONE[CREDITS_DEMO_IDX];
        int act = Sonic1CreditsDemoData.DEMO_ACT[CREDITS_DEMO_IDX];
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, zone, act);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    public void setUp() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");

        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition((short) Sonic1CreditsDemoData.START_X[CREDITS_DEMO_IDX],
                        (short) Sonic1CreditsDemoData.START_Y[CREDITS_DEMO_IDX])
                .startPositionIsCentre()
                .build();

        AbstractPlayableSprite player = fixture.sprite();
        player.setRingCount(0);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setControlLocked(false);
        player.setForcedInputMask(0);

        if (GameServices.level().getObjectManager() != null) {
            GameServices.level().getObjectManager().reset(fixture.camera().getX());
        }
        if (GameServices.level().getRingManager() != null) {
            GameServices.level().getRingManager().reset(fixture.camera().getX());
        }

        Rom rom = GameServices.rom().getRom();
        int demoAddr = Sonic1CreditsDemoData.DEMO_DATA_ADDR[CREDITS_DEMO_IDX];
        int demoSize = Sonic1CreditsDemoData.DEMO_DATA_SIZE[CREDITS_DEMO_IDX];
        byte[] demoData = rom.readBytes(demoAddr, demoSize);
        demoPlayer = new DemoInputPlayer(demoData);

        // Frame-zero priming step (matches AbstractCreditsDemoTraceReplayTest).
        GameServices.level().updateObjectPositionsWithoutTouches();

        // Load trace data so we can use the same VBlank-skip cadence the
        // production replay uses (otherwise demo input cursor drifts).
        trace = TraceData.load(Path.of("src/test/resources/traces/s1/credits_01_mz2"));
    }

    /**
     * Reproduces the MZ2 credits demo frame 341 divergence directly: replays
     * the demo input through frame 341 and asserts the player X matches ROM.
     *
     * <p>Without the fix the player sits 1px left of the recorded X because of
     * the premature platform-rider carry; with the fix X matches the recorded
     * row exactly.
     */
    @Test
    public void mz2PushBlockLavaSlideMatchesRomThroughFrame341() {
        AbstractPlayableSprite player = fixture.sprite();

        for (int i = 0; i <= FRAME_341; i++) {
            TraceFrame expected = trace.getFrame(i);
            TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
            TraceExecutionPhase phase =
                    TraceReplayBootstrap.phaseForReplay(trace, previous, expected);

            if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                // VBlank-only frames: ROM's main loop didn't run; engine and
                // demo cursor stay aligned by skipping the step.
                continue;
            }

            demoPlayer.advanceFrame();
            int input = demoPlayer.getInputMask();
            boolean up = (input & AbstractPlayableSprite.INPUT_UP) != 0;
            boolean down = (input & AbstractPlayableSprite.INPUT_DOWN) != 0;
            boolean left = (input & AbstractPlayableSprite.INPUT_LEFT) != 0;
            boolean right = (input & AbstractPlayableSprite.INPUT_RIGHT) != 0;
            boolean jump = (input & AbstractPlayableSprite.INPUT_JUMP) != 0;
            // S1 REV01 demo-mode bug: jump treated as freshly pressed each
            // frame it's held. See AbstractCreditsDemoTraceReplayTest.
            player.setForcedJumpPress(jump);

            fixture.stepFrame(up, down, left, right, jump);
        }

        int expectedX = trace.getFrame(FRAME_341).x() & 0xFFFF;
        int actualX = player.getCentreX() & 0xFFFF;
        assertEquals(expectedX, actualX,
                "Player centre X at frame " + FRAME_341
                        + " (lava push block first slide frame after state-4 → state-0"
                        + " transition) should match the recorded ROM value 0x"
                        + Integer.toHexString(expectedX)
                        + " but got 0x" + Integer.toHexString(actualX)
                        + " (1-pixel drift indicates a premature platform-rider carry"
                        + " on the lava landing transition).");
    }
}
