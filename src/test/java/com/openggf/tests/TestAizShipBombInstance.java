package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.objects.AizBattleshipInstance;
import com.openggf.game.sonic3k.objects.AizBombExplosionInstance;
import com.openggf.game.sonic3k.objects.AizShipBombInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestAizShipBombInstance {

    private static final int BOMB_SCRIPT_X = 0x3F5C;
    /** ROM AIZShipBomb_ReadyDrop: `addq.w #2,$30(a0)` (sonic3k.asm:105392). */
    private static final int READY_DROP_STEP = 2;
    private static final int PORT_START_Y = 0x0A60;
    private static final int PORT_READY_Y = 0x0A80;

    @Test
    public void testSameFrameAllocationRunsInitBeforeReadyDrop() {
        TestEnvironment.resetAll();
        Camera camera = new Camera();
        camera.setX((short) 0x4380);
        camera.setY((short) 0x0180);

        ObjectServices services = servicesWithCamera(camera);
        AizShipBombInstance bomb = buildWithContext(services,
                () -> new AizShipBombInstance(
                        new ObjectSpawn(camera.getX(), camera.getY(), 0, 0, 0, false, 0),
                        null,
                        BOMB_SCRIPT_X,
                        camera.getY()));

        bomb.update(0, null);

        // Obj_AIZShipBomb's init ends at `move.w #6,$32(a0)` and the very next
        // line is the label Obj_AIZShipBombMain, with no rts between them
        // (docs/skdisasm/sonic3k.asm:105367-105379). Main dispatches routine 0
        // straight into AIZShipBomb_ReadyDrop (:105384, :105391), whose first
        // instruction is `addq.w #2,$30(a0)`. The ship allocates the bomb with
        // AllocateObjectAfterCurrent, so its slot is still ahead of the object
        // pass and it runs on its creation frame -- init AND one ReadyDrop step.
        // This previously asserted the opposite, which cost the bomb an extra
        // frame in the air on every drop of the act.
        assertEquals(PORT_START_Y + READY_DROP_STEP, readIntField(bomb, "portYOffset"),
                "The creation-frame pass runs Obj_AIZShipBomb init AND falls through"
                        + " into the first AIZShipBomb_ReadyDrop step");
        assertTrue(bomb.getY() > camera.getY(),
                "That first ReadyDrop step descends the bomb in the port");
    }

    @Test
    public void testAttachedBombTracksBattleshipTranslationUntilRelease() {
        TestEnvironment.resetAll();
        Camera camera = new Camera();
        camera.setX((short) 0x4380);
        camera.setY((short) 0x0180);

        ObjectServices services = servicesWithCamera(camera);
        int baseSecondaryY = camera.getY() + 0x08F0;

        AizBattleshipInstance ship = buildWithContext(services,
                () -> new AizBattleshipInstance(
                        new ObjectSpawn(camera.getX(), baseSecondaryY, 0, 0, 0, false, 0),
                        baseSecondaryY));
        AizShipBombInstance bomb = buildWithContext(services,
                () -> new AizShipBombInstance(
                        new ObjectSpawn(camera.getX(), camera.getY(), 0, 0, 0, false, 0),
                        ship,
                        BOMB_SCRIPT_X,
                        camera.getY() + (PORT_START_Y - baseSecondaryY)));

        for (int i = 0; i < 17; i++) {
            ship.update(i, null);
            bomb.update(i, null);
        }

        int expectedX = camera.getX() + (BOMB_SCRIPT_X - ship.getSecondaryCameraX());
        int expectedY = camera.getY() + (PORT_READY_Y - ship.getSecondaryCameraY());

        assertTrue(bomb.shouldRenderBehindBattleship(), "Bomb should still be rendered in the battleship overlay before release");
        assertEquals(expectedX, bomb.getX(), "Attached bomb X should keep translating from the ship's live camera");
        assertEquals(expectedY, bomb.getY(), "Attached bomb Y should keep following the ship bob while settling in the bay");

        for (int i = 17; i < 23; i++) {
            ship.update(i, null);
            bomb.update(i, null);
        }

        assertFalse(bomb.shouldRenderBehindBattleship(), "Released bomb should no longer be rendered in the behind-ship overlay");
        assertEquals(camera.getX() + (BOMB_SCRIPT_X - ship.getSecondaryCameraX()), bomb.getX(), "Released bomb X should still use the ship-relative translation");
    }

    @Test
    public void testBattleshipBombScriptMatchesRomUnderflowCadence() {
        TestEnvironment.resetAll();
        Camera camera = new Camera();
        camera.setX((short) 0x4380);
        camera.setY((short) 0x0180);

        ObjectServices services = servicesWithCamera(camera);
        int baseSecondaryY = camera.getY() + 0x08F0;

        AizBattleshipInstance ship = buildWithContext(services,
                () -> new AizBattleshipInstance(
                        new ObjectSpawn(camera.getX(), baseSecondaryY, 0, 0, 0, false, 0),
                        baseSecondaryY));

        // AIZ2SE_ShipRefresh takes the ship's slot with plain AllocateObject,
        // not AllocateObjectAfterCurrent (docs/skdisasm/sonic3k.asm:104917-104928),
        // so the pass that creates it need not reach it -- and in the recorded
        // run it does not. The first call is therefore that unused creation
        // pass; it must do nothing at all.
        advanceShip(ship, 1);
        assertEquals(0, readIntField(ship, "scriptIndex"),
                "The creation pass spawns nothing");
        assertEquals(0x4020 << 16, readIntField(ship, "shipXFixed"),
                "The creation pass does not advance the secondary camera either");

        advanceShip(ship, 420);
        assertEquals(0, readIntField(ship, "scriptIndex"), "The initial $1A4 delay should not spawn a bomb until the counter underflows");

        advanceShip(ship, 1);
        assertEquals(1, readIntField(ship, "scriptIndex"), "The first bomb should spawn on update 421 after the $1A4 counter underflows");

        advanceShip(ship, 32);
        assertEquals(1, readIntField(ship, "scriptIndex"), "A $20 script delay should still be waiting after 32 more updates");

        advanceShip(ship, 1);
        assertEquals(2, readIntField(ship, "scriptIndex"), "A $20 script delay should produce the next bomb after 33 updates");

        advanceShip(ship, 99);
        assertEquals(5, readIntField(ship, "scriptIndex"), "By this point the first five bombs should have spawned on consecutive $20 gaps");

        advanceShip(ship, 32);
        assertEquals(5, readIntField(ship, "scriptIndex"), "Bomb 6 should still be pending because bomb 5's entry keeps the gap at $20+1");

        advanceShip(ship, 1);
        assertEquals(6, readIntField(ship, "scriptIndex"), "Bomb 6 should spawn after bomb 5's $20 delay, not bomb 6's $38 delay");

        advanceShip(ship, 56);
        assertEquals(6, readIntField(ship, "scriptIndex"), "Bomb 7 should still be waiting because bomb 6's entry sets the long $38 gap");

        advanceShip(ship, 1);
        assertEquals(7, readIntField(ship, "scriptIndex"), "Bomb 7 should spawn after the full $38+1-frame delay from bomb 6's entry");
    }

    @Test
    public void testExplosionFragmentAppliesWrapOffsetInWorldSpace() {
        AizBombExplosionInstance explosion = new AizBombExplosionInstance(0x4550, 0x02C0, 0, 0);

        explosion.applyWrapOffset(0x0200);

        assertEquals(0x4350, explosion.getX(), "Explosion fragments should shift back with Level_repeat_offset wraps");
    }

    @Test
    public void testExplosionFragmentCollidableWindowMatchesRomTiming() {
        // ROM Obj_AIZBombExplosion (sonic3k.asm:105471) waits delay+1 frames
        // (`subq.w #1,$2E(a0) / bmi`), then falls through to loc_505E4 and
        // animates on that same frame. Ani_AIZ2BombExplode_Script0 is
        // 1,3 2,4 3,5 4,5 5,5 and Animate_SpriteIrregularDelay's
        // `subq.b #1,anim_frame_timer / bcc` holds a delay byte D for D+1
        // frames, so mapping_frame 1/2/3 occupy 4+5+6 = 15 frames. loc_505FC
        // drops the fragment from the collision-response list once
        // mapping_frame reaches 4 + anim.
        int delay = 6;
        AizBombExplosionInstance explosion = new AizBombExplosionInstance(0x4390, 0x01FD, 0, delay);

        for (int frame = 1; frame <= delay; frame++) {
            explosion.update(frame, null);
            assertEquals(0, explosion.getCollisionFlags(),
                    "Fragment must stay inert for delay+1 frames, frame " + frame);
        }
        for (int frame = delay + 1; frame <= delay + 15; frame++) {
            explosion.update(frame, null);
            assertEquals(0x8B, explosion.getCollisionFlags(),
                    "mapping_frame 1..3 is collidable, frame " + frame);
        }
        explosion.update(delay + 16, null);
        assertEquals(0, explosion.getCollisionFlags(),
                "mapping_frame 4 is not below 4 + anim, so loc_505FC skips the list add");
    }

    @Test
    public void testExplosionFragmentSecondScriptIsNeverCollidable() {
        // Ani_AIZ2BombExplode_Script1 runs frames 6..$B, all at or above its
        // own threshold of 4 + anim = 5, so loc_505FC never adds it.
        AizBombExplosionInstance explosion = new AizBombExplosionInstance(0x4390, 0x01FD, 1, 0);

        for (int frame = 1; frame <= 40 && !explosion.isDestroyed(); frame++) {
            explosion.update(frame, null);
            assertEquals(0, explosion.getCollisionFlags(),
                    "Script 1 fragments never enter the collision-response list, frame " + frame);
        }
    }

    @Test
    public void testFrameStartTouchSnapshotClearsSameFrameSpawnGate() {
        AizBombExplosionInstance explosion = new AizBombExplosionInstance(0x4390, 0x01FD, 0, 0);
        explosion.setSkipTouchThisFrame(true);

        explosion.snapshotTouchResponseState();

        assertFalse(explosion.isSkipTouchThisFrame(),
                "An object spawned in the previous object pass must be eligible for the next ReactToItem pass");
    }

    private static ObjectServices servicesWithCamera(Camera camera) {
        return new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        };
    }

    private static <T extends AbstractObjectInstance> T buildWithContext(ObjectServices services,
                                                                         ObjectBuilder<T> builder) {
        setConstructionContext(services);
        try {
            T object = builder.build();
            object.setServices(services);
            return object;
        } finally {
            clearConstructionContext();
        }
    }

    private static void advanceShip(AizBattleshipInstance ship, int updates) {
        for (int i = 0; i < updates; i++) {
            ship.update(i, null);
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(ObjectServices services) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(services);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    private interface ObjectBuilder<T> {
        T build();
    }
}


