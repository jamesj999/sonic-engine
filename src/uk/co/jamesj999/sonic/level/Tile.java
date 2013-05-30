package uk.co.jamesj999.sonic.level;

public class Tile {
	byte[] heights;

	public Tile(byte[] heights) {
		if (heights.length == 16) {
			this.heights = heights;
		} else {
			System.out.println("NO FUCKING WAY MAN");
		}
	}

	public byte getHeightAt(byte y) {
		return heights[y];
	}
}
