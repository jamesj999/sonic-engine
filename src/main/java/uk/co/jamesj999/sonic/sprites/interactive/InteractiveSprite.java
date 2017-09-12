package uk.co.jamesj999.sonic.sprites.interactive;

import uk.co.jamesj999.sonic.sprites.AbstractSprite;

/**
 * Created by Jamesjohnstone on 01/04/15.
 */
public interface InteractiveSprite {

    /**
     * Action to take when colliding with a sprite.
     *
     * @param sprite The Sprite this sprite has just collided with.
     * @return true if this sprite should now be destroyed, false if it should remain.
     */
    public boolean onCollide(AbstractSprite sprite);

}
