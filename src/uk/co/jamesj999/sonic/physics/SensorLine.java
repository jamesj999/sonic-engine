package uk.co.jamesj999.sonic.physics;

import java.util.ArrayList;
import java.util.List;

import javax.media.opengl.GL2;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Level;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.Tile;
import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class SensorLine {
	private final LevelManager levelManager = LevelManager.getInstance();
	private final GraphicsManager graphicsManager = GraphicsManager
			.getInstance();

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

	public short getHeight() {
		short spriteX = sprite.getCentreX();
		short spriteY = sprite.getCentreY();

		Level level = levelManager.getLevel();

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
				short tileY = (short) ((checkY / 16) * 16);
				Tile tile = level.getTileAt((short) checkX, (short) checkY);
				if (tile != null) {
					byte heightOfTile = tile.getHeightAt(offset);
					if (((startY == endY) && (checkX == endX))
							|| ((startX == endX) && (checkY == endY))) {
						Tile tileAbove = level.getTileAt((short) checkX,
								(short) (checkY + 16));
						if (tileAbove != null) {
							byte heightOfTileAbove = tileAbove
									.getHeightAt(offset);
							if (heightOfTileAbove > 0) {
								heightOfTile = (byte) (heightOfTileAbove + 16);
								tileY++;
							}
						}
					}
					short thisHeight = (short) (heightOfTile + tileY);
					if (thisHeight > highestRealY) {
						highestRealY = thisHeight;
						angle = tile.getAngle();
					}
				}
			}
		}
		if (sprite instanceof AbstractPlayableSprite) {
			((AbstractPlayableSprite) sprite).setAngle(angle);
		}
		return highestRealY;
	}

	public void draw(AbstractSprite sprite) {
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
		commands.add(new GLCommand(GLCommand.Type.VERTEX2I, -1, 1, 0,
				0, startX, startY, 0, 0));
		commands.add(new GLCommand(GLCommand.Type.VERTEX2I, -1, 1, 0,
				0, endX, endY, 0, 0));
		graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES,
				commands));
	}
}