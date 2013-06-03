package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.Tile;

public class TerrainSensor implements Sensor {
	LevelManager levelManager = LevelManager.getInstance();
	private byte xOffset;
	private byte yOffset;

	private short x;
	private short y;

	/**
	 * Provided offsets must be within the range of a byte. (They will be casted
	 * to byte anyway)
	 * 
	 * @param xOffset
	 * @param yOffset
	 */
	public TerrainSensor(int xOffset, int yOffset) {
		this.xOffset = (byte) xOffset;
		this.yOffset = (byte) yOffset;
	}

	@Override
	public boolean isCollide() {
		// check collisions with collision manager on x/y.
		return false;
	}

	public short getTerrainHeight() {
		return 0;
	}

	@Override
	public short getX() {
		return x;
	}

	@Override
	public short getY() {
		return y;
	}

	@Override
	public void setX(short x) {
		this.x = x;
	}

	@Override
	public void setY(short y) {
		this.y = y;
	}

	@Override
	public byte getXOffset() {
		return xOffset;
	}

	@Override
	public byte getYOffset() {
		return yOffset;
	}

	@Override
	public void setXOffset(byte xOffset) {
		this.xOffset = xOffset;
	}

	@Override
	public void setYOffset(byte yOffset) {
		this.yOffset = yOffset;
	}

	@Override
	public void updateX(short x) {
		this.x = (short) (x + xOffset);
	}

	@Override
	public void updateY(short y) {
		this.y = (short) (y + yOffset);
	}

	@Override
	public Tile getTile() {
		return levelManager.getLevel().getTileAt(x, y);
	}

	@Override
	public Tile getTileAbove() {
		return levelManager.getLevel().getTileAt(x, (short) (y + 16));
	}

}
