package uk.co.jamesj999.sonic.graphics;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.JFrame;

import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.sprites.Sprite;

/**
 * Manages drawing of all sprites on the screen. Acts as a Singleton.
 * 
 * @author james
 * 
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

	/**
	 * Draws all sprites to the provided JFrame.
	 */
	public void draw(JFrame frame) {
		for (Entry<String, Sprite> entry : sprites.entrySet()) {
			Sprite sprite = entry.getValue();
			frame.getGraphics().drawImage(sprite.draw(), sprite.getX(), sprite.getY(), sprite.getWidth(), sprite.getHeight(), frame);
		}
	}

	/**
	 * Calls all sprites to recalculate their positions.
	 * 
	 * @param frame
	 */
	public void update() {
		for (Entry<String, Sprite> entry : sprites.entrySet()) {
			update(entry.getValue());
		}
	}

	// private void draw(Sprite sprite) {
	// sprite.draw(spriteBuffer);
	// }

	private void update(Sprite sprite) {
		//
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
