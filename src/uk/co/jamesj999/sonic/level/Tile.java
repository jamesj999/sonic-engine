package uk.co.jamesj999.sonic.level;

public class Tile {
	byte[] heights;
	byte angle;

	public Tile(byte[] heights, byte angle) {
		if (heights.length == 16) {
			this.heights = heights;
		} else {
			System.out.println("NO FUCKING WAY MAN");
		}
		this.angle = angle;
	}

	/**
	 * Shorthand to create a tile without having to create the height map
	 * separately.
	 */
	public Tile(int a, int b, int c, int d, int e, int f, int g, int h, int i,
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
}
