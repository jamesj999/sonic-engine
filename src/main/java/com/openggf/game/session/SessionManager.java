package com.openggf.game.session;

import com.openggf.architecture.CompositionRoot;
import com.openggf.game.GameModule;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;

import java.util.Objects;

@CompositionRoot
public final class SessionManager {
    private static volatile WorldSession currentWorldSession;
    private static volatile GameplayModeContext currentGameplayMode;
    private static volatile EditorModeContext currentEditorMode;
    private static HardwareReadinessAdmissionPolicy nextGameplayAdmissionPolicy =
            HardwareReadinessAdmissionPolicy.LIVE;
    private static final EditorSessionFactory EDITOR_SESSION_FACTORY = new EditorSessionFactory();

    private SessionManager() {
    }

    public static synchronized GameplayModeContext openGameplaySession(GameModule module) {
        return openGameplaySession(
                module, null, consumeNextGameplayAdmissionPolicy());
    }

    public static synchronized GameplayModeContext openGameplaySession(
            GameModule module,
            HardwareReadinessAdmissionPolicy admissionPolicy) {
        return openGameplaySession(module, null, admissionPolicy);
    }

    public static synchronized GameplayModeContext openGameplaySession(GameModule module,
                                                                       SaveSessionContext saveSessionContext) {
        return openGameplaySession(
                module, saveSessionContext, consumeNextGameplayAdmissionPolicy());
    }

    public static synchronized GameplayModeContext openGameplaySession(
            GameModule module,
            SaveSessionContext saveSessionContext,
            HardwareReadinessAdmissionPolicy admissionPolicy) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(admissionPolicy, "admissionPolicy");
        nextGameplayAdmissionPolicy = HardwareReadinessAdmissionPolicy.LIVE;
        destroyCurrentMode();
        currentWorldSession = new WorldSession(module, saveSessionContext);
        currentGameplayMode =
                new GameplayModeContext(currentWorldSession, admissionPolicy);
        return currentGameplayMode;
    }

    public static synchronized void armNextGameplayAdmissionPolicy(
            HardwareReadinessAdmissionPolicy admissionPolicy) {
        nextGameplayAdmissionPolicy =
                Objects.requireNonNull(admissionPolicy, "admissionPolicy");
    }

    public static synchronized void clearNextGameplayAdmissionPolicy() {
        nextGameplayAdmissionPolicy = HardwareReadinessAdmissionPolicy.LIVE;
    }

    public static synchronized GameplayModeContext reopenGameplaySession(
            HardwareReadinessAdmissionPolicy admissionPolicy) {
        Objects.requireNonNull(admissionPolicy, "admissionPolicy");
        if (currentWorldSession == null) {
            throw new IllegalStateException(
                    "Cannot reopen gameplay without an active world session");
        }
        destroyCurrentMode();
        currentGameplayMode =
                new GameplayModeContext(currentWorldSession, admissionPolicy);
        return currentGameplayMode;
    }

    public static synchronized EditorModeContext enterEditorMode(EditorCursorState cursor) {
        return enterEditorMode(cursor, null);
    }

    public static synchronized EditorModeContext enterEditorMode(EditorCursorState cursor,
                                                                 EditorPlaytestStash playtestStash) {
        Objects.requireNonNull(cursor, "cursor");
        if (currentWorldSession == null) {
            throw new IllegalStateException("Cannot enter editor mode without an active world session.");
        }
        destroyCurrentMode();
        currentEditorMode = EDITOR_SESSION_FACTORY.create(
                currentWorldSession, EngineServices.current(), cursor, playtestStash);
        return currentEditorMode;
    }

    public static synchronized GameplayModeContext exitEditorMode() {
        return resumeGameplayFromEditor();
    }

    public static synchronized GameplayModeContext resumeGameplayFromEditor() {
        if (currentEditorMode == null) {
            throw new IllegalStateException("Cannot exit editor mode without an active editor mode.");
        }
        EditorCursorState cursor = currentEditorMode.getCursor();
        EditorPlaytestStash playtestStash = currentEditorMode.getPlaytestStash();
        WorldSession worldSession = currentEditorMode.getWorldSession();
        destroyCurrentMode();
        currentGameplayMode = new GameplayModeContext(worldSession, cursor.x(), cursor.y(), playtestStash);
        return currentGameplayMode;
    }

    public static synchronized GameplayModeContext restartGameplayFromBeginning() {
        if (currentEditorMode == null) {
            throw new IllegalStateException("Cannot restart gameplay without an active editor mode.");
        }
        WorldSession worldSession = currentEditorMode.getWorldSession();
        destroyCurrentMode();
        currentGameplayMode = new GameplayModeContext(worldSession);
        return currentGameplayMode;
    }

    public static synchronized void clear() {
        try {
            destroyCurrentMode();
        } finally {
            currentWorldSession = null;
            clearNextGameplayAdmissionPolicy();
        }
    }

    public static synchronized void closeGameplaySession() {
        clear();
    }

    public static synchronized GameModule requireCurrentGameModule() {
        if (currentWorldSession == null) {
            throw new IllegalStateException("No active WorldSession");
        }
        return currentWorldSession.getGameModule();
    }

    private static void destroyCurrentMode() {
        boolean hadRuntimeMode = currentGameplayMode != null || currentEditorMode != null;
        Throwable failure = null;
        try {
            if (currentGameplayMode != null) {
                currentGameplayMode.destroy();
            }
        } catch (RuntimeException | Error teardownFailure) {
            failure = teardownFailure;
        } finally {
            currentGameplayMode = null;
        }
        try {
            if (currentEditorMode != null) {
                currentEditorMode.destroy();
            }
        } catch (RuntimeException | Error teardownFailure) {
            failure = retainTeardownFailure(failure, teardownFailure);
        } finally {
            currentEditorMode = null;
        }
        try {
            if (hadRuntimeMode) {
                EngineServices.current().graphics().clearRuntimeManagedReferences();
            }
        } catch (RuntimeException | Error teardownFailure) {
            failure = retainTeardownFailure(failure, teardownFailure);
        }
        if (failure != null) {
            // A rejected replay close still disposes the mode. Do not publish its
            // world or let a failed mode switch carry admission into the next run.
            currentWorldSession = null;
            clearNextGameplayAdmissionPolicy();
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw (Error) failure;
        }
    }

    private static Throwable retainTeardownFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    public static WorldSession getCurrentWorldSession() {
        return currentWorldSession;
    }

    public static GameplayModeContext getCurrentGameplayMode() {
        return currentGameplayMode;
    }

    public static EditorModeContext getCurrentEditorMode() {
        return currentEditorMode;
    }

    private static HardwareReadinessAdmissionPolicy
    consumeNextGameplayAdmissionPolicy() {
        HardwareReadinessAdmissionPolicy policy =
                nextGameplayAdmissionPolicy;
        nextGameplayAdmissionPolicy = HardwareReadinessAdmissionPolicy.LIVE;
        return policy;
    }

}
