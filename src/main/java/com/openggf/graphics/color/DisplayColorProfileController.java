package com.openggf.graphics.color;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.graphics.GraphicsManager;

import java.util.Objects;
import java.util.function.Consumer;

public final class DisplayColorProfileController {
    public static final int NOTIFICATION_FRAMES = 120;

    private final int toggleKey;
    private final Consumer<DisplayColorProfile> persistProfile;
    private final Consumer<DisplayColorProfile> applyProfile;
    private final Runnable refreshPalettes;
    private DisplayColorProfile currentProfile;
    private String notificationText;
    private int notificationFramesRemaining;

    public DisplayColorProfileController(DisplayColorProfile initialProfile,
                                         int toggleKey,
                                         Consumer<DisplayColorProfile> persistProfile,
                                         Runnable refreshPalettes) {
        this(initialProfile, toggleKey, persistProfile, profile -> {
        }, refreshPalettes);
    }

    public DisplayColorProfileController(DisplayColorProfile initialProfile,
                                         int toggleKey,
                                         Consumer<DisplayColorProfile> persistProfile,
                                         Consumer<DisplayColorProfile> applyProfile,
                                         Runnable refreshPalettes) {
        this.currentProfile = initialProfile == null ? DisplayColorProfile.RAW_RGB : initialProfile;
        this.toggleKey = toggleKey;
        this.persistProfile = Objects.requireNonNull(persistProfile, "persistProfile");
        this.applyProfile = Objects.requireNonNull(applyProfile, "applyProfile");
        this.refreshPalettes = Objects.requireNonNull(refreshPalettes, "refreshPalettes");
    }

    public static DisplayColorProfileController fromConfig(SonicConfigurationService configService,
                                                           GraphicsManager graphicsManager,
                                                           Runnable refreshPalettes) {
        Objects.requireNonNull(configService, "configService");
        Objects.requireNonNull(graphicsManager, "graphicsManager");
        Objects.requireNonNull(refreshPalettes, "refreshPalettes");

        DisplayColorProfile profile = DisplayColorProfile.parse(
                configService.getString(SonicConfiguration.DISPLAY_COLOR_PROFILE));
        graphicsManager.setDisplayColorProfile(profile);

        return new DisplayColorProfileController(
                profile,
                configService.getInt(SonicConfiguration.DISPLAY_COLOR_PROFILE_TOGGLE_KEY),
                nextProfile -> {
                    configService.setConfigValue(SonicConfiguration.DISPLAY_COLOR_PROFILE, nextProfile.name());
                    configService.saveConfig();
                },
                graphicsManager::setDisplayColorProfile,
                refreshPalettes);
    }

    public void update(InputHandler inputHandler) {
        if (inputHandler != null && toggleKey >= 0 && inputHandler.isKeyPressed(toggleKey)) {
            currentProfile = currentProfile.next();
            persistProfile.accept(currentProfile);
            applyProfile.accept(currentProfile);
            refreshPalettes.run();
            notificationText = "Color: " + currentProfile.label();
            notificationFramesRemaining = NOTIFICATION_FRAMES;
            return;
        }

        if (notificationFramesRemaining > 0) {
            notificationFramesRemaining--;
            if (notificationFramesRemaining == 0) {
                notificationText = null;
            }
        }
    }

    public DisplayColorProfile currentProfile() {
        return currentProfile;
    }

    public String notificationText() {
        return notificationFramesRemaining > 0 ? notificationText : null;
    }
}
