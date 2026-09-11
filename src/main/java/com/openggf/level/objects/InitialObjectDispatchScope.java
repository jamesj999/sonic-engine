package com.openggf.level.objects;

/**
 * Exception-safe ownership envelope for the one native level-load
 * {@code Process_Sprites} object dispatch.
 */
@FunctionalInterface
public interface InitialObjectDispatchScope extends AutoCloseable {
    @Override
    void close();
}
