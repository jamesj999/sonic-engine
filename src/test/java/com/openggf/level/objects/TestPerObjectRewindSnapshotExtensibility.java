package com.openggf.level.objects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestPerObjectRewindSnapshotExtensibility {
    private record ExternalObjectExtra(int value)
            implements PerObjectRewindSnapshot.ObjectSubclassRewindExtra {}

    private record ExternalBadnikExtra(int value)
            implements PerObjectRewindSnapshot.BadnikSubclassRewindExtra {}

    @Test
    void objectAndBadnikSubclassExtrasCanBeDeclaredOutsideCentralSnapshotFile() {
        assertInstanceOf(PerObjectRewindSnapshot.ObjectSubclassRewindExtra.class,
                new ExternalObjectExtra(1));
        assertInstanceOf(PerObjectRewindSnapshot.BadnikSubclassRewindExtra.class,
                new ExternalBadnikExtra(2));
    }
}
