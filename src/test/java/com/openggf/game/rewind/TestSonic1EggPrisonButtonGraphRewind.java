package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1EggPrisonButtonObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1EggPrisonObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1EggPrisonButtonGraphRewind {
    private static final ObjectSpawn PRISON_BODY_SPAWN =
            new ObjectSpawn(0x180, 0x180, Sonic1ObjectIds.EGG_PRISON, 0, 0, false, 0, 60);
    private static final ObjectSpawn PRISON_BUTTON_SPAWN =
            new ObjectSpawn(0x180, 0x180, Sonic1ObjectIds.EGG_PRISON, 1, 0, false, 0, 61);
    private static final ObjectSpawn DISTRACTOR_BODY_SPAWN =
            new ObjectSpawn(0x1C0, 0x180, Sonic1ObjectIds.EGG_PRISON, 0, 0, false, 0, 62);
    private static final ObjectSpawn DISTRACTOR_BUTTON_SPAWN =
            new ObjectSpawn(0x1C0, 0x180, Sonic1ObjectIds.EGG_PRISON, 1, 0, false, 0, 63);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void s1EggPrisonButtonGraphRestoresFreshButtonParentBackrefAndScalars() {
        Harness harness = Harness.create(List.of(
                PRISON_BODY_SPAWN,
                PRISON_BUTTON_SPAWN,
                DISTRACTOR_BODY_SPAWN,
                DISTRACTOR_BUTTON_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        resolvePlacedButtons(objectManager);

        Sonic1EggPrisonObjectInstance sourceParent = parentAt(PRISON_BODY_SPAWN, objectManager);
        Sonic1EggPrisonButtonObjectInstance sourceButton = buttonAt(PRISON_BUTTON_SPAWN, objectManager);
        Sonic1EggPrisonObjectInstance distractorParent = parentAt(DISTRACTOR_BODY_SPAWN, objectManager);
        Sonic1EggPrisonButtonObjectInstance distractorButton = buttonAt(DISTRACTOR_BUTTON_SPAWN, objectManager);
        assertSame(sourceParent, readObjectField(sourceButton, "parent"),
                "fixture must resolve source button to the source parent");
        assertSame(distractorParent, readObjectField(distractorButton, "parent"),
                "fixture must resolve distractor button to the distractor parent");
        setBooleanField(sourceButton, "triggered", true);
        setIntField(sourceButton, "currentY", PRISON_BUTTON_SPAWN.y() + 3);

        ObjectRefId parentId = objectId(objectManager, sourceParent);
        ObjectRefId buttonId = objectId(objectManager, sourceButton);
        ObjectRefId distractorParentId = objectId(objectManager, distractorParent);
        ObjectRefId distractorButtonId = objectId(objectManager, distractorButton);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceButton);
        sourceButton.setDestroyed(true);
        Sonic1EggPrisonButtonObjectInstance divergentButton = objectManager.createDynamicObject(
                () -> new Sonic1EggPrisonButtonObjectInstance(
                        new ObjectSpawn(0x200, 0x180, Sonic1ObjectIds.EGG_PRISON, 1, 0, false, 0, 64)));
        setObjectField(divergentButton, "parent", distractorParent);

        rewindRegistry.restore(snapshot);

        Sonic1EggPrisonObjectInstance restoredParent =
                objectById(objectManager, Sonic1EggPrisonObjectInstance.class, parentId);
        Sonic1EggPrisonButtonObjectInstance restoredButton =
                objectById(objectManager, Sonic1EggPrisonButtonObjectInstance.class, buttonId);
        Sonic1EggPrisonObjectInstance restoredDistractorParent =
                objectById(objectManager, Sonic1EggPrisonObjectInstance.class, distractorParentId);
        Sonic1EggPrisonButtonObjectInstance restoredDistractorButton =
                objectById(objectManager, Sonic1EggPrisonButtonObjectInstance.class, distractorButtonId);

        assertEquals(2, liveObjects(objectManager, Sonic1EggPrisonObjectInstance.class).size(),
                "restore must not drop or duplicate S1 Egg Prison parents");
        assertEquals(2, liveObjects(objectManager, Sonic1EggPrisonButtonObjectInstance.class).size(),
                "restore must not drop or duplicate S1 Egg Prison buttons");
        assertNotSame(sourceParent, restoredParent, "restore must recreate the captured parent");
        assertNotSame(sourceButton, restoredButton, "restore must recreate the captured button");
        assertNotSame(divergentButton, restoredButton, "restore must drop divergent button objects");
        assertNotSame(distractorParent, restoredDistractorParent,
                "restore must recreate the distractor parent too");
        assertNotSame(distractorButton, restoredDistractorButton,
                "restore must recreate the distractor button too");

        assertSame(restoredParent, readObjectField(restoredButton, "parent"),
                "button parent must resolve to the matching restored S1 Egg Prison parent");
        assertNotSame(sourceParent, readObjectField(restoredButton, "parent"),
                "button parent must not retain the stale pre-restore parent");
        assertNotSame(restoredDistractorParent, readObjectField(restoredButton, "parent"),
                "button parent must not relink to a distractor S1 Egg Prison");
        assertSame(restoredButton, readObjectField(restoredParent, "buttonObject"),
                "parent buttonObject must point back at the restored button");
        assertSame(restoredDistractorButton, readObjectField(restoredDistractorParent, "buttonObject"),
                "distractor parent buttonObject must point back at its restored button");

        assertEquals(PRISON_BUTTON_SPAWN.y(), readIntField(restoredButton, "baseY"),
                "spawn-derived final baseY must be recomputed from the captured spawn");
        assertEquals(PRISON_BUTTON_SPAWN.y() + 3, readIntField(restoredButton, "currentY"),
                "button currentY scalar must round-trip");
        assertTrue(readBooleanField(restoredButton, "triggered"),
                "button triggered scalar must round-trip");
    }

    @Test
    void s1EggPrisonButtonParentReferenceStillRequiresRewindIdentity() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        Sonic1EggPrisonObjectInstance unmanagedParent =
                new Sonic1EggPrisonObjectInstance(PRISON_BODY_SPAWN);
        unmanagedParent.setServices(harness.services());
        Sonic1EggPrisonButtonObjectInstance button = objectManager.createDynamicObject(
                () -> new Sonic1EggPrisonButtonObjectInstance(PRISON_BUTTON_SPAWN));
        setObjectField(button, "parent", unmanagedParent);
        assertSame(unmanagedParent, readObjectField(button, "parent"),
                "precondition: button parent is outside ObjectManager identity registration");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, registryFor(objectManager)::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing non-null required parent identity must fail loudly");
    }

    @Test
    void s1EggPrisonButtonGraphUsesRewindRecreatableWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1EggPrisonButtonObjectInstance.class),
                "Sonic1EggPrisonButtonObjectInstance must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1EggPrisonButtonObjectInstance.class.getName()),
                "Sonic1EggPrisonButtonObjectInstance must not keep an explicit dynamic codec");
    }

    /**
     * Bug repro (S1 bug-triage row 3, docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md):
     * "Egg prison capsule button visual persists after destruction." Per
     * docs/s1disasm/_incObj/3E Prison Capsule.asm:81-137, the button
     * (Pri_Switch, routine 4) and the capsule's explosion/animal-spawn driver
     * (Pri_Explosion/Pri_Animals, routines $A/$C) are the SAME ROM object slot
     * -- when the button transitions from Pri_Switch into Pri_Explosion, the
     * ROM stops calling AnimateSprite on it (freezing the last flash frame),
     * then at ".makeanimal" (line 137) explicitly blanks it
     * ("move.b #6,obFrame(a0) ; 'delete' switch by turning it invisible").
     * Before this fix, the engine's button sub-object kept toggling between
     * its two flash frames and rendering them forever after being triggered,
     * with no path to the ROM's blank frame.
     */
    @Test
    void s1EggPrisonButtonGoesBlankAfterExplosionPhaseAndStaysBlank() {
        Harness harness = Harness.create(List.of(PRISON_BODY_SPAWN, PRISON_BUTTON_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        resolvePlacedButtons(objectManager);

        Sonic1EggPrisonButtonObjectInstance button = buttonAt(PRISON_BUTTON_SPAWN, objectManager);
        int startFrame = readIntField(button, "currentFrame");
        assertTrue(startFrame == 1 || startFrame == 3,
                "precondition: button starts on one of its two flash frames");

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x180, (short) 0x160);
        button.onSolidContact(player, new SolidContact(true, false, false, true, false), 2);
        assertTrue(readBooleanField(button, "triggered"), "onSolidContact must trigger the button");
        assertFalse(readBooleanField(button, "blanked"), "button must not blank on the trigger frame itself");

        // ROM Pri_Explosion runs for EXPLOSION_TIMER=60 frames before .makeanimal
        // fires and blanks the switch (3E Prison Capsule.asm:59-60,95,137).
        for (int frame = 3; frame <= 64; frame++) {
            objectManager.update(0, player, List.of(), frame, false);
        }

        assertTrue(readBooleanField(button, "blanked"),
                "button must go blank once the capsule leaves the explosion phase");
        assertEquals(6, readIntField(button, "currentFrame"),
                "button must switch to the ROM blank mapping frame (Map_Pri .blank, index 6)");

        // Keep driving the animal-spawn phase; the blanked switch must never
        // come back (no re-animation, no reverting to a flash frame).
        for (int frame = 65; frame <= 120; frame++) {
            objectManager.update(0, player, List.of(), frame, false);
        }
        assertEquals(6, readIntField(button, "currentFrame"),
                "blanked button must stay blank for the rest of the act, matching ROM's DeleteObject-only lifetime");
    }

    private static void resolvePlacedButtons(ObjectManager objectManager) {
        objectManager.update(0, new TestablePlayableSprite("sonic", (short) 0, (short) 0),
                List.of(), 0, false);
        objectManager.update(1, new TestablePlayableSprite("sonic", (short) 0, (short) 0),
                List.of(), 0, false);
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            com.openggf.game.GameStateManager gameState = new com.openggf.game.GameStateManager();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public com.openggf.game.GameStateManager gameState() { return gameState; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new Sonic1ObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager, services);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        return liveObjects(objectManager, type).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static Sonic1EggPrisonObjectInstance parentAt(ObjectSpawn spawn, ObjectManager objectManager) {
        return liveObjects(objectManager, Sonic1EggPrisonObjectInstance.class).stream()
                .filter(parent -> parent.getSpawn() == spawn)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing parent at " + spawn));
    }

    private static Sonic1EggPrisonButtonObjectInstance buttonAt(ObjectSpawn spawn, ObjectManager objectManager) {
        return liveObjects(objectManager, Sonic1EggPrisonButtonObjectInstance.class).stream()
                .filter(button -> button.getSpawn() == spawn)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing button at " + spawn));
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestSonic1EggPrisonButtonGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof com.openggf.level.objects.AbstractObjectInstance aoi
                ? aoi.getSlotIndex()
                : -1;
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
