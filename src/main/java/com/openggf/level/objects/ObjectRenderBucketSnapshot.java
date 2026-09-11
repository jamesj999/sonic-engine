package com.openggf.level.objects;

import com.openggf.graphics.RenderPriority;

import java.util.Collection;

/**
 * Tracks the object identities and render-priority inputs used by the last
 * render-bucket rebuild.
 */
final class ObjectRenderBucketSnapshot {
    private ObjectInstance[] instances = new ObjectInstance[64];
    private long[] keys = new long[64];
    private int count;

    static {
        if (RenderPriority.MAX - RenderPriority.MIN >= 8) {
            throw new AssertionError("renderBucketKey bucket bits overflow");
        }
    }

    boolean inputsChanged(Collection<? extends ObjectInstance> active,
                          Collection<? extends ObjectInstance> dynamic) {
        if (active.size() + dynamic.size() != count) {
            return true;
        }
        int position = compare(active, 0);
        if (position < 0) {
            return true;
        }
        position = compare(dynamic, position);
        return position < 0 || position != count;
    }

    void capture(Collection<? extends ObjectInstance> active,
                 Collection<? extends ObjectInstance> dynamic) {
        int required = active.size() + dynamic.size();
        if (instances.length < required) {
            int newLength = Math.max(required, instances.length * 2);
            instances = new ObjectInstance[newLength];
            keys = new long[newLength];
        }
        int position = capture(active, 0);
        position = capture(dynamic, position);
        for (int i = position; i < count; i++) {
            instances[i] = null;
        }
        count = position;
    }

    private int compare(Collection<? extends ObjectInstance> source, int position) {
        for (ObjectInstance instance : source) {
            if (position >= count
                    || instances[position] != instance
                    || keys[position] != key(instance)) {
                return -1;
            }
            position++;
        }
        return position;
    }

    private int capture(Collection<? extends ObjectInstance> source, int position) {
        for (ObjectInstance instance : source) {
            instances[position] = instance;
            keys[position] = key(instance);
            position++;
        }
        return position;
    }

    private static long key(ObjectInstance instance) {
        long slot = instance instanceof AbstractObjectInstance object
                ? object.getSlotIndex()
                : Integer.MAX_VALUE;
        int bucket = RenderPriority.clamp(instance.getPriorityBucket()) - RenderPriority.MIN;
        return (slot << 8)
                | (long) (bucket << 1)
                | (instance.isHighPriority() ? 1L : 0L);
    }
}
