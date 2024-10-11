package uk.co.jamesj999.sonic.level;

import org.apache.commons.lang3.ArrayUtils;

public class SolidTile {
	public static final int TILE_SIZE_IN_ROM = 16;

	public final byte[] heights;
	public final byte[] widths;
	private final byte angle;

	private int index = 0;

	public SolidTile(int index, byte[] heights, byte[] widths, byte angle) {
		this.index = index;

		if (heights.length != TILE_SIZE_IN_ROM) {
			throw new IllegalArgumentException("SolidTile size does not match tile size in ROM");
		}
		this.heights = heights;
		this.widths = widths;

		// TODO add angle recalculations
		this.angle = angle;

	}

	public byte getHeightAt(byte x) {
		return heights[x];
	}

	public byte getWidthAt(byte y) {
		return 0;
	}

	public byte getAngle() {
		return angle;
	}

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }
}
