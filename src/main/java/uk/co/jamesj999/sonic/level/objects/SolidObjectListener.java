package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public interface SolidObjectListener {
    void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter);
}
