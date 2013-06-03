package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.Tile;

public interface Sensor {
	public boolean isCollide();

	public short getX();

	public short getY();

	public void setX(short x);

	public void setY(short y);

	public byte getXOffset();

	public byte getYOffset();

	public void setXOffset(byte xOffset);

	public void setYOffset(byte yOffset);
	
	public Tile getTile();
	
	/**
	 * Updates x to be x + xOffset
	 * @param x
	 */
	public void updateX(short x);
	
	/**
	 * Updates y to be y + yOffset
	 * @param y
	 */
	public void updateY(short y);
	
	public Tile getTileAbove();
}
