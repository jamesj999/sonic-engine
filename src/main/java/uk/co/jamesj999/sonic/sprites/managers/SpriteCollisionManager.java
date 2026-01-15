package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.awt.event.KeyEvent;
import java.util.Collection;

/**
 * Created by Jamesjohnstone on 09/04/15. 9 Years later he re-enters the file;
 * curious but weary, in hope of answers
 * to a bygone era of his life
 */
public class SpriteCollisionManager {
        private static SpriteCollisionManager spriteCollisionManager;

        SonicConfigurationService configService = SonicConfigurationService.getInstance();
        SpriteManager spriteManager = SpriteManager.getInstance();
        LevelManager levelManager = LevelManager.getInstance();

        private int upKey;
        private int downKey;
        private int leftKey;
        private int rightKey;
        private int jumpKey;
        private int testKey;
        private int debugModeKey;
        private int frameCounter;

        private SpriteCollisionManager() {
                upKey = configService.getInt(SonicConfiguration.UP);
                downKey = configService.getInt(SonicConfiguration.DOWN);
                leftKey = configService.getInt(SonicConfiguration.LEFT);
                rightKey = configService.getInt(SonicConfiguration.RIGHT);
                jumpKey = configService.getInt(SonicConfiguration.JUMP);
                testKey = configService.getInt(SonicConfiguration.TEST);
                debugModeKey = configService.getInt(SonicConfiguration.DEBUG_MODE_KEY);
        }

        /**
         * Calls all sprites to recalculate their positions.
         */
        public void update(InputHandler handler) {
                frameCounter++;
                Collection<Sprite> sprites = spriteManager.getAllSprites();
                // Firstly calculate key presses:
                boolean up = handler.isKeyDown(upKey);
                boolean down = handler.isKeyDown(downKey);
                boolean left = handler.isKeyDown(leftKey);
                boolean right = handler.isKeyDown(rightKey);
                boolean space = handler.isKeyDown(jumpKey);
                boolean testButton = handler.isKeyDown(testKey);

                // Check for debug mode toggle (edge-triggered: only on key press, not hold)
                boolean debugModePressed = handler.isKeyPressed(debugModeKey);

                // Iterate our Sprites:
                for (Sprite sprite : sprites) {
                        // Check we're dealing with a playable sprite:
                        if (sprite instanceof AbstractPlayableSprite) {
                                AbstractPlayableSprite playable = (AbstractPlayableSprite) sprite;

                                // Toggle debug mode when the debug key is pressed
                                if (debugModePressed) {
                                        playable.toggleDebugMode();
                                }

                                // Check if player is being forced to walk right (end-of-act)
                                boolean controlLocked = playable.isControlLocked();
                                boolean effectiveRight = right || playable.isForceInputRight() || controlLocked;
                                boolean effectiveLeft = !controlLocked && left && !playable.isForceInputRight();
                                boolean effectiveUp = controlLocked ? false : up;
                                boolean effectiveDown = controlLocked ? false : down;
                                boolean effectiveJump = controlLocked ? false : space;
                                boolean effectiveTest = controlLocked ? false : testButton;

                                levelManager.applyPlaneSwitchers(playable);
                                playable.getMovementManager()
                                                .handleMovement(effectiveUp, effectiveDown, effectiveLeft,
                                                                effectiveRight, effectiveJump, effectiveTest);
                                /*
                                 * Idea: We can put object collision handling here - although
                                 * the X and Y have been set for the sprite, we still have the
                                 * latest position in the history arrays so we can revert if
                                 * collisions are found before moving to display part of the
                                 * tick.
                                 * Update: lol, we never did that.
                                 */
                                playable.getAnimationManager().update(frameCounter);
                                playable.tickStatus();
                                playable.endOfTick();
                        }
                }
        }

        /**
         * Updates sprite physics without processing player input.
         * Used during title card to let player settle onto ground while controls are
         * locked.
         * This matches original Sonic 2 behavior where physics runs continuously.
         */
        public void updateWithoutInput() {
                frameCounter++;
                Collection<Sprite> sprites = spriteManager.getAllSprites();

                for (Sprite sprite : sprites) {
                        if (sprite instanceof AbstractPlayableSprite) {
                                AbstractPlayableSprite playable = (AbstractPlayableSprite) sprite;

                                levelManager.applyPlaneSwitchers(playable);
                                // Run physics with no input - gravity and collision still apply
                                playable.getMovementManager()
                                                .handleMovement(false, false, false, false, false, false);
                                playable.getAnimationManager().update(frameCounter);
                                playable.tickStatus();
                                playable.endOfTick();
                        }
                }
        }

        public synchronized static SpriteCollisionManager getInstance() {
                if (spriteCollisionManager == null) {
                        spriteCollisionManager = new SpriteCollisionManager();
                }
                return spriteCollisionManager;
        }
}
