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
     * Returns true if this object should stay in the active spawn set even after being
     * marked as remembered. Used by objects like monitors and capsules that need to
     * complete their destruction/animation sequence before being removed.
     * <p>
     * Objects that return true will:
     * - Be marked as remembered (won't respawn after death/restart)
     * - Stay in the active set to complete their logic
     * - Self-destruct by calling setDestroyed(true) when done
     * <p>
     * Default is false - most objects are removed from active immediately when remembered.
     */
    default boolean shouldStayActiveWhenRemembered() {
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
