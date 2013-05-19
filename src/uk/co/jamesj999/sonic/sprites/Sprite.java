package uk.co.jamesj999.sonic.sprites;

import java.awt.image.BufferedImage;


/**
 * All Sprites on the screen will implement this interface.
 * 
 * @author james
 * 
 */
public interface Sprite {

	public String getCode();

	public void setCode(String code);

	public BufferedImage draw();
	
	public int getX();
	public void setX(int x);
	
	public int getY();
	public void setY(int y);
	
	public int getHeight();
	public void setHeight(int height);
	
	public int getWidth();
	public void setWidth(int width);
}
