package com.openggf.game.dataselect;

import com.openggf.game.DataSelectProvider;

/**
 * Base class for data select providers with shared state management.
 * Tracks lifecycle state and a pending action to be consumed by the game loop.
 */
public abstract class AbstractDataSelectProvider implements DataSelectProvider {

    protected State state = State.INACTIVE;
    protected DataSelectAction pendingAction = DataSelectAction.none();
    protected DataSelectSessionController sessionController;
    protected String launchErrorMessage;

    /**
     * Consumes and returns the pending action, resetting it to {@link DataSelectAction#none()}.
     *
     * @return the action that was pending, or a NONE action if nothing was pending
     */
    public DataSelectAction consumePendingAction() {
        if (sessionController != null) {
            return sessionController.consumePendingAction();
        }
        DataSelectAction action = pendingAction;
        pendingAction = DataSelectAction.none();
        return action;
    }

    public DataSelectSessionController getSessionController() {
        return sessionController;
    }

    @Override
    public DataSelectExitTransition exitTransition() {
        return sessionController != null
                ? sessionController.hostProfile().exitTransition()
                : DataSelectProvider.super.exitTransition();
    }

    @Override
    public void showLaunchError(String message) {
        launchErrorMessage = message == null || message.isBlank()
                ? "Unable to load selected save."
                : message;
        pendingAction = DataSelectAction.none();
        if (sessionController != null) {
            sessionController.queuePendingAction(DataSelectAction.none());
        }
        state = State.ACTIVE;
    }

    @Override
    public java.util.Optional<String> launchErrorMessage() {
        return java.util.Optional.ofNullable(launchErrorMessage);
    }

    protected void attachSessionController(DataSelectSessionController controller) {
        this.sessionController = controller;
    }
}
