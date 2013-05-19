package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.sprites.AbstractSprite;

public abstract class AbstractPlayableSprite extends AbstractSprite {
	protected AbstractPlayableSprite(String code, int x, int y) {
		super(code, x, y);
		
	}
	
	public abstract void leftPressed();

	public abstract void downPressed();

	public abstract void upPressed();

	public abstract void rightPressed();
}
