package com.openggf.game.dataselect;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RgbaImage;
import com.openggf.graphics.ScreenshotCapture;
import com.openggf.level.LevelManager;

/** Render-thread capture choreography shared by donated data-select previews. */
public final class DataSelectPreviewCapture {
    private DataSelectPreviewCapture() {}

    /** Captures act one at a game-resolved camera target using ROM-backed level assets. */
    public static RgbaImage capture(int zoneId, int cameraLeftX, int centreY) {
        GraphicsManager graphics = EngineServices.current().graphics();
        return graphics
                .submitRenderThreadTask(() -> {
                    LevelManager levelManager = GameServices.level();
                    levelManager.loadZoneAndAct(zoneId, 0, com.openggf.game.LevelLoadMode.PREVIEW_CAPTURE);
                    Camera camera = GameServices.camera();
                    camera.setX((short) Math.max(camera.getMinX(), cameraLeftX));
                    camera.setY((short) Math.max(camera.getMinY(), centreY - 96));
                    // The camera jump above happens outside the normal per-frame update
                    // tick, so parallax's cached FG vscroll offset and the object
                    // placement window are still anchored to the load-time camera
                    // position. Resync both before drawing, or the foreground tilemap
                    // samples world Y=0 and no nearby objects/badniks are spawned.
                    levelManager.recomputeParallaxAfterRewindRestore();
                    levelManager.getObjectManager().postCameraPlacementUpdate(camera.getX());
                    levelManager.drawWithRenderOptions(null, LevelManager.LevelRenderOptions.previewCapture());
                    graphics.flush();
                    int viewportX = graphics.getViewportX();
                    int viewportY = graphics.getViewportY();
                    int viewportWidth = graphics.getViewportWidth();
                    int viewportHeight = graphics.getViewportHeight();
                    if (viewportWidth <= 0 || viewportHeight <= 0) {
                        return ScreenshotCapture.captureFramebuffer(320, 224);
                    }
                    return ScreenshotCapture.captureFramebufferRegion(
                            viewportX,
                            viewportY,
                            viewportWidth,
                            viewportHeight);
                })
                .join();
    }
}
