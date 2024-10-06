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

    private int currentLayer = 0;
    private static final int TILE_SIZE = 16;

    public void loadLevel(int levelIndex) throws IOException {
        //TODO proper error handling for ROM checksum etc.
        //and maybe refactor this so Game/Rom are handled elsewhere.
        Rom rom = new Rom();
        rom.open(SonicConfigurationService.getInstance().getString(SonicConfiguration.ROM_FILENAME));
        Game game = new Sonic2(rom);
        level = game.loadLevel(levelIndex);


    }

    public void draw() {
        // Work out our bounds. We don't want to be rendering or iterating tiles
        // which are off screen.
        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int cameraWidth = camera.getWidth();
        int cameraHeight = camera.getHeight();

        //floor values to draw tiles partially offscreen on the left/top.
        int drawX = cameraX - (cameraX % 16);
        int drawY = cameraY - (cameraY % 16);

        int xLeftBound = Math.min(0,drawX);
        int xRightBound = cameraX + cameraWidth; //TODO limit= next screen lock? end of lvl?
        int yTopBound = Math.min(0,drawY); //TODO limit = next screen lock? end of lvl?
        int yBottomBound = (Math.min(level.getMap().getHeight(),cameraY + cameraHeight));
        List<GLCommand> commands = new ArrayList<GLCommand>();

        for (int y = yTopBound; y <= yBottomBound; y += 16) {
            for (int x = xLeftBound; x <= xRightBound; x += 16) {
                Block block = getBlockAtPosition(currentLayer, x, y);
                if (block != null) {
                    int xBlockBit = x % 128 / 16;
                    int yBlockBit = y % 128 / 16;

                    ChunkDesc chunkDesc = block.getChunkDesc(xBlockBit,yBlockBit);
                    Chunk chunk = level.getChunk(chunkDesc.getChunkIndex());
                    //TODO render patterns held in chunk

                    int solidTileIndex = chunk.getSolidTileIndex();
                    if (solidTileIndex!=0) {
                        //Here!
                        int banana = 5+2;
                    }
                    SolidTile solidTile = level.getSolidTile(solidTileIndex);

                    for (int i=0; i<16; i++) {
                        int height = solidTile.getHeightAt((byte) i);
                        if (height > 0) {
                            int drawStartX = x + i;
                            int drawEndX = drawStartX+1;
                            int drawStartY = y;
                            int drawEndY = y + height;

                            commands.add(new GLCommand(
                                    GLCommand.Type.RECTI, GL2.GL_2D, 1, 1,
                                    1, drawStartX, drawEndY, drawEndX, drawStartY));
                        }
                    }
                }

            }
        }

        getGraphicsManager().registerCommand(new GLCommandGroup(GL2.GL_POINTS,
                commands));
    }

    private Block getBlockAtPosition(int layer, int x, int y) {

        Map map = level.getMap();
        int mapX = x / 128;
        int mapY = y / 128;

        byte value = map.getValue(layer, mapX, mapY);
        if (value < 0) {
            return null;
        }

        Block block = level.getBlock(value & 0xFF);

        return block;
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
