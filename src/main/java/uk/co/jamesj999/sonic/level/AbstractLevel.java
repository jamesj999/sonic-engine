package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;

import com.jogamp.opengl.GL2;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractLevel implements Level {
	protected GraphicsManager graphicsManager = GraphicsManager.getInstance();
	protected SpriteManager spriteManager = SpriteManager.getInstance();
	private short xTiles = 256;
	private short yTiles = 256;

	protected SolidTile[][] solidTiles = new SolidTile[xTiles][yTiles];

	public AbstractLevel() {
	}

}
