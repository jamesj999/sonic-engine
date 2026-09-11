package com.openggf.game;

/**
 * Optional capability for level event providers that maintain checkpoint
 * runtime state outside the shared base event counters.
 */
public interface CheckpointRuntimeStateProvider {
    int checkpointDynamicResizeRoutine();
}
