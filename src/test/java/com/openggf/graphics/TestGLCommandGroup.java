package com.openggf.graphics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class TestGLCommandGroup {
    @Test
    void constructorSnapshotsCommandListBeforeCallerReusesIt() throws Exception {
        GLCommand first = mock(GLCommand.class);
        GLCommand second = mock(GLCommand.class);
        List<GLCommand> source = new ArrayList<>();
        source.add(first);

        GLCommandGroup group = new GLCommandGroup(0, source);
        source.clear();
        source.add(second);

        List<GLCommand> commands = commands(group);
        assertEquals(1, commands.size());
        assertSame(first, commands.get(0));
    }

    @SuppressWarnings("unchecked")
    private static List<GLCommand> commands(GLCommandGroup group) throws Exception {
        Field field = GLCommandGroup.class.getDeclaredField("commands");
        field.setAccessible(true);
        return (List<GLCommand>) field.get(group);
    }
}
