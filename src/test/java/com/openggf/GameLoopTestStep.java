package com.openggf;

import com.openggf.game.session.SessionManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Drives a private GameLoop mode handler through the same PLC lifecycle frame
 * that production {@link GameLoop#step()} supplies.
 */
public final class GameLoopTestStep {
    private GameLoopTestStep() {
    }

    public static Object invoke(GameLoop loop, String methodName, Class<?>[] parameterTypes,
                                Object... arguments) throws Exception {
        var frame = Objects.requireNonNull(
                        SessionManager.getCurrentGameplayMode(),
                        "GameLoopTestStep requires an active gameplay mode")
                .plcFrameLifecycle()
                .latchBeforeFadeUpdate();
        Field activeFrame = GameLoop.class.getDeclaredField("activePlcLifecycleFrame");
        activeFrame.setAccessible(true);
        Method method = GameLoop.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        activeFrame.set(loop, frame);
        try {
            return method.invoke(loop, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failure;
        } finally {
            activeFrame.set(loop, null);
            frame.finish();
        }
    }
}
