package com.openggf;

import com.openggf.camera.Camera;
import com.openggf.control.InputHandler;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator.PlcLifecycleFrame;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;

import java.util.function.Consumer;

/** Owns the ROM-specific title-card wait loop and its release handoff. */
final class GameLoopTitleCardLifecycle {
    private GameLoopTitleCardLifecycle() {
    }

    static boolean update(
            boolean doFrameStep,
            TitleCardProvider titleCard,
            PlcLifecycleFrame frame,
            GameplayModeContext gameplayMode,
            LevelManager levelManager,
            SpriteManager spriteManager,
            Camera camera,
            InputHandler input,
            Runnable playerPrelude,
            PostTitleCardDestination destination,
            Runnable exitTitleCard,
            Consumer<LevelFrameResult> releaseResult,
            Runnable beginAudioFrame,
            Runnable advanceAudioFrame,
            Consumer<PlcLifecyclePhase> preparePhase,
            LevelFrameStep.StepWrapper wrapper) {
        frame.claim(PlcLifecyclePhase.LEVEL_TITLE_CARD);
        LevelFrameContext frameContext = LevelFrameContext.from(gameplayMode);
        // The title-card loop owns a real VBlank even when it does not run the
        // ordinary player/object body, and several of its branches never reach
        // a LevelFrameStep edge. Dispatch the game-owned hook here so every
        // branch services it exactly once; the token's guard makes the later
        // LevelFrameStep dispatches on the physics branch no-ops. This adds no
        // hardware service boundary -- VINT_SERVICE stays owned by
        // LevelFrameStep alone.
        LevelFrameStep.dispatchGameVBlank(frameContext, frame);
        boolean hardwareTimedProviderScan = titleCard != null
                && titleCard.shouldAdvanceVblankClockDuringLockedPhase();
        boolean preparedByFrameStep = hardwareTimedProviderScan;
        if (hardwareTimedProviderScan) {
            LevelFrameStep.executeHardwareTimedObjectScan(
                    frameContext, frame,
                    PlcLifecyclePhase.LEVEL_TITLE_CARD, titleCard::update);
        } else if (titleCard != null) {
            titleCard.update();
        }

        if (titleCard == null || titleCard.shouldReleaseControl()) {
            int preludePasses = 0;
            if (titleCard != null) {
                preludePasses = titleCard.levelObjectPreludePassesAtRelease();
                for (int i = 0; i < preludePasses; i++) {
                    levelManager.suppressGlobalOscillationForTitleCardPass();
                    if (titleCard.shouldRunPlayerPreludeAtRelease()) {
                        playerPrelude.run();
                    }
                    levelManager.updateObjectPositionsWithoutTouches();
                }
            }
            if (!preparedByFrameStep) {
                preparePhase.accept(PlcLifecyclePhase.LEVEL_TITLE_CARD);
            }
            // GM_Level clears Level_frame_counter on every non-demo level entry
            // (docs/s2disasm/s2.asm:4771-4773), and none of the pre-Level_MainLoop
            // object passes advance it -- the only increment is Level_MainLoop's
            // own addq at s2.asm:5092. The counter lives in CrossResetRAM, so
            // Level_ClrRam does not clear it; this store is the sole reset and it
            // runs for a special-stage return exactly as for a fresh entry.
            spriteManager.setFrameCounter(0);
            destination.completeRelease(
                    levelManager, exitTitleCard, preludePasses > 0);
            releaseResult.accept(LevelFrameResult.SETUP_ONLY);
            return true;
        }

        beginAudioFrame.run();
        if (titleCard.shouldRunPlayerPhysics()) {
            levelManager.suppressGlobalOscillationForTitleCardPass();
            spriteManager.publishHeldInputForLevelEvents(input);
            LevelFrameStep.execute(LevelFrameContext.from(gameplayMode), frame,
                    PlcLifecyclePhase.LEVEL_TITLE_CARD, levelManager, camera,
                    () -> spriteManager.update(input), wrapper);
            preparedByFrameStep = true;
        } else {
            if (titleCard.shouldRunLevelObjectsDuringLockedPhase()) {
                levelManager.suppressGlobalOscillationForTitleCardPass();
                levelManager.updateObjectPositions();
                camera.updatePosition(true);
            } else if (titleCard.shouldAdvanceVblankClockDuringLockedPhase()) {
                levelManager.advanceTitleCardVblankOnly();
            } else {
                camera.updatePosition(true);
            }
        }
        advanceAudioFrame.run();
        if (!preparedByFrameStep) {
            preparePhase.accept(PlcLifecyclePhase.LEVEL_TITLE_CARD);
        }
        return false;
    }
}
