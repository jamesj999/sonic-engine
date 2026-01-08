package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.level.objects.ObjectInstance;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Interface for projectiles fired by Badniks.
 * Each Badnik can define its own projectile graphics and behavior
 * while sharing common collision logic.
 */
public interface BadnikProjectile extends ObjectInstance {
    /**
     * Called when the projectile hits the player.
     */
    void onPlayerHit(AbstractPlayableSprite player);

    /**
     * Returns true if the projectile should be destroyed.
     */
    boolean shouldDestroy();
}
