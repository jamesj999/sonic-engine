package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Level;
import uk.co.jamesj999.sonic.level.OldLevel;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.SolidTile;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import com.jogamp.opengl.GL2;
import java.util.ArrayList;
import java.util.List;

public class SensorLine {
	private final LevelManager levelManager = LevelManager.getInstance();
	private final GraphicsManager graphicsManager = GraphicsManager
			.getInstance();

	SolidTile solidTileToHighlight;

	private Sprite sprite;

	private byte x;
	private byte y;
	private byte length;
	private boolean horizontal;

	/**
	 * X and Y are in relation to sprite's centre.
	 */
	public SensorLine(Sprite sprite, int x, int y, int length,
			boolean horizontal) {
		this.sprite = sprite;
		this.x = (byte) x;
		this.y = (byte) y;
		this.length = (byte) length;
		this.horizontal = horizontal;
	}

	public void updateParameters(int x, int y, int length, boolean horizontal) {
		this.x = (byte) x;
		this.y = (byte) y;
		this.length = (byte) length;
		this.horizontal = horizontal;
	}

	public short getHeight() {
		short spriteX = sprite.getCentreX();
		short spriteY = sprite.getCentreY();

		Level level = levelManager.getCurrentLevel();

		int startX = spriteX + x;
		int startY = spriteY + y;
		int endX;
		int endY;
		if (horizontal) {
			endX = startX + length;
			endY = startY;
		} else {
			endX = startX;
			endY = startY + length;
		}

		short highestRealY = -1;
		byte angle = 0x00;

		for (int checkX = startX; checkX <= endX; checkX++) {
			byte offset = (byte) (checkX % 16);
			// short tileX = (short) Math.floor((double) checkX / 16);
			for (int checkY = startY; checkY <= endY; checkY++) {
				SolidTile toUse;
				short tileY = (short) ((checkY / 16) * 16);
				/*TODO SolidTile solidTile = level.getTileAt((short) checkX, (short) checkY);
				if (solidTile != null) {
					byte heightOfTile = solidTile.getHeightAt(offset);
					toUse = solidTile;
					if (((startY == endY) && (checkX == endX))
							|| ((startX == endX) && (checkY == endY))) {
						SolidTile solidTileAbove = level.getTileAt((short) checkX,
								(short) (checkY + 16));
						if (solidTileAbove != null) {
							byte heightOfTileAbove = solidTileAbove
									.getHeightAt(offset);
							if (heightOfTileAbove > 0) {
								toUse = solidTileAbove;
								heightOfTile = (byte) (heightOfTileAbove + 16);
								tileY++;
							}
						}
					}
					short thisHeight = (short) (heightOfTile + tileY);
					if (thisHeight > highestRealY) {
						solidTileToHighlight = toUse;
						highestRealY = thisHeight;
						angle = solidTile.getAngle();
					}
				}*/
			}
		}
		if (sprite instanceof AbstractPlayableSprite) {
			((AbstractPlayableSprite) sprite).setAngle(angle);
		}
		return highestRealY;
	}

	public short getX() {
		short spriteX = sprite.getCentreX();
		short spriteY = sprite.getCentreY();

		Level level = levelManager.getCurrentLevel();

		// Starting positions will always be the same
		int startX = spriteX + x;
		int startY = spriteY + y;
		int endX;
		int endY;
		// If we are horizontal, we go along the x axis, otherwise we go along
		// the y axis.
		if (horizontal) {
			endX = startX + length;
			endY = startY;
		} else {
			endX = startX;
			endY = startY + length;
		}

		// Returning -1 means we did not find a collision:
		short highestRealX = -1;
		short lowestRealX = -1;

		// Iterate through our line checking the tile at each point:
		for (int checkX = startX; checkX <= endX; checkX++) {
			// short tileX = (short) Math.floor((double) checkX / 16);
			for (int checkY = startY; checkY <= endY; checkY++) {
				/**SolidTile solidTile = level.getTileAt((short) checkX, (short) checkY);
				if (solidTile != null
						&& (!horizontal)) { // TODO Update: || (horizontal && !solidTile.getJumpThrough()))) {
					if (checkX > highestRealX) {
						highestRealX = (short) checkX;
						if (checkX < lowestRealX || lowestRealX == -1) {
							lowestRealX = (short) checkX;
						}
					}
				}**/
			}
		}

		if (((AbstractPlayableSprite) sprite).getGSpeed() > 0) {
			if (lowestRealX > -1) {
				graphicsManager.registerCommand(new GLCommand(GLCommand.Type.RECTI,
						-1, 1, 0, 0, lowestRealX - 5, spriteY + y - 5,
						lowestRealX + 5, spriteY + y + 5));
			}
			return lowestRealX;
			// This has been removed because it breaks wall collisions whilst in
			// the air... (Gspeed can be 0 in this case)
			// } else if (((AbstractPlayableSprite) sprite).getGSpeed() == 0) {
			// return -1;
		} else {
			if (highestRealX > -1) {
				graphicsManager.registerCommand(new GLCommand(GLCommand.Type.RECTI,
						-1, 1, 0, 0, highestRealX - 5, spriteY + y - 5,
						highestRealX + 5, spriteY + y + 5));
			}
			return highestRealX;
		}
	}

	public void draw() {
		short spriteX = sprite.getCentreX();
		short spriteY = sprite.getCentreY();
		int startX = spriteX + x;
		int startY = spriteY + y;
		int endX;
		int endY;
		if (horizontal) {
			endX = startX + length;
			endY = startY;
		} else {
			endX = startX;
			endY = startY + length;
		}
		List<GLCommand> commands = new ArrayList<GLCommand>();
		commands.add(new GLCommand(GLCommand.Type.VERTEX2I, -1, 1, 0, 0,
				startX, startY, 0, 0));
		commands.add(new GLCommand(GLCommand.Type.VERTEX2I, -1, 1, 0, 0, endX,
				endY, 0, 0));
		graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES,
				commands));
	}
}
