package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public abstract class AbstractObjectInstance implements ObjectInstance {
    /**
     * Cached camera bounds, updated once per frame by ObjectManager.
     * Avoids repeated Camera.getInstance() calls when checking visibility.
     */
    private static CameraBounds cameraBounds = new CameraBounds(0, 0, 320, 224);

    protected final ObjectSpawn spawn;
    protected final String name;
    private boolean destroyed;

    protected AbstractObjectInstance(ObjectSpawn spawn, String name) {
        this.spawn = spawn;
        this.name = name;
    }

    /**
     * Updates the cached camera bounds in place. Called once per frame by ObjectManager
     * before any object updates run.
     */
    public static void updateCameraBounds(int left, int top, int right, int bottom) {
        cameraBounds.update(left, top, right, bottom);
    }

    @Override
    public ObjectSpawn getSpawn() {
        return spawn;
    }

    public String getName() {
        return name;
    }

    protected void setDestroyed(boolean destroyed) {
        this.destroyed = destroyed;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Default no-op.
    }

    @Override
    public abstract void appendRenderCommands(List<GLCommand> commands);

    /**
     * Checks if this object is currently visible on screen.
     * ROM: render_flags.on_screen bit is set by MarkObjGone when object
     * is within camera bounds. Used by PlaySoundLocal (s2.asm line 1555)
     * to prevent off-screen objects from playing audio.
     * <p>
     * Uses pre-computed camera bounds (updated once per frame) for efficiency.
     *
     * @return true if the object is within the camera viewport
     */
    protected boolean isOnScreen() {
        return cameraBounds.contains(getX(), getY());
    }

    /**
     * Checks if this object is within the camera viewport with a margin.
     * Useful for projectiles that should persist slightly off-screen.
     *
     * @param margin pixels of extra space beyond camera bounds
     * @return true if the object is within the extended camera viewport
     */
    protected boolean isOnScreen(int margin) {
        return cameraBounds.contains(getX(), getY(), margin);
    }
}

