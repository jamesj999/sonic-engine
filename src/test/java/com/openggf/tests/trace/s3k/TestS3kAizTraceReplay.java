package com.openggf.tests.trace.s3k;
import com.openggf.trace.*;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAizTraceReplay extends AbstractTraceReplayTest {
    private int scenarioTraceIndex;
    private HeadlessTestFixture scenarioFixture;
    private static final int GIANT_RIDE_VINE_WINDOW_START = 2876;
    private static final int GIANT_RIDE_VINE_WINDOW_END = 2892;
    private static final int AIZ1_RHINOBOT_TRACE_FRAME = 2529;
    private static final int AIZ1_RHINOBOT_OBJECT_ID = 0x8D;
    private static final int AIZ1_RHINOBOT_ROM_X_AT_CONTACT_SETUP = 0x1C39;
    private static final int AIZ1_RHINOBOT_ROM_Y_AT_CONTACT_SETUP = 0x03C2;
    private static final int AIZ1_SIDEKICK_CATCH_UP_GATE_FRAME = 2465;
    private static final int AIZ1_GIANT_RIDE_VINE_GRAB_FRAME = 2696;
    private static final int AIZ1_GIANT_RIDE_VINE_POST_GRAB_FRAME = 2697;
    private static final int AIZ1_SIDEKICK_POST_VINE_AUTO_JUMP_FRAME = 2721;
    private static final int AIZ1_HOLLOW_TREE_CAPTURE_FRAME = 4539;
    private static final int AIZ1_HOLLOW_TREE_CAMERA_LOCK_FRAME = 4540;
    private static final int AIZ1_HOLLOW_TREE_SIDEKICK_JUMP_FRAME = 4577;
    private static final int AIZ1_HOLLOW_TREE_VERTICAL_CLAMP_FRAME = 4646;
    private static final int AIZ1_AIZ2_RELOAD_CAMERA_LOCK_FRAME = 5497;
    private static final int AIZ1_AIZ2_FIRE_REVEAL_CAMERA_RELEASE_FRAME = 5544;
    private static final int AIZ2_RELOAD_SIDEKICK_AUTO_JUMP_FRAME = 5736;
    private static final int AIZ2_RELOAD_SIDEKICK_CATCH_UP_GATE_FRAME = 6313;
    private static final int AIZ2_RELOAD_SIDEKICK_FALLTHROUGH_AUTO_JUMP_FRAME = 7082;
    private static final int AIZ2_MINIBOSS_RESULTS_CAMERA_LOCK_FRAME = 8839;

    @Override
    protected SonicGame game() {
        return SonicGame.SONIC_3K;
    }

    @Override
    protected int zone() {
        return 0;
    }

    @Override
    protected int act() {
        return 0;
    }

    @Override
    protected Path traceDirectory() {
        return Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun");
    }

    @Override
    protected List<ExpectedPendingHardwareWork> expectedPendingHardwareTimingAtTraceEnd(
            TraceData trace) {
        // The fixture ends on the HCZ handoff row immediately after the
        // destination title owner submits its four native KosM parents. The
        // recorder captures the first two completions, but the prefix ends
        // before their title owner polls and claims them; the remaining two
        // parents are still in the ROM FIFO beyond the represented prefix.
        return List.of(
                expectedPendingHardwareWork(
                        HardwareWorkKind.KOS_MODULE_QUEUE,
                        38,
                        0x0D6F28,
                        "sha256:10eb568a70724c579f022914f56227c2c7fa421aafa8578aebaa874f0cffb0ca"),
                expectedPendingHardwareWork(
                        HardwareWorkKind.KOS_MODULE_QUEUE,
                        39,
                        0x15C3A2,
                        "sha256:05324378670c6afa8c6d99f6e5313d625d2d926e6bc16f25cd9d8d1a5a195bf8"),
                expectedPendingHardwareWork(
                        HardwareWorkKind.KOS_MODULE_QUEUE,
                        40,
                        0x0D6D84,
                        "sha256:7059802f2a045495d22a16d8c858c1758b786bf5d5f46c45838fa10bc0d1f6aa"),
                expectedPendingHardwareWork(
                        HardwareWorkKind.KOS_MODULE_QUEUE,
                        41,
                        0x39BEDA,
                        "sha256:2c17b1dba426e8b37a9b4dd2e2fc175f78010f755f4d1d43d7b0a9948eee4ba2"));
    }

    @Override
    @Test
    public void replayMatchesTrace() throws Exception {
        super.replayMatchesTrace();
    }

    @Test
    public void cameraMatchesTraceThroughFirstDelayedScrollBurst() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex(); traceIndex <= 1979; traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);

                if (traceIndex >= 1974) {
                    assertEquals(
                            current.cameraX() & 0xFFFF,
                            GameServices.camera().getX() & 0xFFFF,
                            "camera X mismatch at trace frame " + traceIndex);
                }
                previous = current;
            }
        }
    }

    @Test
    public void playerMatchesTraceThroughFirstGiantRideVineWindow() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= GIANT_RIDE_VINE_WINDOW_END;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);

                if (traceIndex >= GIANT_RIDE_VINE_WINDOW_START) {
                    assertEquals(
                            current.x() & 0xFFFF,
                            fixture.sprite().getCentreX() & 0xFFFF,
                            "player X mismatch at trace frame " + traceIndex);
                    assertEquals(
                            current.y() & 0xFFFF,
                            fixture.sprite().getCentreY() & 0xFFFF,
                            "player Y mismatch at trace frame " + traceIndex);
                }
                previous = current;
            }
        }
    }

    @Test
    public void rhinobotDoesNotDespawnOneFrameBeforeRomContact() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ1_RHINOBOT_TRACE_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);

                previous = current;
            }

            ObjectInstance rhinobot = findActiveObjectByIdNear(
                    AIZ1_RHINOBOT_OBJECT_ID,
                    AIZ1_RHINOBOT_ROM_X_AT_CONTACT_SETUP,
                    AIZ1_RHINOBOT_ROM_Y_AT_CONTACT_SETUP,
                    4);
            assertTrue(
                    rhinobot != null,
                    "Rhinobot should still be active at trace frame "
                            + AIZ1_RHINOBOT_TRACE_FRAME
                            + "; ROM destroys it on the following frame. Nearby objects: "
                            + describeActiveObjectsNear(
                                    AIZ1_RHINOBOT_ROM_X_AT_CONTACT_SETUP,
                                    AIZ1_RHINOBOT_ROM_Y_AT_CONTACT_SETUP,
                                    0x40));
        }
    }

    @Test
    public void giantRideVineGrabsPlayerOnRomFrameAfterPlatformCarry() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ1_GIANT_RIDE_VINE_GRAB_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);
                previous = current;
            }

            AbstractPlayableSprite player = fixture.sprite();
            TraceFrame grabFrame = trace.getFrame(AIZ1_GIANT_RIDE_VINE_GRAB_FRAME);
            assertEquals(grabFrame.x() & 0xFFFF, player.getCentreX() & 0xFFFF,
                    "Obj_AIZGiantRideVine handle should grab after the collapsing platform carries Sonic into range. Nearby objects: "
                            + describeActiveObjectsNear(0x1D70, 0x03F2, 0x100));
            assertEquals(grabFrame.y() & 0xFFFF, player.getCentreY() & 0xFFFF,
                    "Vine grab should write Sonic to handle y_pos+$14 on the ROM frame");
            assertEquals(0, player.getXSpeed() & 0xFFFF,
                    "sub_220C2 clears x_vel on the grab frame");
            assertEquals(0, player.getGSpeed() & 0xFFFF,
                    "sub_220C2 clears ground_vel on the grab frame");
            assertTrue(player.isObjectControlled(),
                    "sub_220C2 writes object_control=3 when the handle grabs Sonic");

            TraceFrame postGrab = trace.getFrame(AIZ1_GIANT_RIDE_VINE_POST_GRAB_FRAME);
            TraceExecutionPhase postGrabPhase = TraceReplayBootstrap.phaseForReplay(trace, previous, postGrab);
            stepReplayFrame(trace, fixture, postGrabPhase);

            assertEquals(postGrab.x() & 0xFFFF, player.getCentreX() & 0xFFFF,
                    "After grab, Obj0C should keep carrying Sonic from loc_2248A's passive vine chain");
            assertEquals(postGrab.y() & 0xFFFF, player.getCentreY() & 0xFFFF,
                    "Post-grab carry should use the ROM handle position plus $14, not the root slot");
        }
    }

    @Test
    public void sidekickAutoJumpsOnRomFrameAfterGiantRideVineHandoff() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ1_SIDEKICK_POST_VINE_AUTO_JUMP_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);
                previous = current;
            }

            AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
            TraceCharacterState expected = trace.getFrame(AIZ1_SIDEKICK_POST_VINE_AUTO_JUMP_FRAME).sidekick();
            assertTrue(expected.present(), "Trace frame should include first-sidekick state");
            assertEquals(expected.x() & 0xFFFF, tails.getCentreX() & 0xFFFF,
                    "Tails x_pos should stay aligned through the AIZ local push-bypass jump");
            assertEquals(expected.y() & 0xFFFF, tails.getCentreY() & 0xFFFF,
                    "Tails y_pos should include the ROM-frame jump position step");
            assertEquals(expected.ySpeed() & 0xFFFF, tails.getYSpeed() & 0xFFFF,
                    "Tails_Jump should write -$680 y_vel on the ROM frame");
            assertTrue(tails.getAir(),
                    "S3K loc_13DD0 -> loc_13E9C sets Ctrl_2_Press_Logical before Obj02_MdNormal runs "
                            + "(sonic3k.asm:26702-26705,26775-26785)");
            assertTrue(tails.getRolling(),
                    "Tails_Jump enters the rolling airborne state on the same frame as the CPU auto-jump");
        }
    }

    @Test
    public void aizIntroSidekickStaysAtCatchUpMarkerUntilRomGate() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
            SidekickCpuController controller = tails.getCpuController();
            StringBuilder transitions = new StringBuilder();
            SidekickCpuController.State lastState = controller.getState();

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ1_SIDEKICK_CATCH_UP_GATE_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);

                SidekickCpuController.State state = controller.getState();
                if (state != lastState) {
                    transitions.append(String.format(
                            " f%d:%s->%s pos=%04X,%04X obj=%b air=%b;",
                            traceIndex,
                            lastState,
                            state,
                            tails.getCentreX() & 0xFFFF,
                            tails.getCentreY() & 0xFFFF,
                            tails.isObjectControlled(),
                            tails.getAir()));
                    lastState = state;
                }
                previous = current;
            }

            assertEquals(SidekickCpuController.State.FLIGHT_AUTO_RECOVERY, controller.getState(),
                    "ROM keeps AIZ intro Tails in Tails_CPU_routine=$02 at the marker until "
                            + "the Level_frame_counter $0880 catch-up gate, then enters routine $04. Transitions:"
                            + transitions);
            assertEquals(0x1B0D, tails.getCentreX() & 0xFFFF,
                    "Tails should warp to Sonic x_pos on the $0880 catch-up gate");
            assertEquals(0x031C, tails.getCentreY() & 0xFFFF,
                    "Tails should warp to Sonic y_pos-$C0 on the $0880 catch-up gate");
            assertEquals(0, tails.getXSpeed(),
                    "Tails_Catch_Up_Flying clears x_vel on the $0880 gate");
            assertTrue(tails.isObjectControlled(),
                    "routine $02 wait path preserves object_control=$81 until catch-up triggers");
        }
    }

    @Test
    public void hollowTreeCameraLockBecomesVisibleOnRomFrameAfterCapture() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ1_HOLLOW_TREE_CAMERA_LOCK_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);

                if (traceIndex == AIZ1_HOLLOW_TREE_CAPTURE_FRAME) {
                    AbstractPlayableSprite player = fixture.sprite();
                    assertEquals(current.x() & 0xFFFF, player.getCentreX() & 0xFFFF,
                            "Obj_AIZHollowTree should capture Sonic on the ROM frame");
                    assertTrue(player.isObjectControlled(),
                            "Obj_AIZHollowTree sets object_control bits 6 and 1 on capture "
                                    + "(sonic3k.asm:43688-43693)");
                    assertEquals(current.cameraX() & 0xFFFF, GameServices.camera().getX() & 0xFFFF,
                            "Obj_AIZHollowTree writes Camera_min/max_X_pos=$2C60 on capture "
                                    + "(sonic3k.asm:43702-43704), but the trace-visible camera clamp "
                                    + "lands on the next frame after the ROM camera step");
                }

                if (traceIndex == AIZ1_HOLLOW_TREE_CAMERA_LOCK_FRAME) {
                    assertEquals(current.cameraX() & 0xFFFF, GameServices.camera().getX() & 0xFFFF,
                            "AIZ hollow-tree camera lock should be visible on the ROM frame after capture");
                    TraceCharacterState expectedTails = current.sidekick();
                    assertTrue(expectedTails.present(), "Trace frame should include first-sidekick state");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
                    assertEquals(expectedTails.x() & 0xFFFF, tails.getCentreX() & 0xFFFF,
                            "Tails_Check_Screen_Boundaries should see the immediate tree camera bound "
                                    + "and clamp to Camera_min_X_pos+$10 (sonic3k.asm:28414-28450)");
                    assertEquals(expectedTails.xSpeed(), tails.getXSpeed(),
                            "Tails boundary clamp should clear x_vel (sonic3k.asm:28446-28450)");
                    assertEquals(expectedTails.gSpeed(), tails.getGSpeed(),
                            "Tails boundary clamp should clear ground_vel (sonic3k.asm:28446-28450)");
                }
                previous = current;
            }
        }
    }

    @Test
    public void hollowTreeSidekickUsesRomVisibleAutoJumpCadenceOnReleaseFrame() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ1_HOLLOW_TREE_SIDEKICK_JUMP_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);
                previous = current;
            }

            AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
            TraceCharacterState expectedTails = trace.getFrame(AIZ1_HOLLOW_TREE_SIDEKICK_JUMP_FRAME).sidekick();
            assertTrue(expectedTails.present(), "Trace frame should include first-sidekick state");
            assertTrue(tails.getAir(),
                    "Tails should consume the ROM-visible low-6 frame auto-jump on the hollow-tree release frame "
                            + "(Sonic_RecordPos sonic3k.asm:22124-22136; Tails_CPU_Control "
                            + "sonic3k.asm:26696-26705,26775-26785; Tails_Jump "
                            + "sonic3k.asm:28519-28568). "
                            + describeLeaderInputHistory(fixture.sprite(), tails));
            assertTrue(tails.getRolling(),
                    "Tails_Jump sets Status_Roll with Status_InAir on the same frame");
            assertEquals(expectedTails.x() & 0xFFFF, tails.getCentreX() & 0xFFFF,
                    "Tails x_pos should match ROM on the release jump frame");
            assertEquals(expectedTails.y() & 0xFFFF, tails.getCentreY() & 0xFFFF,
                    "Tails y_pos should match ROM on the release jump frame");
            assertEquals(expectedTails.xSpeed(), tails.getXSpeed(),
                    "Tails_Jump should add the angle-derived x_vel component on the ROM frame");
            assertEquals(expectedTails.ySpeed(), tails.getYSpeed(),
                    "Tails_Jump should add the angle-derived y_vel component on the ROM frame");
        }
    }

    @Test
    public void hollowTreeRideAppliesRomVerticalCameraMinimum() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ1_HOLLOW_TREE_VERTICAL_CLAMP_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);
                previous = current;
            }

            TraceFrame expected = trace.getFrame(AIZ1_HOLLOW_TREE_VERTICAL_CLAMP_FRAME);
            assertEquals(0x02E0, GameServices.camera().getMinY() & 0xFFFF,
                    "AIZ1_Resize loc_1C550 should raise Camera_min_Y_pos to $02E0 once "
                            + "Camera_X_pos reaches $2C00 (sonic3k.asm:38939-38958)");
            assertEquals(expected.cameraY() & 0xFFFF, GameServices.camera().getY() & 0xFFFF,
                    "Camera_Y_pos should clamp to the ROM AIZ1 hollow-tree minimum on trace frame "
                            + AIZ1_HOLLOW_TREE_VERTICAL_CLAMP_FRAME);
        }
    }

    @Test
    public void aiz2ReloadResumeAppliesRomCameraLock() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ1_AIZ2_RELOAD_CAMERA_LOCK_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);
                previous = current;
            }

            TraceFrame expected = trace.getFrame(AIZ1_AIZ2_RELOAD_CAMERA_LOCK_FRAME);
            assertEquals(0x0010, GameServices.camera().getMinX() & 0xFFFF,
                    "AIZ1BGE_Finish writes long #$00100010 to Camera_min_X_pos, "
                            + "locking min/max X at $0010 after the AIZ2 reload "
                            + "(sonic3k.asm:104758-104759)");
            assertEquals(0x0010, GameServices.camera().getMaxX() & 0xFFFF,
                    "AIZ1BGE_Finish should keep Camera_X_pos clamped to $0010 until "
                            + "AIZ2BGE_WaitFire releases Camera_max_X_pos after the fire subsides "
                            + "(sonic3k.asm:104758-104762,105075-105092)");
            assertEquals(0x0260, GameServices.camera().getMaxY() & 0xFFFF,
                    "AIZ1BGE_Finish writes long #$00000260 to Camera_min_Y_pos, "
                            + "snapping Camera_max_Y_pos before the target write "
                            + "(sonic3k.asm:104760-104762)");
            assertEquals(expected.cameraX() & 0xFFFF, GameServices.camera().getX() & 0xFFFF,
                    "Camera_X_pos should remain locked on the ROM reload-resume frame");
            assertEquals(expected.cameraY() & 0xFFFF, GameServices.camera().getY() & 0xFFFF,
                    "Camera_Y_pos should remain clamped to the ROM reload-resume maxY");
        }
    }

    @Test
    public void aiz2FireRevealReleasesReloadCameraLockOnRomFrame() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            StringBuilder fireRevealDiagnostics = new StringBuilder();
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ1_AIZ2_FIRE_REVEAL_CAMERA_RELEASE_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);
                if (traceIndex >= AIZ1_AIZ2_FIRE_REVEAL_CAMERA_RELEASE_FRAME - 8) {
                    if (GameServices.module().getLevelEventProvider()
                            instanceof com.openggf.game.sonic3k.Sonic3kLevelEventManager events
                            && events.getAizEvents() != null) {
                        var aiz = events.getAizEvents();
                        fireRevealDiagnostics.append(String.format(
                                " f%d cam=%04X maxX=%04X phase=%d bgY=%04X wait=%s;",
                                traceIndex,
                                GameServices.camera().getX() & 0xFFFF,
                                GameServices.camera().getMaxX() & 0xFFFF,
                                aiz.getFireSequencePhaseOrdinal(),
                                aiz.getFireTransitionBgY() & 0xFFFF,
                                aiz.isAct2WaitFireDrawActive()));
                    }
                }
                previous = current;
            }

            TraceFrame expected = trace.getFrame(AIZ1_AIZ2_FIRE_REVEAL_CAMERA_RELEASE_FRAME);
            assertEquals(0x6000, GameServices.camera().getMaxX() & 0xFFFF,
                    "AIZ2BGE_WaitFire writes Camera_max_X_pos=$6000 once "
                            + "Camera_Y_pos_BG_copy reaches $0310 (sonic3k.asm:105075-105092). "
                            + fireRevealDiagnostics);
            assertEquals(expected.cameraX() & 0xFFFF, GameServices.camera().getX() & 0xFFFF,
                    "Camera_X_pos should resume following Sonic on the same ROM frame the fire reveal releases maxX. "
                            + fireRevealDiagnostics);
            assertEquals(expected.cameraY() & 0xFFFF, GameServices.camera().getY() & 0xFFFF,
                    "Camera_Y_pos should stay on the AIZ2 reload maxY while the X lock releases");
        }
    }

    @Test
    public void aiz2ReloadSidekickUsesRomVisiblePushAutoJumpCadence() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ2_RELOAD_SIDEKICK_AUTO_JUMP_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);
                previous = current;
            }

            AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
            TraceCharacterState expectedTails = trace.getFrame(AIZ2_RELOAD_SIDEKICK_AUTO_JUMP_FRAME).sidekick();
            assertTrue(expectedTails.present(), "Trace frame should include first-sidekick state");
            assertTrue(tails.getAir(),
                    "AIZ2 reload push bypass should use ROM-visible Level_frame_counter at loc_13E9C "
                            + "(LevelLoop increments before Process_Sprites, sonic3k.asm:7888-7894; "
                            + "Tails_CPU_Control tests low 6 bits at sonic3k.asm:26702-26705,26775-26785). "
                            + tails.getCpuController().formatLatestNormalStepDiagnostics());
            assertTrue(tails.getRolling(),
                    "Tails_Jump should enter rolling airborne state on the AIZ2 reload auto-jump frame");
            assertEquals(expectedTails.ySpeed(), tails.getYSpeed(),
                    "Tails_Jump should write the ROM -$0680 y_vel on the same frame");
            assertEquals(expectedTails.xSpeed(), tails.getXSpeed(),
                    "Tails x_vel should match the ROM after the auto-jump frame");
            assertEquals(expectedTails.gSpeed(), tails.getGSpeed(),
                    "Tails ground_vel should match the ROM after the auto-jump frame");
        }
    }

    @Test
    public void aiz2ReloadSidekickCatchUpGateUsesRomVisibleCounter() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ2_RELOAD_SIDEKICK_CATCH_UP_GATE_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);
                previous = current;
            }

            AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
            TraceCharacterState expectedTails =
                    trace.getFrame(AIZ2_RELOAD_SIDEKICK_CATCH_UP_GATE_FRAME).sidekick();
            assertTrue(expectedTails.present(), "Trace frame should include first-sidekick state");
            assertEquals(expectedTails.x() & 0xFFFF, tails.getCentreX() & 0xFFFF,
                    "AIZ2 routine-$02 catch-up should fire on the ROM-visible $1780 gate. "
                            + "sub_13ECA parks Tails without clearing speed, then "
                            + "Tails_Catch_Up_Flying loc_13B50 snaps to Sonic and clears x/y/ground velocity "
                            + "(sonic3k.asm:26474-26511,26800-26809). "
                            + tails.getCpuController().formatLatestNormalStepDiagnostics());
            assertEquals(expectedTails.y() & 0xFFFF, tails.getCentreY() & 0xFFFF,
                    "Catch-up gate should write Tails y_pos to Sonic y_pos-$C0");
            assertEquals(expectedTails.xSpeed(), tails.getXSpeed(),
                    "loc_13B50 clears x_vel on the same frame as the catch-up snap");
            assertEquals(expectedTails.ySpeed(), tails.getYSpeed(),
                    "loc_13B50 clears y_vel on the same frame as the catch-up snap");
            assertEquals(expectedTails.gSpeed(), tails.getGSpeed(),
                    "loc_13B50 clears ground_vel on the same frame as the catch-up snap");
            assertTrue(tails.isObjectControlled(),
                    "loc_13B50 writes object_control=$81 while entering routine $04");
        }
    }

    @Test
    public void aiz2ReloadSidekickFallthroughAutoJumpUsesRomVisibleCounter() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ2_RELOAD_SIDEKICK_FALLTHROUGH_AUTO_JUMP_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);
                previous = current;
            }

            AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().getFirst();
            TraceCharacterState expectedTails =
                    trace.getFrame(AIZ2_RELOAD_SIDEKICK_FALLTHROUGH_AUTO_JUMP_FRAME).sidekick();
            assertTrue(expectedTails.present(), "Trace frame should include first-sidekick state");
            assertTrue(tails.getAir(),
                    "AIZ2 normal fallthrough into loc_13E7C/loc_13E9C should use the ROM-visible "
                            + "Level_frame_counter byte on frame 7082 (sonic3k.asm:26760-26785). "
                            + tails.getCpuController().formatLatestNormalStepDiagnostics());
            assertTrue(tails.getRolling(),
                    "Tails_Jump should enter rolling airborne state on the ROM auto-jump frame");
            assertEquals(expectedTails.x() & 0xFFFF, tails.getCentreX() & 0xFFFF,
                    "Tails x_pos should match after the fallthrough auto-jump frame");
            assertEquals(expectedTails.y() & 0xFFFF, tails.getCentreY() & 0xFFFF,
                    "Tails y_pos should include the same-frame jump movement");
            assertEquals(expectedTails.xSpeed(), tails.getXSpeed(),
                    "Tails x_vel should match the ROM on the fallthrough auto-jump frame");
            assertEquals(expectedTails.ySpeed(), tails.getYSpeed(),
                    "Tails_Jump should write the ROM -$0680 y_vel on frame 7082");
            assertEquals(expectedTails.gSpeed(), tails.getGSpeed(),
                    "Tails ground_vel should match the ROM on the fallthrough auto-jump frame");
        }
    }

    @Test
    public void aiz2MinibossResultsHandoffKeepsArenaCameraLocked() throws Exception {
        Path traceDir = traceDirectory();
        Assumptions.assumeTrue(Files.isDirectory(traceDir), "Trace directory not found: " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        try (ReplayConfigScope scope = new ReplayConfigScope()) {

            HeadlessTestFixture fixture = buildReplayFixture(trace, bk2Path);
            TraceReplayBootstrap.ReplayStartState replayStart = primeReplayFixture(trace, fixture);

            TraceFrame previous = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : null;
            for (int traceIndex = replayStart.startingTraceIndex();
                 traceIndex <= AIZ2_MINIBOSS_RESULTS_CAMERA_LOCK_FRAME;
                 traceIndex++) {
                TraceFrame current = trace.getFrame(traceIndex);
                TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, current);
                stepReplayFrame(trace, fixture, phase);
                previous = current;
            }

            TraceFrame expected = trace.getFrame(AIZ2_MINIBOSS_RESULTS_CAMERA_LOCK_FRAME);
            assertEquals(0x10E0, GameServices.camera().getMinX() & 0xFFFF,
                    "Obj_AIZMiniboss loc_68A88 branches through loc_68556, which locks "
                            + "Camera_min_X_pos to $10E0 until Obj_EndSignControl reaches "
                            + "End_of_level_flag/Change_Act2Sizes "
                            + "(sonic3k.asm:137251-137266,136774-136780,180406-180419)");
            assertEquals(0x10E0, GameServices.camera().getMaxX() & 0xFFFF,
                    "The AIZ miniboss should not restore full level X bounds during the "
                            + "results handoff before Change_Act2Sizes spawns the gradual "
                            + "level-size objects (sonic3k.asm:180415-180419,180575-180609)");
            assertEquals(expected.cameraX() & 0xFFFF, GameServices.camera().getX() & 0xFFFF,
                    "Camera_X_pos should still be held at the ROM AIZ miniboss arena lock");
            assertEquals(expected.cameraY() & 0xFFFF, GameServices.camera().getY() & 0xFFFF,
                    "Camera_Y_pos should remain on the ROM miniboss maxY clamp");
        }
    }

    private HeadlessTestFixture buildReplayFixture(TraceData trace, Path bk2Path) throws Exception {
        TraceReplaySessionBootstrap.prepareConfiguration(
                trace, trace.metadata());
        HeadlessTestFixture.Builder builder = HeadlessTestFixture.builder()
                .withZoneAndAct(zone(), act())
                .withRecording(bk2Path)
                .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                .withHardwareReadinessAdmissionPolicy(
                        HardwareReadinessAdmissionPolicy.RECORDED);
        if (TraceReplayBootstrap.shouldGroundSnapMetadataStartForTraceReplay(trace)
                || TraceReplayBootstrap.replaySeedTraceIndexForTraceReplay(trace) == 0) {
            builder.withFreshLevelStartLifecycle();
        }
        scenarioFixture = builder.build();
        return scenarioFixture;
    }

    private TraceReplayBootstrap.ReplayStartState primeReplayFixture(TraceData trace, HeadlessTestFixture fixture) {
        TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
        TraceReplayBootstrap.ReplayStartState replayStart =
                TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1)
                        .replayStart();
        TraceFrame previous = replayStart.hasSeededTraceState()
                ? trace.getFrame(replayStart.seededTraceIndex())
                : null;
        scenarioTraceIndex = replayStart.startingTraceIndex();
        TraceReplaySessionBootstrap.alignFrameCountersForReplayStart(
                        trace,
                        replayStart,
                        previous,
                        scenarioTraceIndex < trace.frameCount()
                                ? trace.getFrame(scenarioTraceIndex) : null);
        return replayStart;
    }

    private void stepReplayFrame(TraceData trace, HeadlessTestFixture fixture, TraceExecutionPhase phase) {
        TraceFrame current = trace.getFrame(scenarioTraceIndex);
        TraceFrame previous = scenarioTraceIndex > 0
                ? trace.getFrame(scenarioTraceIndex - 1) : null;
        fixture.beginTraceRow(scenarioTraceIndex, current.frame());
        TraceReplayBootstrap.markVblankStarvedIterationForReplay(previous, current);
        TraceReplayBootstrap.markIterationHeldIntoNextRowForReplay(
                current,
                scenarioTraceIndex + 1 < trace.frameCount()
                        ? trace.getFrame(scenarioTraceIndex + 1) : null);
        driveScenarioReplayFrame(trace, fixture, phase);
        scenarioTraceIndex++;
    }

    private ObjectInstance findActiveObjectByIdNear(int objectId, int x, int y, int maxDistance) {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager == null) {
            return null;
        }
        return objectManager.getActiveObjects().stream()
                .filter(instance -> instance != null && !instance.isDestroyed())
                .filter(instance -> instance.getSpawn() != null
                        && (instance.getSpawn().objectId() & 0xFF) == objectId)
                .filter(instance -> Math.abs((instance.getX() & 0xFFFF) - x) <= maxDistance
                        && Math.abs((instance.getY() & 0xFFFF) - y) <= maxDistance)
                .findFirst()
                .orElse(null);
    }

    private String describeLeaderInputHistory(AbstractPlayableSprite leader, AbstractPlayableSprite tails) {
        StringBuilder builder = new StringBuilder("leader input history:");
        for (int delay = 14; delay <= 18; delay++) {
            builder.append(String.format(
                    " d%d=%04X/%s",
                    delay,
                    leader.getInputHistory(delay) & 0xFFFF,
                    leader.getJumpPressHistory(delay) ? "press" : "held"));
        }
        SidekickCpuController controller = tails.getCpuController();
        if (controller != null) {
            builder.append(" diag=").append(controller.formatLatestNormalStepDiagnostics());
        }
        return builder.toString();
    }

    private String describeActiveObjectsNear(int x, int y, int maxDistance) {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager == null) {
            return "<no object manager>";
        }
        return objectManager.getActiveObjects().stream()
                .filter(instance -> instance != null && !instance.isDestroyed())
                .filter(instance -> instance.getSpawn() != null)
                .filter(instance -> Math.abs((instance.getX() & 0xFFFF) - x) <= maxDistance
                        && Math.abs((instance.getY() & 0xFFFF) - y) <= maxDistance)
                .sorted(Comparator.comparingInt(instance ->
                        Math.abs((instance.getX() & 0xFFFF) - x)
                                + Math.abs((instance.getY() & 0xFFFF) - y)))
                .limit(24)
                .map(instance -> {
                    var spawn = instance.getSpawn();
                    int slot = instance instanceof com.openggf.level.objects.AbstractObjectInstance object
                            ? object.getSlotIndex()
                            : -1;
                    int execSlot = instance instanceof com.openggf.level.objects.AbstractObjectInstance object
                            ? object.getExecutionSlotIndex()
                            : -1;
                    String touch = instance instanceof TouchResponseProvider provider
                            ? String.format(" flags=%02X prop=%02X",
                                    provider.getCollisionFlags() & 0xFF,
                                    provider.getCollisionProperty() & 0xFF)
                            : "";
                    return String.format(
                            "%s(id=%02X slot=%d exec=%d pos=%04X,%04X%s)",
                            instance.getClass().getSimpleName(),
                            spawn != null ? spawn.objectId() & 0xFF : -1,
                            slot,
                            execSlot,
                            instance.getX() & 0xFFFF,
                            instance.getY() & 0xFFFF,
                            touch);
                })
                .reduce((left, right) -> left + " | " + right)
                .orElse("<none>");
    }

    /**
     * Saves the three replay-relevant config values, applies the AIZ-fullrun
     * settings (skip-intros off, Sonic + Tails), and on {@link #close()} restores
     * the originals and resets the test environment. Replaces the per-@Test
     * save/set/finally-restore scaffold that was copy-pasted across every case;
     * the exact values applied and restored, and the trailing
     * {@code TestEnvironment.resetAll()}, are unchanged.
     */
    private final class ReplayConfigScope implements AutoCloseable {
        private final SonicConfigurationService config;
        private final Object oldSkip;
        private final Object oldMain;
        private final Object oldSidekick;

        private ReplayConfigScope() {
            config = SonicConfigurationService.getInstance();
            oldSkip = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
            oldMain = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
            oldSidekick = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
            config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        }

        @Override
        public void close() {
            if (scenarioFixture != null) {
                scenarioFixture.abortHardwareTimingReplayRun();
                scenarioFixture = null;
            }
            config.setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkip != null ? oldSkip : false);
            config.setConfigValue(
                    SonicConfiguration.MAIN_CHARACTER_CODE,
                    oldMain != null ? oldMain : "sonic");
            config.setConfigValue(
                    SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    oldSidekick != null ? oldSidekick : "tails");
            TestEnvironment.resetAll();
        }
    }

}
