package uk.co.jamesj999.sonic.graphics;

import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;

/**
 * Created by Jamesjohnstone on 09/04/15.
 */
public class SpriteRenderManager {
    private static SpriteRenderManager spriteRenderManager;

    private final SpriteManager spriteManager = SpriteManager.getInstance();

    public void draw() {
        Collection<Sprite> sprites = spriteManager.getAllSprites();
        for (Sprite sprite : sprites) {
            sprite.draw();
        }
    }

    public static synchronized SpriteRenderManager getInstance() {
        if(spriteRenderManager == null) {
            spriteRenderManager = new SpriteRenderManager();
        }
        return spriteRenderManager;
    }

}
