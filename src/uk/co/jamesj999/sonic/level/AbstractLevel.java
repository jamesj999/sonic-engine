package uk.co.jamesj999.sonic.level;

import javax.media.opengl.GL2;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;

public abstract class AbstractLevel implements Level {
	Tile[][] tiles = new Tile[256][256];

	public AbstractLevel() {
		setupTiles();
	}

	public void addTile(Tile tile, int x, int y) {
		tiles[x][y] = tile;
	}

	@Override
	public Tile getTileAt(short x, short y) {
		short xPosition = (short) Math.floor((double) x / 16);
		short yPosition = (short) Math.floor((double) y / 16);
		if (xPosition > -1 && yPosition > -1) {
			return tiles[xPosition][yPosition];
		} else {
			return null;
		}
	}

	protected abstract void setupTiles();

	@Override
	public void draw() {
		GL2 gl = GraphicsManager.getGraphics();
		gl.glBegin(GL2.GL_POINTS);
		for (int x = 0; x < tiles.length; x++) {
			Tile[] tileLine = tiles[x];
			int realX = x * 16;
			if (tileLine != null) {
				for (int y = 0; y < tileLine.length; y++) {
					int realY = y * 16;
					Tile tile = tileLine[y];
					if (tile != null) {
						for (int heightX = 0; heightX < tile.heights.length; heightX++) {
							int height = tile.heights[heightX];
							for (int i = height+realY; i >= realY; i--) {
								gl.glVertex2i(realX + heightX, i);
							}
						}
					}
				}
			}
		}
		gl.glEnd();
	}
}
