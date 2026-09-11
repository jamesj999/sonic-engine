package com.openggf.level.objects.boss;

import com.openggf.graphics.GLCommand;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;

import java.util.List;

/**
 * Interface for boss child components (propellers, weapons, decorative parts).
 * Children update their state based on the parent boss state.
 */
public interface BossChildComponent extends ObjectInstance {
    /**
     * Update the component's state.
     *
     * @param vIntRunCount Object-visible V-int run count
     * @param player       Player sprite
     */
    void update(int vIntRunCount, PlayableEntity player);

    /**
     * Append render commands for this component.
     *
     * @param commands List to append rendering commands to
     */
    void appendRenderCommands(List<GLCommand> commands);

    /**
     * Synchronize position with parent boss.
     * Called by parent after position updates.
     */
    void syncPositionWithParent();

    /**
     * Check if this component should be destroyed.
     *
     * @return true if component should be removed
     */
    boolean isDestroyed();

    /**
     * Mark this component as destroyed.
     */
    void setDestroyed(boolean destroyed);
}
