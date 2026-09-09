package com.openggf.game.sonic2.objects;

import com.openggf.game.rules.GameRules;
import com.openggf.game.PlayableEntity;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.PostContactState;
import com.openggf.game.solid.PreContactState;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPointPokeyObjectInstance {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_2);
        TestEnvironment.activeGameplayMode();
        GameServices.camera().resetState();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void captureUsesObjectControlWithoutGlobalControlLockedLatch() throws Exception {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x04D3, (short) 0x043C);
        player.setGameRulesForTest(GameRules.SONIC_2);
        player.setLogicalInputState(false, false, true, false, false);
        player.endOfTick();
        assertEquals(AbstractPlayableSprite.INPUT_LEFT, player.getInputHistory(0));

        PointPokeyObjectInstance pokey = new PointPokeyObjectInstance(
                new ObjectSpawn(0x04C0, 0x0460, 0xD6, 0x00, 0, false, 0),
                "PointPokey");

        invokeCapture(pokey, player);

        assertTrue(player.isObjectControlled(),
                "ObjD6 writes obj_control=$81, so bit 7 must still suppress movement/physics.");
        assertFalse(player.isControlLocked(),
                "S2 ObjD6 writes obj_control(a1), not global Control_Locked; Obj01_Control must keep "
                        + "copying Ctrl_1 into Ctrl_1_Logical (s2.asm:36227-36235,59021).");

        player.setLogicalInputState(false, false, false, false, false);
        player.endOfTick();
        assertEquals(0, player.getInputHistory(0),
                "With Control_Locked untouched, the next raw zero input refreshes Ctrl_1_Logical before "
                        + "Sonic_RecordPos stores follower history (s2.asm:36227-36246,36346).");
    }

    @Test
    void bottomSolidReturnCapturesWithoutRideLatch() throws Exception {
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x1DBF, (short) 0x0382);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setAir(true);
        tails.setRolling(true);
        tails.setXSpeed((short) 0x03A4);
        tails.setYSpeed((short) 0xFC1E);

        ObjectSpawn spawn = new ObjectSpawn(
                0x1DC0, 0x0368, 0xD6, 0x00, 0, false, 0);
        PointPokeyObjectInstance pokey = new PointPokeyObjectInstance(spawn, "PointPokey");

        invokeCapture(pokey, tails, new PlayerSolidContactResult(
                ContactKind.BOTTOM,
                false,
                false,
                false,
                false,
                new PreContactState(tails.getXSpeed(), tails.getYSpeed(), tails.getRolling(), tails.getAir(),
                        tails.getAnimationId()),
                new PostContactState(tails.getXSpeed(), tails.getYSpeed(), tails.getAir(), tails.isOnObject(),
                        tails.getPushing()),
                0));

        assertTrue(tails.isObjectControlled(),
                "ObjD6 captures on any negative SolidObject return (tst.w d4 / bpl at s2.asm:59045-59046).");
        assertEquals(0x1DC0, tails.getCentreX() & 0xFFFF);
        assertEquals(0x0368, tails.getCentreY() & 0xFFFF);
        assertTrue(tails.getAir(),
                "A bottom-return capture must preserve SolidObject's airborne state; ObjD6 itself only writes "
                        + "position, velocity, radii, rolling/animation, and obj_control.");
        assertFalse(tails.isOnObject(),
                "Only the top-return path has RideObject_SetRide side effects before ObjD6 captures.");
    }

    @Test
    void offscreenReleaseTargetsCapturedSidekick() throws Exception {
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x1E60, (short) 0x01C6);
        main.setGameRulesForTest(GameRules.SONIC_2);
        main.setYSpeed((short) 0x1234);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x1DBF, (short) 0x0382);
        tails.setGameRulesForTest(GameRules.SONIC_2);
        tails.setCpuControlled(true);
        tails.setAir(true);
        tails.setRolling(true);

        ObjectSpawn spawn = new ObjectSpawn(
                0x1DC0, 0x0368, 0xD6, 0x00, 0, false, 0);
        OffscreenPointPokeyObjectInstance pokey = new OffscreenPointPokeyObjectInstance(spawn);
        PointPokeySolidServices services = new PointPokeySolidServices();
        services.sidekicks = List.of(tails);
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, null, GameServices.camera(), services);
        services.objectManager = objectManager;
        objectManager.addDynamicObject(pokey);

        invokeCapture(pokey, tails, new PlayerSolidContactResult(
                ContactKind.BOTTOM,
                false,
                false,
                false,
                false,
                new PreContactState(tails.getXSpeed(), tails.getYSpeed(), tails.getRolling(), tails.getAir(),
                        tails.getAnimationId()),
                new PostContactState(tails.getXSpeed(), tails.getYSpeed(), tails.getAir(), tails.isOnObject(),
                        tails.getPushing()),
                0));

        pokey.update(1, main);

        assertEquals(0x1234, main.getYSpeed() & 0xFFFF,
                "ObjD6 has separate ROM state for main and sidekick (objoff_30 / objoff_34); "
                        + "sidekick release must not eject main.");
        assertEquals(0x0400, tails.getYSpeed() & 0xFFFF,
                "Off-screen occupied ObjD6 branches to loc_2BE2E and applies the release velocity "
                        + "to the captured sidekick.");
        assertFalse(tails.isObjectControlled());
        assertTrue(tails.getAir());
    }

    @Test
    void emptyPrizeCounterReleasesCapturedPlayerOnTheCagesOwnUpdate() throws Exception {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1DC0, (short) 0x0368);
        player.setGameRulesForTest(GameRules.SONIC_2);
        PointPokeyObjectInstance pokey = new PointPokeyObjectInstance(
                new ObjectSpawn(0x1DC0, 0x0368, 0xD6, 0x01, 0, false, 0),
                "PointPokey");

        invokeCapture(pokey, player);
        setIntField(pokey, "playerState", 3);
        setIntField(pokey, "prizesToSpawn", 0);

        pokey.update(1, player);

        assertEquals(0x0400, player.getYSpeed() & 0xFFFF,
                "ObjDC/ObjD3 decrement ObjD6's shared prize counter; ObjD6 reads it at its own point in the "
                        + "object update order (tst.w objoff_2C(a0) in loc_2BD4E) and then takes loc_2BE2E.");
        assertFalse(player.isObjectControlled());
        assertTrue(player.getAir());
    }

    @Test
    void bombSoundClockAdvancesEvenWhenNoPrizeCanSpawnAndWhileWaitingForImpacts() throws Exception {
        var state = GameModuleRegistry.getCurrent().getGameService(
                com.openggf.game.sonic2.slotmachine.CNZPrizeSoundState.class);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1DC0, (short) 0x0368);
        player.setGameRulesForTest(GameRules.SONIC_2);
        PointPokeyObjectInstance pokey = new PointPokeyObjectInstance(
                new ObjectSpawn(0x1DC0, 0x0368, 0xD6, 1, 0, false, 0), "PointPokey");
        var module = GameModuleRegistry.getCurrent();
        ObjectManager objects = new ObjectManager(List.of(), null, 0, null, null, null,
                GameServices.camera(), new StubObjectServices() {
                    @Override public com.openggf.game.GameModule gameModule() { return module; }
                });
        objects.addDynamicObject(pokey);
        invokeCapture(pokey, player);
        setIntField(pokey, "playerState", 3);
        setIntField(pokey, "slotReward", -1);
        setIntField(pokey, "prizesToSpawn", 10);
        Field active = PointPokeyObjectInstance.class.getDeclaredField("activePrizeCount");
        active.setAccessible(true);
        int[] activeCount = (int[]) active.get(pokey);
        activeCount[0] = 16;
        for (int frame = 0; frame < 5; frame++) pokey.update(frame, player);
        assertEquals(5, state.capture());
        assertTrue(state.consumeSpikeSound(), "first impact may sound immediately after five payout updates");
        assertFalse(state.consumeSpikeSound(), "another impact without payout updates must be silent");
        setIntField(pokey, "prizesToSpawn", 0);
        for (int frame = 5; frame < 10; frame++) pokey.update(frame, player);
        assertEquals(5, state.capture(), "clock continues while the final children converge");
        activeCount[0] = 0;
        pokey.update(10, player);
        assertEquals(5, state.capture(), "the eject path skips loc_2BD48");
    }

    @Test
    void bombSoundCounterIsSessionOwnedWrappedAndRewindable() {
        var module = GameModuleRegistry.getCurrent();
        var state = module.getGameService(com.openggf.game.sonic2.slotmachine.CNZPrizeSoundState.class);
        assertTrue(module.rewindAdapters().contains(state));
        state.restore(0xFFFF);
        state.advanceBombPayout();
        assertEquals(0, state.capture());
        for (int i = 0; i < 7; i++) state.advanceBombPayout();
        var saved = state.capture();
        assertTrue(state.consumeSpikeSound());
        state.restore(saved);
        assertTrue(state.consumeSpikeSound());
        module.onLevelLoad();
        assertFalse(state.consumeSpikeSound());
    }

    @Test
    void bombImpactsConsumeTheSharedClockAndUseTheSecondaryMailbox() {
        var module = GameModuleRegistry.getCurrent();
        var state = module.getGameService(com.openggf.game.sonic2.slotmachine.CNZPrizeSoundState.class);
        var audio = org.mockito.Mockito.mock(com.openggf.audio.AudioManager.class);
        ObjectManager objects = new ObjectManager(List.of(), null, 0, null, null, null,
                GameServices.camera(), new StubObjectServices() {
                    @Override public com.openggf.game.GameModule gameModule() { return module; }
                    @Override public com.openggf.audio.AudioManager audioManager() { return audio; }
                });
        int[] active = {2};
        var first = new BombPrizeObjectInstance(100, 100, 100, 100, 1, active);
        var second = new BombPrizeObjectInstance(100, 100, 100, 100, 1, active);
        objects.addDynamicObject(first);
        objects.addDynamicObject(second);
        for (int i = 0; i < 5; i++) state.advanceBombPayout();
        first.update(10, null);
        second.update(10, null);
        org.mockito.Mockito.verify(audio).playSecondarySfx(com.openggf.audio.GameSound.HURT_SPIKE);
        org.mockito.Mockito.verifyNoMoreInteractions(audio);
        assertEquals(0, active[0]);
    }

    private static void invokeCapture(PointPokeyObjectInstance pokey, TestablePlayableSprite player) throws Exception {
        Method capture = PointPokeyObjectInstance.class.getDeclaredMethod("capturePlayer", AbstractPlayableSprite.class);
        capture.setAccessible(true);
        capture.invoke(pokey, player);
    }

    private static void invokeCapture(
            PointPokeyObjectInstance pokey,
            TestablePlayableSprite player,
            PlayerSolidContactResult result) throws Exception {
        Method capture = PointPokeyObjectInstance.class.getDeclaredMethod(
                "capturePlayer", AbstractPlayableSprite.class, PlayerSolidContactResult.class);
        capture.setAccessible(true);
        capture.invoke(pokey, player, result);
    }

    private static void setIntField(PointPokeyObjectInstance pokey, String name, int value) throws Exception {
        Field field = PointPokeyObjectInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(pokey, value);
    }

    private static final class OffscreenPointPokeyObjectInstance extends PointPokeyObjectInstance {
        private OffscreenPointPokeyObjectInstance(ObjectSpawn spawn) {
            super(spawn, "PointPokey");
        }

        @Override
        public boolean isWithinSolidContactBounds() {
            return false;
        }
    }

    private static final class PointPokeySolidServices extends StubObjectServices {
        private final SolidExecutionRegistry solidExecutionRegistry = new DefaultSolidExecutionRegistry();
        private ObjectManager objectManager;
        private List<PlayableEntity> sidekicks = List.of();

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public SolidExecutionRegistry solidExecutionRegistry() {
            return solidExecutionRegistry;
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return sidekicks;
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> null, () -> sidekicks);
        }
    }
}
