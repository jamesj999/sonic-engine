package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;

import com.jogamp.opengl.GL2;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractOldLevel implements OldLevel {
	protected GraphicsManager graphicsManager = GraphicsManager.getInstance();
	protected SpriteManager spriteManager = SpriteManager.getInstance();
	private short xTiles = 256;
	private short yTiles = 256;

	protected SolidTile[][] solidTiles = new SolidTile[xTiles][yTiles];

	public AbstractOldLevel() {
		setupTiles();
		registerSprites();
	}

	public void addTile(SolidTile solidTile, int x, int y) {
		solidTiles[x][y] = solidTile;
	}

	public SolidTile getTileAt(short x, short y) {
		short xPosition = (short) Math.floor((double) x / 16);
		short yPosition = (short) Math.floor((double) y / 16);
		if (xPosition > -1 && yPosition > -1 && xPosition < xTiles
				&& yPosition < yTiles) {
			return solidTiles[xPosition][yPosition];
		} else {
			return null;
		}
	}

	protected abstract void setupTiles();

	protected abstract void registerSprites();

	public void draw() {
		// Work out our bounds. We don't want to be rendering or iterating tiles
		// which are off screen.
		Camera camera = Camera.getInstance();
		int cameraX = camera.getX();
		int cameraY = camera.getY();
		int cameraWidth = camera.getWidth();
		int cameraHeight = camera.getHeight();
		int xLeftBound = cameraX / 16;
		int xRightBound = (cameraX + cameraWidth) / 16;
		int yBottomBound = cameraY / 16;
		int yTopBound = (cameraY + cameraHeight) / 16;
		List<GLCommand> commands = new ArrayList<GLCommand>();
		for (int x = xLeftBound; x <= xRightBound; x++) {
			SolidTile[] solidTileLine = solidTiles[x];
			int realX = x * 16;
			if (solidTileLine != null) {
				for (int y = yBottomBound; y <= yTopBound; y++) {
					int realY = y * 16;
					SolidTile solidTile = solidTileLine[y];
					if (solidTile != null) {
						for (int heightX = 0; heightX < solidTile.heights.length; heightX++) {
							int height = solidTile.heights[heightX];
							if (height > 0) {
								for (int i = height + realY; i >= realY; i--) {
									commands.add(new GLCommand(
											GLCommand.Type.VERTEX2I, -1, 1, 1,
											1, realX + heightX, i, -1, -1));
								}
							}
						}
					}
				}
			}
		}
		// gl.glEnd();
		graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS,
				commands));
	}

	public void drawRange(int xMin, int xMax, int yMin, int yMax, SolidTile solidTile) {
		for (int x = xMin; x <= xMax; x++) {
			for (int y = yMin; y <= yMax; y++) {
				addTile(solidTile, x, y);
			}
		}
	}
}
