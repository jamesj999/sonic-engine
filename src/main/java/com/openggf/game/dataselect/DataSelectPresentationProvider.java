package com.openggf.game.dataselect;

import com.openggf.control.InputHandler;
import com.openggf.game.DataSelectProvider;

import java.util.function.Function;
import java.util.Objects;

/**
 * Thin presentation seam around a concrete data-select renderer/provider.
 */
public final class DataSelectPresentationProvider extends AbstractDataSelectProvider {
    private final Function<DataSelectSessionController, ? extends DataSelectProvider> delegateFactory;
    private final DataSelectSessionController controller;
    private DataSelectProvider delegate;

    public DataSelectPresentationProvider(DataSelectProvider delegate,
                                          DataSelectSessionController controller) {
        this(ignored -> delegate, controller);
    }

    public DataSelectPresentationProvider(Function<DataSelectSessionController, ? extends DataSelectProvider> delegateFactory,
                                          DataSelectSessionController controller) {
        this.delegateFactory = Objects.requireNonNull(delegateFactory, "delegateFactory");
        this.controller = controller;
        attachSessionController(controller);
    }

    public DataSelectProvider delegate() {
        return requireDelegate();
    }

    public DataSelectSessionController controller() {
        return controller;
    }

    @Override
    public void initialize() {
        requireDelegate().initialize();
    }

    @Override
    public void update(InputHandler input) {
        requireDelegate().update(input);
    }

    @Override
    public void draw() {
        requireDelegate().draw();
    }

    @Override
    public void setClearColor() {
        requireDelegate().setClearColor();
    }

    @Override
    public void reset() {
        requireDelegate().reset();
    }

    @Override
    public void showLaunchError(String message) {
        requireDelegate().showLaunchError(message);
    }

    @Override
    public java.util.Optional<String> launchErrorMessage() {
        return requireDelegate().launchErrorMessage();
    }

    @Override
    public State getState() {
        return requireDelegate().getState();
    }

    @Override
    public boolean isExiting() {
        return requireDelegate().isExiting();
    }

    @Override
    public boolean isActive() {
        return requireDelegate().isActive();
    }

    private DataSelectProvider requireDelegate() {
        if (delegate == null) {
            delegate = Objects.requireNonNull(delegateFactory.apply(controller), "delegate");
            if (controller != null) {
                if (!(delegate instanceof AbstractDataSelectProvider provider)) {
                    throw new IllegalStateException("Data select delegate must accept controller injection");
                }
                provider.attachSessionController(controller);
            }
        }
        return delegate;
    }
}
