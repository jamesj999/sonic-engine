package uk.co.jamesj999.sonic.level;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.games.Sonic2;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LevelManager {
    private static LevelManager levelManager;
    private Level level;
    private Rom rom;
    private GraphicsManager graphicsManager = GraphicsManager.getInstance();
    protected SpriteManager spriteManager = SpriteManager.getInstance();
    private short xTiles = 256;
    private short yTiles = 256;

    protected SolidTile[][] solidTiles = new SolidTile[xTiles][yTiles];


    private int currentLayer = 0;;

    public void loadLevel(int levelIndex) throws IOException {
        //TODO proper error handling for ROM checksum etc.
        //and maybe refactor this so Game/Rom are handled elsewhere.
        Rom rom = new Rom();
        rom.open(SonicConfigurationService.getInstance().getString(SonicConfiguration.ROM_FILENAME));
        Game game = new Sonic2(rom);
        level = game.loadLevel(levelIndex);


    }

    public void addTile(SolidTile tile, int x, int y) {
        solidTiles[x][y] = tile;
    }

    public SolidTile getSolidTileAt(short x, short y) {
        short xPosition = (short) Math.floor((double) x / 16);
        short yPosition = (short) Math.floor((double) y / 16);
        if (xPosition > -1 && yPosition > -1 && xPosition < xTiles
                && yPosition < yTiles) {
            return solidTiles[xPosition][yPosition];
        } else {
            return null;
        }
    }

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
            SolidTile[] tileLine = solidTiles[x];
            int realX = x * 16;
            if (tileLine != null) {
                for (int y = yBottomBound; y <= yTopBound; y++) {
                    int realY = y * 16;
                    SolidTile tile = tileLine[y];
                    if (tile != null) {
                        for (int heightX = 0; heightX < tile.heights.length; heightX++) {
                            int height = tile.heights[heightX];
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

    public void drawRange(int xMin, int xMax, int yMin, int yMax, SolidTile tile) {
        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                addTile(tile, x, y);
            }
        }
    }

    private GraphicsManager getGraphicsManager() {
        if (graphicsManager == null) {
            graphicsManager = GraphicsManager.getInstance();
        }
        return graphicsManager;
    }

    public Level getCurrentLevel() {
        return level;
    }

    public synchronized static LevelManager getInstance() {
        if (levelManager == null) {
            levelManager = new LevelManager();
        }
        return levelManager;
    }

    public int getCurrentLayer() {
        return currentLayer;
    }

    public void setCurrentLayer(int currentLayer) {
        this.currentLayer = currentLayer;
    }
}
