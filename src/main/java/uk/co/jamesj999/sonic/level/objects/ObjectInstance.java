package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public interface ObjectInstance {
    ObjectSpawn getSpawn();

    default int getX() {
        return getSpawn().x();
    }

    default int getY() {
        return getSpawn().y();
    }

    void update(int frameCounter, AbstractPlayableSprite player);

    void appendRenderCommands(List<GLCommand> commands);

    boolean isHighPriority();
    default int getPriorityBucket() {
        return RenderPriority.MIN;
    }

    boolean isDestroyed();

    /**
     * Returns true if this object should remain active even when its spawn position
     * is outside the camera window. Used by objects like spin tubes that need to
     * continue controlling the player after they've moved far from the object's origin.
     */
    default boolean isPersistent() {
        return false;
    }

    /**
     * Called when this object is being unloaded from the active object list.
     * Override to perform cleanup when the object goes off-screen or is removed.
     * Default implementation does nothing.
     */
    default void onUnload() {
        // Default no-op
    }
}
