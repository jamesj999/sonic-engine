package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(SingletonResetExtension.class)
@FullReset
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class TestBuggernautBadnikInstance {

    @BeforeEach
    void setUp() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    void tearDown() {
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void chaseTargetingUsesNativeP1P2AndIgnoresExtendedEngineSidekicks() throws Exception {
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x220, (short) 0x100);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x160, (short) 0x100);
        TestablePlayableSprite extendedSidekick =
                new TestablePlayableSprite("knuckles", (short) 0x110, (short) 0x100);
        nativeP2.setCpuControlled(true);
        extendedSidekick.setCpuControlled(true);

        BuggernautBadnikInstance buggernaut = new BuggernautBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.BUGGERNAUT, 0, 0, false, 0));
        buggernaut.setServices(new QueryOnlyPlayerServices(main, List.of(nativeP2, extendedSidekick)));
        writeField(buggernaut, "babySpawned", true);
        writeState(buggernaut, "CHASE");

        buggernaut.update(0, main);

        assertFalse(buggernaut.badnikFacingLeft(),
                "Buggernaut Find_SonicTails targeting should pick native P2 to the right, not an extra sidekick");
    }

    private static void writeState(BuggernautBadnikInstance buggernaut, String stateName) throws Exception {
        Field field = BuggernautBadnikInstance.class.getDeclaredField("state");
        field.setAccessible(true);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Enum<?> state = Enum.valueOf((Class<? extends Enum>) field.getType(), stateName);
        field.set(buggernaut, state);
    }

    private static void writeField(BuggernautBadnikInstance buggernaut, String fieldName, Object value)
            throws Exception {
        Field field = BuggernautBadnikInstance.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(buggernaut, value);
    }

    private static final class QueryOnlyPlayerServices extends StubObjectServices {
        private final ObjectPlayerQuery playerQuery;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> sidekicks) {
            this.playerQuery = new ObjectPlayerQuery(() -> main, () -> sidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return playerQuery;
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }
    }
}
