package com.openggf.level.resources;

import com.openggf.level.Level;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * A game whose level loads can be split into an off-thread build and a
 * frame-thread install.
 *
 * <p>Seamless act transitions (for example the S3K AIZ1 fire hand-off) already
 * wait several seconds on ROM-modelled decompression queues before the target
 * level is installed. The engine's own level construction is host-side work
 * with no ROM counterpart, so it may run during that wait instead of on the
 * install frame. Correctness contract:
 *
 * <ul>
 *   <li>{@link #prepareLevelBuildTask} captures configuration on the frame thread.
 *       Its returned task reads only the bound ROM and immutable inputs; it produces
 *       the same data as a synchronous load for those inputs, minus graphics publication.</li>
 *   <li>{@link #installPreparedLevel} runs on the frame thread and performs
 *       everything the synchronous load does after construction (graphics
 *       publication, load-time palette overrides).</li>
 *   <li>The install frame is chosen by the transition owner exactly as before;
 *       a build that is not yet complete is joined there, never skipped, so
 *       preparation can only change timing, not gameplay state.</li>
 * </ul>
 */
public interface PreparableLevelLoader {

    /**
     * Captures load inputs on the frame thread and returns the ROM-only build task.
     * The task must not resolve current-session services when it later executes.
     *
     * @param levelIndex  the game's level index
     * @param mutationKey the seamless-transition mutation the install will
     *                    apply afterwards, or {@code null}; the loader may
     *                    pre-apply the layout portion so tilemaps built from
     *                    this level already match the post-mutation layout
     */
    Callable<PreparedLevelBuild> prepareLevelBuildTask(int levelIndex, String mutationKey);

    /**
     * Installs a prepared build on the frame thread and returns the level
     * exactly as a synchronous {@code loadLevel(levelIndex)} would have.
     */
    Level installPreparedLevel(PreparedLevelBuild build) throws IOException;
}
