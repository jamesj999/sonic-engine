package uk.co.jamesj999.sonic.level;

public class TestLevel extends AbstractLevel {

	@Override
	protected void setupTiles() {

		byte[] tile1Height = new byte[16];
		tile1Height[0] = 0;
		tile1Height[1] = 0;
		tile1Height[2] = 1;
		tile1Height[3] = 1;
		tile1Height[4] = 2;
		tile1Height[5] = 3;
		tile1Height[6] = 4;
		tile1Height[7] = 4;
		tile1Height[8] = 4;
		tile1Height[9] = 3;
		tile1Height[10] = 2;
		tile1Height[11] = 1;
		tile1Height[12] = 2;
		tile1Height[13] = 2;
		tile1Height[14] = 2;
		tile1Height[15] = 0;

		byte[] tile2Height = new byte[16];
		tile2Height[0] = 1;
		tile2Height[1] = 2;
		tile2Height[2] = 3;
		tile2Height[3] = 4;
		tile2Height[4] = 5;
		tile2Height[5] = 6;
		tile2Height[6] = 5;
		tile2Height[7] = 4;
		tile2Height[8] = 3;
		tile2Height[9] = 2;
		tile2Height[10] = 1;
		tile2Height[11] = 0;
		tile2Height[12] = 0;
		tile2Height[13] = 1;
		tile2Height[14] = 1;
		tile2Height[15] = 0;

		Tile tile1 = new Tile(tile1Height);
		Tile tile2 = new Tile(tile2Height);
		
		addTile(tile1, 0, 0);
		addTile(tile1, 1, 0);
		addTile(tile1, 3, 0);
		addTile(tile1, 4, 0);
		addTile(tile1, 5, 0);
		addTile(tile1, 6, 0);
		addTile(tile1, 7, 0);
		addTile(tile1, 8, 0);
		addTile(tile1, 9, 0);
		addTile(tile2, 3, 0);
	}

}
