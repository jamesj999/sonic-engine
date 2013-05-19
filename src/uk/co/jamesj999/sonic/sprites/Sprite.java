package uk.co.jamesj999.sonic.sprites;

import java.awt.Graphics;

/**
 * All Sprites on the screen will implement this interface.
 * @author james
 *
 */
public interface Sprite {
	
	public String getCode();
	public void setCode(String code);
	
	public Graphics getGraphics();
}
