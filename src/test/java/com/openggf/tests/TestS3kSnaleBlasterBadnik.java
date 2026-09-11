package com.openggf.tests;

import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.GameStateManager;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.tools.Sonic3kObjectProfile;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

class TestS3kSnaleBlasterBadnik {
    private static final int SNALE_BLASTER_ID = 0xBE;

    @BeforeEach
    void setUp() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
    }

    @AfterEach
    void tearDown() {
        clearConstructionContext();
    }

    @Test
    void registryCreatesSnaleBlasterAndMarksS3klSlotImplemented() {
        ObjectInstance instance = createSnaleBlaster(new RecordingServices());

        assertEquals("SnaleBlasterBadnikInstance", instance.getClass().getSimpleName());
        assertTrue(new Sonic3kObjectProfile().getImplementedIds().contains(SNALE_BLASTER_ID));
    }

    @Test
    void firstUpdateSeedsClosedWaitAndCollisionFromRomSetup() throws Exception {
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(new RecordingServices());
        AbstractPlayableSprite player = playerAt(0x0300, 0x0100, 0);

        snaleBlaster.update(0, player);
        assertEquals("INIT", readEnumName(snaleBlaster, "state"),
                "Obj_WaitOffscreen must not initialize before Render_Sprites marks its placeholder");
        snaleBlaster.refreshPostCameraRenderState();
        snaleBlaster.update(1, player);
        assertEquals("INIT", readEnumName(snaleBlaster, "state"),
                "the helper restores the saved operation and returns on its visible dispatch");
        snaleBlaster.update(2, player);

        TouchResponseProvider touch = (TouchResponseProvider) snaleBlaster;
        assertEquals(0x1A, touch.getCollisionFlags());
        assertEquals(0x7F, touch.getCollisionProperty());
        assertEquals("CLOSED_WAIT", readEnumName(snaleBlaster, "state"));
        assertEquals(0x20, readInt(snaleBlaster, "waitTimer"));
        assertEquals(0, readInt(snaleBlaster, "mappingFrame"));
    }

    @Test
    void closedWaitRunsRawOpeningPrepBeforeVerticalMotion() throws Exception {
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(new RecordingServices());
        activateSnaleBlaster(snaleBlaster, playerAt(0x0300, 0x0100, 0));
        setInt(snaleBlaster, "waitTimer", 0);
        int initialY = snaleBlaster.getY();

        snaleBlaster.update(1, playerAt(0x0300, 0x0100, 0));
        assertEquals("OPENING_PREP", readEnumName(snaleBlaster, "state"));

        for (int frame = 2; frame <= 43; frame++) {
            snaleBlaster.update(frame, playerAt(0x0300, 0x0100, 0));
            assertEquals(initialY, snaleBlaster.getY(),
                    "byte_8C2B6 changes mappings without moving native y_pos");
        }
        assertEquals("OPENING_PREP", readEnumName(snaleBlaster, "state"));

        snaleBlaster.update(44, playerAt(0x0300, 0x0100, 0));
        assertEquals("OPENING", readEnumName(snaleBlaster, "state"));
        assertEquals(initialY, snaleBlaster.getY(),
                "the raw-animation callback changes routine without running it in the same dispatch");

        snaleBlaster.update(45, playerAt(0x0300, 0x0100, 0));
        assertEquals(initialY - 2, snaleBlaster.getY(),
                "the first vertical step runs on the dispatch after the raw callback");
    }

    @Test
    void rollingPlayerWithinFortyEightPixelsForcesEarlyClose() throws Exception {
        RecordingServices services = new RecordingServices();
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(services);
        activateSnaleBlaster(snaleBlaster, playerAt(0x0300, 0x0100, 0));
        setEnum(snaleBlaster, "state", "OPENING");
        setInt(snaleBlaster, "verticalStep", -2);
        setInt(snaleBlaster, "openCyclesRemaining", 2);
        setInt(snaleBlaster, "mappingFrame", 2);

        snaleBlaster.update(0, playerAt(0x0208, 0x0108, 2));

        assertEquals("CLOSING_FROM_PLAYER", readEnumName(snaleBlaster, "state"));
        assertEquals(3, readInt(snaleBlaster, "mappingFrame"));
    }

    @Test
    void completedClosingPassUsesTheSharedOpenWaitAndReversesDirection() throws Exception {
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(new RecordingServices());
        activateSnaleBlaster(snaleBlaster, playerAt(0x0300, 0x0100, 0));
        int initialY = snaleBlaster.getY();
        setEnum(snaleBlaster, "state", "CLOSING");
        setInt(snaleBlaster, "verticalStep", 2);
        setInt(snaleBlaster, "openCyclesRemaining", 0);
        setInt(snaleBlaster, "verticalAnimTimer", 0);
        setInt(snaleBlaster, "mappingFrame", 3);

        snaleBlaster.update(0, playerAt(0x0300, 0x0100, 0));

        assertEquals(initialY + 2, snaleBlaster.getY());
        assertEquals("OPEN_WAIT", readEnumName(snaleBlaster, "state"),
                "loc_8C08A returns both movement directions to routine 2");
        assertEquals(0x90, readInt(snaleBlaster, "waitTimer"));
        assertEquals(-2, readInt(snaleBlaster, "verticalStep"),
                "loc_8C08A negates the shared vertical step for the next pass");
        assertTrue(readBoolean(snaleBlaster, "firingWindow"),
                "loc_8C08A sets parent bit 1 after a closing pass too");
    }

    @Test
    void sharedWaitResumesVerticalScriptWithoutResettingItsCursor() throws Exception {
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(new RecordingServices());
        activateSnaleBlaster(snaleBlaster, playerAt(0x0300, 0x0100, 0));
        setEnum(snaleBlaster, "state", "OPEN_WAIT");
        setInt(snaleBlaster, "waitTimer", 0);
        setInt(snaleBlaster, "verticalStep", -2);
        setInt(snaleBlaster, "verticalAnimTimer", 9);
        setInt(snaleBlaster, "mappingFrame", 4);

        snaleBlaster.update(0, playerAt(0x0300, 0x0100, 0));

        assertEquals("OPENING", readEnumName(snaleBlaster, "state"));
        assertEquals(9, readInt(snaleBlaster, "verticalAnimTimer"),
                "loc_8C03E leaves anim_frame_timer untouched");
        assertEquals(4, readInt(snaleBlaster, "mappingFrame"),
                "loc_8C03E leaves mapping_frame untouched");
        assertEquals(2, readInt(snaleBlaster, "openCyclesRemaining"));
    }

    @Test
    void shooterChildFiresSingleProjectileAtRawAnimationOffsetFour() throws Exception {
        RecordingServices services = new RecordingServices();
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(services);
        activateSnaleBlaster(snaleBlaster, playerAt(0x0300, 0x0100, 0));
        Object shooter = readList(snaleBlaster, "shooters").get(0);
        services.spawnedObjects.clear();

        setEnum(shooter, "state", "FIRING");
        setInt(shooter, "animIndex", 1);
        setInt(shooter, "mappingFrame", 7);
        setInt(shooter, "animTimer", 0);

        ((AbstractObjectInstance) shooter).update(1, playerAt(0x0300, 0x0100, 0));

        assertEquals(List.of(Sonic3kSfx.PROJECTILE.id), services.playedSfx);
        assertEquals(1, services.spawnedObjects.size());
        Object projectile = services.spawnedObjects.get(0);
        assertEquals("SnaleBlasterProjectile", ((AbstractObjectInstance) projectile).getName());
        assertEquals(-0x200, readInt(projectile, "xVelocity"));
        assertEquals(-0x100, readInt(projectile, "yVelocity"));
    }

    @Test
    void lowerShooterUsesItsNativeChildSubtypeIndependentOfParentSubtype() throws Exception {
        RecordingServices services = new RecordingServices();
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(services);
        activateSnaleBlaster(snaleBlaster, playerAt(0x0300, 0x0100, 0));
        Object lowerShooter = readList(snaleBlaster, "shooters").get(1);
        services.spawnedObjects.clear();

        assertTrue(readBoolean(lowerShooter, "verticalFlipShot"),
                "ChildObjDat_8C28A gives the lower shooter subtype 2 even when the parent subtype is zero");
        setEnum(lowerShooter, "state", "FIRING");
        setInt(lowerShooter, "animIndex", 1);
        setInt(lowerShooter, "mappingFrame", 7);
        setInt(lowerShooter, "animTimer", 0);
        ((AbstractObjectInstance) lowerShooter).update(1, playerAt(0x0300, 0x0100, 0));

        Object projectile = services.spawnedObjects.get(0);
        assertEquals(0x100, readInt(projectile, "yVelocity"),
                "loc_8C212 negates the lower shooter's projectile y_vel");
    }

    @Test
    void protectedCollisionPropertyReflectsAttackWithoutDestroying() throws Exception {
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(new RecordingServices());
        setBoolean(snaleBlaster, "collisionEnabled", true);
        setInt(snaleBlaster, "collisionProperty", 0x7F);

        ((TouchResponseAttackable) snaleBlaster).onPlayerAttack(
                playerAt(0x0200, 0x0100, 2), enemyTouchResult());

        assertEquals(0x7E, ((TouchResponseProvider) snaleBlaster).getCollisionProperty(),
                "Touch_Enemy decrements the nonzero special-enemy hit byte");
        assertEquals(0, ((TouchResponseProvider) snaleBlaster).getCollisionFlags(),
                "Touch_Enemy clears collision_flags after the protected bounce");
        assertTrue(!snaleBlaster.isDestroyed(),
                "S3K Touch_Enemy reflects nonzero collision_property instead of killing the object");
    }

    @Test
    void earlyCloseWaitDoesNotRearmCollisionAfterProtectedHit() throws Exception {
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(new RecordingServices());
        setEnum(snaleBlaster, "state", "EARLY_REOPEN_WAIT");
        setInt(snaleBlaster, "waitTimer", 10);
        setBoolean(snaleBlaster, "collisionEnabled", false);

        snaleBlaster.update(0, playerAt(0x0300, 0x0100, 0));

        assertEquals(0, ((TouchResponseProvider) snaleBlaster).getCollisionFlags(),
                "routine $A writes collision_property only and retains Touch_Enemy's cleared collision_flags");
    }

    @Test
    void openWindowCollisionPropertyZeroAllowsNormalBadnikDefeat() throws Exception {
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(new RecordingServices());
        setInt(snaleBlaster, "collisionProperty", 0);

        ((TouchResponseAttackable) snaleBlaster).onPlayerAttack(
                playerAt(0x0200, 0x0100, 2), enemyTouchResult());

        assertTrue(snaleBlaster.isDestroyed(),
                "SnaleBlaster is only vulnerable while collision_property is zero");
    }

    @Test
    void coverAnimationCompletionRestoresProtectionDuringRemainingOpenWait() throws Exception {
        AbstractObjectInstance snaleBlaster = createSnaleBlaster(new RecordingServices());
        activateSnaleBlaster(snaleBlaster, playerAt(0x0300, 0x0100, 0));
        Object cover = readField(snaleBlaster, "cover");

        setEnum(snaleBlaster, "state", "OPEN_WAIT");
        setInt(snaleBlaster, "waitTimer", 40);
        setBoolean(snaleBlaster, "firingWindow", true);
        setInt(snaleBlaster, "collisionProperty", 0);
        setEnum(cover, "state", "FIRING");
        setInt(cover, "animIndex", 4);
        setInt(cover, "animTimer", 0);

        ((AbstractObjectInstance) cover).update(1, playerAt(0x0300, 0x0100, 0));
        snaleBlaster.update(1, playerAt(0x0300, 0x0100, 0));

        assertEquals(0x7F, ((TouchResponseProvider) snaleBlaster).getCollisionProperty(),
                "loc_8C16A clears parent bit 1, so loc_8BFF2 restores collision_property=$7F");
    }

    private static AbstractObjectInstance createSnaleBlaster(ObjectServices services) {
        setConstructionContext(services);
        try {
            ObjectInstance instance = new Sonic3kObjectRegistry().create(
                    new ObjectSpawn(0x0200, 0x0100, SNALE_BLASTER_ID, 0, 0, false, 0));
            assertTrue(instance instanceof AbstractObjectInstance,
                    "SnaleBlaster registry entry should create an object instance");
            AbstractObjectInstance object = (AbstractObjectInstance) instance;
            object.setServices(services);
            return object;
        } finally {
            clearConstructionContext();
        }
    }

    private static void activateSnaleBlaster(AbstractObjectInstance snaleBlaster,
            AbstractPlayableSprite player) {
        snaleBlaster.refreshPostCameraRenderState();
        snaleBlaster.update(-2, player);
        snaleBlaster.update(-1, player);
    }

    private static AbstractPlayableSprite playerAt(int x, int y, int animationId) {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) x);
        when(player.getCentreY()).thenReturn((short) y);
        when(player.getAnimationId()).thenReturn(animationId);
        when(player.getDead()).thenReturn(false);
        return player;
    }

    private static TouchResponseResult enemyTouchResult() {
        return new TouchResponseResult(0x1A, 16, 12, TouchCategory.ENEMY);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readList(Object target, String fieldName) throws Exception {
        return (List<Object>) readField(target, fieldName);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setEnum(Object target, String fieldName, String valueName) throws Exception {
        Field field = findField(target, fieldName);
        field.set(target, Enum.valueOf((Class<Enum>) field.getType(), valueName));
    }

    private static void setInt(Object target, String fieldName, int value) throws Exception {
        findField(target, fieldName).setInt(target, value);
    }

    private static void setBoolean(Object target, String fieldName, boolean value) throws Exception {
        findField(target, fieldName).setBoolean(target, value);
    }

    private static int readInt(Object target, String fieldName) throws Exception {
        return findField(target, fieldName).getInt(target);
    }

    private static boolean readBoolean(Object target, String fieldName) throws Exception {
        return findField(target, fieldName).getBoolean(target);
    }

    private static String readEnumName(Object target, String fieldName) throws Exception {
        return ((Enum<?>) readField(target, fieldName)).name();
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        return findField(target, fieldName).get(target);
    }

    private static Field findField(Object target, String fieldName) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new AssertionError("Missing field " + fieldName);
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

    private static final class RecordingServices extends StubObjectServices {
        private final List<Integer> playedSfx = new ArrayList<>();
        private final List<AbstractObjectInstance> spawnedObjects = new ArrayList<>();
        private final ObjectManager objectManager = mock(ObjectManager.class);
        private final ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        private final GameStateManager gameState = mock(GameStateManager.class);

        private RecordingServices() {
            withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
            doAnswer(invocation -> {
                recordSpawn(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any());
            doAnswer(invocation -> {
                recordSpawn(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrentNextFrame(any());
            doAnswer(invocation -> {
                recordSpawn(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObject(any());
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }

        @Override
        public GameStateManager gameState() {
            return gameState;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }

        @Override
        public void playSfx(com.openggf.audio.GameSound sound) {
            playedSfx.add(sound.ordinal());
        }

        private void recordSpawn(AbstractObjectInstance object) {
            object.setServices(this);
            spawnedObjects.add(object);
        }
    }
}
