package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.sonic1.dataselect.S1DataSelectImageGenerator;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.sprites.managers.SpriteManager;
import java.util.function.Supplier;
import java.util.function.BiConsumer;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Comparator;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_B;

/**
 * Owns the developer keyboard shortcuts that jump the loop between modes.
 *
 * <p>None of this runs in a shipped playthrough: every entry point here is
 * reached only from a {@code DEBUG_VIEW_ENABLED} key read in {@link GameLoop}.
 * Keeping it out of the loop itself stops debug-only mode changes from reading
 * like part of the ROM-aligned mode sequencing around them.
 */
final class GameLoopDebugShortcuts {
    private static final Logger LOGGER = Logger.getLogger(GameLoopDebugShortcuts.class.getName());

    private final Supplier<GameMode> currentMode;
    private final Supplier<SpecialStageProvider> specialStageProvider;
    private final BiConsumer<Boolean, Integer> enterResults;

    GameLoopDebugShortcuts(Supplier<GameMode> currentMode,
                           Supplier<SpecialStageProvider> specialStageProvider,
                           BiConsumer<Boolean, Integer> enterResults) {
        this.currentMode = currentMode;
        this.specialStageProvider = specialStageProvider;
        this.enterResults = enterResults;
    }

    static BonusStageType resolveBonusStageDebugShortcut(InputHandler inputHandler) {
        if (inputHandler == null || !inputHandler.isKeyPressed(GLFW_KEY_B)) {
            return BonusStageType.NONE;
        }

        boolean shift = inputHandler.isShiftDown();
        boolean control = inputHandler.isControlDown();
        boolean alt = inputHandler.isAltDown();
        int activeModifierCount = (shift ? 1 : 0) + (control ? 1 : 0) + (alt ? 1 : 0);
        if (activeModifierCount != 1) {
            return BonusStageType.NONE;
        }
        if (shift) {
            return BonusStageType.GUMBALL;
        }
        if (control) {
            return BonusStageType.GLOWING_SPHERE;
        }
        return BonusStageType.SLOT_MACHINE;
    }

    /**
     * Debug function: Teleports the player to the furthest right checkpoint in the level.
     * Only works in LEVEL mode (END key is used for special stage completion in special stage mode).
     */
    void teleportToLastCheckpoint(LevelManager levelManager, SpriteManager spriteManager,
                                  Camera camera, SonicConfigurationService configService,
                                  String mainCode) {
        Level level = levelManager.getCurrentLevel();
        if (level == null) {
            return;
        }

        // Find the furthest right checkpoint (game-agnostic)
        int checkpointId = GameServices.module().getCheckpointObjectId();
        if (checkpointId == 0) {
            LOGGER.info("DEBUG: Current game has no checkpoint object ID configured");
            return;
        }
        ObjectSpawn lastCheckpoint = level.getObjects().stream()
            .filter(spawn -> spawn.objectId() == checkpointId)
            .max(Comparator.comparingInt(ObjectSpawn::x))
            .orElse(null);

        if (lastCheckpoint != null) {
            int checkpointX = lastCheckpoint.x();
            int checkpointY = lastCheckpoint.y();

            var sprite = spriteManager.getSprite(mainCode);
            if (sprite instanceof AbstractPlayableSprite player) {
                // Teleport player to checkpoint position
                player.setX((short) checkpointX);
                player.setY((short) checkpointY);
                player.setXSpeed((short) 0);
                player.setYSpeed((short) 0);
                player.setGSpeed((short) 0);
                player.setAir(false);
                player.setRolling(false);

                // Move camera to center on player (prevents pit death from camera mismatch)
                int screenWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
                int screenHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
                int cameraX = checkpointX - (screenWidth / 2);
                int cameraY = checkpointY - (screenHeight / 2);

                // Clamp camera to reasonable range (floor at 0)
                cameraX = Math.max(0, cameraX);
                cameraY = Math.max(0, cameraY);

                camera.setX((short) cameraX);
                camera.setY((short) cameraY);

                LOGGER.info("DEBUG: Teleported to checkpoint at (" + checkpointX + ", " + checkpointY +
                    "), camera at (" + cameraX + ", " + cameraY + ")");
            }
        } else {
            LOGGER.info("DEBUG: No checkpoints found in this level");
        }
    }

    void logCurrentPreviewCaptureOverride(Camera camera, LevelManager levelManager) {
        if (camera == null || levelManager == null) {
            return;
        }
        S1DataSelectImageGenerator.PreviewCapturePoint point =
                S1DataSelectImageGenerator.previewCapturePointFromCamera(
                        camera.getX(), camera.getY());
        LOGGER.info("DEBUG: Preview capture override for zone "
                + levelManager.getRomZoneId()
                + " -> new PreviewCapturePoint("
                + point.centreX()
                + ", "
                + point.centreY()
                + ")");
    }

    /**
     * Debug function: Immediately completes the special stage with emerald
     * collected.
     * Simulates successful completion with the ring requirement met.
     * Press END key during special stage to trigger.
     */
    void debugCompleteSpecialStageWithEmerald() {
        if (currentMode.get() != GameMode.SPECIAL_STAGE) {
            return;
        }

        SpecialStageProvider ssProvider = specialStageProvider.get();

        // Force emerald collection state
        ssProvider.setEmeraldCollected(true);

        // Get the ring count for this stage from the active provider
        int stageIndex = ssProvider.getCurrentStage();
        int ringRequirement = ssProvider.getDebugCompletionRingCount(stageIndex);

        LOGGER.info("DEBUG: Completing Special Stage " + (stageIndex + 1) +
                " with emerald (forcing " + ringRequirement + " rings)");

        // Enter results screen with emerald collected and simulated ring count
        enterResults.accept(true, ringRequirement);
    }

    /**
     * Debug method to fail special stage and go directly to results screen.
     * Press DEL key during special stage to trigger.
     */
    void debugFailSpecialStage() {
        if (currentMode.get() != GameMode.SPECIAL_STAGE) {
            return;
        }

        int stageIndex = specialStageProvider.get().getCurrentStage();
        int smallRingCount = 15; // A small amount of rings to show ring bonus tally

        LOGGER.info("DEBUG: Failing Special Stage " + (stageIndex + 1) +
                " (with " + smallRingCount + " rings)");

        // Enter results screen without emerald and with small ring count
        enterResults.accept(false, smallRingCount);
    }

}
