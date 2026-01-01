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
        private int frameCounter;

        private SpriteCollisionManager() {
                upKey = configService.getInt(SonicConfiguration.UP);
                downKey = configService.getInt(SonicConfiguration.DOWN);
                leftKey = configService.getInt(SonicConfiguration.LEFT);
                rightKey = configService.getInt(SonicConfiguration.RIGHT);
                jumpKey = configService.getInt(SonicConfiguration.JUMP);
                testKey = configService.getInt(SonicConfiguration.TEST);
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

                // Iterate our Sprites:
                for (Sprite sprite : sprites) {
                        // Check we're dealing with a playable sprite:
                        if (sprite instanceof AbstractPlayableSprite) {
                                levelManager.applyPlaneSwitchers((AbstractPlayableSprite) sprite);
                                ((AbstractPlayableSprite) sprite).getMovementManager()
                                                .handleMovement(up, down, left, right, space, testButton);
                                /*
                                 * Idea: We can put object collision handling here - although
                                 * the X and Y have been set for the sprite, we still have the
                                 * latest position in the history arrays so we can revert if
                                 * collisions are found before moving to display part of the
                                 * tick.
                                 * Update: lol, we never did that.
                                 */
                                ((AbstractPlayableSprite) sprite).getAnimationManager().update(frameCounter);
                                ((AbstractPlayableSprite) sprite).endOfTick();
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
