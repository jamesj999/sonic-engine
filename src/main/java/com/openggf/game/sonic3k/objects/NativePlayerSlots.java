package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Resolves the two player slots addressed directly by S3K object routines.
 *
 * <p>This remains package-private because {@link ObjectPlayerQuery} is a pinned
 * Mod API type. Native slot fallback is engine execution policy, not creator API.
 */
record NativePlayerSlots(AbstractPlayableSprite p1, AbstractPlayableSprite p2) {

    static NativePlayerSlots resolve(ObjectPlayerQuery query, PlayableEntity updatePlayer) {
        PlayableEntity main = query.mainPlayerOrNull();
        if (!(main instanceof AbstractPlayableSprite) && updatePlayer instanceof AbstractPlayableSprite) {
            main = updatePlayer;
        }

        AbstractPlayableSprite p1 = main instanceof AbstractPlayableSprite sprite ? sprite : null;
        AbstractPlayableSprite p2 = null;
        for (PlayableEntity candidate : query.playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (candidate == main || !(candidate instanceof AbstractPlayableSprite sprite)) {
                continue;
            }
            p2 = sprite;
            break;
        }
        if (p2 == p1) {
            p2 = null;
        }
        return new NativePlayerSlots(p1, p2);
    }

    AbstractPlayableSprite player(int slot) {
        return switch (slot) {
            case 0 -> p1;
            case 1 -> p2;
            default -> null;
        };
    }
}
