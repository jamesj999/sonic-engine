package uk.co.jamesj999.sonic.level;

import org.apache.commons.lang3.ArrayUtils;

public class SolidTile {
	public static final int TILE_SIZE_IN_ROM = 16;

	private final byte[] heights;
	private byte[] widths;
	private final byte angle;
	private boolean jumpThrough;
	private CollisionMode collisionMode;

	public SolidTile(byte[] heights, byte angle) {
		this(heights, angle, false, false);
	}

	private SolidTile(byte[] heights, byte angle, boolean flipX, boolean flipY) {
		if (heights.length != TILE_SIZE_IN_ROM) {
			throw new IllegalArgumentException("SolidTile size does not match tile size in ROM");
		}
		this.heights = heights;
		if (flipX) {
			ArrayUtils.reverse(heights);
		}
		if(flipY) {
			reverseY();
		}
		// TODO set widths properly if required

		// TODO add angle recalculations
		this.angle = angle;
	}

	/**
	 * Shorthand to create a tile without having to create the height map
	 * separately.
	 */
	public SolidTile(int a, int b, int c, int d, int e, int f, int g, int h, int i,
					 int j, int k, int l, int m, int n, int o, int p, byte angle) {
		heights = new byte[16];
		heights[0] = (byte) a;
		heights[1] = (byte) b;
		heights[2] = (byte) c;
		heights[3] = (byte) d;
		heights[4] = (byte) e;
		heights[5] = (byte) f;
		heights[6] = (byte) g;
		heights[7] = (byte) h;
		heights[8] = (byte) i;
		heights[9] = (byte) j;
		heights[10] = (byte) k;
		heights[11] = (byte) l;
		heights[12] = (byte) m;
		heights[13] = (byte) n;
		heights[14] = (byte) o;
		heights[15] = (byte) p;
		this.angle = angle;
	}

	public byte getHeightAt(byte y) {
		return heights[y];
	}

	public byte getAngle() {
		return angle;
	}

	public SolidTile copy(boolean flipX, boolean flipY) {
		return new SolidTile(heights, angle, flipX, flipY);
	}

	private void reverseY() {
		for(int i = 0; i < heights.length; i++) {
			heights[i] = (byte) (16 - heights[i]);
		}
	}

}