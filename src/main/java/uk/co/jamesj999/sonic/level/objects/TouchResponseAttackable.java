package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public interface TouchResponseAttackable {
    void onPlayerAttack(AbstractPlayableSprite player, TouchResponseResult result);
}
