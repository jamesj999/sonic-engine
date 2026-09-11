package com.openggf.game.launch;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.MasterTitleScreen;

import java.util.Objects;
import java.util.logging.Logger;

public class MasterTitleLaunchCoordinator {
    private static final Logger LOGGER = Logger.getLogger(MasterTitleLaunchCoordinator.class.getName());

    private final SonicConfigurationService configService;
    private final LaunchProfileStore profileStore;
    private final LaunchProfileApplier profileApplier;
    private Runnable pendingLaunchCallback;
    private Runnable afterStepLaunchCallback;
    private Runnable returnToMasterTitleHandler;
    private Runnable launchFailureHandler;

    public MasterTitleLaunchCoordinator(SonicConfigurationService configService) {
        this(configService, new LaunchProfileStore(configService), new LaunchProfileApplier(configService));
    }

    public MasterTitleLaunchCoordinator(SonicConfigurationService configService,
                                        LaunchProfileStore profileStore,
                                        LaunchProfileApplier profileApplier) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.profileStore = Objects.requireNonNull(profileStore, "profileStore");
        this.profileApplier = Objects.requireNonNull(profileApplier, "profileApplier");
    }

    public void prepareExit(String selectedGameId, boolean programmaticSelection) {
        configService.clearSessionOverrides();
        MasterTitleScreen.GameEntry entry = MasterTitleScreen.GameEntry.fromGameId(selectedGameId);
        if (!programmaticSelection) {
            profileApplier.apply(profileStore.load(entry), entry);
        }
        configService.resolveDisplayAspect();
    }

    public void returnToMasterTitle() {
        resetOverrides();
        if (returnToMasterTitleHandler != null) {
            returnToMasterTitleHandler.run();
        }
    }

    public void restoreAfterFailedExit(String selectedGameId, Runnable fadeFromBlack) {
        resetOverrides();
        if (launchFailureHandler != null) {
            launchFailureHandler.run();
        }
        clearCallbacks();
        fadeFromBlack.run();
        LOGGER.info("Master title screen exit failed; restored master title for game: " + selectedGameId);
    }

    public void setPendingLaunchCallback(Runnable pendingLaunchCallback) {
        this.pendingLaunchCallback = pendingLaunchCallback;
    }

    public void clearPendingLaunchCallback() {
        pendingLaunchCallback = null;
    }

    public void stagePendingLaunchCallback() {
        afterStepLaunchCallback = pendingLaunchCallback;
        pendingLaunchCallback = null;
    }

    public void runAfterStepLaunchCallbackIfPresent() {
        Runnable callback = afterStepLaunchCallback;
        afterStepLaunchCallback = null;
        if (callback != null) {
            callback.run();
        }
    }

    public void setReturnToMasterTitleHandler(Runnable returnToMasterTitleHandler) {
        this.returnToMasterTitleHandler = returnToMasterTitleHandler;
    }

    public void setLaunchFailureHandler(Runnable launchFailureHandler) {
        this.launchFailureHandler = launchFailureHandler;
    }

    private void resetOverrides() {
        configService.clearSessionOverrides();
        configService.resolveDisplayAspect();
    }

    private void clearCallbacks() {
        pendingLaunchCallback = null;
        afterStepLaunchCallback = null;
    }
}
