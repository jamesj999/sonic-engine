package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.awt.event.KeyEvent;
import java.util.Collection;

/**
 * Created by Jamesjohnstone on 09/04/15. 9 Years later he re-enters the file; curious but weary, in hope of answers
 * to a bygone era of his life
 */
public class SpriteCollisionManager {
    private static SpriteCollisionManager spriteCollisionManager;

    SonicConfigurationService configService = SonicConfigurationService.getInstance();
    SpriteManager spriteManager = SpriteManager.getInstance();

    /**
     * Calls all sprites to recalculate their positions.
     */
    public void update(InputHandler handler) {
        Collection<Sprite> sprites = spriteManager.getAllSprites();
        // Firstly calculate key presses:
        boolean up = handler.isKeyDown(configService
                .getInt(SonicConfiguration.UP));
        boolean down = handler.isKeyDown(configService
                .getInt(SonicConfiguration.DOWN));
        boolean left = handler.isKeyDown(configService
                .getInt(SonicConfiguration.LEFT));
        boolean right = handler.isKeyDown(configService
                .getInt(SonicConfiguration.RIGHT));
        boolean space = handler.isKeyDown(configService
                .getInt(SonicConfiguration.JUMP));
        boolean testButton = handler.isKeyDown(configService
                .getInt(SonicConfiguration.TEST));

        // Iterate our Sprites:
        for (Sprite sprite : sprites) {
            // Check we're dealing with a playable sprite:
            if (sprite instanceof AbstractPlayableSprite) {
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
                ((AbstractPlayableSprite) sprite).endOfTick();
            }
        }
    }

    public synchronized static SpriteCollisionManager getInstance() {
        if(spriteCollisionManager == null) {
            spriteCollisionManager = new SpriteCollisionManager();
        }
        return spriteCollisionManager;
    }
}
