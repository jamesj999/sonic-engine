package com.openggf.game.sonic3k;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAizIntroVisibleDiagnostics {
    @Test
    public void logVisibleChunkCoverageAcrossAizIntro() throws Exception {
        GraphicsManager.getInstance().initHeadless();

        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        GameServices.sprites().addSprite(sonic);

        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sonic);
        camera.setFrozen(false);

        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);
        GroundSensor.setLevelManager(levelManager);
        camera.updatePosition(true);

        Level level = levelManager.getCurrentLevel();
        HeadlessTestRunner runner = new HeadlessTestRunner(sonic);

        int[] checkpoints = {1, 120, 240, 360, 480, 600, 720, 840, 960, 1080, 1200};
        int checkpointIndex = 0;
        int maxFrames = checkpoints[checkpoints.length - 1];

        for (int frame = 1; frame <= maxFrames; frame++) {
            runner.stepFrame(false, false, false, false, false);
            if (checkpointIndex >= checkpoints.length || frame != checkpoints[checkpointIndex]) {
                continue;
            }

            int cameraX = camera.getX() & 0xFFFF;
            int cameraY = camera.getY();
            int fgInvalid = visibleInvalidRefs(levelManager, level, (byte) 0, cameraX, cameraY);
            int bgInvalid = visibleInvalidRefs(levelManager, level, (byte) 1, cameraX, cameraY);
            int bgParallaxInvalid = visibleBgParallaxInvalidRefs(levelManager, level);
            int bgParallaxZeroBlocks = visibleBgParallaxZeroBlocks(levelManager);
            int bgParallaxZeroBlocks512 = visibleBgParallaxZeroBlocksWrapped(levelManager, 512);
            int bgParallaxZeroBlocks1024 = visibleBgParallaxZeroBlocksWrapped(levelManager, 1024);
            int bgParallaxZeroBlocks2048 = visibleBgParallaxZeroBlocksWrapped(levelManager, 2048);
            int[] bgParallaxScroll = bgParallaxScrollRange();
            Palette.Color backdrop = level.getPalette(2).getColor(0);

            System.out.println(String.format(
                    "AIZ intro visible diag frame=%d cam=(0x%04X,%d) mainPhase=%s "
                            + "fgInvalid=%d bgInvalid=%d bgParallaxInvalid=%d bgZeroBlocks=%d "
                            + "bgZeroWrap[512=%d,1024=%d,2048=%d] "
                            + "bgScroll[min=%d,max=%d] backdropRGB=(%d,%d,%d)",
                    frame, cameraX, cameraY, AizPlaneIntroInstance.isMainLevelPhaseActive(),
                    fgInvalid, bgInvalid, bgParallaxInvalid, bgParallaxZeroBlocks,
                    bgParallaxZeroBlocks512, bgParallaxZeroBlocks1024, bgParallaxZeroBlocks2048,
                    bgParallaxScroll[0], bgParallaxScroll[1],
                    backdrop.r & 0xFF, backdrop.g & 0xFF, backdrop.b & 0xFF));
            assertEquals(0, fgInvalid, "No invalid FG chunk references at frame " + frame);
            assertEquals(0, bgInvalid, "No invalid BG chunk references at frame " + frame);
            assertEquals(0, bgParallaxInvalid, "No invalid BG parallax chunk references at frame " + frame);
            checkpointIndex++;
        }
    }

    private static int visibleInvalidRefs(LevelManager levelManager,
                                          Level level,
                                          byte layer,
                                          int cameraX,
                                          int cameraY) {
        int invalid = 0;
        int startX = cameraX;
        int endX = cameraX + 320;
        int startY = cameraY;
        int endY = cameraY + 224;
        int step = 16;
        int chunkCount = level.getChunkCount();

        for (int y = startY; y < endY; y += step) {
            for (int x = startX; x < endX; x += step) {
                ChunkDesc desc = levelManager.getChunkDescAt(layer, x, y);
                if (desc == null || desc.getChunkIndex() >= chunkCount) {
                    invalid++;
                }
            }
        }
        return invalid;
    }

    private static int visibleBgParallaxInvalidRefs(LevelManager levelManager, Level level) {
        ParallaxManager parallaxManager = GameServices.parallax();
        int[] hScroll = parallaxManager.getHScrollForShader();
        int vscrollBg = parallaxManager.getVscrollFactorBG();
        int chunkCount = level.getChunkCount();
        int invalid = 0;

        for (int screenY = 0; screenY < 224; screenY += 16) {
            int packed = (hScroll != null && screenY < hScroll.length) ? hScroll[screenY] : 0;
            int bgScroll = (short) (packed & 0xFFFF);
            int worldY = screenY + vscrollBg;

            for (int screenX = 0; screenX < 320; screenX += 16) {
                int worldX = screenX - bgScroll;
                ChunkDesc desc = levelManager.getChunkDescAt((byte) 1, worldX, worldY);
                if (desc == null || desc.getChunkIndex() >= chunkCount) {
                    invalid++;
                }
            }
        }

        return invalid;
    }

    private static int visibleBgParallaxZeroBlocks(LevelManager levelManager) {
        ParallaxManager parallaxManager = GameServices.parallax();
        int[] hScroll = parallaxManager.getHScrollForShader();
        int vscrollBg = parallaxManager.getVscrollFactorBG();
        int zero = 0;

        for (int screenY = 0; screenY < 224; screenY += 16) {
            int packed = (hScroll != null && screenY < hScroll.length) ? hScroll[screenY] : 0;
            int bgScroll = (short) (packed & 0xFFFF);
            int worldY = screenY + vscrollBg;

            for (int screenX = 0; screenX < 320; screenX += 16) {
                int worldX = screenX - bgScroll;
                ChunkDesc desc = levelManager.getChunkDescAt((byte) 1, worldX, worldY);
                if (desc != null && desc.getChunkIndex() == 0) {
                    zero++;
                }
            }
        }
        return zero;
    }

    private static int visibleBgParallaxZeroBlocksWrapped(LevelManager levelManager, int wrapWidthPx) {
        ParallaxManager parallaxManager = GameServices.parallax();
        int[] hScroll = parallaxManager.getHScrollForShader();
        int vscrollBg = parallaxManager.getVscrollFactorBG();
        int zero = 0;

        for (int screenY = 0; screenY < 224; screenY += 16) {
            int packed = (hScroll != null && screenY < hScroll.length) ? hScroll[screenY] : 0;
            int bgScroll = (short) (packed & 0xFFFF);
            int worldY = screenY + vscrollBg;

            for (int screenX = 0; screenX < 320; screenX += 16) {
                int worldX = screenX - bgScroll;
                int wrapped = ((worldX % wrapWidthPx) + wrapWidthPx) % wrapWidthPx;
                ChunkDesc desc = levelManager.getChunkDescAt((byte) 1, wrapped, worldY);
                if (desc != null && desc.getChunkIndex() == 0) {
                    zero++;
                }
            }
        }
        return zero;
    }

    private static int[] bgParallaxScrollRange() {
        ParallaxManager parallaxManager = GameServices.parallax();
        int[] hScroll = parallaxManager.getHScrollForShader();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int y = 0; y < 224; y++) {
            int packed = (hScroll != null && y < hScroll.length) ? hScroll[y] : 0;
            int bgScroll = (short) (packed & 0xFFFF);
            if (bgScroll < min) {
                min = bgScroll;
            }
            if (bgScroll > max) {
                max = bgScroll;
            }
        }
        if (min == Integer.MAX_VALUE) {
            min = 0;
            max = 0;
        }
        return new int[]{min, max};
    }
}


