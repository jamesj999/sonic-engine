package com.openggf.level.resources;

import com.openggf.level.Level;

import java.io.IOException;

/**
 * Game-specific level loading that can consume a transition-scoped deferred
 * resource tracker.
 */
public interface DeferredLevelResourceLoader {

    Level loadLevelWithDeferredResources(
            int levelIndex,
            DeferredLevelResourceTracker deferredResources)
            throws IOException;
}
