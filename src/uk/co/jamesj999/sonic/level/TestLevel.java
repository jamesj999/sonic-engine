package uk.co.jamesj999.sonic.level;

public class TestLevel extends AbstractLevel {

	@Override
	protected void setupTiles() {
		Tile halfFlat = new Tile(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
				8, (byte) 0x00);
		Tile lowFlat = new Tile(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
				(byte) 0x00);
		Tile lowFlatToSlope = new Tile(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3,
				3, 3, 3, (byte) 0xFE);
		Tile lowFlatToSlope2 = new Tile(3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 5,
				5, 5, 5, (byte) 0xFC);
		Tile lowFlatToSlope3 = new Tile(5, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8,
				8, 8, 8, (byte) 0xFA);
		Tile lowFlatToSlope4 = new Tile(9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11,
				12, 12, 12, 12, 13, (byte) 0xF8);
		Tile lowFlatToSlope5 = new Tile(13, 13, 14, 14, 14, 15, 15, 15, 16, 16,
				16, 16, 16, 16, 16, 16, (byte) 0xF6);
		Tile lowSteepSlope = new Tile(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4,
				4, 4, (byte) 0xF8);
		Tile lowSteepSlope2 = new Tile(5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8,
				8, 8, 8, (byte) 0xF8);
		Tile lowSteepSlope3 = new Tile(9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11,
				11, 12, 12, 12, 12, (byte) 0xF8);
		Tile lowSteepSlope4 = new Tile(13, 13, 13, 13, 14, 14, 14, 14, 15, 15,
				15, 15, 16, 16, 16, 16, (byte) 0xF8);
		Tile halfHighPlatform = new Tile(16, 16, 16, 16, 16, 16, 16, 16, 0, 0,
				0, 0, 0, 0, 0, 0, (byte) 0xFF);
		Tile lowLessSteep = new Tile(1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3,
				3, 3, (byte) 0xFC);
		Tile lowLessSteep2 = new Tile(3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6,
				7, 7, (byte) 0xF8);

		Tile highSlopeDown = new Tile(16, 16, 15, 14, 14, 13, 12, 11, 11, 10,
				10, 9, 8, 8, 7, 7, (byte) 0x18);

		Tile highSlopeDown2 = new Tile(6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0, 0,
				0, 0, 0, (byte) 0x10);

		Tile full = new Tile(16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16,
				16, 16, 16, 16, (byte) 0x00);

		addTile(lowSteepSlope, 10, 1);
		addTile(lowSteepSlope2, 11, 1);
		addTile(lowSteepSlope3, 12, 1);
		addTile(lowSteepSlope4, 13, 1);

		drawRange(0, 9, 1, 1, lowFlat);

		addTile(lowSteepSlope, 14, 2);
		addTile(lowSteepSlope2, 15, 2);
		addTile(lowSteepSlope3, 16, 2);
		addTile(lowSteepSlope4, 17, 2);

		drawRange(14, 17, 1, 1, full);

		addTile(lowSteepSlope, 18, 3);
		addTile(lowSteepSlope2, 19, 3);
		addTile(lowSteepSlope3, 20, 3);
		addTile(lowSteepSlope4, 21, 3);

		drawRange(18, 21, 1, 2, full);

		addTile(lowSteepSlope, 22, 4);
		addTile(lowSteepSlope2, 23, 4);
		addTile(lowSteepSlope3, 24, 4);
		addTile(lowSteepSlope4, 25, 4);

		drawRange(22, 25, 1, 3, full);

		addTile(lowSteepSlope, 26, 5);
		addTile(lowSteepSlope2, 27, 5);
		addTile(lowSteepSlope3, 28, 5);
		addTile(lowSteepSlope4, 29, 5);

		drawRange(26, 29, 1, 4, full);

		addTile(lowSteepSlope, 30, 6);
		addTile(lowSteepSlope2, 31, 6);
		addTile(lowSteepSlope3, 32, 6);
		addTile(lowSteepSlope4, 33, 6);

		drawRange(30, 33, 1, 5, full);

		drawRange(34, 40, 1, 1, lowFlat);

		addTile(lowFlatToSlope, 41, 1);
		addTile(lowFlatToSlope2, 42, 1);
		addTile(lowFlatToSlope3, 43, 1);
		addTile(lowFlatToSlope4, 44, 1);
		addTile(lowFlatToSlope5, 45, 1);

		drawRange(0, 255, 0, 0, full);
	}
}
