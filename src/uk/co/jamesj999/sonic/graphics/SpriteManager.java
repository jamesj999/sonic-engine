package uk.co.jamesj999.sonic.graphics;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import uk.co.jamesj999.sonic.sprites.Sprite;

/**
 * Manages drawing of all sprites on the screen. Acts as a Singleton.
 * 
 * @author james
 * 
 */
public class SpriteManager {

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

	/**
	 * Draws all sprites.
	 */
	public void drawSprites() {
		for (Entry<String, Sprite> entry : sprites.entrySet()) {
			draw(entry.getValue());
		}
	}

	private void draw(Sprite sprite) {
		// TODO some shit to draw the fucker
	}

	private Sprite getSprite(String code) {
		return sprites.get(code);
	}

	private boolean removeSprite(Sprite sprite) {
		return (sprites.remove(sprite) != null);
	}

	public static SpriteManager getInstance() {
		if (spriteManager == null) {
			spriteManager = new SpriteManager();
		}
		return spriteManager;
	}
}
