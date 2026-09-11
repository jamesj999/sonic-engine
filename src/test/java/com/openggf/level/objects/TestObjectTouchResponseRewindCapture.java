package com.openggf.level.objects;

import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestObjectTouchResponseRewindCapture {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void captureOmitsEmptySidekickOverlapEntries() throws Exception {
        ObjectTouchResponseController controller = new ObjectTouchResponseController(null, null);
        Tails sidekick = new Tails("tails_p2", (short) 0, (short) 0);
        sidekick.setCpuControlled(true);

        sidekickOverlapMap(controller).put(sidekick, newEmptyOverlapPair());

        var snapshot = controller.captureRewindState();

        assertTrue(snapshot.sidekickEntries().isEmpty(),
                "empty per-sidekick overlap buffers should not allocate snapshot entries");
    }

    @SuppressWarnings("unchecked")
    private static Map<com.openggf.game.PlayableEntity, Object> sidekickOverlapMap(
            ObjectTouchResponseController controller) throws Exception {
        Field field = ObjectTouchResponseController.class.getDeclaredField("sidekickOverlaps");
        field.setAccessible(true);
        return (Map<com.openggf.game.PlayableEntity, Object>) field.get(controller);
    }

    private static Object newEmptyOverlapPair() throws Exception {
        Class<?> pairClass = Class.forName(
                "com.openggf.level.objects.ObjectTouchResponseController$OverlapBufferPair");
        Constructor<?> constructor = pairClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
