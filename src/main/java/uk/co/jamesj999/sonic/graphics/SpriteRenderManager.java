package uk.co.jamesj999.sonic.graphics;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.physics.SensorLine;
import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;
import java.util.Map;

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
            if (sprite instanceof AbstractPlayableSprite) {
                // TODO temp debug stuff, remove
                for (SensorLine sensorLine : ((AbstractSprite) sprite)
                        .getTerrainSensorLines()) {
                    sensorLine.draw();
                }
                SensorLine wallSensorLine = ((AbstractPlayableSprite) sprite)
                        .getWallSensorLine();
                if (wallSensorLine != null) {
                    ((AbstractPlayableSprite) sprite).getWallSensorLine()
                            .draw();
                }
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
