package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.GroundMode;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.MGZPulleyObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.objects.badniks.MantisBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kMgzPulleyAndMantis {

    @BeforeEach
    void setUp() {
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        clearConstructionContext();
    }

    @Test
    void registryCreatesMgzPulleyAndMantisInstances() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        RecordingServices services = new RecordingServices();

        setConstructionContext(services);
        ObjectInstance pulley;
        ObjectInstance mantis;
        try {
            pulley = registry.create(
                    new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MGZ_PULLEY, 0x00, 0x00, false, 0));
            mantis = registry.create(
                    new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MANTIS, 0x00, 0x00, false, 0));
        } finally {
            clearConstructionContext();
        }

        assertInstanceOf(MGZPulleyObjectInstance.class, pulley);
        assertFalse(pulley instanceof SolidObjectProvider,
                "MGZ pulley uses explicit proximity capture and never calls a ROM solid routine");
        assertInstanceOf(MantisBadnikInstance.class, mantis);
    }

    @Test
    void mgzPulleyCapturesApproachingPlayerAndJumpLaunchesLeft() throws Exception {
        RecordingServices services = new RecordingServices();
        MGZPulleyObjectInstance pulley = createPulley(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MGZ_PULLEY, 0x00, 0x00, false, 0));
        pulley.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x01DA, (short) 0x012E);
        player.setSubpixelRaw(0x2E00, 0xA600);
        player.setXSpeed((short) -0x100);
        player.setOnObject(true);
        player.setAir(false);
        player.setGroundMode(GroundMode.RIGHTWALL);
        player.setJumpInputPressed(false);

        pulley.update(0, player);

        assertTrue(player.isObjectControlled(), "Pulley should capture a player entering the handle box");
        assertEquals(0x01DA, player.getCentreX());
        assertEquals(0x012E, player.getCentreY());
        assertEquals(0x2E00, player.getXSubpixelRaw(),
                "ROM pulley capture writes x_pos without clearing x_sub");
        assertEquals(0xA600, player.getYSubpixelRaw(),
                "ROM pulley capture writes y_pos without clearing y_sub");
        assertTrue(player.isObjectControlAllowsCpu(),
                "Pulley writes positive object_control bit 0 rather than the signed bit-7 gate");
        assertTrue(player.isOnObject(),
                "Pulley capture does not clear the native standing-object status bit");
        assertFalse(player.getAir(),
                "Pulley capture does not force Status_InAir before a jump release");
        assertEquals(Sonic3kAnimationIds.GET_UP.id(), player.getAnimationId());
        assertTrue(services.playedSfx.contains(Sonic3kSfx.PULLEY_GRAB.id));

        player.setJumpInputPressed(true);
        pulley.update(1, player);

        assertFalse(player.isObjectControlled(), "Jump should release object control");
        assertEquals(0x01DA, player.getCentreX(),
                "Pulley jump release must not shift x_pos while changing wall-oriented radii");
        assertEquals(-0x400, player.getXSpeed());
        assertEquals(-0x600, player.getYSpeed());
        assertTrue(player.getRolling());
        assertFalse(player.isJumping(), "Pulley launch does not write the native jumping byte");
        assertEquals(GroundMode.GROUND, player.getGroundMode());
        assertEquals(Sonic3kAnimationIds.ROLL.id(), player.getAnimationId());
    }

    @Test
    void mgzPulleyCapturesNativeP2FromPlayerQueryWhenRawSidekickListIsEmpty() throws Exception {
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0100, (short) 0x0100);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x01DA, (short) 0x012E);
        nativeP2.setXSpeed((short) -0x100);

        RecordingServices services = new QueryOnlyPlayerServices(main, List.of(nativeP2));
        MGZPulleyObjectInstance pulley = createPulley(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MGZ_PULLEY, 0x00, 0x00, false, 0));
        pulley.setServices(services);

        pulley.update(0, main);

        assertFalse(main.isObjectControlled(), "Main/update player should remain native P1 and stay outside the handle");
        assertTrue(nativeP2.isObjectControlled(),
                "MGZ pulley has only native P1/P2 grab slots, so P2 must come from ObjectPlayerQuery NATIVE_P1_P2");
        assertEquals(0x01DA, nativeP2.getCentreX());
        assertEquals(0x012E, nativeP2.getCentreY());
    }

    @Test
    void mgzPulleyExcludesExtraSidekicksBeyondNativeP2() throws Exception {
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0100, (short) 0x0100);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x0100, (short) 0x0100);
        TestablePlayableSprite extraSidekick = new TestablePlayableSprite("knuckles", (short) 0x01DA, (short) 0x012E);
        extraSidekick.setXSpeed((short) -0x100);

        RecordingServices services = new QueryOnlyPlayerServices(main, List.of(nativeP2, extraSidekick));
        MGZPulleyObjectInstance pulley = createPulley(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MGZ_PULLEY, 0x00, 0x00, false, 0));
        pulley.setServices(services);

        pulley.update(0, main);

        assertFalse(extraSidekick.isObjectControlled(),
                "Additional engine sidekicks must not consume the pulley's native P2 slot");
    }

    @Test
    void mgzPulleyExpandsDuringRecoveryThenRetractsWhileHeld() throws Exception {
        RecordingServices services = new RecordingServices();
        MGZPulleyObjectInstance pulley = createPulley(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MGZ_PULLEY, 0x04, 0x00, false, 0));
        pulley.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x01CA, (short) 0x014E);
        player.setXSpeed((short) -0x100);
        player.setJumpInputPressed(false);

        pulley.update(0, player);
        assertEquals(0x20, readCurrentExtension(pulley));

        for (int frame = 1; frame <= 4; frame++) {
            pulley.update(frame, player);
        }
        assertEquals(0x28, readCurrentExtension(pulley), "Pulley should overshoot by 8px during release recovery");

        for (int frame = 5; frame <= 26; frame++) {
            pulley.update(frame, player);
        }
        assertEquals(0, readCurrentExtension(pulley), "Pulley should fully retract while the player remains hanging");
    }

    @Test
    void mgzPulleyRelaxesAndCarriesP2AfterP1Releases() throws Exception {
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0236, (short) 0x014E);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x0236, (short) 0x014E);
        main.setXSpeed((short) 0x100);
        nativeP2.setXSpeed((short) 0x100);

        RecordingServices services = new QueryOnlyPlayerServices(main, List.of(nativeP2));
        MGZPulleyObjectInstance pulley = createPulley(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MGZ_PULLEY, 0x04, 0x01, false, 0));
        pulley.setServices(services);

        pulley.update(0, main);
        assertTrue(main.isObjectControlled());
        assertTrue(nativeP2.isObjectControlled());

        for (int frame = 1; frame <= 26; frame++) {
            pulley.update(frame, main);
        }
        assertEquals(0, readCurrentExtension(pulley));

        main.setJumpInputPressed(true);
        pulley.update(27, main);
        main.setJumpInputPressed(false);
        int p2XBeforeRelax = nativeP2.getCentreX();
        int p2YBeforeRelax = nativeP2.getCentreY();
        nativeP2.setRenderFlagOnScreen(true);
        services.camera = new Camera();

        pulley.update(28, main);

        assertFalse(main.isObjectControlled());
        assertTrue(nativeP2.isObjectControlled());
        assertEquals(2, readCurrentExtension(pulley),
                "ROM loc_34900 consults only the P1 grab byte when choosing extension motion");
        assertEquals(p2XBeforeRelax + 1, nativeP2.getCentreX());
        assertEquals(p2YBeforeRelax + 2, nativeP2.getCentreY());
    }

    @Test
    void mgzPulleyOnUnloadDestroysChainChildAndReleasesGrabbedPlayer() throws Exception {
        RecordingServices services = new RecordingServices();
        MGZPulleyObjectInstance pulley = createPulley(services,
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MGZ_PULLEY, 0x04, 0x00, false, 0));
        pulley.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x01CA, (short) 0x014E);
        player.setXSpeed((short) -0x100);

        pulley.update(0, player);
        assertTrue(player.isObjectControlled(), "Precondition: pulley should own the grabbed player");

        AbstractObjectInstance chainChild = readChainChild(pulley);
        assertFalse(chainChild.isDestroyed(), "Precondition: spawned chain child should still be active");

        pulley.onUnload();

        assertTrue(chainChild.isDestroyed(), "Unloading the parent pulley must destroy its chain child");
        assertFalse(player.isObjectControlled(),
                "Unloading the parent pulley must release the grabbed player from object control");
    }

    @Test
    void mantisNearbyPlayerTriggersLaunchCycle() throws Exception {
        MantisBadnikInstance mantis = new MantisBadnikInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MANTIS, 0x00, 0x00, false, 0));
        mantis.setServices(new RecordingServices());

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0210, (short) 0x0100);
        player.setCentreX((short) 0x0210);
        player.setCentreY((short) 0x0100);

        mantis.refreshPostCameraRenderState();
        mantis.update(0, player); // Obj_WaitOffscreen restores the Mantis operation
        mantis.update(1, player); // init
        mantis.update(2, player); // detect player and begin prep
        assertEquals("PREPARE", readMantisState(mantis));

        for (int frame = 3; frame <= 10; frame++) {
            mantis.update(frame, player);
        }

        assertEquals("LAUNCH", readMantisState(mantis));
        assertTrue(mantis.getY() < 0x0100, "Mantis should have leapt upward");
    }

    @Test
    void mantisRunsRestoredRoutineOutsideStrictViewportAndDetectsP2() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x0100, (short) 0x0100);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x0140, (short) 0x0100);
        RecordingServices services = new QueryOnlyPlayerServices(main, List.of(nativeP2));
        MantisBadnikInstance mantis = new MantisBadnikInstance(
                new ObjectSpawn(0x015E, 0x0100, Sonic3kObjectIds.MANTIS, 0x00, 0x00, false, 0));
        mantis.setServices(services);

        mantis.refreshPostCameraRenderState();
        mantis.update(0, main); // Obj_WaitOffscreen restores the Mantis operation
        mantis.update(1, main); // init just inside the placeholder's render margin
        mantis.update(2, main); // native routine runs although its centre is beyond x=320

        assertEquals("PREPARE", readMantisState(mantis),
                "The restored routine must detect native P2 before the Mantis centre enters the strict viewport");
    }

    @Test
    void mantisPrepSequenceMatchesDisassemblyTiming() throws Exception {
        MantisBadnikInstance mantis = new MantisBadnikInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MANTIS, 0x00, 0x00, false, 0));
        mantis.setServices(new RecordingServices());

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0210, (short) 0x0100);
        player.setCentreX((short) 0x0210);
        player.setCentreY((short) 0x0100);

        mantis.refreshPostCameraRenderState();
        mantis.update(0, player); // Obj_WaitOffscreen restores the Mantis operation
        mantis.update(1, player); // init
        mantis.update(2, player); // detect player, enter prep
        assertEquals("PREPARE", readMantisState(mantis));
        assertEquals(0, readMantisMappingFrame(mantis));
        assertEquals(0x0100, mantis.getY());

        mantis.update(3, player); // first Animate_RawNoSSTMultiDelay tick
        assertEquals(1, readMantisMappingFrame(mantis));
        assertEquals(0x00FB, mantis.getY());

        mantis.update(4, player); // delay 2: hold
        assertEquals(1, readMantisMappingFrame(mantis));
        assertEquals(0x00FB, mantis.getY());

        mantis.update(5, player); // delay 2: hold
        assertEquals(1, readMantisMappingFrame(mantis));
        assertEquals(0x00FB, mantis.getY());

        mantis.update(6, player); // next frame in script
        assertEquals(2, readMantisMappingFrame(mantis));
        assertEquals(0x00E8, mantis.getY());

        mantis.update(7, player); // $F4 callback arms the jump, movement starts next frame
        assertEquals("LAUNCH", readMantisState(mantis));
        assertEquals(0x00E8, mantis.getY());
    }

    @Test
    void mantisContinuesLaunchWhenOnlyVerticallyOffScreen() throws Exception {
        MantisBadnikInstance mantis = new MantisBadnikInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.MANTIS, 0x00, 0x00, false, 0));
        mantis.setServices(new RecordingServices());

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0210, (short) 0x0160);
        player.setCentreX((short) 0x0210);
        player.setCentreY((short) 0x0160);

        mantis.refreshPostCameraRenderState();
        mantis.update(0, player); // Obj_WaitOffscreen restores the Mantis operation
        mantis.update(1, player); // init
        mantis.update(2, player); // detect player, enter prep
        for (int frame = 3; frame <= 7; frame++) {
            mantis.update(frame, player);
        }

        assertEquals("LAUNCH", readMantisState(mantis));
        assertEquals(0x00E8, mantis.getY());

        // Reproduce the MGZ2 regression: Sonic can stay low enough that the
        // mantis leaves the top of the viewport during its leap. The ROM keeps
        // updating the arc because Obj_WaitOffscreen / Sprite_CheckDeleteTouch
        // only gate on X visibility.
        AbstractObjectInstance.updateCameraBounds(0, 0x00E9, 1024, 0x0200, 0);

        mantis.update(8, player);

        assertNotEquals(0x00E8, mantis.getY(),
                "Mantis should keep moving through its jump arc even when only vertically off-screen");
    }

    private static int readCurrentExtension(MGZPulleyObjectInstance pulley) throws Exception {
        Field field = MGZPulleyObjectInstance.class.getDeclaredField("currentExtension");
        field.setAccessible(true);
        return field.getInt(pulley);
    }

    private static AbstractObjectInstance readChainChild(MGZPulleyObjectInstance pulley) throws Exception {
        Field field = MGZPulleyObjectInstance.class.getDeclaredField("chainChild");
        field.setAccessible(true);
        return (AbstractObjectInstance) field.get(pulley);
    }

    private static MGZPulleyObjectInstance createPulley(ObjectServices services, ObjectSpawn spawn) {
        setConstructionContext(services);
        try {
            return new MGZPulleyObjectInstance(spawn);
        } finally {
            clearConstructionContext();
        }
    }

    private static String readMantisState(MantisBadnikInstance mantis) throws Exception {
        Field field = MantisBadnikInstance.class.getDeclaredField("state");
        field.setAccessible(true);
        return String.valueOf(field.get(mantis));
    }

    private static int readMantisMappingFrame(MantisBadnikInstance mantis) throws Exception {
        Field field = mantis.getClass().getSuperclass().getDeclaredField("mappingFrame");
        field.setAccessible(true);
        return field.getInt(mantis);
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

    private static class RecordingServices extends StubObjectServices {
        private final List<Integer> playedSfx = new ArrayList<>();
        private Camera camera;

        private RecordingServices() {
            withPlayerQuery(new ObjectPlayerQuery(() -> null, List::of));
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }

        @Override
        public Camera camera() {
            return camera;
        }
    }

    private static final class QueryOnlyPlayerServices extends RecordingServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }
    }
}
