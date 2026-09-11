package com.openggf.level.resources;

import com.openggf.level.Level;

/**
 * A level built ahead of its install by {@link PreparableLevelLoader}.
 *
 * <p>The build is pure ROM-derived data: it holds no reference to live
 * gameplay state and nothing has been published to the graphics manager yet.
 * Only {@link PreparableLevelLoader#installPreparedLevel} may turn it into
 * the current level, on the frame thread.
 */
public interface PreparedLevelBuild {
    /** Level index this build was prepared for. */
    int levelIndex();

    /** The built level, not yet installed or published to graphics. */
    Level level();
}
