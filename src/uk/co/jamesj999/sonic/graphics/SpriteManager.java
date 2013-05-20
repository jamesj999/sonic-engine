package uk.co.jamesj999.sonic.graphics;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

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
	 * Draws all sprites to the provided JFrame. Takes a Graphics2D to avoid
	 * retrieving it from Canvas/Panel every time
	 */
	public void draw(Graphics graphics, Component target) {
		for (Entry<String, Sprite> entry : sprites.entrySet()) {
			Sprite sprite = entry.getValue();
			graphics.drawImage(sprite.draw(), sprite.getX(), sprite.getY(),
					sprite.getWidth(), sprite.getHeight(), target);
		}
	}

	/**
	 * Calls all sprites to recalculate their positions.
	 * 
	 * @param frame
	 */
	public void update(InputHandler handler) {
		// Firstly calculate key presses:
		boolean left = handler.isKeyDown(KeyEvent.VK_LEFT);
		boolean right = handler.isKeyDown(KeyEvent.VK_RIGHT);
		boolean up = handler.isKeyDown(KeyEvent.VK_UP);
		boolean down = handler.isKeyDown(KeyEvent.VK_DOWN);

		// Iterate our Sprites:
		for (Entry<String, Sprite> entry : sprites.entrySet()) {
			Sprite sprite = entry.getValue();
			// Check we're dealing with a playable sprite:
			if (sprite instanceof AbstractPlayableSprite) {
				((AbstractPlayableSprite) sprite).getMovementManager()
						.handleMovement(left, right);
			}
		}
	}

	/*
	 * These will probably be added back in when things get more complex:
	 */
	// private void draw(Sprite sprite) {
	// sprite.draw();
	// }

	// private void update(Sprite sprite) {
	// //
	// }

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
