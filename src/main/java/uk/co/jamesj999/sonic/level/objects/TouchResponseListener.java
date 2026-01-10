package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public interface TouchResponseListener {
    void onTouchResponse(AbstractPlayableSprite player, TouchResponseResult result, int frameCounter);
}
