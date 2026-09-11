package com.openggf.game.recording;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

public final class RecordingMainPlayerResolver {
    private RecordingMainPlayerResolver() {
    }

    public static AbstractPlayableSprite resolve(SonicConfigurationService configService,
                                                 SpriteManager spriteManager) {
        String mainCode = ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
        Sprite sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            return playable;
        }
        throw new IllegalStateException("Main playable sprite not available for code: " + mainCode);
    }
}
