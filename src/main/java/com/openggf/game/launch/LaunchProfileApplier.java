package com.openggf.game.launch;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.MasterTitleScreen;

import java.util.Objects;

public class LaunchProfileApplier {
    private final SonicConfigurationService configService;

    public LaunchProfileApplier(SonicConfigurationService configService) {
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    public void apply(LaunchProfile profile, MasterTitleScreen.GameEntry entry) {
        Objects.requireNonNull(profile, "profile");
        LaunchProfile sanitized = profile.sanitizedFor(Objects.requireNonNull(entry, "entry"));
        configService.setSessionOverride(SonicConfiguration.LIVE_REWIND_ENABLED, sanitized.rewind());
        if ("off".equals(sanitized.crossGameSource())) {
            configService.setSessionOverride(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
            configService.setSessionOverride(SonicConfiguration.CROSS_GAME_SOURCE,
                    configService.getDefaultValue(SonicConfiguration.CROSS_GAME_SOURCE));
        } else {
            configService.setSessionOverride(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, true);
            configService.setSessionOverride(SonicConfiguration.CROSS_GAME_SOURCE, sanitized.crossGameSource());
        }
        configService.setSessionOverride(SonicConfiguration.DEBUG_VIEW_ENABLED, sanitized.debugTools());
        if (!sanitized.usesS3kDataSelectCharacters()) {
            configService.setSessionOverride(SonicConfiguration.MAIN_CHARACTER_CODE, sanitized.mainCharacter());
            configService.setSessionOverride(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                    "none".equals(sanitized.sidekick()) ? "" : sanitized.sidekick());
        }
        if (!"global".equals(sanitized.aspect())) {
            configService.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, sanitized.aspect());
        }
    }
}
