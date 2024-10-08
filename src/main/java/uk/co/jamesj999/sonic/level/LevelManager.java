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
import uk.co.jamesj999.sonic.sprites.Sprite;
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

        Sprite sprite = spriteManager.getSprite(SonicConfigurationService.getInstance().getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        int currentLayer = sprite.getLayer();

        //floor values to draw tiles partially offscreen on the left/top.
        int drawX = cameraX - (cameraX % 16);
        int drawY = cameraY - (cameraY % 16);
        int levelWidth = level.getMap().getWidth()*64;
        int levelHeight = level.getMap().getHeight()*64;


        int xLeftBound = Math.max(drawX, 0);
        int xRightBound = Math.min(cameraX + cameraWidth, levelWidth);
        int yTopBound = Math.max(drawY, 0);
        int yBottomBound = Math.min(cameraY + cameraHeight, levelHeight);
        List<GLCommand> commands = new ArrayList<GLCommand>();

        for (int y = yTopBound; y <= yBottomBound; y += 16) {
            for (int x = xLeftBound; x <= xRightBound; x += 16) {
                Block block = getBlockAtPosition(currentLayer, x, y);
                if (block != null) {
                    int xBlockBit = x % 128 / 16;
                    int yBlockBit = y % 128 / 16;

                    ChunkDesc chunkDesc = block.getChunkDesc(xBlockBit,yBlockBit);
                    int chunkIndex = chunkDesc.getChunkIndex();
                    if (chunkIndex > level.getChunkCount()) {
                        System.err.println("Chunk index out of bounds; Zeroing!");
                        chunkIndex = 0;
                    }


                    Chunk chunk = level.getChunk(chunkIndex);
                    //TODO render patterns held in chunk
                    if (chunkDesc.getPrimaryCollisionMode() != CollisionMode.NO_COLLISION) {
                        int solidTileIndex = chunk.getSolidTileIndex();
                        SolidTile solidTile = level.getSolidTile(solidTileIndex);

                        if (chunkDesc.getHFlip()) {
                            int flipper = 15;
                            for (int i = 0; i < 16; i++) {

                                int height = solidTile.getHeightAt((byte) flipper);
                                flipper--;
                                if (height > 0) {
                                    int drawStartX = x + i;
                                    int drawEndX = drawStartX + 1;
                                    int drawStartY = y;
                                    int drawEndY = y - height;

                                    commands.add(new GLCommand(
                                            GLCommand.Type.RECTI, GL2.GL_2D, 1, 1,
                                            1, drawStartX, drawEndY, drawEndX, drawStartY));
                                }
                            }
                        } else {
                            for (int i = 0; i < 16; i++) {
                                int height = solidTile.getHeightAt((byte) i);
                                if (height > 0) {
                                    int drawStartX = x + i;
                                    int drawEndX = drawStartX + 1;
                                    int drawStartY = y;
                                    int drawEndY = y - height;

                                    commands.add(new GLCommand(
                                            GLCommand.Type.RECTI, GL2.GL_2D, 1, 1,
                                            1, drawStartX, drawEndY, drawEndX, drawStartY));
                                }
                            }
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
