package com.openggf.editor;

import com.openggf.camera.Camera;
import com.openggf.control.InputHandler;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.MutableLevel;

import java.util.Objects;

public final class EditorMouseTransform {
    private static final int NATIVE_WIDTH = 320;
    private static final int NATIVE_HEIGHT = 224;

    private EditorMouseTransform() {
    }

    public static Result toWorldTile(InputHandler inputHandler,
                                     Camera camera,
                                     GraphicsManager graphicsManager,
                                     MutableLevel level) {
        Objects.requireNonNull(inputHandler, "inputHandler");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(graphicsManager, "graphicsManager");
        Objects.requireNonNull(level, "level");
        return toWorldTile(
                inputHandler.getMouseX(),
                inputHandler.getMouseY(),
                camera.getX(),
                camera.getY(),
                graphicsManager.getViewportX(),
                graphicsManager.getViewportY(),
                graphicsManager.getViewportWidth(),
                graphicsManager.getViewportHeight(),
                level.getBlockPixelSize(),
                level.getMap().getWidth(),
                level.getMap().getHeight());
    }

    public static Result toWorldTile(double mouseX,
                                     double mouseY,
                                     int cameraX,
                                     int cameraY,
                                     int viewportX,
                                     int viewportY,
                                     int viewportWidth,
                                     int viewportHeight,
                                     int blockPixelSize,
                                     int mapWidth,
                                     int mapHeight) {
        if (viewportWidth <= 0 || viewportHeight <= 0 || blockPixelSize <= 0
                || mapWidth <= 0 || mapHeight <= 0) {
            return Result.outside();
        }
        if (mouseX < viewportX || mouseY < viewportY
                || mouseX >= viewportX + viewportWidth
                || mouseY >= viewportY + viewportHeight) {
            return Result.outside();
        }

        double scaleX = viewportWidth / (double) NATIVE_WIDTH;
        double scaleY = viewportHeight / (double) NATIVE_HEIGHT;
        int gameX = (int) ((mouseX - viewportX) / scaleX);
        int gameY = (int) ((mouseY - viewportY) / scaleY);
        int worldX = cameraX + gameX;
        int worldY = cameraY + gameY;
        int tileX = clamp(worldX / blockPixelSize, 0, mapWidth - 1);
        int tileY = clamp(worldY / blockPixelSize, 0, mapHeight - 1);
        return new Result(true, worldX, worldY, tileX, tileY);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Result(boolean inViewport, int worldX, int worldY, int tileX, int tileY) {
        private static Result outside() {
            return new Result(false, 0, 0, -1, -1);
        }
    }
}
