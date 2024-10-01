package uk.co.jamesj999.sonic.level;

import org.apache.commons.lang3.ArrayUtils;

public class Tile {
	public static final int TILE_SIZE_IN_ROM = 32;

	public byte[] heights;
	public byte[] widths;
	private byte angle;
	private boolean jumpThrough;
	private CollisionMode collisionMode;

	public Tile(byte[] heights, byte angle) {
		this(heights, angle, false, false);
	}

	/**
	 * To be called from Level loading ROM logic.
	 * @param buffer
	 */
	public Tile(byte[] buffer) {
		if (buffer.length != TILE_SIZE_IN_ROM) {
			throw new IllegalArgumentException("Tile size does not match tile size in ROM");
		}
		System.out.println();
		for(byte value : buffer) {
			System.out.print((value & 0xFF) + ",");
		}
		System.out.println();

		// Extract 16 bytes into two arrays of nibbles and see if they look anything like tile definitions (they don't):
		int[] starts = new int[16];
		int[] stops = new int[16];
		for(byte i = 0; i < 16; i++) {
			starts[i] = ((buffer[i] & 0xFF) & 0x0F);
			stops[i] = (((buffer[i] & 0xFF) >> 4) & 0X0F);
		}

		System.out.println("Starts:");
		for(int i = 0; i < 16; i++) {
			System.out.print(starts[i] + ",");
		}

		System.out.println("\nStops:");
		for(int i = 0; i < 16; i++) {
			System.out.print(stops[i] + ",");
		}

	}

	private Tile(byte[] heights, byte angle, boolean flipX, boolean flipY) {
		if (heights.length == 16) {
			this.heights = heights;
			if (flipX) {
				ArrayUtils.reverse(heights);
			}
			if(flipY) {
				reverseY();
			}
			// TODO add angle recalculations
		} else {
			System.out.println("Error flipping tile...");
		}
		this.angle = angle;
	}

	/**
	 * Shorthand to create a tile without having to create the height map
	 * separately.
	 */
	public Tile(int a, int b, int c, int d, int e, int f, int g, int h, int i,
			int j, int k, int l, int m, int n, int o, int p, byte angle, boolean jumpThrough) {
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
		this.jumpThrough = jumpThrough;
	}

	public byte getHeightAt(byte y) {
		return heights[y];
	}

	public byte getAngle() {
		return angle;
	}

	public Tile copy(boolean flipX, boolean flipY) {
		return new Tile(heights, angle, flipX, flipY);
	}
	
	private void reverseY() {
		for(int i = 0; i < heights.length; i++) {
			heights[i] = (byte) (16 - heights[i]);
		}
	}
	
	public boolean getJumpThrough() {
		return jumpThrough;
	}

}
