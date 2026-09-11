package com.openggf.sprites.managers;

import com.openggf.game.GameServices;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.PerObjectRewindSnapshot.PlayerRewindExtra;
import com.openggf.level.objects.PerObjectRewindSnapshot.SidekickCpuRewindExtra;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.TailsCarryController;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceCharacterState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestInitialPlayableProcessSpritesPass {

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void nativeNeutralInputContainsNoRawOrLogicalControllerState() {
        InitialPlayableInput input = InitialPlayableInput.nativeNeutral();

        assertEquals(0, input.p1Held());
        assertEquals(0, input.p1Pressed());
        assertEquals(0, input.p2Held());
        assertEquals(0, input.p2Pressed());
        assertTrue(input.consumeQueuedObjectControlState(),
                "queued runtime object-control state is not user input");
    }

    @Test
    void firstOrdinaryEpochFollowsTheSetupEpochWithoutMutatingEitherValue() {
        ProcessSpritesEpoch setup = new ProcessSpritesEpoch(0, 1, false);

        ProcessSpritesEpoch ordinary =
                ProcessSpritesEpoch.ordinary(1, setup.objectDispatchOrdinal());

        assertEquals(new ProcessSpritesEpoch(0, 1, false), setup);
        assertEquals(new ProcessSpritesEpoch(1, 2, true), ordinary);
    }

    @Test
    void spriteManagerExposesTheNarrowPlayableSstDispatcherContract() {
        assertInstanceOf(PlayableSstDispatcher.class, new SpriteManager());
    }

    @Test
    void setupConsumesQueuedObjectControlAndPreservesForcedRuntimeInput() throws Exception {
        SharedLevel level = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            SpriteManager manager = GameServices.sprites();
            AbstractPlayableSprite p1 = manager.getMainPlayable();
            p1.queueControlLockedForNextFrame(true);
            p1.queueForceInputRightForNextFrame(true);

            manager.processInitialPlayableSlots(
                    new ProcessSpritesEpoch(0, 1, false),
                    InitialPlayableInput.nativeNeutral());

            PlayerRewindExtra state = p1.captureRewindState(false).playerExtra();
            assertTrue(p1.isControlLocked());
            assertTrue(p1.isForceInputRight());
            assertEquals(AbstractPlayableSprite.INPUT_RIGHT,
                    p1.getLogicalInputState() & AbstractPlayableSprite.INPUT_RIGHT);
            assertFalse(state.hasQueuedControlLockedState());
            assertFalse(state.hasQueuedForceInputRightState());
        } finally {
            level.dispose();
        }
    }

    @Test
    void p2InitPreservesActiveRuntimeControlOwnership() throws Exception {
        SharedLevel level = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            SpriteManager manager = GameServices.sprites();
            AbstractPlayableSprite p2 = manager.getSidekicks().getFirst();
            SidekickCpuController cpu = p2.getCpuController();
            SidekickCpuRewindExtra baselineCpu = cpu.captureRewindState();
            cpu.restoreRewindState(withCpuSetupSentinels(cpu.captureRewindState()));
            p2.setControlLocked(true);
            ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(p2);
            p2.setForcedAnimationId(0x1B);
            p2.queueForceInputRightForNextFrame(true);
            p2.getTailsCarryController().restore(new TailsCarryController.Snapshot(
                    (short) 0x1234,
                    (short) 0x5678,
                    false,
                    false,
                    0x3C,
                    TailsCarryController.CarryContext.MANUAL));
            PerObjectRewindSnapshot seededSetupState = p2.captureRewindState(false);

            p2.setControlLocked(false);
            ObjectControlState.none().applyTo(p2);
            p2.queueForceInputRightForNextFrame(false);
            p2.getTailsCarryController().restore(new TailsCarryController.Snapshot(
                    (short) 0,
                    (short) 0,
                    false,
                    false,
                    0,
                    TailsCarryController.CarryContext.NONE));
            cpu.restoreRewindState(baselineCpu);
            p2.restoreRewindState(seededSetupState);

            assertEquals(0x44, cpu.captureRewindState().frameCounter());
            assertEquals(0x3C, p2.getTailsCarryController().capture().cooldown());
            assertTrue(p2.isControlLocked());
            assertTrue(p2.isObjectControlled());

            manager.processInitialPlayableSlots(
                    new ProcessSpritesEpoch(0, 1, false),
                    InitialPlayableInput.nativeNeutral());

            assertTrue(p2.isControlLocked());
            assertTrue(p2.isObjectControlled());
            assertTrue(p2.isObjectControlAllowsCpu());
            assertTrue(p2.isObjectControlSuppressesMovement());
            assertTrue(p2.isForceInputRight());
            assertEquals(0x1B, p2.getForcedAnimationId(),
                    "Tails_Init does not overwrite the animation selected by level assembly");

            SidekickCpuRewindExtra state = cpu.captureRewindState();
            assertEquals(SidekickCpuController.State.INIT, state.state());
            assertEquals(-1, state.deadFallingRomCpuRoutine());
            assertEquals(0, state.despawnCounter());
            assertEquals(0, state.frameCounter());
            assertEquals(0, state.controlCounter());
            assertEquals(0, state.controller2Held());
            assertEquals(0, state.controller2Logical());
            assertEquals(0, state.normalFrameCount());
            assertEquals(0, state.approachFrameCount());
            assertEquals(0, state.flightTimer());
            assertEquals(0, state.catchUpTargetX());
            assertEquals(0, state.catchUpTargetY());
            assertEquals(new TailsCarryController.Snapshot(
                            (short) 0,
                            (short) 0,
                            false,
                            false,
                            0,
                            TailsCarryController.CarryContext.NONE),
                    p2.getTailsCarryController().capture());
        } finally {
            level.dispose();
        }
    }

    @Test
    void ordinaryCpuResetClearsCarryCooldownAndVelocityLatches() throws Exception {
        SharedLevel level = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            AbstractPlayableSprite p2 = GameServices.sprites().getSidekicks().getFirst();
            p2.getTailsCarryController().restore(new TailsCarryController.Snapshot(
                    (short) 0x1234,
                    (short) 0x5678,
                    false,
                    false,
                    0x3C,
                    TailsCarryController.CarryContext.MANUAL));

            p2.getCpuController().reset();

            assertEquals(new TailsCarryController.Snapshot(
                            (short) 0,
                            (short) 0,
                            false,
                            false,
                            0,
                            TailsCarryController.CarryContext.NONE),
                    p2.getTailsCarryController().capture());
        } finally {
            level.dispose();
        }
    }

    @Test
    void nativeNeutralInputAndEpochProduceTheCapturedAiz1PlayerState() throws Exception {
        SharedLevel level = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            SpriteManager manager = GameServices.sprites();
            AbstractPlayableSprite p1 = manager.getMainPlayable();
            AbstractPlayableSprite p2 = manager.getSidekicks().getFirst();
            seedPreInitialPlayerSlot(p1);
            p2.getCpuController().reset();
            seedPreInitialPlayerSlot(p2);
            int spriteFrame = manager.getFrameCounter();
            int levelFrame = GameServices.level().getFrameCounter();
            int objectFrame = GameServices.level().getObjectManager().getFrameCounter();
            int vblank = GameServices.level().getObjectManager().getVblaCounter();

            assertEquals(0, spriteFrame);
            assertEquals(0, levelFrame);
            assertEquals(0, objectFrame);
            assertEquals(0, TraceCharacterState.routineFromSprite(p1));
            assertEquals(0, TraceCharacterState.routineFromSprite(p2));
            assertEquals(0, p1.getDrowningController().getRemainingAir());
            assertEquals(0, p2.getDrowningController().getRemainingAir());
            assertEquals(0, p1.getFlipSpeed());
            assertEquals(0, p2.getFlipSpeed());

            manager.processInitialPlayableSlots(
                    new ProcessSpritesEpoch(0, 1, false),
                    InitialPlayableInput.nativeNeutral());

            // Locked-on ROM oracle, pre-call $647E -> return $6484:
            // docs/skdisasm/sonic3k.asm:7848-7856,21931-21941,26101-26156.
            assertEquals(0x0040, p1.getCentreX());
            assertEquals(0x0420, p1.getCentreY());
            assertEquals(0x0020, p2.getCentreX());
            assertEquals(0x0424, p2.getCentreY());
            assertEquals(0, p1.getXSubpixelRaw());
            assertEquals(0, p1.getYSubpixelRaw());
            assertEquals(0, p2.getXSubpixelRaw());
            assertEquals(0, p2.getYSubpixelRaw());
            assertEquals(0, p1.getXSpeed());
            assertEquals(0, p1.getYSpeed());
            assertEquals(0, p1.getGSpeed());
            assertEquals(0, p2.getXSpeed());
            assertEquals(0, p2.getYSpeed());
            assertEquals(0, p2.getGSpeed());
            assertEquals(2, TraceCharacterState.routineFromSprite(p1));
            assertEquals(2, TraceCharacterState.routineFromSprite(p2));
            assertEquals(0, TraceCharacterState.statusByteFromSprite(p1));
            assertEquals(0, TraceCharacterState.statusByteFromSprite(p2));
            assertEquals(0, p1.getLogicalInputState());
            assertEquals(0, p2.getLogicalInputState());
            assertEquals(30, p1.getDrowningController().getRemainingAir());
            assertEquals(30, p2.getDrowningController().getRemainingAir());
            assertEquals(4, p1.getFlipSpeed());
            assertEquals(4, p2.getFlipSpeed());
            assertEquals(0, p1.getDoubleJumpFlag());
            assertEquals(0, p2.getDoubleJumpFlag());
            assertEquals(0, p1.getFlipsRemaining());
            assertEquals(0, p2.getFlipsRemaining());
            assertEquals(0, p1.getMoveLockTimer());
            assertEquals(0, p2.getMoveLockTimer());
            assertEquals(0, p1.getAnimationId());
            assertEquals(0, p1.getAnimationFrameIndex());
            assertEquals(0, p1.getAnimationTick());
            assertEquals(0, p2.getAnimationId());
            assertEquals(0, p2.getAnimationFrameIndex());
            assertEquals(0, p2.getAnimationTick());
            assertEquals(0, p1.getAnimationManager().captureRewindState().lastAnimationId());
            assertEquals(0, p2.getAnimationManager().captureRewindState().lastAnimationId());
            assertFalse(p1.getAir());
            assertFalse(p2.getAir());

            PlayerRewindExtra p1State = p1.captureRewindState(false).playerExtra();
            PlayerRewindExtra p2State = p2.captureRewindState(false).playerExtra();
            assertEquals(0, nativeStatusSecondary(p1));
            assertEquals(0, nativeStatusSecondary(p2));
            assertSecondaryStatusAndPowerTimersZero(p1, p1State);
            assertSecondaryStatusAndPowerTimersZero(p2, p2State);
            assertFalse(p1 instanceof TouchResponseProvider,
                    "Player collision_flags/property remain native zero; no touch-list publisher owns them");
            assertFalse(p2 instanceof TouchResponseProvider,
                    "Player collision_flags/property remain native zero; no touch-list publisher owns them");
            assertEquals(0, p1.captureRewindState(false).preUpdateCollisionFlags());
            assertEquals(0, p2.captureRewindState(false).preUpdateCollisionFlags());
            assertTrue(GameServices.water().hasWater(0, 0),
                    "AIZ1 Water_flag remains 1 across the setup pass");

            // The setup call is outside LevelLoop. It receives the immutable
            // (level=0, object ordinal=1) epoch without publishing gameplay/VInt
            // counters or fabricating a temporary SpriteManager counter.
            assertEquals(spriteFrame, manager.getFrameCounter());
            assertEquals(levelFrame, GameServices.level().getFrameCounter());
            assertEquals(objectFrame, GameServices.level().getObjectManager().getFrameCounter());
            assertEquals(vblank, GameServices.level().getObjectManager().getVblaCounter());
        } finally {
            level.dispose();
        }
    }

    private static void seedPreInitialPlayerSlot(AbstractPlayableSprite player) {
        player.setObjectRoutineOverride(0);
        player.getDrowningController().setRemainingAirFromFixedCountdown(0);
        player.setFlipSpeed(0);
        player.getAnimationManager().resetLastAnimationId();
    }

    @Test
    void p1InitializesTheTemporaryOffsetHistoryBeforeP2WithoutAdvancingItsCursor() throws Exception {
        SharedLevel level = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
        try {
            SpriteManager manager = GameServices.sprites();
            AbstractPlayableSprite p1 = manager.getMainPlayable();
            AbstractPlayableSprite p2 = manager.getSidekicks().getFirst();
            SidekickCpuController cpu = p2.getCpuController();
            short originalX = p1.getCentreX();
            short originalY = p1.getCentreY();
            for (int i = 1; i <= 37; i++) {
                p1.setCentreXPreserveSubpixel((short) (0x6100 + i));
                p1.setCentreYPreserveSubpixel((short) (0x7100 + i));
                p1.endOfTick();
            }
            p1.setCentreXPreserveSubpixel(originalX);
            p1.setCentreYPreserveSubpixel(originalY);

            assertEquals(List.of(p1, p2),
                    SpriteManager.buildPlayableUpdateOrder(
                            manager.getAllSprites(), manager.getSidekicks(), false),
                    "Process_Sprites visits P1 slot 0 before P2 slot 1");
            // Level init leaves the engine cursor at slot 63 so the first live
            // Sonic_RecordPos write lands in ROM slot 0. Thirty-seven writes
            // therefore leave the latest entry in slot 36.
            assertEquals(36, p1.historyPos());
            assertEquals(0x6125, p1.copyXHistory()[36] & 0xFFFF);
            assertEquals(0x7125, p1.copyYHistory()[36] & 0xFFFF);

            manager.processInitialPlayableSlots(
                    new ProcessSpritesEpoch(0, 1, false),
                    InitialPlayableInput.nativeNeutral());

            // Sonic_Init temporarily shifts P1 to $20,$424 and calls
            // Reset_Player_Position_Array. Tails_Init runs later and does not
            // perform an ordinary delayed-follow read (sonic3k.asm:21931-21941,
            // 22166-22193,26101-26156).
            assertEquals(0, p1.historyPos());
            assertTrue(allEqual(p1.copyXHistory(), 0x0020));
            assertTrue(allEqual(p1.copyYHistory(), 0x0424));
            assertEquals(0, cpu.getDiagnosticRomCpuRoutine());
            assertEquals(0, cpu.targetX());
            assertEquals(0, cpu.targetY());
            assertEquals(0, cpu.getDiagnosticGeneratedHeldInput());
            assertEquals(0, cpu.getDiagnosticGeneratedPressedInput());
            assertEquals(0, cpu.getDiagnosticJumpingFlag());
            SidekickCpuRewindExtra cpuState = cpu.captureRewindState();
            assertEquals(0, cpuState.frameCounter());
            assertEquals(0, cpuState.controlCounter());
            assertEquals(0, cpuState.flightTimer());
            assertEquals(0, cpuState.catchUpTargetX());
            assertEquals(0, cpuState.catchUpTargetY());
        } finally {
            level.dispose();
        }
    }

    private static SidekickCpuRewindExtra withCpuSetupSentinels(SidekickCpuRewindExtra source) {
        return new SidekickCpuRewindExtra(
                SidekickCpuController.State.CATCH_UP_FLIGHT,
                0x22,
                0x33,
                0x44,
                0x55,
                0x66,
                0x77,
                source.inputUp(),
                source.inputDown(),
                source.inputLeft(),
                source.inputRight(),
                source.inputJump(),
                source.inputJumpPress(),
                true,
                source.minXBound(),
                source.maxXBound(),
                source.minYBound(),
                source.maxYBound(),
                0x12,
                source.normalDespawnLastRenderFlagOffscreen(),
                source.normalDespawnFreshRenderEntryDelayConsumed(),
                0x3456,
                0x23,
                0x34,
                source.sidekickCount(),
                0x45,
                source.suppressNextAirbornePushFollowSteering(),
                source.releasedUnderwaterPushConsumed(),
                source.objectOrderGracePushBypassThisFrame(),
                0x56,
                0x67,
                source.suppressNextLevelEventNormalMovement(),
                source.catchUpUsesRomVisibleLevelFrameCounter(),
                source.levelEventDormantMarkerReleasePending(),
                source.skipPhysicsThisFrame(),
                source.deadOnObjectReenteredVisibleWindow(),
                source.deferredDespawnDeadFallContinuingThisFrame(),
                source.levelStartLeaderHistoryPrefillPending(),
                source.bootstrapPreludePlacementApplied(),
                source.cpuFrameCounterFromStoredLevelFrame(),
                0x78,
                0x79,
                0x7A,
                source.controller2SignedLocked(),
                source.nativeEndingPosePending(),
                source.latestNormalStepDiagnostics(),
                true,
                0x7C,
                true,
                (short) 0x1234,
                (short) 0x5678,
                0x7D,
                0x1357,
                0x2468,
                source.initialPresentationSuppressed(),
                source.initialPresentationWasHidden());
    }

    private static void assertSecondaryStatusAndPowerTimersZero(
            AbstractPlayableSprite player, PlayerRewindExtra state) {
        // status_secondary bits are Shield, Invincible, SpeedShoes, and the
        // three elemental-shield bits (sonic3k.constants.asm:183-191).
        assertFalse(player.hasShield());
        assertFalse(player.hasSpeedShoes());
        assertEquals(0, player.getInvulnerableFrames());
        assertEquals(0, player.getInvincibleFrames());
        assertEquals(0, state.speedShoesRemainingTicks());
        assertEquals(0, state.doubleJumpFlag());
        assertEquals(0, state.moveLockTimer());
        assertEquals(0, state.flipsRemaining() & 0xFF);
    }

    private static int nativeStatusSecondary(AbstractPlayableSprite player) {
        int value = 0;
        if (player.hasShield()) {
            value |= 0x01;
            value |= switch (player.getShieldType()) {
                case FIRE -> 0x10;
                case LIGHTNING -> 0x20;
                case BUBBLE -> 0x40;
                case BASIC -> 0;
            };
        }
        if (player.getInvincibleFrames() > 0) value |= 0x02;
        if (player.hasSpeedShoes()) value |= 0x04;
        return value;
    }

    private static boolean allEqual(short[] values, int expected) {
        for (short value : values) {
            if ((value & 0xFFFF) != expected) {
                return false;
            }
        }
        return true;
    }
}
