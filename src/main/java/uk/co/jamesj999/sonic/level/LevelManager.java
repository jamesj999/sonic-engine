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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LevelManager {
    private static LevelManager levelManager;
    private Level level;
    private Rom rom;
    private GraphicsManager graphicsManager;

    public void loadLevel(int levelIndex) throws IOException {
        //TODO proper error handling for ROM checksum etc.
        //and maybe refactor this so Game/Rom are handled elsewhere.
        Rom rom = new Rom();
        rom.open(SonicConfigurationService.getInstance().getString(SonicConfiguration.ROM_FILENAME));
        Game game = new Sonic2(rom);
        level = game.loadLevel(levelIndex);
    }

    public void draw() {
        // Old method. Need to update method of getting current tile for x/y coords and refactor accordingly.

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
            int realX = x * 16;
            for (int y = yBottomBound; y <= yTopBound; y++) {
                int realY = y * 16;
                SolidTile solidTile = getSolidTileAt(realX, realY);
                if (solidTile != null) {
                    for (int heightX = 0; heightX < SolidTile.TILE_SIZE_IN_ROM; heightX++) {
                        int height = solidTile.getHeightAt((byte) heightX);
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
        getGraphicsManager().registerCommand(new GLCommandGroup(GL2.GL_POINTS,
                commands));
    }

    public SolidTile getSolidTileAt(int x, int y) {
        // TODO
        int mapX = x / 128;
        int mapY = y / 128;
        if(level == null) {
            return null;
        }
        Map map = level.getMap();
        byte value = map.getValue(0, x, y);
        Block block = level.getBlock(value);
        ChunkDesc chunkDesc = block.getChunkDesc(x % 128, y % 128);
        return null;
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
}
