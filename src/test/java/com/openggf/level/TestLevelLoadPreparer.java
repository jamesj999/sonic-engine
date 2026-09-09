package com.openggf.level;

import com.openggf.level.resources.PreparedLevelBuild;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TestLevelLoadPreparer {
    @Test
    void matchingIdentityConsumesTheBuildOnlyOnce() {
        LevelLoadPreparer preparer = new LevelLoadPreparer();
        Object owner = new Object();
        PreparedLevelBuild build = mock(PreparedLevelBuild.class);
        preparer.prepare(owner, 1, "fire", () -> build);
        assertSame(build, preparer.take(owner, 1, "fire"));
        assertNull(preparer.take(owner, 1, "fire"));
    }

    @Test
    void anotherLoaderCannotConsumeAFormerOwnersBuild() {
        LevelLoadPreparer preparer = new LevelLoadPreparer();
        Object owner = new Object();
        preparer.prepare(owner, 1, "fire", () -> mock(PreparedLevelBuild.class));
        assertNull(preparer.take(new Object(), 1, "fire"));
        assertNull(preparer.take(owner, 1, "fire"));
    }

    @Test
    void anotherTargetDiscardsThePendingBuild() {
        LevelLoadPreparer preparer = new LevelLoadPreparer();
        Object owner = new Object();
        preparer.prepare(owner, 1, "fire", () -> mock(PreparedLevelBuild.class));
        assertNull(preparer.take(owner, 2, "fire"));
        assertNull(preparer.take(owner, 1, "fire"));
    }
}
