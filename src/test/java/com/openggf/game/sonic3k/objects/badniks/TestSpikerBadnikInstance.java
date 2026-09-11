package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.session.SessionManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@ExtendWith(SingletonResetExtension.class)
@FullReset
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class TestSpikerBadnikInstance {

    @BeforeEach
    void setUp() {
        SessionManager.clear();
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void registryCreatesSpikerInstance() {
        ObjectInstance instance = new com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.SPIKER, 0, 0, false, 0));
        assertInstanceOf(SpikerBadnikInstance.class, instance);
    }

    @Test
    void nearbyPlayerOpensBodyAndLeftLauncherFiresProjectile() throws Exception {
        RecordingServices services = new RecordingServices();
        SpikerBadnikInstance spiker = new SpikerBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.SPIKER, 0, 0, false, 0));
        spiker.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x100, (short) 0x100);
        services.withMain(player);

        advancePastWaitOffscreenInit(spiker, player);
        assertEquals(3, services.spawnedChildren.size(), "Spiker should create two launchers and the top spike");

        for (int frame = 2; frame <= 10; frame++) {
            spiker.update(frame, player);
        }

        assertEquals("OPEN", readState(spiker));
        assertEquals(0x0F8, spiker.getY(), "Spiker should rise 8 pixels while opening");

        AbstractObjectInstance leftLauncher = findChild(services.spawnedChildren, 0x110, 0x104);
        leftLauncher.update(11, player);
        assertFalse(services.playedSfx.contains(Sonic3kSfx.PROJECTILE.id),
                "Launcher should not fire on the trigger frame");

        for (int frame = 12; frame <= 28; frame++) {
            leftLauncher.update(frame, player);
        }
        assertFalse(services.playedSfx.contains(Sonic3kSfx.PROJECTILE.id),
                "Launcher should not reach frame 4 before the ROM delay elapses");

        leftLauncher.update(29, player);

        assertTrue(services.playedSfx.contains(Sonic3kSfx.PROJECTILE.id), "Expected projectile SFX");
        assertTrue(services.spawnedChildren.size() >= 4, "Expected launcher to spawn a spike projectile");
        AbstractObjectInstance projectile = findChild(services.spawnedChildren, "SpikerSpikeProjectile");
        assertEquals(0x10C, projectile.getX(),
                "The launcher should leave allocation-frame movement to the new higher SST slot");
        projectile.update(30, player);
        assertEquals(0x10A, projectile.getX(), "Left projectile should travel at -$200");
    }

    @Test
    void leftLauncherWaitsUntilPlayerCrossesItsOwnPosition() throws Exception {
        RecordingServices services = new RecordingServices();
        SpikerBadnikInstance spiker = new SpikerBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.SPIKER, 0, 0, false, 0));
        spiker.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x118, (short) 0x100);
        services.withMain(player);
        advancePastWaitOffscreenInit(spiker, player);
        for (int frame = 2; frame <= 10; frame++) {
            spiker.update(frame, player);
        }

        AbstractObjectInstance leftLauncher = findChild(services.spawnedChildren, 0x110, 0x104);
        leftLauncher.update(11, player);
        assertEquals("ARMED", readNestedState(leftLauncher),
                "Find_SonicTails runs from the launcher, not the parent body");

        player.setCentreX((short) 0x110);
        leftLauncher.update(12, player);
        assertEquals("ATTACK", readNestedState(leftLauncher));
    }

    @Test
    void launcherFindSonicTailsMeasuresFromLauncherPosition() throws Exception {
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x126, (short) 0x100);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x110, (short) 0x100);
        RecordingServices services = new QueryOnlyPlayerServices(main, List.of(nativeP2), List.of());
        SpikerBadnikInstance spiker = new SpikerBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.SPIKER, 0, 0, false, 0));
        spiker.setServices(services);

        advancePastWaitOffscreenInit(spiker, main);
        for (int frame = 2; frame <= 10; frame++) {
            spiker.update(frame, main);
        }

        AbstractObjectInstance leftLauncher = findChild(services.spawnedChildren, 0x110, 0x104);
        leftLauncher.update(11, main);
        leftLauncher.update(12, main);

        assertEquals("ATTACK", readNestedState(leftLauncher),
                "Find_SonicTails must select native P2 from the launcher coordinate, not the parent center");
    }

    @Test
    void topSpikeTouchStartsCompressionThenLaunchesPlayerUpward() throws Exception {
        RecordingServices services = new RecordingServices();
        SpikerBadnikInstance spiker = new SpikerBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.SPIKER, 0, 0, false, 0));
        spiker.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x100, (short) 0x100);
        services.withMain(player);

        advancePastWaitOffscreenInit(spiker, player);
        for (int frame = 2; frame <= 10; frame++) {
            spiker.update(frame, player);
        }

        player.setCentreX((short) 0x120);
        player.setCentreY((short) 0x0F0);
        AbstractObjectInstance topSpike = findChild(services.spawnedChildren, 0x120, 0x0EC);
        TouchResponseListener listener = (TouchResponseListener) topSpike;
        TouchResponseProvider provider = (TouchResponseProvider) topSpike;
        TouchResponseResult result = new TouchResponseResult(0x0A, 0, 0, TouchCategory.SPECIAL);

        listener.onTouchResponse(player, result, 11);
        assertEquals(0, player.getYSpeed());
        assertEquals(0x0F6, player.getCentreY());
        assertEquals(0, spiker.getCollisionFlags(), "Parent hurtbox should disable during compression");
        assertTrue(services.playedSfx.contains(Sonic3kSfx.SPRING.id), "Expected spring SFX");

        for (int frame = 12; frame <= 15; frame++) {
            spiker.update(frame, player);
        }

        assertEquals(-0x600, player.getYSpeed());
        assertEquals(0, spiker.getCollisionFlags(),
                "Launch pass must not also consume the following raw-animation pair");

        for (int frame = 16; frame <= 21; frame++) {
            spiker.update(frame, player);
        }
        assertEquals(0, spiker.getCollisionFlags(), "Final frame delay should keep the parent hurtbox disabled");

        spiker.update(22, player);
        assertEquals("OPEN", readState(spiker));
        assertEquals(0x0A, spiker.getCollisionFlags(), "Parent hurtbox should restore after the launch anim");
        assertEquals(0, provider.getCollisionFlags(), "Top spike should still be in cooldown");

        for (int frame = 23; frame <= 38; frame++) {
            topSpike.update(frame, player);
        }
        assertEquals(0, provider.getCollisionFlags(),
                "Obj_Wait keeps collision cleared when the $10 counter reaches zero");

        topSpike.update(39, player);
        assertEquals(0, provider.getCollisionFlags(),
                "The touch frame installs Obj_Wait without decrementing its new counter");

        topSpike.update(40, player);
        assertEquals(0xCA, provider.getCollisionFlags(),
                "loc_88D98 restores collision only after the wait word underflows");
    }

    @Test
    void unloadDestroysChildrenSoLaunchersCannotKeepRunningOffscreen() {
        RecordingServices services = new RecordingServices();
        SpikerBadnikInstance spiker = new SpikerBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.SPIKER, 0, 0, false, 0));
        spiker.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x100, (short) 0x100);
        services.withMain(player);

        advancePastWaitOffscreenInit(spiker, player);
        assertEquals(3, services.spawnedChildren.size(), "Expected launcher and top-spike children");

        spiker.onUnload();
        for (ObjectInstance child : services.spawnedChildren) {
            assertTrue(child.isDestroyed(), "Unload should mark child objects destroyed");
            child.update(1, player);
        }

        assertTrue(services.playedSfx.isEmpty(), "Destroyed children must not keep firing after unload");
        assertEquals(3, services.spawnedChildren.size(), "Unload should not spawn replacement children");
    }

    @Test
    void bodyDetectionUsesObjectPlayerQueryWhenRawSidekickListIsEmpty() throws Exception {
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x220, (short) 0x100);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x100, (short) 0x100);
        RecordingServices services = new QueryOnlyPlayerServices(main, List.of(nativeP2), List.of());
        SpikerBadnikInstance spiker = new SpikerBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.SPIKER, 0, 0, false, 0));
        spiker.setServices(services);

        advancePastWaitOffscreenInit(spiker, main);
        for (int frame = 2; frame <= 10; frame++) {
            spiker.update(frame, main);
        }

        assertEquals("OPEN", readState(spiker),
                "Spiker should detect query native P2 even when raw sidekicks() is empty");
    }

    @Test
    void spikeProjectileDeclaresShieldDeflectProfileAndKeepsDeflectBehavior() throws Exception {
        RecordingServices services = new RecordingServices();
        SpikerBadnikInstance spiker = new SpikerBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.SPIKER, 0, 0, false, 0));
        spiker.setServices(services);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x100, (short) 0x100);
        services.withMain(player);

        advancePastWaitOffscreenInit(spiker, player);
        for (int frame = 2; frame <= 10; frame++) {
            spiker.update(frame, player);
        }
        AbstractObjectInstance leftLauncher = findChild(services.spawnedChildren, 0x110, 0x104);
        for (int frame = 11; frame <= 29; frame++) {
            leftLauncher.update(frame, player);
        }

        AbstractObjectInstance projectile = findChild(services.spawnedChildren, "SpikerSpikeProjectile");
        TouchResponseProvider provider = (TouchResponseProvider) projectile;
        TouchResponseProfile expected = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                false,
                TouchShieldDeflectCapability.SHIELD_DEFLECT,
                0x08,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        assertEquals(expected, provider.getTouchResponseProfile());
        assertTrue(projectile.usesCurrentTouchResponseState(),
                "loc_86D5E publishes the projectile after its movement callback");
        assertEquals(expected, provider.getTouchResponseProfile(false));
        assertDoesNotThrow(() -> projectile.getClass().getDeclaredMethod("getTouchResponseProfile"));
        assertDoesNotThrow(() -> projectile.getClass().getDeclaredMethod("getTouchResponseProfile", boolean.class));

        player.setCentreX((short) (projectile.getX() + 0x20));
        player.setCentreY((short) projectile.getY());
        int projectileX = projectile.getX();

        assertTrue(provider.onShieldDeflect(player));
        assertEquals(0, provider.getCollisionFlags(), "Deflected projectile should stop hurting the player");

        projectile.update(32, player);
        assertTrue(projectile.getX() < projectileX, "Deflected projectile should rebound away from the player");
    }

    @Test
    void spikeProjectileUsesNativeAsymmetricDeleteRangeAndDelayedSlotRelease() throws Exception {
        RecordingServices services = new RecordingServices();
        SpikerBadnikInstance spiker = new SpikerBadnikInstance(
                new ObjectSpawn(0x120, 0x100, Sonic3kObjectIds.SPIKER, 0, 0, false, 0));
        spiker.setServices(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x100, (short) 0x100);
        services.withMain(player);

        advancePastWaitOffscreenInit(spiker, player);
        for (int frame = 2; frame <= 10; frame++) {
            spiker.update(frame, player);
        }
        AbstractObjectInstance leftLauncher = findChild(services.spawnedChildren, 0x110, 0x104);
        for (int frame = 11; frame <= 29; frame++) {
            leftLauncher.update(frame, player);
        }
        AbstractObjectInstance projectile = findChild(services.spawnedChildren, "SpikerSpikeProjectile");
        TouchResponseProvider provider = (TouchResponseProvider) projectile;
        setNestedIntField(projectile, "currentX", 0x100);
        setNestedIntField(projectile, "currentY", 0x180);
        setNestedIntField(projectile, "xVelocity", 0);
        setNestedIntField(projectile, "yVelocity", 0);

        projectile.update(30, player);
        assertFalse(projectile.isDestroyed(),
                "Sprite_CheckDeleteTouchXY keeps y_pos-cameraY == $180 alive");
        assertEquals(0x98, provider.getCollisionFlags());
        assertTrue(projectile.isPersistent(),
                "The native XY delete tail must own dynamic projectile lifetime");

        setNestedIntField(projectile, "currentY", 0x181);
        projectile.update(31, player);
        assertFalse(projectile.isDestroyed(),
                "Go_Delete_Sprite leaves a Delete_Current_Sprite marker for one object pass");
        assertEquals(0, provider.getCollisionFlags(),
                "The pending delete marker no longer participates in touch response");

        projectile.update(32, player);
        assertTrue(projectile.isDestroyed(),
                "Delete_Current_Sprite frees the projectile on its following execution");
    }

    private static String readState(SpikerBadnikInstance spiker) throws Exception {
        Field field = SpikerBadnikInstance.class.getDeclaredField("state");
        field.setAccessible(true);
        return String.valueOf(field.get(spiker));
    }

    private static String readNestedState(AbstractObjectInstance object) throws Exception {
        Field field = object.getClass().getDeclaredField("state");
        field.setAccessible(true);
        return String.valueOf(field.get(object));
    }

    private static void setNestedIntField(AbstractObjectInstance object, String name, int value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(object, value);
    }

    private static AbstractObjectInstance findChild(List<ObjectInstance> children, int x, int y) {
        return children.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(child -> child.getX() == x && child.getY() == y)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing child at (" + x + ", " + y + ")"));
    }

    private static AbstractObjectInstance findChild(List<ObjectInstance> children, String name) {
        return children.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(child -> child.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing child named " + name));
    }

    private static void advancePastWaitOffscreenInit(SpikerBadnikInstance spiker, PlayableEntity player) {
        spiker.update(0, player);
        spiker.update(1, player);
    }

    private static class RecordingServices extends StubObjectServices {
        private final List<Integer> playedSfx = new ArrayList<>();
        private final List<ObjectInstance> spawnedChildren = new ArrayList<>();
        private final ObjectManager objectManager;
        private PlayableEntity main;

        private RecordingServices() {
            objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                ObjectInstance child = invocation.getArgument(0);
                if (child instanceof AbstractObjectInstance instance) {
                    instance.setServices(this);
                }
                spawnedChildren.add(child);
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any());
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }

        private void withMain(PlayableEntity main) {
            this.main = main;
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, List::of);
        }
    }

    private static final class QueryOnlyPlayerServices extends RecordingServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;
        private final List<PlayableEntity> rawSidekicks;

        private QueryOnlyPlayerServices(PlayableEntity main,
                List<? extends PlayableEntity> queriedSidekicks,
                List<PlayableEntity> rawSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
            this.rawSidekicks = List.copyOf(rawSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return rawSidekicks;
        }
    }
}
