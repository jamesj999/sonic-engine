package uk.co.jamesj999.sonic.sprites;



/**
 * All Sprites on the screen will implement this interface.
 * 
 * @author james
 * 
 */
public interface Sprite {

	public String getCode();

	public void setCode(String code);

	public void draw();
	
	public short getCentreX();
	public short getCentreY();
	
	public short getX();
	public void setX(short x);
	
	public short getY();
	public void setY(short y);
	
	public int getHeight();
	public void setHeight(int height);
	
	public int getWidth();
	public void setWidth(int width);
	
	public void move(short xSpeed, short ySpeed);
}
