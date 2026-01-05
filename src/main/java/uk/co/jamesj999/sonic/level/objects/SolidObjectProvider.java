package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public interface SolidObjectProvider {
    SolidObjectParams getSolidParams();

    default boolean isSolidFor(AbstractPlayableSprite player) {
        return true;
    }
}
