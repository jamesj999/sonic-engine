package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.level.render.SpritePieceRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.render.PlayerSpriteRenderer;

import java.util.List;
import java.util.logging.Logger;

/**
 * End of level signpost (Object 0D).
 * <p>
 * Behavior from s2.asm:
 * <ol>
 * <li>Wait for player to pass (Obj0D_Main)</li>
 * <li>On pass: lock screen, play signpost sound, start spinning
 * (Obj0D_Main_State2)</li>
 * <li>Spawn sparkle effects while spinning</li>
 * <li>When spin completes, lock player controls (Obj0D_Main_State3)</li>
 * <li>Player walks off-screen automatically</li>
 * <li>Spawn results screen (Obj3A), play end-level jingle</li>
 * </ol>
 */
public class SignpostObjectInstance extends BoxObjectInstance {
    private static final Logger LOGGER = Logger.getLogger(SignpostObjectInstance.class.getName());

    // Routine states (matching ROM)
    private static final int STATE_IDLE = 0;
    private static final int STATE_SPINNING = 2;
    private static final int STATE_WALK_OFF = 4;

    // Mapping frames (verified via art viewer)
    // 0=Spin right, 1=Tails, 2=Sonic, 3=Eggman, 4=Side-on, 5=Spin left
    private static final int FRAME_SPIN_RIGHT = 0;
    private static final int FRAME_TAILS = 1;
    private static final int FRAME_SONIC = 2;
    private static final int FRAME_EGGMAN = 3;
    private static final int FRAME_SIDE_ON = 4;
    private static final int FRAME_SPIN_LEFT = 5;

    // Spin timing
    private static final int SPIN_FRAME_DELAY = 2;
    private static final int SPIN_CYCLES = 3;
    private static final int WALK_OFF_OFFSET = 0x128;

    // Spinning animation frame sequence (Ani_obj0D mapped to current frames)
    private static final int[] SPIN_FRAMES = {
            FRAME_EGGMAN, FRAME_SPIN_RIGHT, FRAME_SIDE_ON, FRAME_SPIN_LEFT,
            FRAME_TAILS, FRAME_SPIN_RIGHT, FRAME_SIDE_ON, FRAME_SPIN_LEFT,
            FRAME_SONIC, FRAME_SPIN_RIGHT, FRAME_SIDE_ON, FRAME_SPIN_LEFT
    };

    // Sparkle effect timing and positions (from s2.asm Obj0D_RingSparklePositions)
    private static final int SPARKLE_SPAWN_DELAY = 11;
    private static final int[][] SPARKLE_POSITIONS = {
            { -24, -16 }, { 8, 8 }, { -16, 0 }, { 24, -8 },
            { 0, -8 }, { 16, 0 }, { -24, 8 }, { 24, 16 }
    };

    private int routineState = STATE_IDLE;
    private int mappingFrame = FRAME_EGGMAN;
    private int animTimer = 0;
    private int spinFrameIndex = 0;
    private int spinCycleCount = 0;
    private int sparkleTimer = 0;
    private int sparkleIndex = 0;

    private short groundLockY = 0;
    private boolean resultsSpawned = false;

    public SignpostObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 24, 40, 0.3f, 0.8f, 0.3f, false);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        switch (routineState) {
            case STATE_IDLE -> checkPlayerPass(player);
            case STATE_SPINNING -> {
                updateSpinning();
                clampPlayerRight(player);
            }
            case STATE_WALK_OFF -> {
                updateWalkOff(player);
                clampPlayerRight(player);
            }
        }
    }

    private void checkPlayerPass(AbstractPlayableSprite player) {
        int signpostX = spawn.x();
        int playerX = player.getX();

        if (playerX >= signpostX - 0x20) {
            activateSignpost(player);
        }
    }

    private void activateSignpost(AbstractPlayableSprite player) {
        LOGGER.info("Signpost activated at X=" + spawn.x());

        try {
            AudioManager.getInstance().playSfx(Sonic2Constants.SndID_Signpost);
        } catch (Exception e) {
            LOGGER.warning("Failed to play signpost sound: " + e.getMessage());
        }

        lockCamera();

        routineState = STATE_SPINNING;
        spinFrameIndex = 0;
        spinCycleCount = 0;
        animTimer = 0;
        sparkleTimer = 0;
        sparkleIndex = 0;
        mappingFrame = SPIN_FRAMES[0];
    }

    private void lockCamera() {
        Camera camera = Camera.getInstance();
        if (camera != null) {
            short camX = camera.getX();
            camera.setMinX(camX);
            camera.setMaxX(camX);
            LOGGER.fine("Camera locked at X=" + camX);
        }
    }

    private void updateSpinning() {
        // Update animation frame
        animTimer++;
        if (animTimer >= SPIN_FRAME_DELAY) {
            animTimer = 0;
            spinFrameIndex++;

            if (spinFrameIndex >= SPIN_FRAMES.length) {
                spinFrameIndex = 0;
                spinCycleCount++;

                if (spinCycleCount >= SPIN_CYCLES) {
                    mappingFrame = FRAME_SONIC;
                    routineState = STATE_WALK_OFF;
                    LOGGER.fine("Signpost spin complete, entering walk-off state");
                    return;
                }
            }

            mappingFrame = SPIN_FRAMES[spinFrameIndex];
        }

        // Spawn sparkle effects
        spawnSparkleIfReady();
    }

    private void spawnSparkleIfReady() {
        sparkleTimer++;
        if (sparkleTimer >= SPARKLE_SPAWN_DELAY) {
            sparkleTimer = 0;

            int[] offset = SPARKLE_POSITIONS[sparkleIndex];
            int sparkleX = spawn.x() + offset[0];
            int sparkleY = spawn.y() + offset[1];

            SignpostSparkleObjectInstance sparkle = new SignpostSparkleObjectInstance(sparkleX, sparkleY);
            ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
            if (objectManager != null) {
                objectManager.addDynamicObject(sparkle);
            }

            // Cycle through positions (ROM: addq.b #2, andi.b #$E => 0,2,4,6,0,2...)
            sparkleIndex = (sparkleIndex + 1) % SPARKLE_POSITIONS.length;
        }
    }

    private void updateWalkOff(AbstractPlayableSprite player) {
        // Only need to set control lock once (ROM behavior)
        if (groundLockY == 0) {
            groundLockY = 1; // Just use as "initialized" flag
            player.setForceInputRight(true);
            player.setControlLocked(true);
            LOGGER.fine("Walk-off initiated: forceInputRight=true, controlLocked=true");
        }

        // Check for off-screen transition
        Camera camera = Camera.getInstance();
        if (camera != null && !resultsSpawned) {
            int offScreenX = resolveRightLimit(camera);
            if (resolvePlayerRenderRight(player) > offScreenX) {
                spawnResultsScreen(player);
            }
        }
    }

    private void clampPlayerRight(AbstractPlayableSprite player) {
        Camera camera = Camera.getInstance();
        if (camera == null) {
            return;
        }
        int maxX = resolveRightLimit(camera);
        int renderRight = resolvePlayerRenderRight(player);
        if (renderRight > maxX) {
            int delta = renderRight - maxX;
            int clampedLeft = player.getX() - delta;
            if (clampedLeft < 0) {
                clampedLeft = 0;
            }
            player.setX((short) clampedLeft);
            player.setXSpeed((short) 0);
            player.setGSpeed((short) 0);
        }
    }

    private int resolveRightLimit(Camera camera) {
        return camera.getX() + camera.getWidth() + WALK_OFF_OFFSET;
    }

    private int resolvePlayerRenderRight(AbstractPlayableSprite player) {
        PlayerSpriteRenderer renderer = player.getSpriteRenderer();
        if (renderer != null) {
            SpritePieceRenderer.FrameBounds bounds = renderer.getFrameBounds(
                    player.getMappingFrame(),
                    player.getRenderHFlip(),
                    player.getRenderVFlip());
            if (bounds.width() > 0) {
                return player.getRenderCentreX() + bounds.maxX();
            }
        }
        return player.getRightX();
    }

    private void spawnResultsScreen(AbstractPlayableSprite player) {
        resultsSpawned = true;
        LOGGER.info("Player off-screen, triggering end of act sequence");

        try {
            AudioManager.getInstance().playMusic(Sonic2Constants.MusID_StageClear);
        } catch (Exception e) {
            LOGGER.warning("Failed to play stage clear music: " + e.getMessage());
        }

        // Calculate elapsed time (simple approximation: frames / 60)
        LevelManager levelManager = LevelManager.getInstance();
        int elapsedSeconds = levelManager.getCurrentAct() > 0 ? 45 : 30; // Placeholder - TODO: implement proper timer
        int ringCount = player.getRingCount();
        int actNumber = levelManager.getCurrentAct() + 1; // 1-indexed for display
        boolean allRingsCollected = levelManager != null && levelManager.areAllRingsCollected();

        // Spawn the results screen
        ResultsScreenObjectInstance resultsScreen = new ResultsScreenObjectInstance(
                elapsedSeconds, ringCount, actNumber, allRingsCollected);
        ObjectManager objectManager = levelManager.getObjectManager();
        if (objectManager != null) {
            objectManager.addDynamicObject(resultsScreen);
            LOGGER.info("Results screen spawned");
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getSignpostRenderer();
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    public int getCenterX() {
        return spawn.x();
    }

    public int getCenterY() {
        return spawn.y();
    }
}
