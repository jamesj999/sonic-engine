package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.Tile;
import uk.co.jamesj999.sonic.sprites.Sprite;

import com.jogamp.opengl.GL2;

import java.util.ArrayList;
import java.util.List;

public class SensorLine {
	private final LevelManager levelManager = LevelManager.getInstance();
	private final GraphicsManager graphicsManager = GraphicsManager
			.getInstance();

	Tile tileToHighlight;

	private final Sprite sprite;
	private boolean enabled;

	private byte x;
	private byte y;
	private byte x2;
	private byte y2;
	private Direction direction;

	/**
	 * X and Y are in relation to sprite's centre.
	 */
	public SensorLine(Sprite sprite, boolean enabled, byte x, byte y, byte x2, byte y2, Direction direction) {
		this.sprite = sprite;
		this.enabled = enabled;
		updateParameters(x, y, x2, y2, direction);
	}

	public void updateParameters(byte x, byte y, byte x2, byte y2, Direction direction) {
		//TODO Add validation of direction vs x/y coords.
		this.x = x;
		this.y = y;
		this.x2 = x2;
		this.y2 = y2;
		this.direction = direction;
	}

	public SensorResult scan() {
		if(!enabled) {
			return null;
		}
		short spriteCentreX = sprite.getCentreX();
		short spriteCentreY = sprite.getCentreY();

		int startX = x + spriteCentreX;
		int endX = x2 + spriteCentreX;

		int startY = y + spriteCentreY;
		int endY = y2 + spriteCentreY;

		int xIncrement = (startX < endX || startX == endX) ? 1 : -1;
		int yIncrement = (startY < endY || startY == endY) ? 1 : -1;

		// Increment end by 1 so we catch it in our weird loop below
		endY++;
		endX++;

		for(int xValue = startX; xValue != endX; xValue += xIncrement) {
			for (int yValue = startY; yValue != endY; yValue += yIncrement) {
				Tile tile = levelManager.getLevel().getTileAt((short) xValue, (short) yValue);
				if(tile != null) {
					if(direction == Direction.DOWN) {
						byte tileHeight = tile.getHeightAt((byte) (xValue % 16));

//						if(tileHeight == 16) {
//							// we have to go up one tile to check we're not stuck somewhere
//							int upYValue = (yValue % 16) - 16;
//							Tile upTile = levelManager.getLevel().getTileAt((short) xValue, (short) upYValue);
//
//						}
						if (yValue <= tileHeight + (yValue - (yValue % 16))) {
							// we found it
							byte distance = (byte) ((yValue % 16) + tileHeight);
							return new SensorResult(tile.getAngle(), distance, -1); // TODO Add Tile ID
						}
					} else {
						//TODO handle other directions
					}
				}
			}
		}

		return null;
	}

	public void draw() {
		if (!enabled) {
			return;
		}
		short spriteX = sprite.getCentreX();
		short spriteY = sprite.getCentreY();
		int startX = spriteX + x;
		int startY = spriteY + y;
		int endX = spriteX + x2;
		int endY = spriteY + y2;

		List<GLCommand> commands = new ArrayList<GLCommand>();
		commands.add(new GLCommand(GLCommand.Type.VERTEX2I, -1, 1, 0, 0,
				startX, startY, 0, 0));
		commands.add(new GLCommand(GLCommand.Type.VERTEX2I, -1, 1, 0, 0, endX,
				endY, 0, 0));
		graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES,
				commands));
	}

	public Direction getDirection() {
		return direction;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
}
