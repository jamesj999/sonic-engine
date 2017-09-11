package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.sprites.Sprite;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages collection of available sprites to be provided to renderer and collision manager.
 *
 * @author james
 */
public class SpriteManager {
   private final SonicConfigurationService configService = SonicConfigurationService
           .getInstance();

   private static SpriteManager spriteManager;

   private Map<String, Sprite> sprites;

   private SpriteManager() {
      sprites = new HashMap<String, Sprite>();
   }

   /**
    * Adds the given sprite to the SpriteManager. Returns true if we have
    * overwritten a sprite, false if we are creating a new one.
    *
    * @param sprite
    * @return
    */
   public boolean addSprite(Sprite sprite) {
      return (sprites.put(sprite.getCode(), sprite) != null);
   }

   /**
    * Removes the Sprite with provided code from the SpriteManager. Returns
    * true if a Sprite was removed and false if none could be found.
    *
    * @param code
    * @return
    */
   public boolean removeSprite(String code) {
      return removeSprite(getSprite(code));
   }

   public Collection<Sprite> getAllSprites() {
      return sprites.values();
   }

   public Sprite getSprite(String code) {
      return sprites.get(code);
   }

   private boolean removeSprite(Sprite sprite) {
      return (sprites.remove(sprite) != null);
   }

   public synchronized static SpriteManager getInstance() {
      if (spriteManager == null) {
         spriteManager = new SpriteManager();
      }
      return spriteManager;
   }
}
