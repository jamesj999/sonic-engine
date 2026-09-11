package com.openggf.level;

import com.openggf.level.objects.InitialObjectDispatchScope;
import com.openggf.sprites.managers.InitialPlayableInput;
import com.openggf.sprites.managers.PlayableSstDispatcher;
import com.openggf.sprites.managers.ProcessSpritesEpoch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestInitialProcessSpritesCoordinator {

    @Test
    void executesTheNativeSstOrderAndClosesTheDispatchScope() {
        List<String> calls = new ArrayList<>();
        InitialProcessSpritesStages stages = recordingStages(calls, null);

        new InitialProcessSpritesCoordinator().execute(new InitialProcessSpritesContext(
                stages, new ProcessSpritesEpoch(0, 1, false)));

        // Process_Sprites walks Object_RAM in ascending $4A-byte slots after
        // Load_Sprites (docs/skdisasm/sonic3k.asm:7848-7856,35965-36008;
        // sonic3k.constants.asm:303-323).
        assertEquals(List.of(
                "LOAD", "P1", "P2", "RESET", "DYNAMIC_SLOT_3",
                "DYNAMIC_SLOTS_4_92", "FIXED", "CAPTURE", "CLOSE"), calls);
    }

    @Test
    void closesTheDispatchScopeWhenAnySstStageThrows() {
        for (String failingStage : List.of(
                "LOAD", "P1", "P2", "RESET", "DYNAMIC_SLOT_3",
                "DYNAMIC_SLOTS_4_92", "FIXED", "CAPTURE")) {
            List<String> calls = new ArrayList<>();
            InitialProcessSpritesStages stages = recordingStages(calls, failingStage);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new InitialProcessSpritesCoordinator().execute(
                            new InitialProcessSpritesContext(
                                    stages, new ProcessSpritesEpoch(0, 1, false))));

            assertEquals("boom at " + failingStage, failure.getMessage());
            assertEquals("CLOSE", calls.getLast(),
                    "scope must close after failure at " + failingStage);
        }
    }

    @Test
    void failedDynamicBeginCannotRegisterFixedOwners() {
        boolean[] fixedPrepared = { false };
        InitialDynamicSstDispatcher dynamic = new InitialDynamicSstDispatcher() {
            @Override
            public InitialObjectDispatchScope begin(ProcessSpritesEpoch epoch) {
                throw new IllegalStateException("begin boom");
            }

            @Override public void loadSprites() {}
            @Override public void processAbsoluteDynamicSlot3() {}
            @Override public void processManagedDynamicSlots4Through93() {}
        };
        InitialFixedSstDispatcher fixed = new InitialFixedSstDispatcher() {
            @Override
            public void onInitialScopeAcquired() {
                fixedPrepared[0] = true;
            }

            @Override
            public void processPostDynamicFixedSlots(ProcessSpritesEpoch epoch) {
            }
        };
        InitialProcessSpritesStages stages = new InitialProcessSpritesStages(
                dynamic,
                (epoch, input) -> {},
                new CollisionListSstDispatcher() {
                    @Override public void freezePreviousReadView() {}
                    @Override public void resetCurrentBuild() {}
                    @Override public void captureCompletedBuild() {}
                },
                fixed);

        assertThrows(IllegalStateException.class,
                () -> new InitialProcessSpritesCoordinator().execute(
                        new InitialProcessSpritesContext(
                                stages, new ProcessSpritesEpoch(0, 1, false))));

        assertEquals(false, fixedPrepared[0]);
    }

    private static InitialProcessSpritesStages recordingStages(
            List<String> calls, String failingStage) {
        InitialDynamicSstDispatcher dynamic = new InitialDynamicSstDispatcher() {
            @Override
            public InitialObjectDispatchScope begin(ProcessSpritesEpoch epoch) {
                return () -> append(calls, failingStage, "CLOSE");
            }

            @Override public void loadSprites() {
                append(calls, failingStage, "LOAD");
            }

            @Override public void processAbsoluteDynamicSlot3() {
                append(calls, failingStage, "DYNAMIC_SLOT_3");
            }

            @Override public void processManagedDynamicSlots4Through93() {
                append(calls, failingStage, "DYNAMIC_SLOTS_4_92");
            }
        };
        PlayableSstDispatcher playables = (epoch, input) -> {
            assertEquals(InitialPlayableInput.nativeNeutral(), input);
            append(calls, failingStage, "P1");
            append(calls, failingStage, "P2");
        };
        CollisionListSstDispatcher collision = new CollisionListSstDispatcher() {
            @Override public void freezePreviousReadView() {
                // The selected previous list is frozen before LOAD but is not an SST label.
            }

            @Override public void resetCurrentBuild() {
                append(calls, failingStage, "RESET");
            }

            @Override public void captureCompletedBuild() {
                append(calls, failingStage, "CAPTURE");
            }
        };
        InitialFixedSstDispatcher fixed =
                epoch -> append(calls, failingStage, "FIXED");
        return new InitialProcessSpritesStages(dynamic, playables, collision, fixed);
    }

    private static void append(List<String> calls, String failingStage, String stage) {
        calls.add(stage);
        if (stage.equals(failingStage)) {
            throw new IllegalStateException("boom at " + stage);
        }
    }
}
