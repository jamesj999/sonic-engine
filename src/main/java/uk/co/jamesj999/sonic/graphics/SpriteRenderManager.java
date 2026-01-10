package uk.co.jamesj999.sonic.graphics;

import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.graphics.RenderPriority;

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

    public void drawLowPriority() {
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            drawPriorityBucket(bucket, false);
        }
    }

    public void drawHighPriority() {
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            drawPriorityBucket(bucket, true);
        }
    }

    public void drawPriorityBucket(int bucket, boolean highPriority) {
        Collection<Sprite> sprites = spriteManager.getAllSprites();
        int targetBucket = RenderPriority.clamp(bucket);
        for (Sprite sprite : sprites) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                int spriteBucket = RenderPriority.clamp(playable.getPriorityBucket());
                if (playable.isHighPriority() == highPriority && spriteBucket == targetBucket) {
                    sprite.draw();
                }
                continue;
            }
            if (highPriority && targetBucket == RenderPriority.MIN) {
                sprite.draw();
            }
        }
    }

    public static synchronized SpriteRenderManager getInstance() {
        if(spriteRenderManager == null) {
            spriteRenderManager = new SpriteRenderManager();
        }
        return spriteRenderManager;
    }

}
