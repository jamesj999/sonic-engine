package com.openggf.util;

import com.openggf.game.session.SessionManager;
import com.openggf.level.render.SpriteMappingFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class TestLazyMappingHolder {

    @BeforeEach
    public void clearGameplaySession() {
        SessionManager.clear();
    }

    @Test
    public void testReturnsEmptyListWhenLevelManagerNull() {
        LazyMappingHolder holder = new LazyMappingHolder();
        List<SpriteMappingFrame> result = holder.get(0x1234,
                (reader, addr) -> { throw new AssertionError("should not be called"); },
                "TestObj");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testLoadAttemptedOnlyOnce() {
        LazyMappingHolder holder = new LazyMappingHolder();
        // First call - LevelManager is null, so loader won't be called, returns empty
        holder.get(0x1234, (reader, addr) -> Collections.emptyList(), "TestObj");
        // Second call - should not retry loading
        List<SpriteMappingFrame> result = holder.get(0x1234,
                (reader, addr) -> { throw new AssertionError("should not retry"); },
                "TestObj");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}


