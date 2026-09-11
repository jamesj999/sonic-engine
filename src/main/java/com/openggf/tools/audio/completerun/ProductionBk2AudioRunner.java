package com.openggf.tools.audio.completerun;

import com.openggf.Engine;
import com.openggf.ExternalFrameOrInputOwnership;
import com.openggf.GameLoop;
import com.openggf.InputBindingFactory;
import com.openggf.audio.AudioManager;
import com.openggf.audio.HeadlessSmpsAudioBackend;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.game.session.EngineContext;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.PreRowBoundary;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.RowObservation;

import java.util.Objects;

/**
 * Authenticated one-BK2-row/one-production-frame audio runner.
 *
 * <p>The runner owns its startup route, input cursor, headless SMPS backend,
 * diagnostic lease and outer-frame presentation. Observations leave this
 * boundary only after the row cursor advances, and never feed runtime state.
 */
public final class ProductionBk2AudioRunner {
    private ProductionBk2AudioRunner() {
    }

    /** One immutable, fully presented row and its exact logical boundaries. */
    public record RowResult(int absoluteFrame,
            PreRowBoundary preRow,
            RowObservation observation) {
        public RowResult {
            if (absoluteFrame < 0) {
                throw new IllegalArgumentException(
                        "absoluteFrame must be non-negative");
            }
            Objects.requireNonNull(preRow, "preRow");
            Objects.requireNonNull(observation, "observation");
            if (preRow.absoluteFrame() != absoluteFrame
                    || observation.absoluteFrame() != absoluteFrame) {
                throw new IllegalArgumentException(
                        "row result boundaries must identify the same frame");
            }
        }
    }

    @FunctionalInterface
    public interface RowConsumer {
        void accept(RowResult row) throws Exception;
    }

    /**
     * Runs every row of {@code movie} through the configured production boot.
     * The host configuration is validated, never rewritten.
     */
    public static void run(EngineContext context, Bk2Movie movie,
            RowConsumer consumer) throws Exception {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(movie, "movie");
        Objects.requireNonNull(consumer, "consumer");
        if (movie.getFrameCount() == 0) {
            throw new IllegalArgumentException(
                    "authenticated audio requires at least one BK2 row");
        }
        requireAuthenticatedConfiguration(context.configuration());
        requireExclusiveFrameDrive(
                ExternalFrameOrInputOwnership.active(context));

        AudioManager audio = context.audio();
        InputHandler input = new InputHandler(
                InputBindingFactory.supplier(context.configuration()));
        HeadlessSmpsAudioBackend backend = new HeadlessSmpsAudioBackend(
                context.configuration(), context.profiler());
        Bk2InputCursor cursor = new Bk2InputCursor(movie);
        Engine engine = new Engine(context);
        GameLoop loop = engine.getGameLoop();
        CompleteRunAudioObserverLease observations = null;
        Throwable primaryFailure = null;
        try {
            requireExclusiveFrameDrive(
                    loop.externalFrameOrInputOwnerActive());
            observations = CompleteRunAudioObserverLease.acquire(audio);
            requireExclusiveFrameDrive(
                    loop.externalFrameOrInputOwnerActive());
            engine.initializeConfiguredHeadlessSession(input, backend);
            requireRetainedIdentity(audio, loop, input, backend);
            driveRows(audio, loop, input, backend, cursor,
                    observations, consumer);
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = primaryFailure;
            CompleteRunAudioObserverLease installedObservations = observations;
            if (installedObservations != null) {
                cleanupFailure = appendCleanupFailure(
                        cleanupFailure, installedObservations::close);
            }
            cleanupFailure = appendCleanupFailure(
                    cleanupFailure, engine::closeConfiguredHeadlessSession);
            cleanupFailure = appendCleanupFailure(
                    cleanupFailure, input::clearLogicalOverride);
            cleanupFailure = appendCleanupFailure(
                    cleanupFailure, Engine::clearGlobalInstance);
            if (primaryFailure == null && cleanupFailure != null) {
                rethrowCleanupFailure(cleanupFailure);
            }
        }
    }

    static void driveRows(AudioManager audio, GameLoop loop,
            InputHandler input, HeadlessSmpsAudioBackend backend,
            Bk2InputCursor cursor,
            CompleteRunAudioObserverLease observations,
            RowConsumer consumer) throws Exception {
        Objects.requireNonNull(audio, "audio");
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(consumer, "consumer");
        while (!cursor.exhausted()) {
            requireExclusiveFrameDrive(
                    loop.externalFrameOrInputOwnerActive());
            requireRetainedIdentity(audio, loop, input, backend);
            int absoluteFrame = cursor.absoluteFrame();
            AudioLogicalSnapshot preSnapshot =
                    audio.captureLogicalSnapshot();
            PreRowBoundary preRow = observations.beginRow(
                    absoluteFrame, preSnapshot);
            cursor.publish(input);
            loop.step();
            requireRetainedIdentity(audio, loop, input, backend);
            Engine.presentOuterAudioFrame(loop, false, false);
            AudioLogicalSnapshot postSnapshot =
                    audio.captureLogicalSnapshot();
            requireRetainedIdentity(audio, loop, input, backend);
            RowObservation observation = observations.finishRow(
                    absoluteFrame, postSnapshot);
            cursor.advance();
            consumer.accept(new RowResult(
                    absoluteFrame, preRow, observation));
        }
    }

    static void requireAuthenticatedConfiguration(
            SonicConfigurationService configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.getBoolean(SonicConfiguration.AUDIO_ENABLED)) {
            throw new IllegalStateException(
                    "authenticated BK2 audio requires audio.enabled=true");
        }
        if (configuration.getBoolean(
                SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP)) {
            throw new IllegalStateException(
                    "authenticated BK2 audio cannot drive the legal screen");
        }
        if (configuration.getBoolean(
                SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP)) {
            throw new IllegalStateException(
                    "authenticated BK2 audio cannot drive the master title");
        }
    }

    private static void requireExclusiveFrameDrive(boolean externalOwner) {
        if (externalOwner) {
            throw new IllegalStateException(
                    "another production owner controls frame or input cardinality");
        }
    }

    private static void requireRetainedIdentity(AudioManager audio,
            GameLoop loop, InputHandler input,
            HeadlessSmpsAudioBackend backend) {
        if (loop.getInputHandler() != input) {
            throw new IllegalStateException(
                    "configured BK2 input handler identity was replaced");
        }
        if (!audio.hasInstalledBackend(backend)) {
            throw new IllegalStateException(
                    "configured headless SMPS backend identity was replaced");
        }
    }

    static Throwable appendCleanupFailure(Throwable aggregate,
            Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error failure) {
            if (aggregate == null) {
                return failure;
            }
            aggregate.addSuppressed(failure);
        }
        return aggregate;
    }

    private static void rethrowCleanupFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }
}
