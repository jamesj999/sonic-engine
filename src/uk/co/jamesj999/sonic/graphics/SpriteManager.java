package uk.co.jamesj999.sonic.graphics;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.JFrame;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
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

	private final BufferedImage spriteBuffer = new BufferedImage(
			configService.getInt(SonicConfiguration.SCREEN_WIDTH),
			configService.getInt(SonicConfiguration.SCREEN_HEIGHT),
			BufferedImage.TYPE_INT_RGB);

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
			draw(entry.getValue());
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

	private void draw(Sprite sprite) {
		Graphics graphics = sprite.getGraphics();
	}

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
