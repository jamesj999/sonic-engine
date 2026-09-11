package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.GameServices;
import com.openggf.game.LevelGamestate;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestMegaChopperBadnikInstance {

    @BeforeEach
    public void setUp() throws Exception {
        SessionManager.clear();
        SessionManager.openGameplaySession(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();

        Field levelStateField = GameServices.level().getClass().getDeclaredField("levelGamestate");
        levelStateField.setAccessible(true);
        levelStateField.set(GameServices.level(), new LevelGamestate());
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void declaresContinuousTouchResponseProfile() {
        MegaChopperBadnikInstance megaChopper = new MegaChopperBadnikInstance(
                new ObjectSpawn(0x208, 0x180, Sonic3kObjectIds.MEGA_CHOPPER, 0, 0, false, 0));

        TouchResponseProfile expected = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                true,
                true,
                false,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        assertEquals(expected, megaChopper.getTouchResponseProfile());
        assertEquals(expected, megaChopper.getTouchResponseProfile(false));
        assertDoesNotThrow(() -> MegaChopperBadnikInstance.class
                .getDeclaredMethod("getTouchResponseProfile"));
        assertDoesNotThrow(() -> MegaChopperBadnikInstance.class
                .getDeclaredMethod("getTouchResponseProfile", boolean.class));
    }

    @Test
    public void captureKeepsPlayerMobileAndDrainsOneRingAfterSixtyFrames() throws Exception {
        RecordingServices services = new RecordingServices();
        MegaChopperBadnikInstance megaChopper = new MegaChopperBadnikInstance(
                new ObjectSpawn(0x208, 0x180, Sonic3kObjectIds.MEGA_CHOPPER, 0, 0, false, 0));
        megaChopper.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x200, (short) 0x180);
        player.setRingCount(3);
        GameServices.camera().setFocusedSprite(player);

        // Obj_MegaChopper begins with jsr (Obj_WaitOffscreen).l (sonic3k.asm:184233),
        // so no routine runs until Render_Sprites has drawn the placeholder. These
        // cases exercise post-Init behaviour directly, so release the gate here as
        // production does once render_flags bit 7 is set.
        megaChopper.testReleaseOffscreenWait();

        megaChopper.onTouchResponse(player, new TouchResponseResult(0x17, 0x20, 0x20, TouchCategory.SPECIAL), 0);
        megaChopper.update(0, player);

        assertFalse(player.isControlLocked());
        assertFalse(player.isObjectControlled());
        assertEquals("CARRY", readState(megaChopper));
        assertEquals(0x208, megaChopper.getX());
        assertEquals(0x180, megaChopper.getY());

        player.setCentreX((short) 0x218);
        player.setCentreY((short) 0x184);
        megaChopper.update(1, player);

        assertEquals(0x220, megaChopper.getX());
        assertEquals(0x184, megaChopper.getY());

        for (int frame = 2; frame <= 61; frame++) {
            megaChopper.update(frame, player);
        }

        assertEquals(2, player.getRingCount());
        assertTrue(services.playedSfx.contains(Sonic3kSfx.RING_RIGHT.id));
    }

    @Test
    public void alternatingLeftRightInputReleasesCapturedPlayer() throws Exception {
        MegaChopperBadnikInstance megaChopper = new MegaChopperBadnikInstance(
                new ObjectSpawn(0x200, 0x180, Sonic3kObjectIds.MEGA_CHOPPER, 0, 0, false, 0));
        megaChopper.setServices(new RecordingServices());

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x200, (short) 0x180);
        player.setRingCount(10);
        GameServices.camera().setFocusedSprite(player);

        // Obj_MegaChopper begins with jsr (Obj_WaitOffscreen).l (sonic3k.asm:184233),
        // so no routine runs until Render_Sprites has drawn the placeholder. These
        // cases exercise post-Init behaviour directly, so release the gate here as
        // production does once render_flags bit 7 is set.
        megaChopper.testReleaseOffscreenWait();

        megaChopper.onTouchResponse(player, new TouchResponseResult(0x17, 0x20, 0x20, TouchCategory.SPECIAL), 0);
        megaChopper.update(0, player);

        boolean[] leftInputs = {true, false, true, false, true, false};
        boolean[] rightInputs = {false, true, false, true, false, true};
        for (int i = 0; i < leftInputs.length; i++) {
            player.setDirectionalInputPressed(false, false, leftInputs[i], rightInputs[i]);
            megaChopper.update(i + 1, player);
        }

        assertFalse(player.isControlLocked());
        assertFalse(player.isObjectControlled());
        assertEquals("RELEASED", readState(megaChopper));
        assertTrue(megaChopper.getCollisionFlags() != 0);
    }

    @Test
    public void nativeP2CollisionFallbackUsesObjectPlayerQueryWhenRawSidekickListIsEmpty() throws Exception {
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x210, (short) 0x180);
        nativeP2.setRingCount(3);
        MegaChopperBadnikInstance megaChopper = new MegaChopperBadnikInstance(
                new ObjectSpawn(0x210, 0x180, Sonic3kObjectIds.MEGA_CHOPPER, 0, 0, false, 0));
        megaChopper.setServices(new QueryOnlyPlayerServices(null, List.of(nativeP2), List.of()));

        // Obj_MegaChopper begins with jsr (Obj_WaitOffscreen).l (sonic3k.asm:184233),
        // so no routine runs until Render_Sprites has drawn the placeholder. These
        // cases exercise post-Init behaviour directly, so release the gate here as
        // production does once render_flags bit 7 is set.
        megaChopper.testReleaseOffscreenWait();

        writeIntField(megaChopper, "pendingCollisionProperty", 2);
        megaChopper.update(0, new TestablePlayableSprite("sonic", (short) 0x300, (short) 0x180));

        assertEquals("CARRY", readState(megaChopper),
                "MegaChopper native P2 fallback should resolve through ObjectPlayerQuery");

        nativeP2.setCentreX((short) 0x220);
        nativeP2.setCentreY((short) 0x184);
        megaChopper.update(1, new TestablePlayableSprite("sonic", (short) 0x300, (short) 0x180));

        assertEquals(0x220, megaChopper.getX(),
                "Captured native P2 should drive carry follow even when raw sidekicks() is empty");
        assertEquals(0x184, megaChopper.getY());
    }

    private static String readState(MegaChopperBadnikInstance megaChopper) throws Exception {
        Field field = MegaChopperBadnikInstance.class.getDeclaredField("state");
        field.setAccessible(true);
        return String.valueOf(field.get(megaChopper));
    }

    private static void writeIntField(MegaChopperBadnikInstance megaChopper, String fieldName, int value)
            throws Exception {
        Field field = MegaChopperBadnikInstance.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(megaChopper, value);
    }

    private static class RecordingServices extends StubObjectServices {
        private final List<Integer> playedSfx = new ArrayList<>();

        private RecordingServices() {
            withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }
    }

    private static final class QueryOnlyPlayerServices extends RecordingServices {
        private final TestablePlayableSprite main;
        private final List<? extends TestablePlayableSprite> queriedSidekicks;
        private final List<com.openggf.game.PlayableEntity> rawSidekicks;

        private QueryOnlyPlayerServices(TestablePlayableSprite main,
                List<? extends TestablePlayableSprite> queriedSidekicks,
                List<com.openggf.game.PlayableEntity> rawSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
            this.rawSidekicks = List.copyOf(rawSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<com.openggf.game.PlayableEntity> sidekicks() {
            return rawSidekicks;
        }
    }
}


