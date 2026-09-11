package com.openggf.sprites.playable;

import com.openggf.game.rules.GameRules;
import com.openggf.tests.TestEnvironment;
import com.openggf.trace.TraceCharacterState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceCharacterState {

    @BeforeEach
    void resetRuntime() {
        TestEnvironment.resetAll();
    }

    @Test
    void cpuSidekickDeadFallingCapturesObjectRoutineSix() {
        Sonic sonic = new Sonic("sonic", (short) 0x04AF, (short) 0x0665);
        Tails tails = new Tails("tails_p2", (short) 0x0464, (short) 0x07FF);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setAir(true);
        tails.setXSpeed((short) 0);
        tails.setYSpeed((short) -0x06C8);

        SidekickCpuController controller = new SidekickCpuController(tails, sonic);
        controller.forceStateForTest(SidekickCpuController.State.DEAD_FALLING, 0);

        TraceCharacterState state = TraceCharacterState.fromSprite(tails);

        assertEquals(0x06, state.routine(),
                "ROM Obj02 switches Tails to routine 6 on the level-boundary death frame");
        assertEquals(0x02, state.statusByte(),
                "Kill_Character leaves only the in-air status bit set for this trace state");
    }

    @Test
    void hurtRoutineStillTakesPrecedenceForPlayableCapture() {
        Sonic sonic = new Sonic("sonic", (short) 0x0100, (short) 0x0200);
        sonic.setHurt(true);

        TraceCharacterState state = TraceCharacterState.fromSprite(sonic);

        assertEquals(0x04, state.routine());
    }

    @Test
    void hurtLandingFrameKeepsRoutineFourForTraceCapture() {
        Tails tails = new Tails("tails_p2", (short) 0x2990, (short) 0x0298);
        tails.setHurt(true);
        tails.captureOnObjectAtFrameStart();

        tails.setHurt(false);
        tails.setOnObject(true);

        TraceCharacterState state = TraceCharacterState.fromSprite(tails);

        assertEquals(0x04, state.routine(),
                "S2 Obj02_Hurt owns the landing frame and only writes routine 2 from Tails_HurtStop");
    }

    @Test
    void completedHurtStopFrameCapturesRoutineTwo() {
        Tails tails = new Tails("tails_p2", (short) 0x0C0E, (short) 0x066E);
        tails.setHurt(true);
        tails.captureOnObjectAtFrameStart();

        tails.setOnObject(true);
        tails.completeHurtLandingRecovery();

        TraceCharacterState state = TraceCharacterState.fromSprite(tails);

        assertEquals(0x02, state.routine(),
                "Tails_HurtStop writes routine 2 in the sampled frame once recovery completes");
    }

    @Test
    void hurtLandingPreservesPathSwitcherArtPriority() {
        Sonic sonic = new Sonic("sonic", (short) 0x48A0, (short) 0x02B4);
        sonic.setHighPriority(true);
        sonic.setHurt(true);
        sonic.setAir(true);

        sonic.setAir(false);

        assertTrue(sonic.isHighPriority(),
                "HurtCharacter/HurtStop never changes art_tile bit 7, so the AIZ2 path-switch priority must survive landing");
    }

    @Test
    void explicitHurtRecoveryPreservesPathSwitcherArtPriority() {
        Sonic sonic = new Sonic("sonic", (short) 0x48A0, (short) 0x02B4);
        sonic.setHighPriority(true);
        sonic.setHurt(true);

        sonic.completeHurtLandingRecovery();

        assertTrue(sonic.isHighPriority(),
                "The explicit HurtStop path must preserve the art_tile priority inherited from Obj_PathSwap");
    }

    @Test
    void s3kObjectLandingDoesNotUseS2HurtRoutineCaptureLatch() {
        Tails tails = new Tails("tails_p2", (short) 0x23A5, (short) 0x067F);
        tails.setGameRulesForTest(GameRules.SONIC_3K);
        tails.setHurt(true);
        tails.captureOnObjectAtFrameStart();

        tails.setHurt(false);
        tails.setOnObject(true);

        TraceCharacterState state = TraceCharacterState.fromSprite(tails);

        assertEquals(0x02, state.routine(),
                "S3K's sampled RideObject landing closure publishes the normal routine");
    }
}
